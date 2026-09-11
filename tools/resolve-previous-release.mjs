#!/usr/bin/env node

/**
 * Resolve the unique previous production GA release from an offline registry
 * snapshot. The command never sorts releases and never calls a remote API:
 * the previous pointer (or one explicitly reviewed registry record) must be
 * present and hash-bound.
 */
import fs from 'node:fs';
import {
  baseResult,
  digestFromManifest,
  hashInfo,
  inputFile,
  isImageDigest,
  isPlaceholder,
  isSha256,
  normalizeHash,
  outputFile,
  pathsEquivalent,
  readSafeJson,
  redactRegistryRef,
  registryRecords,
  registrySource,
  relative,
  resolveRoot,
  resolvePointerFile,
  safeId,
  safePointerSummary,
  safeText,
  samePath,
  unique,
  validateExpiry,
  validateManifestBinding,
  validateSchemaDocument,
  validateTimestamp,
  writeResult
} from './lib/release-pointer.mjs';
import {parseArgs} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const checks = [];
const noClobber = boolArg(args['no-clobber']);
const allowPending = boolArg(args['allow-pending']) || boolArg(args['what-if']);
const known = new Set([
  'repo-root', 'registry', 'registry-file', 'active-pointer', 'environment',
  'require-environment', 'require-release-class', 'require-status', 'schema',
  'candidate', 'previous-record', 'expected-registry', 'output', 'report',
  'allow-pending', 'what-if', 'no-clobber'
]);

for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!known.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

let root = null;
let registry = null;
let registryInfo = null;
let active = null;
let activeManifest = null;
let activeManifestInfo = null;
let previous = null;
let previousInfo = null;
let previousManifest = null;
let candidate = null;
let candidateInfo = null;
let activeValid = false;
let previousValid = false;
let candidateValid = false;
let outputBlocked = false;
let reportBlocked = false;

try {
  root = resolveRoot(args['repo-root'] ?? '.');
} catch (error) {
  errors.push(`repo-root: ${safeText(error.message)}`);
}

const environment = scalar(args['require-environment']) ?? scalar(args.environment);
const requiredClasses = list(args['require-release-class']);
const requiredStatuses = list(args['require-status']);
const expectedRegistry = scalar(args['expected-registry']);

if (environment !== 'prod') errors.push('上一版本解析只允许 --environment prod');
if (!isFlag(args['active-pointer'])) errors.push('必须显式提供 --active-pointer');
if (!isPathArg(args.candidate)) errors.push('--candidate 必须显式提供当前候选 manifest');
for (const value of ['registry-file', 'candidate', 'previous-record', 'schema', 'output', 'report']) {
  if (args[value] === true || args[value] === false) errors.push(`--${value} 必须是显式路径`);
}
if (requiredClasses.some((value) => value !== 'GA')) errors.push('--require-release-class 只能包含 GA');
if (requiredStatuses.some((value) => value !== 'GA')) errors.push('--require-status 只能包含 GA');
checkOutputCollision();

if (root && errors.length === 0) {
  loadCandidate();
  loadRegistry();
  if (active && !activeValid) active = null;
  if (previous && !previousValid) previous = null;
  if (active && candidate && activeValid && candidateValid) {
    resolveActiveManifest();
    if (activeManifest) validateActiveAgainstCandidate();
  }
  if (previous && candidate && previousValid && candidateValid) {
    resolvePreviousManifest();
    if (previousManifest) validatePreviousAgainstCandidate();
  }
  loadSchema();
}

