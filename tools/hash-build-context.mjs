#!/usr/bin/env node

/**
 * Hash the effective Docker build context without including local secrets.
 * This intentionally implements the small Docker-ignore glob subset used by
 * this repository and fails closed when the context contract is incomplete.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {currentCommit, generatedAt, parseArgs, repoRelative, repoRoot, sha256File, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const allowedOptions = new Set(['repo-root', 'context', 'output', 'dockerfile', 'dockerignore', 'no-clobber']);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!allowedOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

// The v1.1 contract makes --repo-root the anchor for every relative path.
// Keep the historical no-flag behaviour for the documented frontend command,
// where the process is launched from the frontend context itself.
const argumentBase = args['repo-root']
  ? path.resolve(process.cwd(), String(args['repo-root']))
  : path.resolve(process.cwd());
if (args['repo-root'] && !samePath(argumentBase, repoRoot)) {
  errors.push(`--repo-root 必须指向仓库根目录: ${args['repo-root']}`);
}
const context = path.resolve(argumentBase, args.context ?? '.');
const output = path.resolve(argumentBase, args.output ?? path.join('artifacts', 'v1.1', 'baseline', 'context-hashes.json'));
// Dockerfile/.dockerignore arguments are conventionally relative to the
// selected context (the documented frontend command passes `.dockerignore`
// after changing directory).  Keep a repo-root fallback for callers that
// pass an explicit `vio-ai-agent-frontend/.dockerignore` while staying at the
// repository root.
const dockerignore = args.dockerignore
  ? resolveContextFile(String(args.dockerignore), context, argumentBase)
  : path.join(context, '.dockerignore');
const dockerfile = path.resolve(context, args.dockerfile ?? 'Dockerfile');
const files = [];
const excluded = [];

if (!isWithin(repoRoot, context)) errors.push('Docker context 必须位于仓库内');
if (!fs.existsSync(context) || !fs.statSync(context).isDirectory()) errors.push('Docker context 不存在或不是目录');
else {
  try {
    const realContext = fs.realpathSync(context);
    if (!isWithin(repoRoot, realContext)) errors.push('Docker context 符号链接越出仓库');
  } catch (error) { errors.push(`Docker context 无法解析: ${error.message}`); }
}
if (!isWithin(context, dockerfile)) errors.push('Dockerfile 必须位于 context 内');
if (!fs.existsSync(dockerfile) || !fs.statSync(dockerfile).isFile()) errors.push(`Dockerfile 不存在: ${path.relative(context, dockerfile)}`);
else {
  try {
    const realDockerfile = fs.realpathSync(dockerfile);
    if (!isWithin(repoRoot, realDockerfile) || !isWithin(context, realDockerfile)) errors.push('Dockerfile 符号链接越出 context');
  } catch (error) { errors.push(`Dockerfile 无法解析: ${error.message}`); }
}
if (!isWithin(repoRoot, dockerignore) || !isWithin(context, dockerignore)) errors.push('.dockerignore 必须位于 context 和仓库内');
if (!fs.existsSync(dockerignore) || !fs.statSync(dockerignore).isFile()) errors.push('.dockerignore 不存在或不是文件');
else {
  try {
    const realDockerignore = fs.realpathSync(dockerignore);
    if (!isWithin(repoRoot, realDockerignore) || !isWithin(context, realDockerignore)) errors.push('.dockerignore 符号链接越出 context');
  } catch (error) { errors.push(`.dockerignore 无法解析: ${error.message}`); }
}
if (!isWithin(repoRoot, output)) errors.push('输出路径必须位于仓库内');
else {
  try {
    const parent = nearestExistingPath(path.dirname(output));
    if (!isWithin(repoRoot, fs.realpathSync(parent))) errors.push('输出父目录越出仓库');
    if (fs.existsSync(output)) {
      const realOutput = fs.realpathSync(output);
      if (!isWithin(repoRoot, realOutput) || !fs.statSync(realOutput).isFile()) errors.push('输出不是仓库内普通文件');
    }
  } catch (error) { errors.push(`输出路径无法解析: ${error.message}`); }
}
const rules = fs.existsSync(dockerignore) ? readRules(dockerignore) : [];
const ignoredOutput = isWithin(context, output) ? normalize(path.relative(context, output)) : null;

if (errors.length === 0) walk(context, '');
files.sort((left, right) => left.path.localeCompare(right.path, 'en'));
const aggregate = crypto.createHash('sha256');
for (const file of files) aggregate.update(`${file.path}\0${file.sha256}\n`, 'utf8');
const contextRelative = normalize(path.relative(repoRoot, context));
const report = {
  schemaVersion: 'v1.1', producer: 'hash-build-context', toolVersion: '1.1.0',
  generatedAt: generatedAt(), gitCommit: currentCommit(),
  repoRoot: '.', context: contextRelative || '.', dockerfile: normalize(path.relative(repoRoot, dockerfile)),
  dockerignore: normalize(path.relative(repoRoot, dockerignore)),
  dockerfileSha256: hashIfFile(dockerfile), dockerignoreSha256: hashIfFile(dockerignore),
  parser: 'dockerignore-subset-v1', fileCount: files.length, excludedCount: excluded.length,
  excluded: excluded.slice(0, 500), files, contextSha256: aggregate.digest('hex'),
  passed: errors.length === 0, errors,
  policy: '只纳入真实 Docker context；缺少 Dockerfile/.dockerignore、Secret 未被规则排除或路径越界均 fail-closed。'
};
if (isWithin(repoRoot, output)) writeJson(output, report, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify({output: isWithin(repoRoot, output) ? normalize(path.relative(repoRoot, output)) : '[external-path]', context: report.context, passed: report.passed, fileCount: report.fileCount, excludedCount: report.excludedCount, contextSha256: report.contextSha256, errors}, null, 2));
if (errors.length > 0) process.exitCode = 1;

function walk(directory, relative) {
  let entries;
  try { entries = fs.readdirSync(directory, {withFileTypes: true}); }
  catch (error) { errors.push(`无法读取 context: ${error.message}`); return; }
  for (const entry of entries) {
    const childRelative = relative ? `${relative}/${entry.name}` : entry.name;
    const child = path.join(directory, entry.name);
    const normalized = normalize(childRelative);
    if (normalized === ignoredOutput) { excluded.push({path: normalized, reason: 'producer-output'}); continue; }
    if (normalized === '.git' || normalized.startsWith('.git/')) { excluded.push({path: normalized, reason: 'git-metadata'}); continue; }
    const ignored = shouldIgnore(normalized, entry.isDirectory());
    if (ignored) { excluded.push({path: normalized, reason: 'dockerignore'}); continue; }
    if (entry.isDirectory()) { walk(child, childRelative); continue; }
    if (entry.isSymbolicLink()) {
      try {
        const real = fs.realpathSync(child);
        if (!isWithin(context, real)) errors.push(`${normalized}: 符号链接越出 context`);
        else errors.push(`${normalized}: 不允许把符号链接作为可复现 context 输入`);
      } catch (error) { errors.push(`${normalized}: 无法解析符号链接 (${error.message})`); }
      continue;
    }
    if (!entry.isFile()) { errors.push(`${normalized}: 不支持的文件类型`); continue; }
    if (isSecretPath(normalized)) errors.push(`${normalized}: Secret 文件进入 Docker context`);
    try { files.push({path: normalized, sha256: sha256File(child)}); }
    catch (error) { errors.push(`${normalized}: hash 失败 (${error.message})`); }
  }
}

function readRules(file) {
  return fs.readFileSync(file, 'utf8').split(/\r?\n/)
    .map((line) => line.trim()).filter((line) => line && !line.startsWith('#'));
}

function shouldIgnore(value) {
  let ignored = false;
  for (const raw of rules) {
    const negated = raw.startsWith('!');
    const pattern = negated ? raw.slice(1) : raw;
    if (globMatch(value, pattern)) ignored = !negated;
  }
  return ignored;
}

function globMatch(value, pattern) {
  let normalized = normalize(pattern).replace(/^\//, '');
  const directoryOnly = normalized.endsWith('/');
  normalized = normalized.replace(/\/$/, '');
  const source = globRegex(normalized);
  if (!normalized.includes('/')) return new RegExp(`(?:^|/)${source}(?:/|$)`).test(value);
  return new RegExp(`^${source}(?:/|$)`).test(value) || new RegExp(`(?:^|/)${source}(?:/|$)`).test(value);
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

function isSecretPath(value) {
  const normalized = value.replaceAll('\\', '/');
  if (/(^|\/)\.env(?:\.[^/]+)?\.example$/i.test(normalized)) return false;
  return /(^|\/)(?:\.env(?:\.|$)|application-local\.(?:yml|yaml|properties)(?:\.|$)|mcp-servers\.json(?:\.|$))/i.test(normalized);
}

function hashIfFile(file) {
  try { return fs.existsSync(file) && fs.statSync(file).isFile() ? sha256File(file) : null; } catch { return null; }
}
function normalize(value) { return value.replaceAll('\\', '/').replace(/^\.\//, ''); }
function isWithin(root, target) {
  const relative = path.relative(root, target);
  return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}
function samePath(left, right) {
  const normalizedLeft = path.resolve(left);
  const normalizedRight = path.resolve(right);
  return process.platform === 'win32'
    ? normalizedLeft.toLowerCase() === normalizedRight.toLowerCase()
    : normalizedLeft === normalizedRight;
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

function resolveContextFile(value, contextRoot, fallbackRoot) {
  if (path.isAbsolute(value)) return path.normalize(value);
  const contextCandidate = path.resolve(contextRoot, value);
  const fallbackCandidate = path.resolve(fallbackRoot, value);
  return fs.existsSync(contextCandidate) || !fs.existsSync(fallbackCandidate)
    ? contextCandidate
    : fallbackCandidate;
}
