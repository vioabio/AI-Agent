#!/usr/bin/env node

/** Validate one execution stage against the frozen non-secret contract. */
import fs from 'node:fs';
import path from 'node:path';
import {
  generatedAt, listArg, parseArgs, placeholder, readJson, redactEndpoint, repoRelative, repoRoot, sha256Text, writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const contractPath = args.contract ?? 'artifacts/v1.1/baseline/environment-contract.json';
const stage = args.stage ?? 'n0-finalize';
const requiredOverrides = new Set(listArg(args.require));
const output = args.output;
const errors = [];
const checks = [];
const allowedOptions = new Set([
  'contract', 'stage', 'require', 'output', 'target', 'expected-run-id',
  'expected-eval-dir', 'expected-release-id', 'expected-dev-compose-project',
  'expected-canary-compose-project', 'expected-release-compose-project',
  'expected-registry', 'expected-cosign-identity', 'expected-cosign-issuer', 'no-clobber'
]);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!allowedOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);
const contractInfo = validateInputPath(contractPath, 'environment contract');
const outputInfo = output ? validateOutputPath(output, 'validation report') : null;
if (outputInfo && contractInfo && samePath(outputInfo.absolute, contractInfo.absolute)) errors.push('validation report 不能覆盖 environment contract');
if (outputInfo && args['no-clobber'] === true && fs.existsSync(outputInfo.absolute)) errors.push(`validation report 已存在（--no-clobber）: ${outputInfo.relative}`);
let contract = {};
if (contractInfo) {
  try { contract = readJson(contractInfo.real, 'environment contract'); }
  catch (error) { errors.push(error.message); }
}
const supportedFormats = new Set([
  'url', 'jdbc-url', 'url-list', 'positive-integer', 'sha256', 'project', 'run-id', 'path',
  'release-id', 'registry', 'identity', 'principal', 'approval-id', 'secret', 'boolean', 'identifier'
]);
const target = args.target ?? process.env.VIO_CONTRACT_TARGET ?? contract.target?.mode ?? null;
const localStages = new Set(contract.target?.localhostAllowedStages ?? ['local', 'n0-local', 'n0-fixture', 'n0-dependency']);
if (target !== null && !['local', 'external'].includes(String(target))) errors.push('target 必须为 local 或 external');
const externalStage = target ? target === 'external' : !localStages.has(stage);

if (contract.schemaVersion !== 'v1.1') errors.push('environment contract schemaVersion 必须为 v1.1');
if (contract.producer !== 'create-environment-contract') errors.push('environment contract producer 不受信任');
if (!Array.isArray(contract.variables)) errors.push('environment contract variables 必须为数组');

const definitions = new Map();
for (const variable of Array.isArray(contract.variables) ? contract.variables : []) {
  if (!variable || typeof variable.name !== 'string' || variable.name.trim() === '') {
    errors.push('environment contract 存在无名变量定义');
    continue;
  }
  if (definitions.has(variable.name)) {
    errors.push(`${variable.name}: 重复变量定义`);
    continue;
  }
  if (!supportedFormats.has(variable.format)) errors.push(`${variable.name}: 未知格式 ${variable.format}`);
  if (!['secret', 'non-secret'].includes(variable.kind)) errors.push(`${variable.name}: kind 必须为 secret/non-secret`);
  definitions.set(variable.name, variable);
}

for (const name of requiredOverrides) {
  if (!definitions.has(name)) errors.push(`${name}: --require 引用了 contract 中不存在的变量`);
}

for (const variable of definitions.values()) {
  const required = requiredOverrides.has(variable.name) || (variable.requiredStages ?? []).includes(stage);
  const raw = process.env[variable.name];
  const present = !placeholder(raw);
  const check = {
    name: variable.name,
    kind: variable.kind,
    format: variable.format,
    required,
    present,
    stage,
    observed: variable.kind === 'secret' ? undefined : summarize(raw, variable.format),
    passed: true
  };
  if (required && !present) fail(check, variable.kind === 'secret' ? 'Secret 缺失（未记录值）' : '环境变量缺失');
  if (present && variable.kind !== 'secret') validateValue(check, String(raw).trim(), variable);
  checks.push(check);
}

