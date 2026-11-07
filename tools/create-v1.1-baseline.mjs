#!/usr/bin/env node

/**
 * Create a reproducible, explicitly BLOCKED v1.1 baseline draft.
 *
 * This producer is deliberately fail-closed. It never reads a path outside
 * the repository, follows a symlink outside the repository, trusts a hash
 * sidecar without re-computing the target, or emits a manifest when one of
 * the required N0 inputs is malformed. The final manifest is produced by
 * finalize-v1.1-baseline.mjs after the remaining N0 gates are complete.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {
  currentCommit,
  generatedAt,
  isGitDirty,
  parseArgs,
  parseJsonBlock,
  readJson,
  repoRelative,
  repoRoot,
  resolvePath,
  sha256File,
  sha256Text,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const noClobber = args['no-clobber'] === true;
const allowedOptions = new Set([
  'commit', 'toolchain', 'snapshot', 'snapshot-inputs', 'lockfiles', 'lockfile-hash',
  'support-matrix', 'support-matrix-hash', 'switches', 'switches-hash',
  'dependency-inventory', 'dependency-inventory-hash', 'backend-context',
  'frontend-context', 'environment-contract', 'environment-contract-hash',
  'fixture-manifest', 'fixture-audit', 'evaluation-manifest', 'evaluation-dictionary',
  'evaluation-dataset', 'test-summary', 'manifest', 'hash-report', 'report', 'no-clobber'
]);

for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!allowedOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const outputArg = args.manifest ?? 'artifacts/v1.1/baseline/manifest.draft.json';
const hashReportArg = args['hash-report'] ?? 'artifacts/v1.1/baseline/baseline-file-hashes.draft.json';
const reportArg = args.report;
const defaultEnvironment = 'artifacts/v1.1/baseline/environment-contract.json';
const defaultSnapshotInputs = 'artifacts/v1.1/baseline/snapshot-input-hashes.json';
const defaultLockfileHash = 'artifacts/v1.1/baseline/lockfile-hash.txt';

// Every path is validated before any file is read. `resolved` contains only
// ordinary files whose real target remains below repoRoot.
const resolved = {};
const relative = {};
const outputInfo = validateDestination(outputArg, 'manifest 输出');
const hashReportInfo = validateDestination(hashReportArg, 'hash report 输出');
const reportInfo = reportArg ? validateDestination(reportArg, 'producer report 输出') : null;

if (outputInfo && hashReportInfo && samePath(outputInfo.absolute, hashReportInfo.absolute)) {
  errors.push('manifest 输出不能与 hash report 输出相同');
}
if (reportInfo && outputInfo && samePath(reportInfo.absolute, outputInfo.absolute)) {
  errors.push('producer report 输出不能覆盖 manifest 输出');
}
if (reportInfo && hashReportInfo && samePath(reportInfo.absolute, hashReportInfo.absolute)) {
  errors.push('producer report 输出不能覆盖 hash report 输出');
}

const requiredInputs = {
  toolchain: args.toolchain,
  snapshot: args.snapshot,
  snapshotInputs: args['snapshot-inputs'] ?? defaultSnapshotInputs,
  lockfiles: args.lockfiles,
  lockfileHash: args['lockfile-hash'] ?? defaultLockfileHash,
  supportMatrix: args['support-matrix'],
  switches: args.switches,
  dependencyInventory: args['dependency-inventory'],
  backendContext: args['backend-context'],
  frontendContext: args['frontend-context'],
  environmentContract: args['environment-contract'] ?? defaultEnvironment
};

for (const [name, value] of Object.entries(requiredInputs)) {
  const info = validateInput(value, `${name} 输入`);
  if (info) {
    resolved[name] = info;
    relative[name] = info.relative;
  }
}

// Optional inputs are validated when supplied (or when a canonical default
// exists). This prevents an absolute/path-traversal reference from being
// silently copied into the draft while allowing Step 1 to run before fixture
// and evaluation gates are complete.
const optionalInputs = {
  fixtureManifest: args['fixture-manifest'],
  fixtureAudit: args['fixture-audit'],
  evaluationManifest: args['evaluation-manifest'],
  evaluationDictionary: args['evaluation-dictionary'] ?? 'evaluation/v1.1/metric-dictionary.json',
  evaluationDataset: args['evaluation-dataset'] ?? 'evaluation/v1.1/dataset-manifest.json',
  testSummary: args['test-summary']
};
for (const [name, value] of Object.entries(optionalInputs)) {
  if (value === undefined || value === null || value === '') continue;
  // Evaluation defaults may not exist until the migration step; that is
  // pending evidence, not an invalid user path.
  if ((name === 'evaluationDictionary' || name === 'evaluationDataset') && !fs.existsSync(resolvePath(value))) {
    warnings.push(`${name} 尚未生成，保持 pending: ${value}`);
    continue;
  }
  const info = validateInput(value, `${name} 输入`);
  if (info) {
    resolved[name] = info;
    relative[name] = info.relative;
  }
}

// Producer hash sidecars are required for the three Step-1 producers. The
// environment contract is generated later in N0; if its sidecar is absent we
// keep the draft blocked via a warning, while an explicitly supplied sidecar
// is always validated.
const sidecarSpecs = [
  {input: 'supportMatrix', arg: args['support-matrix-hash'], required: true},
  {input: 'switches', arg: args['switches-hash'], required: true},
  {input: 'dependencyInventory', arg: args['dependency-inventory-hash'], required: true},
  {input: 'environmentContract', arg: args['environment-contract-hash'], required: false}
];
for (const spec of sidecarSpecs) {
  const target = resolved[spec.input];
  if (!target) continue;
  const candidate = spec.arg ? resolvePath(String(spec.arg)) : siblingHashPath(target.absolute);
  if (!fs.existsSync(candidate) && !spec.arg) {
    const message = `${spec.input} hash sidecar 缺失: ${repoDisplay(candidate)}`;
    if (spec.required) errors.push(message);
    else warnings.push(message);
    continue;
  }
  const sidecar = validateInput(candidate, `${spec.input} hash sidecar`);
  if (!sidecar) continue;
  resolved[`${spec.input}Hash`] = sidecar;
  relative[`${spec.input}Hash`] = sidecar.relative;
  validateHashSidecar(sidecar, target, `${spec.input} hash sidecar`);
}

// Outputs must never overwrite a required input (including a sidecar).
for (const [name, info] of Object.entries(resolved)) {
  if (!info) continue;
  if (outputInfo && samePath(outputInfo.absolute, info.absolute)) errors.push(`manifest 输出不能覆盖输入: ${name}`);
  if (hashReportInfo && samePath(hashReportInfo.absolute, info.absolute)) errors.push(`hash report 输出不能覆盖输入: ${name}`);
  if (reportInfo && samePath(reportInfo.absolute, info.absolute)) errors.push(`producer report 输出不能覆盖输入: ${name}`);
}
for (const [label, info] of [['manifest', outputInfo], ['hash report', hashReportInfo], ['producer report', reportInfo]]) {
  if (info && noClobber && fs.existsSync(info.absolute)) errors.push(`${label} 输出已存在（--no-clobber）: ${info.relative}`);
}

const commit = args.commit ?? currentCommit();
if (commit && commit !== 'unknown' && !/^[0-9a-f]{40}$/i.test(String(commit))) {
  warnings.push(`commit 不是完整 SHA-1，将保持原值并由 finalizer 复核: ${String(commit).slice(0, 16)}`);
}

const support = safeSupport(resolved.supportMatrix);
const switches = safeReadJson(resolved.switches, 'switch snapshot');
const dependencies = safeReadJson(resolved.dependencyInventory, 'dependency inventory');
const environmentContract = safeReadJson(resolved.environmentContract, 'environment contract');
const lockfileInput = safeReadJson(resolved.lockfiles, 'lockfile input hashes');
const snapshotInputs = safeReadJson(resolved.snapshotInputs, 'snapshot input hashes');
const snapshotHash = resolved.snapshot ? firstLine(resolved.snapshot) : null;
const lockfileHash = resolved.lockfileHash ? firstLine(resolved.lockfileHash) : null;

validateToolchain(resolved.toolchain);
validateSnapshotInputs(snapshotInputs, resolved.snapshotInputs, resolved.snapshot, snapshotHash);
validateLockfileInput(lockfileInput, resolved.lockfiles, resolved.lockfileHash, lockfileHash);
validateSupport(support);
validateSwitches(switches);
validateDependencies(dependencies);
validateEnvironmentContract(environmentContract);
validateContextReport(resolved.backendContext, 'backend context');
validateContextReport(resolved.frontendContext, 'frontend context');
validateOptionalArtifacts();

const dirty = isGitDirty();
const candidateTag = gitTagExists('v1.0-candidate');
if (dirty) warnings.push('工作树非 clean，候选标签保持 pending');
if (support?.status !== 'ready-for-review') warnings.push('支持矩阵尚未完成领域负责人评审');
if (dependencies?.status !== 'ready') warnings.push('依赖仍为 environment-pending，不能进入 GA');
if (environmentContract?.status !== 'ready') warnings.push('environment contract 尚未 ready；相关 staging/recovery 步骤保持 pending');

const testSummary = resolved.testSummary
  ? safeReadJson(resolved.testSummary, 'clean test summary')
  : {tests: null, failures: null, errors: null, skipped: null, status: 'pending', reason: '缺少同一次 clean test 的聚合报告'};
if (!resolved.testSummary) {
  warnings.push('未提供 aggregate-test-report summary，禁止把 target/surefire 当作本次干净构建证据');
} else if (testSummary?.status !== 'captured' || (testSummary?.failures ?? 1) !== 0 || (testSummary?.errors ?? 1) !== 0) {
  warnings.push('clean test summary 未达到 captured/0/0');
}

if (!snapshotHash || !/^[a-f0-9]{64}$/i.test(snapshotHash)) warnings.push('snapshot hash 尚未由 hash-files producer 生成');
if (resolved.snapshotInputs && snapshotHash) {
  const actual = hashIfExists(resolved.snapshotInputs.real);
  if (actual && /^[a-f0-9]{64}$/i.test(snapshotHash) && actual.toLowerCase() !== snapshotHash.toLowerCase()) errors.push('snapshot-hash.txt 与 snapshot-input-hashes.json 的 SHA-256 不一致');
}

const manifest = {
  schemaVersion: 'v1.1',
  baseline: 'v1.0-candidate',
  generatedAt: generatedAt(),
  gitCommit: commit,
  tagStatus: candidateTag ? 'created' : 'pending',
  tagReason: candidateTag ? null : (dirty ? '工作树存在未提交变更或尚未获授权创建 tag' : '尚未创建/签名 v1.0-candidate tag'),
  snapshotHash: snapshotHash ?? null,
  snapshotInputFile: artifactRelativeInfo(resolved.snapshotInputs),
  snapshotHashFile: artifactRelativeInfo(resolved.snapshot),
  buildContextHashes: {backend: refInfo(resolved.backendContext), frontend: refInfo(resolved.frontendContext)},
  legacyArtifacts: {image: 'N/A—not produced', database: 'N/A—not produced', activeIndex: 'N/A—not produced'},
  switchSnapshot: refInfo(resolved.switches),
  baselineFileHashes: {path: artifactRelativeInfo(hashReportInfo), sha256: null, algorithm: 'SHA-256', status: 'pending-finalizer'},
  environmentContract: refInfo(resolved.environmentContract),
  dependencyInventory: refInfo(resolved.dependencyInventory),
  supplyChainIdentity: {registryEnv: 'VIO_REGISTRY', cosignIdentityEnv: 'VIO_COSIGN_IDENTITY', cosignIssuerEnv: 'VIO_COSIGN_ISSUER', valuesRecorded: false},
  toolchain: summarizeToolchain(resolved.toolchain),
  javaVersion: toolchainField(resolved.toolchain, 'java'),
  nodeVersion: toolchainField(resolved.toolchain, 'node'),
  npmVersion: toolchainField(resolved.toolchain, 'npm'),
  mavenWrapper: toolchainField(resolved.toolchain, 'maven'),
  dockerVersion: toolchainField(resolved.toolchain, 'docker'),
  lockfileInputFile: artifactRelativeInfo(resolved.lockfiles),
  lockfileSha256: hashIfExists(resolved.lockfiles?.real),
  springBootVersion: pomVersion('spring-boot-starter-parent'),
  springAiCoreVersion: bomVersion('org.springframework.ai', 'spring-ai-bom'),
  springAiAlibabaVersion: bomVersion('com.alibaba.cloud.ai', 'spring-ai-alibaba-bom'),
  supportMatrix: support ? {...support, path: artifactRelativeInfo(resolved.supportMatrix), sha256: hashIfExists(resolved.supportMatrix?.real)} : {path: artifactRelativeInfo(resolved.supportMatrix), sha256: null},
  fixture: {manifest: refInfo(resolved.fixtureManifest), sourceAudit: refInfo(resolved.fixtureAudit), status: resolved.fixtureManifest ? 'captured' : 'pending'},
  evaluation: {
    manifest: refInfo(resolved.evaluationManifest),
    dictionary: artifactRelativeInfo(resolved.evaluationDictionary) ?? artifactRelative(optionalInputs.evaluationDictionary),
    dataset: artifactRelativeInfo(resolved.evaluationDataset) ?? artifactRelative(optionalInputs.evaluationDataset),
    status: resolved.evaluationManifest ? 'captured' : 'pending'
  },
  metricDictionary: {path: artifactRelativeInfo(resolved.evaluationDictionary) ?? artifactRelative(optionalInputs.evaluationDictionary), sha256: hashIfExists(resolved.evaluationDictionary?.real), schemaVersion: 'v1.1', status: resolved.evaluationDictionary ? 'captured' : 'pending'},
  recoveryTargets: {rpoSeconds: positiveEnv('VIO_TARGET_RPO_SECONDS'), rtoSeconds: positiveEnv('VIO_TARGET_RTO_SECONDS'), source: 'ADR-002/recovery policy; replace null only after owner approval'},
  metricThresholds: {source: artifactRelativeInfo(resolved.evaluationDictionary) ?? artifactRelative(optionalInputs.evaluationDictionary), sha256: hashIfExists(resolved.evaluationDictionary?.real), status: resolved.evaluationDictionary ? 'captured' : 'pending'},
  backendTests: testSummary,
  releaseStatus: 'BLOCKED'
};

const hashInputs = Object.entries(resolved)
  .filter(([, info]) => info && (!outputInfo || !samePath(info.absolute, outputInfo.absolute)) && (!hashReportInfo || !samePath(info.absolute, hashReportInfo.absolute)) && (!reportInfo || !samePath(info.absolute, reportInfo.absolute)))
  .map(([name, info]) => ({name, path: info.relative, sha256: hashIfExists(info.real), algorithm: 'SHA-256'}))
  .filter((item) => item.path && item.sha256);
const hashReport = {schemaVersion: 'v1.1', producer: 'create-v1.1-baseline', generatedAt: generatedAt(), gitCommit: commit, files: dedupeEntries(hashInputs), status: errors.length === 0 ? 'draft' : 'blocked', errors: [...errors]};

if (errors.length === 0) {
  safeWriteJson(outputInfo, manifest);
  safeWriteJson(hashReportInfo, hashReport);
}

const report = {schemaVersion: 'v1.1', producer: 'create-v1.1-baseline', generatedAt: generatedAt(), output: outputInfo?.relative ?? '[invalid-path]', hashReport: hashReportInfo?.relative ?? '[invalid-path]', passed: errors.length === 0, status: manifest.releaseStatus, errors, warnings};
if (reportInfo && errors.length === 0) safeWriteJson(reportInfo, report);
console.log(JSON.stringify(report, null, 2));
if (errors.length > 0) process.exitCode = 1;

function validateInput(value, label) {
  if (value === undefined || value === null || String(value).trim() === '') {
    errors.push(`${label} 缺失`);
    return null;
  }
  try {
    const absolute = resolvePath(String(value));
    const rel = repoRelative(absolute);
    if (!rel || rel === '.') throw new Error('不能是仓库根目录');
    if (!fs.existsSync(absolute)) throw new Error('文件不存在');
    const real = fs.realpathSync(absolute);
    if (!isWithin(repoRoot, real) || samePath(real, repoRoot)) throw new Error('符号链接目标越出仓库');
    if (!fs.statSync(real).isFile()) throw new Error('不是普通文件');
    return {absolute, real, relative: rel};
  } catch (error) {
    errors.push(`${label} 路径无效: ${safePath(value)} (${error.message})`);
    return null;
  }
}

function validateDestination(value, label) {
  if (value === undefined || value === null || String(value).trim() === '') {
    errors.push(`${label} 缺失`);
    return null;
  }
  try {
    const absolute = resolvePath(String(value));
    const rel = repoRelative(absolute);
    if (!rel || rel === '.') throw new Error('不能是仓库根目录');
    const parentReal = fs.realpathSync(nearestExistingPath(path.dirname(absolute)));
    if (!isWithin(repoRoot, parentReal)) throw new Error('输出父目录越出仓库');
    if (fs.existsSync(absolute)) {
      const real = fs.realpathSync(absolute);
      if (!isWithin(repoRoot, real)) throw new Error('现有输出符号链接越出仓库');
      if (!fs.statSync(real).isFile()) throw new Error('现有输出不是普通文件');
    }
    return {absolute, relative: rel};
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

function safePath(value) {
  try { return repoRelative(resolvePath(String(value))); } catch { return '[external-path]'; }
}
function repoDisplay(value) { try { return repoRelative(value); } catch { return '[external-path]'; } }
function samePath(left, right) { const a = path.resolve(left); const b = path.resolve(right); return process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b; }
function isWithin(root, target) { const rel = path.relative(root, target); return rel === '' || (!rel.startsWith(`..${path.sep}`) && rel !== '..' && !path.isAbsolute(rel)); }
function siblingHashPath(value) { const extension = path.extname(value); return `${extension ? value.slice(0, -extension.length) : value}.sha256.json`; }

function safeReadJson(info, label) { if (!info) return null; try { return readJson(info.real, label); } catch (error) { errors.push(`${label} 读取失败: ${error.message}`); return null; } }
function safeSupport(info) { if (!info) return null; try { return parseJsonBlock(fs.readFileSync(info.real, 'utf8')); } catch (error) { errors.push(`support matrix JSON 解析失败: ${error.message}`); return null; } }

function validateHashSidecar(sidecarInfo, targetInfo, label) {
  let payload;
  try { payload = JSON.parse(fs.readFileSync(sidecarInfo.real, 'utf8')); } catch (error) { errors.push(`${label} JSON 解析失败: ${error.message}`); return; }
  if (!Array.isArray(payload) && payload?.schemaVersion !== 'v1.1') errors.push(`${label} schemaVersion 必须为 v1.1`);
  if (!payload?.generatedAt && !Array.isArray(payload)) errors.push(`${label} 缺少 generatedAt`);
  if (!payload?.gitCommit && !Array.isArray(payload)) errors.push(`${label} 缺少 gitCommit`);
  const entries = Array.isArray(payload) ? payload : payload?.files;
  if (!Array.isArray(entries) || entries.length === 0) { errors.push(`${label} 必须包含非空 files 数组`); return; }
  const targetPath = targetInfo.relative.toLowerCase();
  const normalizedEntries = [];
  for (const item of entries) {
    const entryPath = normalizeRepoEntry(item?.path);
    if (!entryPath || entryPath === '[invalid-path]') {
      errors.push(`${label} 包含越界/无效 path`);
      continue;
    }
    if (!/^[a-f0-9]{64}$/i.test(String(item?.sha256 ?? ''))) {
      errors.push(`${label} sha256 格式无效: ${entryPath}`);
      continue;
    }
    if (Array.isArray(payload) && (!item.generatedAt || !item.commit)) errors.push(`${label} item 缺少 generatedAt/commit: ${entryPath}`);
    if (!item.algorithm || String(item.algorithm).toUpperCase() !== 'SHA-256') errors.push(`${label} 算法必须为 SHA-256: ${entryPath}`);
    const info = validateInput(entryPath, `${label} item`);
    if (info) {
      const actual = hashIfExists(info.real);
      if (!actual || actual.toLowerCase() !== String(item.sha256).toLowerCase()) errors.push(`${label} item hash 不一致: ${entryPath}`);
    }
    normalizedEntries.push({item, path: entryPath});
  }
  const entry = normalizedEntries.find((candidate) => candidate.path.toLowerCase() === targetPath)?.item;
  if (!entry) { errors.push(`${label} 未包含目标文件: ${targetInfo.relative}`); return; }
  if (!/^[a-f0-9]{64}$/i.test(String(entry.sha256 ?? ''))) { errors.push(`${label} sha256 格式无效: ${targetInfo.relative}`); return; }
  const actual = hashIfExists(targetInfo.real);
  if (!actual || actual.toLowerCase() !== String(entry.sha256).toLowerCase()) errors.push(`${label} 与目标文件 hash 不一致: ${targetInfo.relative}`);
}

function normalizeRepoEntry(value) {
  if (value === undefined || value === null) return '';
  const text = String(value).replaceAll('\\', '/').replace(/^\.\//, '');
  if (path.isAbsolute(String(value)) || text.startsWith('../') || text === '..') return '[invalid-path]';
  return text;
}

function validateToolchain(info) {
  if (!info) return;
  let lines;
  try { lines = fs.readFileSync(info.real, 'utf8').split(/\r?\n/).map((line) => line.trim()).filter(Boolean); } catch (error) { errors.push(`toolchain 读取失败: ${error.message}`); return; }
  const missing = ['java', 'node', 'npm', 'maven', 'docker'].filter((name) => !lines.some((line) => new RegExp(`^${name}\\s*[:=]`, 'i').test(line)));
  if (missing.length > 0) warnings.push(`toolchain 缺少版本行: ${missing.join(', ')}`);
}

function validateSnapshotInputs(payload, snapshotInfo, snapshotHashInfo, snapshotHashValue) {
  if (!snapshotInfo || !payload) return;
  const entries = Array.isArray(payload) ? payload : payload.files;
  if (!Array.isArray(entries) || entries.length === 0) { errors.push('snapshot input hashes 必须是非空数组或包含 files 数组的对象'); return; }
  const required = [
    'artifacts/v1.1/baseline/working-tree.patch', 'artifacts/v1.1/baseline/status-ignored.txt',
    'artifacts/v1.1/baseline/untracked.txt', 'artifacts/v1.1/baseline/ignored.txt',
    'artifacts/v1.1/baseline/untracked-and-ignored.txt', 'artifacts/v1.1/baseline/untracked-hashes.json',
    'artifacts/v1.1/baseline/lockfile-hash.txt', 'artifacts/v1.1/baseline/toolchain.txt',
    'artifacts/v1.1/baseline/lockfile-input-hashes.json', 'artifacts/v1.1/baseline/backend-context-hashes.json',
    'artifacts/v1.1/baseline/frontend-context-hashes.json'
  ];
  const seen = new Set();
  for (const item of entries) {
    const entryPath = normalizeRepoEntry(item?.path);
    const key = entryPath.toLowerCase();
    if (!entryPath || entryPath === '[invalid-path]' || seen.has(key)) { errors.push(`snapshot input 路径无效或重复: ${String(item?.path ?? '')}`); continue; }
    seen.add(key);
    if (key === snapshotInfo.relative.toLowerCase() || key === snapshotHashInfo?.relative?.toLowerCase()) errors.push(`snapshot input 不得包含自身: ${entryPath}`);
    if (!/^[a-f0-9]{64}$/i.test(String(item?.sha256 ?? ''))) { errors.push(`snapshot input hash 格式无效: ${entryPath}`); continue; }
    const info = validateInput(entryPath, 'snapshot input item');
    if (!info) continue;
    const actual = hashIfExists(info.real);
    if (!actual || actual.toLowerCase() !== String(item.sha256).toLowerCase()) errors.push(`snapshot input hash 不一致: ${entryPath}`);
  }
  for (const expected of required) if (!seen.has(expected.toLowerCase())) errors.push(`snapshot input 缺少必需项: ${expected}`);
  if (snapshotHashValue && /^[a-f0-9]{64}$/i.test(snapshotHashValue)) {
    const actual = hashIfExists(snapshotInfo.real);
    if (actual && actual.toLowerCase() !== snapshotHashValue.toLowerCase()) errors.push('snapshot-hash.txt 与 snapshot-input-hashes.json 不一致');
  }
}

function validateLockfileInput(payload, lockfileInfo, hashInfo, hashValue) {
  if (!lockfileInfo || !payload) return;
  if (payload.schemaVersion !== 'v1.1') errors.push('lockfile input schemaVersion 必须为 v1.1');
  if (payload.producer && payload.producer !== 'hash-lockfiles') warnings.push(`lockfile input producer 非 hash-lockfiles: ${payload.producer}`);
  if (!Array.isArray(payload.files) || payload.files.length === 0) { errors.push('lockfile input 必须包含非空 files 数组'); return; }
  const files = [...payload.files].sort((a, b) => String(a.path).localeCompare(String(b.path), 'en'));
  const seen = new Set();
  for (const item of files) {
    const entryPath = normalizeRepoEntry(item?.path);
    if (!entryPath || entryPath === '[invalid-path]' || seen.has(entryPath.toLowerCase())) { errors.push(`lockfile input 路径无效或重复: ${String(item?.path ?? '')}`); continue; }
    seen.add(entryPath.toLowerCase());
    if (!/^[a-f0-9]{64}$/i.test(String(item?.sha256 ?? ''))) errors.push(`lockfile hash 格式无效: ${entryPath}`);
    const info = validateInput(entryPath, 'lockfile item');
    if (!info) continue;
    const actual = hashIfExists(info.real);
    if (!actual || actual.toLowerCase() !== String(item.sha256).toLowerCase()) errors.push(`lockfile hash 不一致: ${entryPath}`);
    if (item.bytes !== undefined && Number(item.bytes) !== fs.statSync(info.real).size) errors.push(`lockfile bytes 不一致: ${entryPath}`);
  }
  const canonical = `${JSON.stringify(files, null, 2)}\n`;
  const aggregate = sha256Text(canonical);
  if (!/^[a-f0-9]{64}$/i.test(String(payload.aggregateSha256 ?? '')) || aggregate.toLowerCase() !== String(payload.aggregateSha256).toLowerCase()) errors.push('lockfile aggregateSha256 不一致');
  if (hashValue && hashValue.toLowerCase() !== aggregate.toLowerCase()) errors.push('lockfile-hash.txt 与 lockfile aggregateSha256 不一致');
  if (hashInfo && samePath(hashInfo.absolute, lockfileInfo.absolute)) errors.push('lockfile hash 文本不能覆盖 lockfile input JSON');
}

function validateSupport(value) {
  if (!value || value.schemaVersion !== 'v1.1') { errors.push('support matrix schemaVersion 必须为 v1.1'); return; }
  if (!value.owner || !value.generatedAt || !value.gitCommit) errors.push('support matrix 缺少 owner/generatedAt/gitCommit');
  if (!value.id || !value.game_release_id || !value.language || !value.data_release_id) errors.push('support matrix canonical 字段不完整');
  if (!nonEmptyList(value.battle_format_id) || !nonEmptyList(value.ruleset_version_id)) errors.push('support matrix battle/ruleset 字段不能为空');
  if (!value.producer) warnings.push('support matrix JSON block 未记录 producer；由外层 producer report 追溯');
}

function validateSwitches(value) {
  if (!value || value.schemaVersion !== 'v1.1') { errors.push('switch snapshot schemaVersion 必须为 v1.1'); return; }
  if (value.producer !== 'export-switch-snapshot') errors.push('switch snapshot producer 必须为 export-switch-snapshot');
  if (!Array.isArray(value.switches) || value.switches.length === 0) { errors.push('switch snapshot 必须包含非空 switches 数组'); return; }
  const names = new Set();
  for (const item of value.switches) {
    if (!item?.name || !/^[A-Z][A-Z0-9_]+$/.test(String(item.name)) || names.has(String(item.name))) errors.push(`switch 名称无效或重复: ${String(item?.name ?? '')}`);
    names.add(String(item?.name ?? ''));
    if (!item.currentDefault || !item.targetDefault || !item.owner) errors.push(`switch 字段不完整: ${String(item?.name ?? '')}`);
  }
  const expected = ['VIO_AGENT_V1_ENABLED', 'VIO_RAG_PGVECTOR_ENABLED', 'VIO_RUN_DURABLE_RESUME', 'VIO_EVENT_BROKER_ENABLED', 'VIO_REMOTE_MCP_ENABLED', 'VIO_MCP_ENABLED', 'VIO_HIGH_RISK_TOOLS_ENABLED', 'VIO_MEMORY_ENABLED', 'VIO_FEEDBACK_ENABLED', 'VIO_LEGACY_AI_ENABLED', 'VIO_LEGACY_MANUS_ENABLED', 'VIO_REAL_EVAL_GATE', 'VIO_CANARY_ENABLED'];
  for (const name of expected) if (!names.has(name)) errors.push(`switch snapshot 缺少必需开关: ${name}`);
}

function validateDependencies(value) {
  if (!value || value.schemaVersion !== 'v1.1') { errors.push('dependency inventory schemaVersion 必须为 v1.1'); return; }
  if (value.producer !== 'export-dependency-inventory') errors.push('dependency inventory producer 必须为 export-dependency-inventory');
  if (!Array.isArray(value.dependencies) || value.dependencies.length === 0) { errors.push('dependency inventory 必须包含非空 dependencies 数组'); return; }
  const names = new Set();
  for (const item of value.dependencies) {
    const name = String(item?.name ?? '');
    if (!name || names.has(name)) errors.push(`依赖名称无效或重复: ${name}`);
    names.add(name);
    for (const field of ['version', 'imageOrEndpointDigest', 'source', 'healthProbe', 'networkBoundary', 'requiredStage', 'status']) if (!(field in (item ?? {}))) errors.push(`依赖缺少字段 ${field}: ${name}`);
    if (typeof item?.version === 'string' && /(?:^|[/:])latest(?:$|[.@])/i.test(item.version)) errors.push(`依赖禁止浮动 tag: ${name}`);
  }
  for (const name of ['postgresql-pgvector', 'redis', 'dashscope-chat-model', 'dashscope-embedding', 'search-api']) if (!names.has(name)) errors.push(`依赖 inventory 缺少 P0 依赖: ${name}`);
}

function validateEnvironmentContract(value) {
  if (!value || value.schemaVersion !== 'v1.1') { errors.push('environment contract schemaVersion 必须为 v1.1'); return; }
  if (value.producer !== 'create-environment-contract') errors.push('environment contract producer 不匹配');
  if (!Array.isArray(value.variables) || value.variables.length === 0) { errors.push('environment contract 必须包含非空 variables 数组'); return; }
  const names = new Set();
  for (const variable of value.variables) {
    const name = String(variable?.name ?? '');
    if (!/^[A-Z][A-Z0-9_]+$/.test(name) || names.has(name)) errors.push(`environment variable 名称无效或重复: ${name}`);
    names.add(name);
    if (!variable.kind || !variable.format || !Array.isArray(variable.requiredStages)) errors.push(`environment variable 字段不完整: ${name}`);
    if (String(variable.kind).toLowerCase() === 'secret' && ('value' in (variable ?? {}) || 'resolvedValue' in (variable ?? {}) || 'observedValue' in (variable ?? {}))) errors.push(`Secret 变量禁止写入值: ${name}`);
  }
}

function validateContextReport(info, label) {
  const value = safeReadJson(info, label);
  if (!value) return;
  if (value.schemaVersion !== 'v1.1' || value.producer !== 'hash-build-context') errors.push(`${label} schemaVersion/producer 不匹配`);
  if (value.passed !== true) errors.push(`${label} producer 未通过`);
  if (!/^[a-f0-9]{64}$/i.test(String(value.contextSha256 ?? ''))) errors.push(`${label} contextSha256 格式无效`);
  if (!Array.isArray(value.files) || value.files.length === 0) { errors.push(`${label} files 不能为空`); return; }
  let context;
  try {
    context = resolvePath(value.context ?? '.');
    const contextReal = fs.realpathSync(context);
    if (!isWithin(repoRoot, contextReal) || !fs.statSync(contextReal).isDirectory()) throw new Error('context 越出仓库或不是目录');
    context = contextReal;
  } catch (error) { errors.push(`${label} context 无效: ${error.message}`); return; }
  const seen = new Set();
  const entries = [];
  for (const item of value.files) {
    const p = normalizeContextEntry(item?.path);
    if (!p || seen.has(p)) { errors.push(`${label} 文件路径无效或重复: ${String(item?.path ?? '')}`); continue; }
    seen.add(p);
    if (!/^[a-f0-9]{64}$/i.test(String(item?.sha256 ?? ''))) { errors.push(`${label} 文件 hash 格式无效: ${p}`); continue; }
    const file = path.resolve(context, p);
    try {
      const real = fs.realpathSync(file);
      if (!isWithin(context, real) || !fs.statSync(real).isFile()) throw new Error('文件越出 context 或不是普通文件');
      if (isSecretPath(p)) throw new Error('Secret 文件进入 context');
      const actual = hashIfExists(real);
      if (!actual || actual.toLowerCase() !== String(item.sha256).toLowerCase()) throw new Error('hash 不一致');
    } catch (error) { errors.push(`${label} 文件 ${p} 校验失败: ${error.message}`); }
    entries.push({path: p, sha256: String(item.sha256).toLowerCase()});
  }
  entries.sort((a, b) => a.path.localeCompare(b.path, 'en'));
  const aggregate = crypto.createHash('sha256');
  for (const item of entries) aggregate.update(`${item.path}\0${item.sha256}\n`, 'utf8');
  if (aggregate.digest('hex').toLowerCase() !== String(value.contextSha256).toLowerCase()) errors.push(`${label} contextSha256 聚合值不一致`);
  for (const field of ['dockerfile', 'dockerignore']) {
    if (!value[field]) { errors.push(`${label} 缺少 ${field}`); continue; }
    try {
      const dockerPath = path.resolve(repoRoot, String(value[field]));
      const dockerReal = fs.realpathSync(dockerPath);
      if (!isWithin(context, dockerReal) || !fs.statSync(dockerReal).isFile()) throw new Error('路径不在 context 内或不是普通文件');
      const expectedHash = field === 'dockerfile' ? value.dockerfileSha256 : value.dockerignoreSha256;
      if (!/^[a-f0-9]{64}$/i.test(String(expectedHash ?? '')) || hashIfExists(dockerReal).toLowerCase() !== String(expectedHash).toLowerCase()) throw new Error('hash 不一致');
    } catch (error) { errors.push(`${label} ${field} 校验失败: ${error.message}`); }
  }
}

function validateOptionalArtifacts() {
  if (resolved.fixtureManifest) validateManifestFiles(resolved.fixtureManifest, 'fixture manifest');
  if (resolved.evaluationManifest) validateSchemaOnly(resolved.evaluationManifest, 'evaluation manifest');
  if (resolved.evaluationDictionary) validateSchemaOnly(resolved.evaluationDictionary, 'evaluation dictionary');
  if (resolved.evaluationDataset) validateSchemaOnly(resolved.evaluationDataset, 'evaluation dataset');
  if (resolved.fixtureAudit) validateSchemaOnly(resolved.fixtureAudit, 'fixture source audit');
}
function validateManifestFiles(info, label) { const value = safeReadJson(info, label); if (!value) return; if (value.schemaVersion !== 'v1.1') errors.push(`${label} schemaVersion 必须为 v1.1`); if (!Array.isArray(value.files)) warnings.push(`${label} 未包含 files 数组`); }
function validateSchemaOnly(info, label) { const value = safeReadJson(info, label); if (value && value.schemaVersion !== 'v1.1') errors.push(`${label} schemaVersion 必须为 v1.1`); }
function normalizeContextEntry(value) { if (value === undefined || value === null) return ''; const text = String(value).replaceAll('\\', '/').replace(/^\.\//, ''); if (!text || text === '..' || text.startsWith('../') || path.isAbsolute(String(value))) return ''; return text; }
function isSecretPath(value) { if (/(^|\/)\.env(?:\.[^/]+)?\.example$/i.test(value)) return false; return /(^|\/)(?:\.env(?:\.|$)|application-local\.(?:yml|yaml|properties)(?:\.|$)|mcp-servers\.json(?:\.|$))/i.test(value); }
function nonEmptyList(value) { return Array.isArray(value) ? value.length > 0 && value.every((item) => typeof item === 'string' && item.trim()) : typeof value === 'string' && value.trim() !== ''; }
function hashIfExists(value) { try { return value && fs.existsSync(value) && fs.statSync(value).isFile() ? sha256File(value) : null; } catch { return null; } }
function firstLine(info) { try { return fs.readFileSync(info.real, 'utf8').split(/\r?\n/).map((line) => line.trim()).find(Boolean) ?? null; } catch { return null; } }
function artifactRelativeInfo(info) { return info ? artifactRelative(info.relative) : null; }
function artifactRelative(value) { if (!value) return null; const text = typeof value === 'string' ? value : value.relative; if (!text) return null; const marker = 'artifacts/v1.1/'; return text.startsWith(marker) ? text.slice(marker.length) : text; }
function refInfo(info) { if (!info) return {path: 'missing', sha256: null, algorithm: 'SHA-256', status: 'pending'}; const hash = hashIfExists(info.real); return {path: artifactRelativeInfo(info), sha256: hash, algorithm: 'SHA-256', status: hash ? 'captured' : 'pending'}; }
function summarizeToolchain(info) { if (!info) return {status: 'pending', summary: null}; let lines; try { lines = fs.readFileSync(info.real, 'utf8').split(/\r?\n/).filter(Boolean); } catch { return {status: 'pending', summary: null}; } const missing = ['java', 'node', 'npm', 'maven', 'docker'].filter((name) => !lines.some((line) => new RegExp(`^${name}\\s*[:=]`, 'i').test(line.trim()))); return {status: missing.length === 0 ? 'captured' : 'pending', summary: lines.slice(0, 20).join(' | '), sha256: hashIfExists(info.real), missing}; }
function toolchainField(info, name) { if (!info) return null; try { const line = fs.readFileSync(info.real, 'utf8').split(/\r?\n/).find((item) => new RegExp(`^${name}\\s*[:=]`, 'i').test(item.trim())); return line ? line.replace(new RegExp(`^${name}\\s*[:=]\\s*`, 'i'), '').trim() : null; } catch { return null; } }
function positiveEnv(name) { const value = process.env[name]; return /^\d+$/.test(value ?? '') && Number(value) > 0 ? Number(value) : null; }
function pomVersion(groupId, artifactId) { const file = resolvePath('pom.xml'); if (!fs.existsSync(file)) return null; const text = fs.readFileSync(file, 'utf8'); if (groupId === 'spring-boot-starter-parent') return text.match(/<parent>[\s\S]*?<artifactId>spring-boot-starter-parent<\/artifactId>[\s\S]*?<version>([^<]+)<\/version>[\s\S]*?<\/parent>/)?.[1] ?? null; const pattern = artifactId ? new RegExp(`<groupId>${groupId.replaceAll('.', '\\\\.')}</groupId>[\\s\\S]{0,200}<artifactId>${artifactId}</artifactId>[\\s\\S]{0,200}<version>([^<]+)</version>`) : new RegExp(`<artifactId>${groupId}</artifactId>[\\s\\S]{0,200}<version>([^<]+)</version>`); return text.match(pattern)?.[1] ?? null; }
function bomVersion(groupId, artifactId) { const file = resolvePath('pom.xml'); if (!fs.existsSync(file)) return null; const text = fs.readFileSync(file, 'utf8'); const block = text.match(new RegExp(`<groupId>${groupId.replaceAll('.', '\\\\.')}</groupId>[\\s\\S]{0,200}<artifactId>${artifactId}</artifactId>[\\s\\S]{0,300}`))?.[0]; return block?.match(/<version>([^<]+)<\/version>/)?.[1] ?? null; }
function gitTagExists(tag) { try { return Boolean(execFileSync('git', ['rev-parse', '--verify', `refs/tags/${tag}`], {cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore']}).trim()); } catch { return false; } }
function dedupeEntries(entries) { const seen = new Set(); return entries.filter((item) => { const key = item.path.toLowerCase(); if (seen.has(key)) return false; seen.add(key); return true; }).sort((a, b) => a.path.localeCompare(b.path, 'en')); }
function safeWriteJson(info, payload) { try { writeJson(info.absolute, payload, {noClobber}); } catch (error) { errors.push(`写入 ${info.relative} 失败: ${error.message}`); } }