const passed = errors.length === 0 && Boolean(previousManifest);
const resolutionStatus = passed ? 'resolved' : (allowPending ? 'environment-pending' : 'blocked');
const previousId = previousManifest?.releaseId ?? previous?.releaseId ?? previous?.manifest?.releaseId ?? null;
const outputPayload = {
  ...baseResult('resolve-previous-release', root),
  status: resolutionStatus,
  passed,
  exitCode: passed ? 0 : 1,
  environment: 'prod',
  releaseClass: 'GA',
  releaseStatus: 'GA',
  releaseId: previousId,
  previousReleaseId: previousId,
  currentReleaseId: candidate?.releaseId ?? null,
  pointerVersion: pointerVersion(previous),
  registryVersion: pointerVersion(previous),
  manifest: previousInfo && root ? hashInfo(previousInfo) : null,
  imageDigest: previousManifest ? safeDigest(previousManifest) : safePointerDigest(previous),
  frontendImageDigest: previousManifest ? safeFrontendDigest(previousManifest) : safePointerFrontendDigest(previous),
  pointer: previous ? safePointerSummary(previous) : null,
  activePointer: active ? safePointerSummary(active) : null,
  activeManifest: activeManifestInfo && root ? hashInfo(activeManifestInfo) : null,
  registryUri: registryReference(),
  registry: registryInfo && root ? hashInfo(registryInfo) : null,
  resolvedAt: passed ? new Date().toISOString() : null,
  errors: unique(errors),
  warnings: unique(warnings),
  checks
};

let outputInfo = null;
if (root && isPathArg(args.output) && !outputBlocked) {
  try {
    // A failed or pending resolution must never create a usable previous
    // manifest. Emit only a redacted pending envelope in that case.
    const artifact = passed ? previousManifest : outputPayload;
    outputInfo = writeResult(root, args.output, artifact, {noClobber, label: 'previous manifest'});
    outputPayload.output = relative(root, outputInfo.absolute);
  } catch (error) {
    outputPayload.errors.push(`输出失败: ${safeText(error.message)}`);
    outputPayload.status = 'blocked';
    outputPayload.passed = false;
    outputPayload.exitCode = 1;
  }
}

const report = {
  ...baseResult('resolve-previous-release', root),
  status: outputPayload.status,
  passed: outputPayload.passed,
  exitCode: outputPayload.exitCode,
  environment: 'prod',
  releaseClass: 'GA',
  releaseStatus: 'GA',
  previousReleaseId: outputPayload.previousReleaseId,
  currentReleaseId: outputPayload.currentReleaseId,
  pointerVersion: outputPayload.pointerVersion,
  output: outputInfo && root ? relative(root, outputInfo.absolute) : null,
  registryUri: registryReference(),
  registry: outputPayload.registry,
  pointer: outputPayload.pointer,
  activePointer: outputPayload.activePointer,
  errors: unique(outputPayload.errors),
  warnings: unique(outputPayload.warnings),
  checks
};

if (root && isPathArg(args.report) && !reportBlocked) {
  try {
    writeResult(root, args.report, report, {noClobber, label: 'previous release report'});
  } catch (error) {
    report.errors.push(`报告输出失败: ${safeText(error.message)}`);
    report.passed = false;
    report.status = 'blocked';
    report.exitCode = 1;
  }
}

console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;

function loadCandidate() {
  try {
    const loaded = readSafeJson(root, args.candidate, 'candidate manifest');
    candidateInfo = loaded.info;
    candidate = loaded.data;
    validateManifestBinding(candidate, {environment: 'prod'});
    if (!['GA', 'RC'].includes(String(candidate.releaseClass))) throw new Error('candidate releaseClass 必须为 GA 或 RC');
    if (!['BLOCKED', 'RC'].includes(String(candidate.status))) throw new Error('candidate status 必须为 BLOCKED 或 RC，不能预先标记 GA');
    validateTimestamp(candidate.generatedAt, 'candidate generatedAt');
    candidateValid = true;
  } catch (error) {
    errors.push(`candidate: ${safeText(error.message)}`);
  }
}

