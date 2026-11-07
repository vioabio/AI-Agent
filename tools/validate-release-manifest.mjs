#!/usr/bin/env node

/**
 * Validate an explicit v1.1 release manifest.
 *
 * This is a structural/evidence validator, not a registry client.  It never
 * runs Docker, contacts a registry, or prints input contents.  A BLOCKED
 * preflight may reference files that are not ready yet; RC/DEMO_ONLY/GA
 * manifests must have every hashed evidence file present and matching.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  collectEvidenceEntries,
  isImageDigest,
  isPlaceholder,
  normalizeHash,
  normalizeSlashes,
  readJsonFile,
  resolveRepoFile,
  resolveRoot,
  safeText,
  scanSecretLikeText,
  sha256File,
  validateManifestStructure,
  writeAtomicJson
} from './lib/release-manifest.mjs';
import {parseArgs} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const checks = [];
const noClobber = args['no-clobber'] === true;
const preflightOnly = boolArg(args['preflight-only']);
let root;
let manifestInfo;
let manifest = null;

const knownOptions = new Set([
  'repo-root', 'manifest', 'schema', 'allow-status', 'require-status',
  'require-release-class', 'require-environment', 'min-observation-hours',
  'min-observation-requests', 'min-requests-per-window', 'preflight-only',
  'report', 'output', 'no-clobber'
]);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

try {
  root = resolveRoot(args['repo-root'] ?? '.');
} catch (error) {
  errors.push(error.message);
}

const manifestArg = args.manifest;
if (!manifestArg || manifestArg === true) {
  errors.push('--manifest 必须显式提供');
} else if (root) {
  try {
    manifestInfo = resolveRepoFile(root, manifestArg, 'release manifest');
    manifest = readJsonFile(manifestInfo.real, 'release manifest');
  } catch (error) {
    errors.push(error.message);
  }
}

if (args.schema && root) {
  try {
    const schemaInfo = resolveRepoFile(root, args.schema, 'release manifest schema');
    const schema = readJsonFile(schemaInfo.real, 'release manifest schema');
    if (schema?.$id && !String(schema.$id).includes('release-manifest-v1.1')) errors.push('schema 不是 release-manifest-v1.1 契约');
  } catch (error) {
    errors.push(error.message);
  }
}

if (manifest && root) {
  const validation = validateManifestStructure(manifest, {
    repoRoot: root,
    manifestPath: manifestInfo.absolute,
    preflightOnly,
    requireReleaseClass: scalar(args['require-release-class']),
    requireEnvironment: scalar(args['require-environment']),
    allowStatuses: list(args['allow-status']),
    requireStatus: scalar(args['require-status']),
    verifyEvidence: true
  });
  errors.push(...validation.errors);
  warnings.push(...validation.warnings);
  checks.push(...validation.checks);
  validateModeAndEvidence(manifest, validation.evidence, root, preflightOnly);
  validateSecretSurface(manifest);
}

const passed = errors.length === 0;
const report = {
  schemaVersion: 'v1.1',
  producer: 'validate-release-manifest',
  generatedAt: new Date().toISOString(),
  repoRoot: root ? '.' : null,
  manifest: manifestInfo && root ? safeRepoPath(manifestInfo.absolute, root) : safeText(manifestArg),
  mode: preflightOnly ? 'preflight' : 'final-validation',
  releaseClass: manifest?.releaseClass ?? null,
  environment: manifest?.environment ?? null,
  status: passed ? 'passed' : 'blocked',
  passed,
  exitCode: passed ? 0 : 1,
  redacted: true,
  checks,
  errors: unique(errors),
  warnings: unique(warnings),
  inputHashes: manifestInfo ? {manifest: sha256File(manifestInfo.real)} : {}
};

const reportArg = args.report ?? args.output;
if (reportArg && root) {
  try {
    const reportInfo = resolveOutput(root, reportArg, 'manifest validation report');
    if (manifestInfo && samePath(reportInfo.absolute, manifestInfo.absolute)) throw new Error('报告不能覆盖 manifest 输入');
    writeAtomicJson(reportInfo.absolute, report, {noClobber});
  } catch (error) {
    report.errors.push(`报告写入失败: ${safeText(error.message)}`);
    report.status = 'blocked'; report.passed = false; report.exitCode = 1;
  }
}

console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;

function validateModeAndEvidence(value, evidence, repoRoot, preflight) {
  const releaseClass = String(value.releaseClass ?? '');
  const status = String(value.status ?? '');
  const environment = String(value.environment ?? '');
  if (preflight && status !== 'BLOCKED') errors.push('preflight-only 只接受 status=BLOCKED，不能验证已发布 manifest');
  if (!preflight && status === 'BLOCKED') warnings.push('status=BLOCKED 仍是候选/预检状态，不能作为发布签收');
  if (releaseClass === 'GA' && status === 'GA' && environment !== 'prod') errors.push('GA manifest 必须是 prod 环境');
  if (releaseClass === 'DEMO_ONLY' && status === 'DEMO_ONLY' && environment !== 'staging') errors.push('DEMO_ONLY manifest 必须是 staging 环境');
  const expectedHours = numberArg(args['min-observation-hours'], releaseClass === 'GA' ? 72 : 24);
  const expectedRequests = numberArg(args['min-observation-requests'], releaseClass === 'GA' ? 500 : 100);
  const expectedPerWindow = numberArg(args['min-requests-per-window'], 20);
  if (value.observationWindowHours !== undefined && Number(value.observationWindowHours) !== expectedHours) errors.push('observationWindowHours 与 gate 要求不一致');
  if (value.minRequests !== undefined && Number(value.minRequests) !== expectedRequests) errors.push('minRequests 与 gate 要求不一致');
  if (value.minRequestsPerWindow !== undefined && Number(value.minRequestsPerWindow) !== expectedPerWindow) errors.push('minRequestsPerWindow 与 gate 要求不一致');
  if (releaseClass === 'GA' && status === 'GA') {
    if (Number(value.observationWindowHours) < 72 || Number(value.minRequests) < 500) errors.push('GA 观察期至少 72 小时/500 请求');
  }
  if (releaseClass === 'DEMO_ONLY' && status === 'DEMO_ONLY') {
    if (Number(value.observationWindowHours) < 24 || Number(value.minRequests) < 100) errors.push('DEMO_ONLY 观察期至少 24 小时/100 请求');
  }

  const paths = evidence.map((item) => normalizeSlashes(item.path));
  if (!preflight && status !== 'BLOCKED') {
    if (releaseClass === 'GA' && paths.some((item) => item.includes('staging/'))) errors.push('GA evidence 禁止引用 staging 路径');
    if (releaseClass === 'DEMO_ONLY' && paths.some((item) => item.includes('prod/'))) errors.push('DEMO_ONLY evidence 禁止引用 prod 路径');
    const expectedRoot = releaseClass === 'GA' ? 'prod/' : (releaseClass === 'DEMO_ONLY' ? 'staging/' : null);
    if (expectedRoot && !paths.some((item) => item.includes(expectedRoot))) errors.push('发布 evidence 缺少与环境绑定的 prod/staging 证据路径');
  }
  for (const item of evidence) {
    if (item.sha256 !== null && !/^(?:sha256:)?[a-f0-9]{64}$/i.test(String(item.sha256))) errors.push(`${item.location}: sha256 格式无效`);
  }
  // A manifest may only advertise a digest-shaped image, even in BLOCKED
  // preflight.  The shared validator already checks this; this check keeps a
  // clear N8-specific reason in the report.
  for (const name of ['backend', 'frontend']) {
    const raw = typeof value.images?.[name] === 'string' ? value.images[name] : value.images?.[name]?.repoDigest;
    if (raw && !isImageDigest(raw)) errors.push(`images.${name}: digest 不是不可变引用`);
  }
  if (manifestInfo && !within(repoRoot, manifestInfo.absolute)) errors.push('manifest 不在 repo-root 内');
}

function validateSecretSurface(value) {
  const findings = scanSecretLikeText(JSON.stringify(value));
  if (findings.length > 0) {
    for (const finding of findings) errors.push(`manifest secret surface: ${finding.reasonCode} (${finding.key})`);
  }
}

function resolveOutput(repoRoot, value, label) {
  const raw = String(value ?? '').trim();
  if (!raw) throw new Error(`${label} 路径为空`);
  const absolute = path.isAbsolute(raw) ? path.normalize(raw) : path.resolve(repoRoot, raw);
  if (!within(repoRoot, absolute) || samePath(repoRoot, absolute)) throw new Error(`${label} 越出 repo-root`);
  let parent = path.dirname(absolute);
  while (!fs.existsSync(parent)) {
    const next = path.dirname(parent);
    if (next === parent) throw new Error(`${label} 父目录无效`);
    parent = next;
  }
  const realParent = fs.realpathSync(parent);
  if (!within(repoRoot, realParent)) throw new Error(`${label} 父目录越出 repo-root`);
  if (fs.existsSync(absolute)) {
    const real = fs.realpathSync(absolute);
    if (!within(repoRoot, real) || !fs.statSync(real).isFile()) throw new Error(`${label} 已存在但不是仓库内普通文件`);
  }
  return {absolute};
}

function safeRepoPath(file, repoRoot) {
  return path.relative(repoRoot, file).split(path.sep).join('/');
}
function samePath(a, b) {
  const left = path.resolve(a); const right = path.resolve(b);
  return process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right;
}
function within(root, target) {
  const rel = path.relative(path.resolve(root), path.resolve(target));
  return rel === '' || (rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel));
}
function list(value) {
  if (value === undefined || value === null || value === '' || value === true || value === false) return [];
  return (Array.isArray(value) ? value : [value]).flatMap((item) => String(item).split(',')).map((item) => item.trim()).filter(Boolean);
}
function scalar(value) { return value === undefined || value === true || value === false ? null : String(value).trim() || null; }
function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function numberArg(value, fallback) {
  if (value === undefined || value === true || value === false || String(value).trim() === '') return fallback;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}
function unique(values) { return [...new Set(values.map((item) => String(item)))]; }