validateLockedValue('VIO_N3_RUN_ID', args['expected-run-id']);
validateLockedValue('VIO_EVAL_RUN_DIR', args['expected-eval-dir']);
validateLockedValue('VIO_PUBLISHED_RELEASE_ID', args['expected-release-id']);
validateLockedValue('VIO_DEV_COMPOSE_PROJECT', args['expected-dev-compose-project']);
validateLockedValue('VIO_CANARY_COMPOSE_PROJECT', args['expected-canary-compose-project']);
validateLockedValue('VIO_RELEASE_COMPOSE_PROJECT', args['expected-release-compose-project']);
validateLockedValue('VIO_REGISTRY', args['expected-registry']);
validateLockedValue('VIO_COSIGN_IDENTITY', args['expected-cosign-identity']);
validateLockedValue('VIO_COSIGN_ISSUER', args['expected-cosign-issuer']);

const composeNames = ['VIO_DEV_COMPOSE_PROJECT', 'VIO_CANARY_COMPOSE_PROJECT', 'VIO_RELEASE_COMPOSE_PROJECT']
  .map((name) => process.env[name]?.trim()).filter((value) => !placeholder(value));
if (new Set(composeNames).size !== composeNames.length) errors.push('开发、灰度、发布 Compose 项目名必须彼此隔离');

const report = {
  schemaVersion: 'v1.1', producer: 'validate-environment-contract', generatedAt: generatedAt(),
  contract: safePath(contractPath), contractGitCommit: contract.gitCommit ?? null, stage,
  target: target ?? (externalStage ? 'external' : 'local'),
  parser: 'strict-v1.1', passed: errors.length === 0, exitCode: errors.length === 0 ? 0 : 1,
  errors, checks
};
if (outputInfo && (!args['no-clobber'] || !fs.existsSync(outputInfo.absolute))) {
  try { writeJson(outputInfo.absolute, report, {noClobber: args['no-clobber'] === true}); }
  catch (error) { errors.push(`validation report 写入失败: ${error.message}`); }
}
console.log(JSON.stringify(report, null, 2));
if (errors.length > 0) process.exitCode = 1;

function fail(check, reason) {
  check.passed = false;
  check.reason = check.reason ? `${check.reason}; ${reason}` : reason;
  errors.push(`${check.name}: ${reason}`);
}

function validateValue(check, value, variable) {
  switch (variable.format) {
    case 'url': {
      try {
        const parsed = new URL(value);
        if (!['http:', 'https:'].includes(parsed.protocol)) fail(check, 'URL 仅允许 http/https');
        if (externalStage && ['localhost', '127.0.0.1', '::1'].includes(parsed.hostname)) fail(check, '外部 stage 禁止 localhost');
      } catch { fail(check, 'URL 格式无效'); }
      break;
    }
    case 'jdbc-url':
      if (!/^jdbc:[a-z][a-z0-9+.-]*:\/\/[^\s/]+(?:\/[^\s?]*)?(?:\?[^\s]*)?$/i.test(value)) fail(check, 'JDBC URL 格式无效');
      if (/(?:password|user|token|secret)=/i.test(value)) fail(check, 'JDBC URL 不得包含内嵌凭据');
      if (externalStage && /:\/\/(?:localhost|127\.0\.0\.1|\[::1\])(?::|\/|$)/i.test(value)) fail(check, '外部 stage 禁止 localhost JDBC');
      break;
    case 'url-list':
      {
        const origins = value.split(',').map((item) => item.trim()).filter(Boolean);
        if (origins.length === 0) fail(check, 'CORS origin 列表不能为空');
        for (const origin of origins) {
        try {
          const parsed = new URL(origin);
          if (!['http:', 'https:'].includes(parsed.protocol)) fail(check, 'CORS origin 仅允许 http/https');
          if (externalStage && ['localhost', '127.0.0.1', '::1'].includes(parsed.hostname)) fail(check, '外部 stage 禁止 localhost CORS origin');
        } catch { fail(check, 'CORS origin URL 格式无效'); }
      }
      }
      break;
    case 'positive-integer':
      if (!/^\d+$/.test(value) || Number(value) <= 0) fail(check, '必须为正整数');
      break;
    case 'sha256':
      if (!/^(sha256:)?[a-f0-9]{64}$/i.test(value)) fail(check, '必须为 SHA-256');
      break;
    case 'project':
      if (!/^[a-z0-9][a-z0-9_-]{1,62}$/i.test(value)) fail(check, 'Compose 项目名格式无效');
      break;
    case 'run-id':
    case 'release-id':
    case 'approval-id':
      if (!/^[a-z0-9][a-z0-9._:-]{2,127}$/i.test(value)) fail(check, `${variable.format} 格式无效`);
      break;
    case 'path': {
      const normalized = value.replaceAll('\\', '/');
      if (path.isAbsolute(value) || normalized === '..' || normalized.startsWith('../') || normalized.includes('/../')) {
        fail(check, '证据目录必须是仓库内相对路径');
      }
      break;
    }
    case 'registry':
      if (/^https?:\/\//i.test(value) || /\s/.test(value) || !/^[a-z0-9._:-]+(?:\/[a-z0-9._-]+)+$/i.test(value)) {
        fail(check, 'registry 必须是锁定的 host/namespace 路径且不带 scheme/tag');
      }
      break;
    case 'identity':
    case 'principal':
      if (value.length < 3 || /\s/.test(value)) fail(check, `${variable.format} 格式无效`);
      break;
    case 'secret':
      break;
    case 'boolean':
      if (!/^(true|false)$/i.test(value)) fail(check, '必须为 true/false');
      break;
    case 'identifier':
      if (!/^[a-z][a-z0-9._-]{1,63}$/i.test(value)) fail(check, '标识格式无效');
      break;
    default:
      fail(check, `未实现格式校验: ${variable.format}`);
  }
  if (Array.isArray(variable.allowedValues) && !variable.allowedValues.map(String).includes(value)) {
    fail(check, '当前值不在 contract 允许集合内');
  }
  if (variable.expectedValue !== undefined && variable.expectedValue !== null && String(variable.expectedValue) !== value) {
    fail(check, '当前值与 contract 预期值不一致');
  }
  if (variable.observedValue !== undefined && variable.observedValue !== null && variable.observedValue !== '') {
    const observed = summarize(value, variable.format);
    if (observed !== variable.observedValue) fail(check, '当前值与冻结 contract 不一致');
  }
}