function loadRegistry() {
  try {
    const source = registrySource(root, args.registry, args['registry-file']);
    registry = source.data;
    registryInfo = source.info;
    if (!registry || typeof registry !== 'object' || Array.isArray(registry)) throw new Error('registry snapshot 必须是 JSON 对象');
    if (registry.schemaVersion !== 'v1.1') errors.push('registry snapshot schemaVersion 必须为 v1.1');
    if (registry.registryVersion !== undefined) {
      const registryVersion = Number(registry.registryVersion);
      if (!Number.isInteger(registryVersion) || registryVersion <= 0) errors.push('registry registryVersion 必须为正整数');
      else {
        if (registry.active?.registryVersion !== undefined && Number(registry.active.registryVersion) !== registryVersion) errors.push('active pointer registryVersion 与 registry snapshot 不一致');
        if (registry.previous?.registryVersion !== undefined && Number(registry.previous.registryVersion) !== registryVersion) errors.push('previous pointer registryVersion 与 registry snapshot 不一致');
      }
    }
    active = pointerSlot(registry, 'active');
    previous = isPathArg(args['previous-record'])
      ? readSafeJson(root, args['previous-record'], 'previous record').data
      : pointerSlot(registry, 'previous');
    if (registry.registryVersion !== undefined) {
      const registryVersion = Number(registry.registryVersion);
      if (active?.registryVersion !== undefined && Number(active.registryVersion) !== registryVersion) errors.push('active pointer registryVersion 与 registry snapshot 不一致');
      if (previous?.registryVersion !== undefined && Number(previous.registryVersion) !== registryVersion) errors.push('previous pointer registryVersion 与 registry snapshot 不一致');
    }
    if (!active || typeof active !== 'object' || Array.isArray(active)) {
      errors.push('registry 缺少 active pointer；不能证明当前生产链路');
    } else {
      const before = errors.length;
      validatePointerRecord(active, 'active pointer', {allowRc: true});
      activeValid = errors.length === before;
    }
    if (!previous) {
      // A single exact GA record is acceptable as an explicitly reviewed
      // snapshot; multiple records are intentionally never sorted/guessed.
      const candidates = registryRecords(registry).filter((row) => row && row.releaseClass === 'GA' && row.environment === 'prod' && effectiveStatus(row) === 'GA');
      if (candidates.length === 1) previous = candidates[0];
      else errors.push('registry 缺少唯一 previous pointer；禁止按时间猜测上一版本');
    }
    if (!previous || typeof previous !== 'object' || Array.isArray(previous)) {
      errors.push('previous pointer 必须是 JSON 对象');
    } else {
      const before = errors.length;
      validatePointerRecord(previous, 'previous pointer', {allowRc: false});
      previousValid = errors.length === before;
    }
    if (active && previous && active.releaseId && previous.releaseId && active.releaseId === previous.releaseId) {
      errors.push('active pointer 与 previous pointer releaseId 不能相同');
    }
    if (active?.registry && previous?.registry && !sameRef(active.registry, previous.registry)) {
      errors.push('active pointer 与 previous pointer registry 不一致');
    }
    if (expectedRegistry && active?.registry && !sameRef(active.registry, expectedRegistry)) errors.push('active pointer registry 不匹配');
    if (args.registry && !isLocalRegistryPath(args.registry) && active?.registry && !sameRef(active.registry, args.registry)) errors.push('active pointer registry 与 --registry 不匹配');
    if (args.registry && !isLocalRegistryPath(args.registry) && previous?.registry && !sameRef(previous.registry, args.registry)) errors.push('previous pointer registry 与 --registry 不匹配');
  } catch (error) {
    errors.push(`registry: ${safeText(error.message)}`);
    if (allowPending) warnings.push('registry 不可离线解析；输出保持 environment-pending');
  }
}

