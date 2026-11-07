#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import {currentCommit, generatedAt, parseArgs, placeholder, redactEndpoint, repoRelative, repoRoot, resolvePath, sha256Text, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const output = args.output ?? 'artifacts/v1.1/baseline/environment-contract.json';
const reportOutput = args.report;
const owner = args.owner ?? process.env.VIO_BASELINE_OWNER ?? 'unassigned';
const target = args.target ?? process.env.VIO_CONTRACT_TARGET ?? 'local';
const errors = [];
let pathErrors = 0;
const allowedOptions = new Set(['output', 'report', 'owner', 'target', 'allow-pending', 'no-clobber']);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!allowedOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);
const outputRelative = validateOutput(output, 'output');
const reportRelative = reportOutput ? validateOutput(reportOutput, 'report') : null;
if (!['local', 'external'].includes(String(target))) errors.push('target 必须为 local 或 external');
if (!/^[a-z0-9][a-z0-9._-]{2,63}$/i.test(String(owner))) errors.push('owner 必须是稳定标识');
if (outputRelative && reportRelative && sameKey(outputRelative, reportRelative)) { errors.push('output 与 report 不能是同一路径'); pathErrors += 1; }
for (const [label, value, relative] of [['output', output, outputRelative], ['report', reportOutput, reportRelative]]) {
  if (relative && args['no-clobber'] === true && fs.existsSync(resolvePath(value))) errors.push(`${label} 输出已存在（--no-clobber）: ${relative}`);
}
const definitions = [
  ['SPRING_DATASOURCE_URL', 'non-secret', ['n0-fixture', 'n0-dependency', 'n7-staging'], 'jdbc-url', '数据库 JDBC URL'],
  ['DB_PASSWORD', 'secret', ['n0-secret', 'n7-staging'], 'secret', 'Compose 到数据库密码的映射'],
  ['SPRING_DATASOURCE_PASSWORD', 'secret', ['n0-secret', 'n7-staging'], 'secret', '应用数据库密码'],
  ['VIO_CORS_ALLOWED_ORIGINS', 'non-secret', ['n0-secret', 'n7-staging', 'n8-ga'], 'url-list', '允许的 CORS origins'],
  ['VIO_FEEDBACK_OPERATOR_ID', 'non-secret', ['n0-secret', 'n3', 'n7-staging', 'n8-ga'], 'principal', '反馈运营者主体'],
  ['GRAFANA_ADMIN_PASSWORD', 'secret', ['n7-staging', 'n8-ga'], 'secret', 'Grafana 管理密码'],
  ['VIO_AGENT_V1_ENABLED', 'non-secret', ['n0-finalize', 'n7-staging', 'n8-ga'], 'boolean', '版本化 Agent 主链开关'],
  ['VIO_RAG_PGVECTOR_ENABLED', 'non-secret', ['n0-finalize', 'n1', 'n7-staging', 'n8-ga'], 'boolean', '生产 PGVector 开关'],
  ['VIO_MCP_ENABLED', 'non-secret', ['n3', 'n7-staging', 'n8-ga'], 'boolean', 'MCP 总开关'],
  ['VIO_FEEDBACK_OPERATOR_ROLE', 'non-secret', ['n3', 'n7-staging'], 'identifier', '反馈运营角色'],
  ['VIO_BASE_URL', 'non-secret', ['n0-dependency', 'n7-staging', 'n8-ga'], 'url', 'staging/prod API base URL'],
  ['VIO_PROXY_BASE_URL', 'non-secret', ['n7-staging', 'n8-ga'], 'url', 'trusted proxy URL'],
  ['VIO_PROMETHEUS_URL', 'non-secret', ['n7-staging', 'n8-ga'], 'url', 'Prometheus URL'],
  ['VIO_ALERTMANAGER_URL', 'non-secret', ['n7-staging', 'n8-ga'], 'url', 'Alertmanager URL'],
  ['VIO_GRAFANA_URL', 'non-secret', ['n7-staging', 'n8-ga'], 'url', 'Grafana URL'],
  ['VIO_EVAL_REVIEWER', 'non-secret', ['n0-evaluation', 'n0-finalize', 'n6a', 'n8-ga'], 'principal', '评测 reviewer'],
  ['VIO_N3_RUN_ID', 'non-secret', ['n3', 'n6a'], 'run-id', 'N3 红队运行 ID'],
  ['VIO_EVAL_RUN_DIR', 'non-secret', ['n6a', 'n8-ga'], 'path', '评测运行目录'],
  ['VIO_PUBLISHED_RELEASE_ID', 'non-secret', ['n6b', 'n7', 'n8-ga'], 'release-id', '当前生产 release'],
  ['VIO_TARGET_RPO_SECONDS', 'non-secret', ['n0-finalize', 'n7', 'n8-ga'], 'positive-integer', 'RPO 目标'],
  ['VIO_TARGET_RTO_SECONDS', 'non-secret', ['n0-finalize', 'n7', 'n8-ga'], 'positive-integer', 'RTO 目标'],
  ['BACKUP_SHA256', 'non-secret', ['n7', 'n8-ga'], 'sha256', '备份内容 hash'],
  ['VIO_DEV_COMPOSE_PROJECT', 'non-secret', ['n0-fixture', 'n0-dependency'], 'project', '开发 Compose 项目名'],
  ['VIO_CANARY_COMPOSE_PROJECT', 'non-secret', ['n7-canary'], 'project', '灰度 Compose 项目名'],
  ['VIO_RELEASE_COMPOSE_PROJECT', 'non-secret', ['n7-release', 'n8-ga'], 'project', '发布 Compose 项目名'],
  ['VIO_REGISTRY', 'non-secret', ['n7', 'n8-ga'], 'registry', '镜像仓库'],
  ['VIO_COSIGN_IDENTITY', 'non-secret', ['n7', 'n8-ga'], 'identity', '签名身份'],
  ['VIO_COSIGN_ISSUER', 'non-secret', ['n7', 'n8-ga'], 'url', '签名 issuer'],
  ['VIO_CANARY_APPROVAL_ID', 'non-secret', ['n7-canary'], 'approval-id', '灰度审批 ID'],
  ['VIO_RECOVERY_APPROVAL_ID', 'non-secret', ['n7-recovery'], 'approval-id', '恢复审批 ID'],
  ['VIO_READINESS_TOKEN', 'secret', ['n7-staging', 'n8-ga'], 'secret', 'readiness token'],
  ['VIO_API_KEY', 'secret', ['n0-secret', 'n7-staging', 'n8-ga'], 'secret', '应用 API key'],
  ['AI_DASHSCOPE_API_KEY', 'secret', ['n0-secret', 'n7-staging'], 'secret', '模型密钥'],
  ['SEARCH_API_KEY', 'secret', ['n0-secret', 'n7-staging'], 'secret', '搜索密钥']
];
const variables = definitions.map(([name, kind, requiredStages, format, purpose]) => {
  const raw = process.env[name];
  const present = !placeholder(raw);
  const validationErrors = present && kind !== 'secret' ? validateValue(String(raw).trim(), format, target) : [];
  return {
    name, kind, requiredStages, format, purpose, owner,
    source: kind === 'secret' ? 'environment-or-secret-store' : 'environment-contract',
    present,
    observedValue: kind === 'secret' ? undefined : safeObserved(raw, format),
    aliases: name === 'SPRING_DATASOURCE_PASSWORD' ? ['DB_PASSWORD'] : [],
    ...(validationErrors.length > 0 ? {validationErrors} : {})
  };
});
const missing = variables.filter((item) => item.requiredStages.length > 0 && !item.present).map((item) => item.name);
const invalid = variables.filter((item) => item.validationErrors?.length > 0).map((item) => ({name: item.name, errors: item.validationErrors}));
for (const item of invalid) errors.push(`${item.name}: ${item.errors.join('; ')}`);
const payload = {
  schemaVersion: 'v1.1', producer: 'create-environment-contract', generatedAt: generatedAt(), gitCommit: currentCommit(), owner,
  target: {mode: target, localhostAllowedStages: ['local', 'n0-local', 'n0-fixture', 'n0-dependency']},
  status: missing.length === 0 && invalid.length === 0 && errors.length === 0 ? 'ready-for-review' : 'pending',
  passed: missing.length === 0 && invalid.length === 0 && errors.length === 0,
  producerCompleted: true, exitCode: missing.length === 0 && invalid.length === 0 && errors.length === 0 ? 0 : 1,
  redacted: true, rejectDefaults: true,
  variables, policy: {
    secretValuesNeverPersisted: true,
    localhostRejectedForExternalStages: true,
    noImplicitDefaults: true,
    composeSecretAliases: {'DB_PASSWORD': 'SPRING_DATASOURCE_PASSWORD'},
    contractMustBeRegeneratedWhenNonSecretTargetsChange: true
  }
};
if (outputRelative && pathErrors === 0 && (!errors.length || args['allow-pending'] === true)) {
  try { writeJson(output, payload, {noClobber: args['no-clobber'] === true}); }
  catch (error) { errors.push(`output 写入失败: ${error.message}`); }
}
const report = {schemaVersion: 'v1.1', producer: payload.producer, generatedAt: payload.generatedAt, output: outputRelative ?? '[invalid-path]', target, passed: missing.length === 0 && invalid.length === 0 && errors.length === 0, status: payload.status, redacted: true, missingVariables: missing, invalidVariables: invalid, errors};
if (reportRelative && (!args['no-clobber'] || !fs.existsSync(resolvePath(reportOutput)))) {
  try { writeJson(reportOutput, report, {noClobber: args['no-clobber'] === true}); }
  catch (error) { errors.push(`report 写入失败: ${error.message}`); }
}
console.log(JSON.stringify(report, null, 2));
if (!report.passed && args['allow-pending'] !== true) process.exitCode = 1;

