#!/usr/bin/env node

/**
 * Check ignored local secret files without reading or hashing their contents.
 * The report is intentionally metadata-only so it is safe to retain as CI
 * evidence. History and image-layer scans are separate gates.
 */
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {currentCommit, generatedAt, listArg, parseArgs, repoRelative, resolvePath, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const root = resolvePath(args['repo-root'] ?? '.');
const listFile = args.paths;
const output = args.output;
const excludeRegex = args['exclude-regex'] ? new RegExp(args['exclude-regex']) : /(^|[\\/])(?:\.env(?!\.example$)(?:\.|$)|application-local\.(?:yml|yaml|properties)(?:\.|$)|mcp-servers\.json(?:\.|$))([\\/]|$)/i;
if (!listFile) throw new Error('必须提供 --paths');
const pathList = fs.readFileSync(resolvePath(listFile), 'utf8').split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
const errors = [];
const warnings = [];
const entries = [];
const dockerRules = loadDockerRules(root);

for (const listedPath of pathList) {
  let relative;
  try { relative = repoRelative(listedPath, root); } catch (error) {
    errors.push(`${listedPath}: ${error.message}`);
    continue;
  }
  const excluded = excludeRegex.test(relative);
  const absolute = path.join(root, relative.split('/').join(path.sep));
  const exists = fs.existsSync(absolute);
  const tracked = gitCheck(['ls-files', '--error-unmatch', '--', relative], root);
  const ignored = gitCheck(['check-ignore', '-q', '--', relative], root);
  const mode = exists ? (fs.statSync(absolute).mode & 0o777).toString(8) : null;
  const backendContextIncluded = exists && !matchesDockerRules(relative, dockerRules.root);
  const frontendRelative = relative.startsWith('vio-ai-agent-frontend/') ? relative.slice('vio-ai-agent-frontend/'.length) : null;
  const frontendContextIncluded = frontendRelative !== null && exists && !matchesDockerRules(frontendRelative, dockerRules.frontend);
  const permissionVerified = process.platform !== 'win32' ? exists : false;
  const entry = {
    path: relative,
    excludedPathCheck: excluded,
    exists,
    gitTracked: tracked,
    gitIgnored: ignored,
    permission: {checked: exists, mode, evidence: process.platform === 'win32' ? 'platform-mode-only; ACL review pending' : 'mode'},
    permissionVerified,
    backendContextIncluded,
    frontendContextIncluded,
    contentHashOmitted: excluded
  };
  entries.push(entry);
  if (exists && !permissionVerified) warnings.push(`${relative}: 当前平台未完成 ACL/权限实证`);
  if (!excluded) continue;
  if (tracked) errors.push(`${relative}: 已进入 Git/index，必须先轮换并清理历史`);
  if (backendContextIncluded) errors.push(`${relative}: 会进入 backend Docker context`);
  if (frontendContextIncluded) errors.push(`${relative}: 会进入 frontend Docker context`);
  if (exists && !ignored) errors.push(`${relative}: 存在但未被 .gitignore 忽略`);
}

const report = {
  schemaVersion: 'v1.1', producer: 'check-local-secret-store', generatedAt: generatedAt(), gitCommit: currentCommit(),
  root: '.', status: errors.length > 0 ? 'blocked' : (warnings.length > 0 ? 'environment-pending' : 'ready-for-review'),
  passed: errors.length === 0 && warnings.length === 0, errors, warnings, entries,
  policy: '仅登记路径、存在性、Git/构建上下文和权限元数据；不读取、不输出、不计算 Secret 内容 hash。'
};
if (output) writeJson(output, report, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify(report, null, 2));
if (errors.length > 0 || (warnings.length > 0 && args['allow-pending'] !== true)) process.exitCode = 1;

function gitCheck(command, cwd) {
  try {
    execFileSync('git', command, {cwd, stdio: ['ignore', 'ignore', 'ignore']});
    return true;
  } catch {
    return false;
  }
}

function loadDockerRules(rootPath) {
  const read = (file) => fs.existsSync(file) ? fs.readFileSync(file, 'utf8').split(/\r?\n/).map((line) => line.trim()).filter((line) => line && !line.startsWith('#')) : [];
  return {root: read(path.join(rootPath, '.dockerignore')), frontend: read(path.join(rootPath, 'vio-ai-agent-frontend', '.dockerignore'))};
}

function matchesDockerRules(value, rules) {
  const normalized = value.replaceAll('\\', '/').replace(/^\.\//, '');
  let ignored = false;
  for (const raw of rules) {
    const negated = raw.startsWith('!');
    const pattern = negated ? raw.slice(1) : raw;
    if (globMatch(normalized, pattern)) ignored = !negated;
  }
  return ignored;
}

function globMatch(value, pattern) {
  const normalized = pattern.replaceAll('\\', '/').replace(/^\//, '');
  if (normalized === '.') return false;
  const source = normalized.includes('/') ? globRegex(normalized) : `(?:^|/)${globRegex(normalized)}$`;
  return new RegExp(`^${source}$`).test(value) || new RegExp(`(?:^|/)${globRegex(normalized)}$`).test(value);
}

function globRegex(pattern) {
  let result = '';
  for (let index = 0; index < pattern.length; index += 1) {
    const char = pattern[index];
    if (char === '*' && pattern[index + 1] === '*') { result += '.*'; index += 1; }
    else if (char === '*') result += '[^/]*';
    else if (char === '?') result += '[^/]';
    else result += char.replace(/[\\^$+?.()|{}\[\]]/g, '\\$&');
  }
  return result;
}