function resolvePreviousManifest() {
  try {
    const info = resolvePointerFile(root, previous, 'previous manifest');
    const loaded = readSafeJson(root, info.relative, 'previous manifest');
    previousInfo = info;
    previousManifest = loaded.data;
    validateManifestBinding(previousManifest, {environment: 'prod', releaseClass: 'GA', status: 'GA'});
    const pointerHash = previous.manifestSha256 ?? previous.manifest?.sha256;
    if (!isSha256(pointerHash) || normalizeHash(pointerHash) !== hashInfo(info).sha256) throw new Error('previous pointer manifestSha256 与文件不匹配');
    if (previous.releaseId !== previousManifest.releaseId) throw new Error('previous pointer/manifest releaseId 不一致');
    if (previous.releaseClass !== previousManifest.releaseClass) throw new Error('previous pointer/manifest releaseClass 不一致');
    if (previous.environment !== 'prod') throw new Error('previous pointer environment 必须为 prod');
    if (previous.registry && previousManifest.registry && !sameRef(previous.registry, previousManifest.registry)) throw new Error('previous pointer/manifest registry 不一致');
    if (effectiveStatus(previous) !== 'GA') throw new Error('previous pointer status 必须为 GA');
    if ((previous.imageDigest ?? previous.images?.backend) !== digestFromManifest(previousManifest, 'backend')) throw new Error('previous pointer/backend digest 不一致');
    const frontendPointer = previous.frontendImageDigest ?? previous.images?.frontend;
    if (frontendPointer !== undefined && frontendPointer !== digestFromManifest(previousManifest, 'frontend')) throw new Error('previous pointer/frontend digest 不一致');
    validateTimestamp(previousManifest.generatedAt, 'previous manifest generatedAt');
    validateExpiry(previousManifest.expiresAt, 'previous manifest expiresAt');
    validateExpiry(previous.expiresAt, 'previous pointer expiresAt');
    if (previous.expiresAt !== undefined && previousManifest.expiresAt !== undefined
        && new Date(previous.expiresAt).getTime() !== new Date(previousManifest.expiresAt).getTime()) {
      throw new Error('previous pointer/manifest expiresAt 不一致');
    }
    checks.push({name: 'previous-pointer-manifest-binding', passed: true});
  } catch (error) {
    errors.push(`previous manifest: ${safeText(error.message)}`);
  }
}

function resolveActiveManifest() {
  try {
    const info = resolvePointerFile(root, active, 'active manifest');
    const loaded = readSafeJson(root, info.relative, 'active manifest');
    activeManifestInfo = info;
    activeManifest = loaded.data;
    validateManifestBinding(activeManifest, {environment: 'prod', releaseClass: 'GA'});
    const pointerHash = active.manifestSha256 ?? active.manifest?.sha256;
    if (!isSha256(pointerHash) || normalizeHash(pointerHash) !== hashInfo(info).sha256) throw new Error('active pointer manifestSha256 与文件不匹配');
    if (active.releaseId !== activeManifest.releaseId) throw new Error('active pointer/manifest releaseId 不一致');
    if (active.releaseClass !== activeManifest.releaseClass) throw new Error('active pointer/manifest releaseClass 不一致');
    if (active.environment !== activeManifest.environment) throw new Error('active pointer/manifest environment 不一致');
    if (active.registry && activeManifest.registry && !sameRef(active.registry, activeManifest.registry)) throw new Error('active pointer/manifest registry 不一致');
    const pointerStatus = effectiveStatus(active);
    if (pointerStatus && pointerStatus !== activeManifest.status) throw new Error('active pointer/manifest status 不一致');
    if ((active.imageDigest ?? active.images?.backend) !== digestFromManifest(activeManifest, 'backend')) throw new Error('active pointer/backend digest 不一致');
    const frontendPointer = active.frontendImageDigest ?? active.images?.frontend;
    if (frontendPointer !== undefined && frontendPointer !== digestFromManifest(activeManifest, 'frontend')) throw new Error('active pointer/frontend digest 不一致');
    const pointerDate = validateTimestamp(active.updatedAt ?? active.registeredAt, 'active pointer updatedAt');
    const manifestDate = validateTimestamp(activeManifest.generatedAt, 'active manifest generatedAt');
    if (pointerDate.getTime() < manifestDate.getTime()) throw new Error('active pointer 时间早于 manifest generatedAt');
    validateExpiry(activeManifest.expiresAt, 'active manifest expiresAt');
    validateExpiry(active.expiresAt, 'active pointer expiresAt');
    if (active.expiresAt !== undefined && activeManifest.expiresAt !== undefined
        && new Date(active.expiresAt).getTime() !== new Date(activeManifest.expiresAt).getTime()) {
      throw new Error('active pointer/manifest expiresAt 不一致');
    }
    checks.push({name: 'active-pointer-manifest-binding', passed: true});
  } catch (error) {
    errors.push(`active manifest: ${safeText(error.message)}`);
  }
}