function validateOutput(value, label) {
  try {
    const absolute = resolvePath(value);
    const relative = repoRelative(absolute);
    if (!relative || relative === '.') throw new Error('不能是仓库根目录');
    const parent = nearestExistingPath(path.dirname(absolute));
    if (!isWithin(repoRoot, fs.realpathSync(parent))) throw new Error('输出父目录越出仓库');
    if (fs.existsSync(absolute)) {
      if (fs.lstatSync(absolute).isSymbolicLink()) throw new Error('输出不得是符号链接');
      const real = fs.realpathSync(absolute);
      if (!isWithin(repoRoot, real) || !fs.statSync(real).isFile()) throw new Error('现有输出不是仓库内普通文件');
    }
    return relative;
  } catch (error) {
    errors.push(`${label} 路径无效: ${error.message}`);
    pathErrors += 1;
    return null;
  }
}

function nearestExistingPath(value) {
  let current = path.resolve(value);
  while (!fs.existsSync(current)) {
    const parent = path.dirname(current);
    if (parent === current) return current;
    current = parent;
  }
  return current;
}

function isWithin(root, targetPath) {
  const relativePath = path.relative(root, targetPath);
  return relativePath === '' || (!relativePath.startsWith(`..${path.sep}`) && relativePath !== '..' && !path.isAbsolute(relativePath));
}

