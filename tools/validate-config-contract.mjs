#!/usr/bin/env node

/** Static, comment-aware v1.1 configuration contract validator. */
import fs from 'node:fs';
import {generatedAt, parseArgs, readJson, repoRelative, resolvePath, writeAtomic, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const applicationPath = resolvePath(args.application ?? 'src/main/resources/application-prod.yml');
const composePath = resolvePath(args.compose ?? 'docker-compose.yml');
const contractPath = args['environment-contract'] ?? 'artifacts/v1.1/baseline/environment-contract.json';
const output = args.output;
const errors = [];
const warnings = [];
const checks = [];
const application = readConfigText(applicationPath, 'application profile');
const compose = readConfigText(composePath, 'docker compose');
let contract = null;
try { contract = readJson(contractPath, 'environment contract'); } catch (error) { errors.push(error.message); }
if (!applicationPath || application.length === 0) errors.push('application 配置为空或不存在');
if (!composePath || compose.length === 0) errors.push('Compose 配置为空或不存在');
if (!contract || contract.schemaVersion !== 'v1.1') errors.push('必须提供 schemaVersion=v1.1 的 environment contract');

const requiredPlaceholders = [
  'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_PASSWORD', 'AI_DASHSCOPE_API_KEY',
  'SEARCH_API_KEY', 'VIO_API_KEY', 'VIO_CORS_ALLOWED_ORIGINS', 'VIO_FEEDBACK_OPERATOR_ID',
  'VIO_AGENT_V1_ENABLED', 'VIO_RAG_PGVECTOR_ENABLED'
];
const secretNames = new Set(['SPRING_DATASOURCE_PASSWORD', 'AI_DASHSCOPE_API_KEY', 'SEARCH_API_KEY', 'VIO_API_KEY', 'VIO_READINESS_TOKEN', 'DB_PASSWORD', 'GRAFANA_ADMIN_PASSWORD']);
for (const name of requiredPlaceholders) {
  const matches = [...application.matchAll(new RegExp(`\\$\\{${name}(?::([^}]*))?\\}`, 'g'))];
  const present = matches.length > 0;
  const hasDefault = matches.some((match) => match[1] !== undefined && match[1] !== '');
  checks.push({name, source: 'application-prod.yml', present, hasDefault, required: true});
  if (!present) errors.push(`prod 配置缺少必填 placeholder: ${name}`);
  if (secretNames.has(name) && hasDefault) errors.push(`Secret ${name} 不允许配置默认值`);
}
for (const pattern of [/sk-prod-change-me/i, /your-[a-z0-9-]+-key-here/i, /\$\{(?:VIO_AGENT_V1_ENABLED|VIO_RAG_PGVECTOR_ENABLED):true\}/i]) {
  if (pattern.test(application)) errors.push(`prod 配置仍存在不允许的公开 fallback: ${pattern}`);
}

const ignoreRules = ['**/application-local.yml', '**/application-local.yaml', '**/application-local.properties', '**/mcp-servers.json', '**/.env.*'];
for (const rule of ignoreRules) {
  const rootGit = readOptional('.gitignore');
  const rootDocker = readOptional('.dockerignore');
  const frontendDocker = readOptional('vio-ai-agent-frontend/.dockerignore');
  const rootGitOk = hasRule(rootGit, rule) || (rule === '**/.env.*' && hasRule(rootGit, '.env.*'));
  const rootDockerOk = hasRule(rootDocker, rule) || (rule === '**/.env.*' && hasRule(rootDocker, '**/.env.*'));
  const frontendOk = hasRule(frontendDocker, rule) || (rule === '**/.env.*' && hasRule(frontendDocker, '**/.env.*'));
  checks.push({rule, rootGit: rootGitOk, rootDocker: rootDockerOk, frontendDocker: frontendOk});
  if (!rootGitOk) errors.push(`根 .gitignore 缺少 ${rule}`);
  if (!rootDockerOk) errors.push(`根 .dockerignore 缺少 ${rule}`);
  if (!frontendOk) errors.push(`前端 .dockerignore 缺少 ${rule}`);
}

const composeRequired = ['DB_PASSWORD', 'VIO_API_KEY', 'AI_DASHSCOPE_API_KEY', 'SEARCH_API_KEY', 'VIO_CORS_ALLOWED_ORIGINS', 'VIO_FEEDBACK_OPERATOR_ID', 'VIO_AGENT_V1_ENABLED', 'VIO_RAG_PGVECTOR_ENABLED'];
for (const name of composeRequired) {
  const pattern = new RegExp(`\\$\\{${name}(?::)?\\?`);
  const present = pattern.test(compose);
  checks.push({name, source: 'docker-compose.yml', composeRequiredInterpolation: present});
  if (!present) errors.push(`Compose 未对 ${name} 使用必填插值`);
}
if (!/SPRING_DATASOURCE_PASSWORD:\s*\$\{DB_PASSWORD(?::)?\?/m.test(compose)) errors.push('Compose 必须显式建立 DB_PASSWORD → SPRING_DATASOURCE_PASSWORD 映射');
if (/MCP_ENABLED/.test(compose) && !/VIO_MCP_ENABLED/.test(compose)) errors.push('Compose 未声明 VIO_MCP_ENABLED 优先级');

const contractNames = new Set((contract?.variables ?? []).map((item) => item.name));
for (const name of [...requiredPlaceholders, ...composeRequired, 'GRAFANA_ADMIN_PASSWORD']) {
  if (!contractNames.has(name) && !(name === 'DB_PASSWORD' && contractNames.has('SPRING_DATASOURCE_PASSWORD'))) errors.push(`environment contract 缺少 ${name}`);
}
checks.push({name: 'parser', parser: 'comment-aware-static-limited', note: '不替代 Spring Boot 运行时绑定/Compose 实际插值'});

const report = {schemaVersion: 'v1.1', producer: 'validate-config-contract', generatedAt: generatedAt(), profile: args.profile ?? 'prod', parser: 'comment-aware-static-limited', passed: errors.length === 0, exitCode: errors.length === 0 ? 0 : 1, errors, warnings, checks};
if (output) {
  if (String(output).toLowerCase().endsWith('.json')) writeJson(output, report, {noClobber: args['no-clobber'] === true});
  else writeAtomic(output, `${JSON.stringify(report, null, 2)}\n`, {noClobber: args['no-clobber'] === true});
}
console.log(JSON.stringify(report, null, 2));
if (errors.length > 0) process.exitCode = 1;

function readConfigText(file, label) {
  if (!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) return '';
  // Remove full-line and trailing YAML comments while preserving quoted values
  // sufficiently for placeholder/fallback checks; this is intentionally not a
  // claim of full YAML semantic parsing.
  return fs.readFileSync(file, 'utf8').split(/\r?\n/).map((line) => {
    let quote = null;
    for (let i = 0; i < line.length; i += 1) {
      const ch = line[i];
      if ((ch === '"' || ch === "'") && line[i - 1] !== '\\') quote = quote === ch ? null : (quote ?? ch);
      if (ch === '#' && !quote && (i === 0 || /\s/.test(line[i - 1]))) return line.slice(0, i);
    }
    return line;
  }).join('\n');
}
function readOptional(file) { const p = resolvePath(file); return fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : ''; }
function hasRule(text, rule) { return text.split(/\r?\n/).some((line) => line.trim() === rule); }