function validateLockedValue(name, expected) {
  if (expected === undefined || expected === null || expected === '') return;
  if (!definitions.has(name)) {
    errors.push(`${name}: 无法应用预期值，contract 缺少定义`);
    return;
  }
  const actual = process.env[name];
  if (placeholder(actual) || String(actual).trim() !== String(expected).trim()) {
    errors.push(`${name}: 当前值与命令行预期值不一致`);
    const check = checks.find((item) => item.name === name);
    if (check) {
      check.passed = false;
      check.reason = check.reason ? `${check.reason}; 与预期值不一致` : '与预期值不一致';
    }
  }
}

function summarize(value, format) {
  if (value === undefined || value === null) return null;
  const text = String(value).trim();
  if (format === 'url') return redactEndpoint(text);
  if (format === 'jdbc-url') return text.replace(/(jdbc:[^:]+:\/\/)[^/]+/i, '$1[host]');
  if (format === 'path') {
    const normalized = text.replaceAll('\\', '/');
    const root = repoRoot.replaceAll('\\', '/').replace(/\/$/, '');
    return normalized.startsWith(`${root}/`) ? normalized.slice(root.length + 1) : `external-path-sha256:${sha256Text(text)}`;
  }
  if (format === 'identity' || format === 'principal') return `sha256:${sha256Text(text)}`;
  return text;
}

function safePath(value) {
  try { return repoRelative(value); } catch { return '[external-path]'; }
}

function validateInputPath(value, label) {
  try {
    const absolute = path.isAbsolute(String(value)) ? path.normalize(String(value)) : path.resolve(repoRoot, String(value));
    const relative = repoRelative(absolute);
    if (!relative || relative === '.') throw new Error('不能是仓库根目录');
    if (!fs.existsSync(absolute)) throw new Error('文件不存在');
    const real = fs.realpathSync(absolute);
    if (!isWithin(repoRoot, real) || !fs.statSync(real).isFile()) throw new Error('文件越出仓库或不是普通文件');
    return {absolute, real, relative};
  } catch (error) {
    errors.push(`${label} 路径无效: ${safePath(value)} (${error.message})`);
    return null;
  }
}

function validateOutputPath(value, label) {
  try {
    const absolute = path.isAbsolute(String(value)) ? path.normalize(String(value)) : path.resolve(repoRoot, String(value));
    const relative = repoRelative(absolute);
    if (!relative || relative === '.') throw new Error('不能是仓库根目录');
    const parent = nearestExistingPath(path.dirname(absolute));
    if (!isWithin(repoRoot, fs.realpathSync(parent))) throw new Error('输出父目录越出仓库');
    if (fs.existsSync(absolute)) {
      const real = fs.realpathSync(absolute);
      if (!isWithin(repoRoot, real) || !fs.statSync(real).isFile()) throw new Error('现有输出不是仓库内普通文件');
    }
    return {absolute, relative};
  } catch (error) {
    errors.push(`${label} 路径无效: ${safePath(value)} (${error.message})`);
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

function samePath(left, right) {
  const a = path.resolve(left);
  const b = path.resolve(right);
  return process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b;
}