function validateActiveAgainstCandidate() {
  if (!activeManifest) return;
  if (activeManifest.releaseId === candidate.releaseId) errors.push('active releaseId 必须与 candidate 不同');
  if (!(new Date(activeManifest.generatedAt).getTime() < new Date(candidate.generatedAt).getTime())) {
    errors.push('active generatedAt 必须早于 candidate');
  }
  checks.push({name: 'active-before-candidate', passed: errors.length === 0});
}

function validatePreviousAgainstCandidate() {
  const previousDate = new Date(previousManifest.generatedAt);
  const candidateDate = new Date(candidate.generatedAt);
  if (previousManifest.releaseId === candidate.releaseId) errors.push('previous releaseId 必须与 candidate 不同');
  if (!(previousDate.getTime() < candidateDate.getTime())) errors.push('previous generatedAt 必须早于 candidate');
  for (const field of ['dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId', 'migrationVersion']) {
    if (isPlaceholder(previousManifest[field]) || isPlaceholder(candidate[field])) errors.push(`release lineage ${field} 不完整`);
  }
  checks.push({name: 'previous-before-candidate', passed: errors.length === 0});
}

function validatePointerRecord(value, label, {allowRc}) {
  if (value.schemaVersion !== 'v1.1') errors.push(`${label} schemaVersion 必须为 v1.1`);
  if (value.pointerVersion !== undefined && value.registryVersion !== undefined
      && Number(value.pointerVersion) !== Number(value.registryVersion)) errors.push(`${label} pointerVersion 与 registryVersion 不一致`);
  if (value.environment !== 'prod') errors.push(`${label} environment 必须为 prod`);
  if (value.releaseClass !== 'GA') errors.push(`${label} releaseClass 必须为 GA`);
  const status = effectiveStatus(value);
  if (status !== 'GA' && !(allowRc && status === 'RC')) errors.push(`${label} release status 不符合 GA 回滚要求`);
  if (!['active', 'resolved', 'GA', 'RC'].includes(String(value.status))) errors.push(`${label} status 无效或仍为 pending`);
  if (['GA', 'RC'].includes(String(value.status)) && status !== String(value.status)) errors.push(`${label} status 与 releaseStatus 不一致`);
  try { safeId(value.releaseId, `${label}.releaseId`); } catch (error) { errors.push(error.message); }
  if (!Number.isInteger(pointerVersion(value)) || pointerVersion(value) <= 0) errors.push(`${label} pointerVersion 必须为正整数`);
  if (!isSha256(value.manifestSha256 ?? value.manifest?.sha256)) errors.push(`${label} manifestSha256 缺失或无效`);
  if (!isImageDigest(value.imageDigest ?? value.images?.backend)) errors.push(`${label} imageDigest 缺失或无效`);
  if (value.frontendImageDigest !== undefined && !isImageDigest(value.frontendImageDigest)) errors.push(`${label} frontendImageDigest 无效`);
  if (value.images?.frontend !== undefined && !isImageDigest(value.images.frontend)) errors.push(`${label} images.frontend 无效`);
  if (!value.manifestPath && !value.manifest?.path) errors.push(`${label} manifestPath 缺失`);
  if (!value.registry) errors.push(`${label} registry 缺失`);
  try { validateTimestamp(value.updatedAt ?? value.registeredAt, `${label} updatedAt`); } catch (error) { errors.push(error.message); }
  try { validateExpiry(value.expiresAt, `${label} expiresAt`); } catch (error) { errors.push(error.message); }
}

function loadSchema() {
  if (!isPathArg(args.schema)) return;
  try { validateSchemaDocument(readSafeJson(root, args.schema, 'manifest schema').data); }
  catch (error) { errors.push(`schema: ${safeText(error.message)}`); }
}