function sameKey(left, right) {
  if (!left || !right) return false;
  return process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right;
}

function validateValue(value, format, targetMode) {
  const failures = [];
  const localhostAllowed = targetMode === 'local';
  switch (format) {
    case 'url': {
      try {
        const parsed = new URL(value);
        if (!['http:', 'https:'].includes(parsed.protocol)) failures.push('URL 仅允许 http/https');
        if (!localhostAllowed && ['localhost', '127.0.0.1', '::1'].includes(parsed.hostname)) failures.push('external target 禁止 localhost');
      } catch { failures.push('URL 格式无效'); }
      break;
    }
    case 'jdbc-url':
      if (!/^jdbc:[a-z][a-z0-9+.-]*:\/\/[^\s/]+(?:\/[^\s?]*)?(?:\?[^\s]*)?$/i.test(value)) failures.push('JDBC URL 格式无效');
      if (/(?:password|user|token|secret)=/i.test(value)) failures.push('JDBC URL 不得包含内嵌凭据');
      if (!localhostAllowed && /:\/\/(?:localhost|127\.0\.0\.1|\[::1\])(?::|\/|$)/i.test(value)) failures.push('external target 禁止 localhost JDBC');
      break;
    case 'url-list': {
      const origins = value.split(',').map((item) => item.trim()).filter(Boolean);
      if (origins.length === 0) failures.push('URL list 不能为空');
      for (const origin of origins) {
        try {
          const parsed = new URL(origin);
          if (!['http:', 'https:'].includes(parsed.protocol)) failures.push('CORS origin 仅允许 http/https');
          if (!localhostAllowed && ['localhost', '127.0.0.1', '::1'].includes(parsed.hostname)) failures.push('external target 禁止 localhost CORS origin');
        } catch { failures.push('CORS origin URL 格式无效'); }
      }
      break;
    }
    case 'positive-integer':
      if (!/^\d+$/.test(value) || Number(value) <= 0) failures.push('必须为正整数');
      break;
    case 'sha256':
      if (!/^(sha256:)?[a-f0-9]{64}$/i.test(value)) failures.push('必须为 SHA-256');
      break;
    case 'project':
      if (!/^[a-z0-9][a-z0-9_-]{1,62}$/i.test(value)) failures.push('Compose 项目名格式无效');
      break;
    case 'run-id':
    case 'release-id':
    case 'approval-id':
      if (!/^[a-z0-9][a-z0-9._:-]{2,127}$/i.test(value)) failures.push(`${format} 格式无效`);
      break;
    case 'path': {
      const normalized = value.replaceAll('\\', '/');
      if (normalized === '..' || normalized.startsWith('../') || normalized.includes('/../') || /^[A-Za-z]:\//.test(normalized) || normalized.startsWith('/')) failures.push('证据目录必须是仓库内相对路径');
      break;
    }
    case 'registry':
      if (/^https?:\/\//i.test(value) || /\s/.test(value) || !/^[a-z0-9._:-]+(?:\/[a-z0-9._-]+)+$/i.test(value)) failures.push('registry 必须是锁定的 host/namespace 路径');
      break;
    case 'identity':
    case 'principal':
      if (value.length < 3 || /\s/.test(value)) failures.push(`${format} 格式无效`);
      break;
    case 'boolean':
      if (!/^(true|false)$/i.test(value)) failures.push('必须为 true/false');
      break;
    case 'identifier':
      if (!/^[a-z][a-z0-9._-]{1,63}$/i.test(value)) failures.push('标识格式无效');
      break;
    case 'secret':
      break;
    default:
      failures.push(`未实现格式校验: ${format}`);
  }
  return failures;
}

function safeObserved(raw, format) {
  if (raw === undefined || raw === null || placeholder(raw)) return null;
  const value = String(raw).trim();
  if (format === 'url') return redactEndpoint(value);
  if (format === 'jdbc-url') return value.replace(/(jdbc:[^:]+:\/\/)[^/]+/i, '$1[host]');
  if (format === 'path') {
    const normalized = value.replaceAll('\\', '/');
    const root = repoRoot.replaceAll('\\', '/').replace(/\/$/, '');
    return normalized.startsWith(`${root}/`) ? normalized.slice(root.length + 1) : `external-path-sha256:${sha256Text(value)}`;
  }
  if (format === 'identity' || format === 'principal') return `sha256:${sha256Text(value)}`;
  return value;
}