function pointerSlot(value, name) {
  const aliases = name === 'active' ? ['active', 'activePointer', 'pointer'] : ['previous', 'previousPointer', 'previousRelease'];
  const present = aliases.filter((key) => Object.prototype.hasOwnProperty.call(value ?? {}, key));
  if (present.length > 1) {
    const first = JSON.stringify(value[present[0]]);
    if (present.some((key) => JSON.stringify(value[key]) !== first)) errors.push(`${name} pointer 别名内容不一致`);
  }
  const selected = present.find((key) => value[key] !== null && value[key] !== undefined);
  return selected ? value[selected] : null;
}
function effectiveStatus(value) { return String(value?.releaseStatus ?? value?.manifestStatus ?? (['GA', 'DEMO_ONLY', 'RC'].includes(String(value?.status)) ? value.status : '')); }
function pointerVersion(value) { return Number(value?.pointerVersion ?? value?.registryVersion ?? NaN); }
function safePointerDigest(value) { const digest = value?.imageDigest ?? value?.images?.backend; return isImageDigest(digest) ? digest : null; }
function safeDigest(value) { try { return digestFromManifest(value, 'backend'); } catch { return null; } }
function safePointerFrontendDigest(value) { const digest = value?.frontendImageDigest ?? value?.images?.frontend; return isImageDigest(digest) ? digest : null; }
function safeFrontendDigest(value) { try { return digestFromManifest(value, 'frontend'); } catch { return null; } }
function sameRef(left, right) { return redactRegistryRef(left) === redactRegistryRef(right); }
function registryReference() {
  const cli = isLocalRegistryPath(args.registry) ? null : args.registry;
  return redactRegistryRef(cli ?? active?.registry ?? previous?.registry ?? registry?.registry ?? registry?.registryUri);
}
function isLocalRegistryPath(value) {
  if (!isPathArg(value) || !root) return false;
  try { inputFile(root, value, 'registry'); return true; } catch { return false; }
}
function checkOutputCollision() {
  if (!root || (!isPathArg(args.output) && !isPathArg(args.report))) return;
  const destinations = [];
  for (const [name, value, flag] of [['output', args.output, 'output'], ['report', args.report, 'report']]) {
    if (!isPathArg(value)) continue;
    try {
      destinations.push({name, flag, info: outputFile(root, value, name === 'output' ? 'previous manifest' : 'previous release report')});
    } catch (error) {
      errors.push(`${name === 'output' ? '输出' : '报告'}路径: ${safeText(error.message)}`);
      if (flag === 'output') outputBlocked = true;
      else reportBlocked = true;
    }
  }
  for (let i = 0; i < destinations.length; i += 1) {
    for (let j = i + 1; j < destinations.length; j += 1) {
      if (samePath(destinations[i].info.absolute, destinations[j].info.absolute)) {
        errors.push('--output 与 --report 不能是同一文件');
        outputBlocked = true;
        reportBlocked = true;
      }
    }
  }
  if (noClobber) {
    for (const destination of destinations) {
      if (fs.existsSync(destination.info.absolute)) {
        errors.push(`${destination.name === 'output' ? '输出' : '报告'}已存在（--no-clobber）`);
        if (destination.flag === 'output') outputBlocked = true;
        else reportBlocked = true;
      }
    }
  }
  const inputs = [
    ['registry-file', args['registry-file']],
    ['registry', isPathArg(args.registry) ? args.registry : null],
    ['candidate', args.candidate],
    ['previous-record', args['previous-record']],
    ['schema', args.schema]
  ];
  for (const [name, value] of inputs) {
    if (!isPathArg(value)) continue;
    try {
      const input = inputFile(root, value, name);
      for (const destination of destinations) {
        if (samePath(destination.info.absolute, input.absolute) || samePath(destination.info.absolute, input.real)) {
          errors.push(`${destination.name === 'output' ? '输出' : '报告'}不能覆盖 ${name}`);
          if (destination.flag === 'output') outputBlocked = true;
          else reportBlocked = true;
        }
      }
    } catch { /* normal input validation reports missing/invalid paths */ }
  }
}
function isFlag(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function isPathArg(value) { return value !== undefined && value !== null && value !== true && value !== false && String(value).trim() !== ''; }
function list(value) {
  if (value === undefined || value === null || value === '' || value === true || value === false) return [];
  return (Array.isArray(value) ? value : [value]).flatMap((item) => String(item).split(',')).map((item) => item.trim()).filter(Boolean);
}
function scalar(value) { return value === undefined || value === true || value === false ? null : String(value).trim() || null; }
function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
