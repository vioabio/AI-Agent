#!/usr/bin/env node

/**
 * Resolve an active release from an explicit, local registry snapshot.
 *
 * This producer is deliberately offline. `--registry` is metadata only;
 * callers must pass a reviewed JSON snapshot with `--registry-file` (or use a
 * local path in `--registry`). No registry client, DNS lookup, or fallback
 * directory scan is used here.
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
  'require-environment', 'require-release-class', 'require-status',
  'expected-release-id', 'expected-manifest', 'expected-registry', 'schema',
  'output', 'report', 'allow-pending', 'what-if', 'no-clobber'
]);

for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!known.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

let root = null;
let registry = null;
let registryInfo = null;
let pointer = null;
let pointerSummary = null;
let manifest = null;
let manifestInfo = null;
let pointerValid = false;
let manifestValid = false;
let outputBlocked = false;
let reportBlocked = false;

try {
  root = resolveRoot(args['repo-root'] ?? '.');
} catch (error) {
  errors.push(`repo-root: ${safeText(error.message)}`);
}

const environment = scalar(args['require-environment']) ?? scalar(args.environment);
const expectedClasses = list(args['require-release-class']);
const expectedStatuses = list(args['require-status']);
const expectedReleaseId = scalar(args['expected-release-id']);
const expectedRegistry = scalar(args['expected-registry']);

if (!environment || !['prod', 'staging'].includes(environment)) {
  errors.push('--environment/--require-environment 必须是 prod 或 staging');
}
if (!isFlag(args['active-pointer'])) {
  errors.push('必须显式提供 --active-pointer，禁止解析未知指针');
}
if (!isPathArg(args['expected-manifest'])) errors.push('--expected-manifest 必须显式提供');
for (const name of ['registry-file', 'expected-manifest', 'schema', 'output', 'report']) {
  if (args[name] === true || args[name] === false) errors.push(`--${name} 必须是显式路径`);
}
if (expectedClasses.some((value) => !['GA', 'DEMO_ONLY', 'RC'].includes(value))) {
  errors.push('--require-release-class 含无效值');
}
if (expectedStatuses.some((value) => !['GA', 'DEMO_ONLY', 'RC', 'BLOCKED'].includes(value))) {
  errors.push('--require-status 含无效值');
}
checkOutputCollision();

if (root && errors.length === 0) {
  loadRegistry();
  loadExpectedManifest();
  if (pointer && pointerValid && manifest && manifestInfo && manifestValid) {
    bindPointerToManifest(pointer, manifest, manifestInfo);
  }
  loadSchema();
}

const passed = errors.length === 0;
const resolutionStatus = passed ? 'resolved' : (allowPending ? 'environment-pending' : 'blocked');
const outputPayload = {
  ...baseResult('resolve-active-release', root),
  status: resolutionStatus,
  passed,
  exitCode: passed ? 0 : 1,
  environment: environment ?? null,
  releaseId: pointer?.releaseId ?? manifest?.releaseId ?? null,
  releaseClass: pointer?.releaseClass ?? manifest?.releaseClass ?? null,
  releaseStatus: effectiveReleaseStatus(pointer) || manifest?.status || null,
  pointerVersion: pointerVersion(pointer),
  registryVersion: pointerVersion(pointer),
  manifest: manifestInfo && root ? hashInfo(manifestInfo) : null,
  imageDigest: pointer?.imageDigest ?? safeDigest(manifest),
  frontendImageDigest: pointer?.frontendImageDigest ?? pointer?.images?.frontend ?? safeFrontendDigest(manifest),
  activePointer: pointerSummary,
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
    outputInfo = writeResult(root, args.output, outputPayload, {
      noClobber,
      label: 'active release resolution'
    });
    outputPayload.output = relative(root, outputInfo.absolute);
  } catch (error) {
    outputPayload.errors.push(`输出失败: ${safeText(error.message)}`);
    outputPayload.status = 'blocked';
    outputPayload.passed = false;
    outputPayload.exitCode = 1;
  }
}

const report = {
  ...baseResult('resolve-active-release', root),
  status: outputPayload.status,
  passed: outputPayload.passed,
  exitCode: outputPayload.exitCode,
  environment: environment ?? null,
  releaseId: outputPayload.releaseId,
  releaseClass: outputPayload.releaseClass,
  releaseStatus: outputPayload.releaseStatus,
  pointerVersion: outputPayload.pointerVersion,
  output: outputInfo && root ? relative(root, outputInfo.absolute) : null,
  registryUri: registryReference(),
  registry: outputPayload.registry,
  activePointer: pointerSummary,
  errors: unique(outputPayload.errors),
  warnings: unique(outputPayload.warnings),
  checks
};

if (root && isPathArg(args.report) && !reportBlocked) {
  try {
    writeResult(root, args.report, report, {noClobber, label: 'active release report'});
  } catch (error) {
    report.errors.push(`报告输出失败: ${safeText(error.message)}`);
    report.passed = false;
    report.status = 'blocked';
    report.exitCode = 1;
  }
}

console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;

function loadRegistry() {
  try {
    const source = registrySource(root, args.registry, args['registry-file']);
    registry = source.data;
    registryInfo = source.info;
    if (!registry || typeof registry !== 'object' || Array.isArray(registry)) {
      throw new Error('registry snapshot 必须是 JSON 对象');
    }
    if (registry.schemaVersion !== 'v1.1') errors.push('registry snapshot schemaVersion 必须为 v1.1');
    pointer = pointerSlot(registry, 'active');
    if (!pointer || typeof pointer !== 'object' || Array.isArray(pointer)) {
      errors.push('registry 缺少 active pointer');
      return;
    }
    if (registry.registryVersion !== undefined) {
      const registryVersion = Number(registry.registryVersion);
      if (!Number.isInteger(registryVersion) || registryVersion <= 0) errors.push('registry registryVersion 必须为正整数');
      else if (pointer.registryVersion !== undefined && Number(pointer.registryVersion) !== registryVersion) errors.push('active pointer registryVersion 与 registry snapshot 不一致');
    }
    if (args.registry && pointer.registry && !isLocalRegistryPath(args.registry)
        && !sameRef(pointer.registry, args.registry)) {
      errors.push('active pointer registry 与 --registry 不匹配');
    }
    pointerSummary = safePointerSummary(pointer, registryInfo);
    const before = errors.length;
    validatePointer(pointer);
    pointerValid = errors.length === before;
  } catch (error) {
    errors.push(`registry: ${safeText(error.message)}`);
    if (allowPending) warnings.push('registry 不可离线解析；输出保持 environment-pending');
  }
}

function loadExpectedManifest() {
  if (!isPathArg(args['expected-manifest'])) return;
  try {
    const loaded = readSafeJson(root, args['expected-manifest'], 'expected manifest');
    manifestInfo = loaded.info;
    manifest = loaded.data;
    validateManifestBinding(manifest, {environment});
    validateExpectedManifest(manifest);
    manifestValid = true;
  } catch (error) {
    errors.push(`expected manifest: ${safeText(error.message)}`);
  }
}

function loadSchema() {
  if (!isPathArg(args.schema)) return;
  try {
    validateSchemaDocument(readSafeJson(root, args.schema, 'manifest schema').data);
  } catch (error) {
    errors.push(`schema: ${safeText(error.message)}`);
  }
}

function validatePointer(value) {
  if (value.schemaVersion !== 'v1.1') errors.push('active pointer schemaVersion 必须为 v1.1');
  if (value.pointerVersion !== undefined && value.registryVersion !== undefined
      && Number(value.pointerVersion) !== Number(value.registryVersion)) errors.push('active pointer pointerVersion 与 registryVersion 不一致');
  const status = String(value.status ?? '');
  if (!['active', 'resolved', 'GA', 'DEMO_ONLY', 'RC'].includes(status)) {
    errors.push('active pointer status 无效或仍为 pending');
  }
  if (value.environment !== environment) errors.push('active pointer environment 不匹配');
  if (!['GA', 'DEMO_ONLY', 'RC'].includes(String(value.releaseClass))) errors.push('active pointer releaseClass 无效');
  if (expectedClasses.length > 0 && !expectedClasses.includes(String(value.releaseClass))) {
    errors.push('active pointer releaseClass 不在要求集合');
  }
  const releaseStatus = effectiveReleaseStatus(value);
  if (!['GA', 'DEMO_ONLY', 'RC'].includes(releaseStatus)) errors.push('active pointer release status 缺失或无效');
  if (['GA', 'DEMO_ONLY', 'RC'].includes(status) && releaseStatus !== status) errors.push('active pointer status 与 releaseStatus 不一致');
  if (expectedStatuses.length > 0 && releaseStatus && !expectedStatuses.includes(releaseStatus)) {
    errors.push('active pointer release status 不在要求集合');
  }
  if (value.releaseClass === 'GA' && releaseStatus && !['GA', 'RC'].includes(releaseStatus)) {
    errors.push('GA pointer release status 必须为 GA 或 RC');
  }
  if (value.releaseClass === 'DEMO_ONLY' && releaseStatus && releaseStatus !== 'DEMO_ONLY') {
    errors.push('DEMO_ONLY pointer release status 不一致');
  }
  if (expectedReleaseId && value.releaseId !== expectedReleaseId) errors.push('active pointer releaseId 不匹配');
  if (expectedRegistry && (!value.registry || !sameRef(value.registry, expectedRegistry))) errors.push('active pointer registry 不匹配');
  if (!value.registry) errors.push('active pointer registry 缺失');
  try { safeId(value.releaseId, 'active pointer releaseId'); } catch (error) { errors.push(error.message); }
  const version = pointerVersion(value);
  if (!Number.isInteger(version) || version <= 0) errors.push('active pointer pointerVersion 必须为正整数');
  const digest = value.imageDigest ?? value.images?.backend;
  if (!isImageDigest(digest)) errors.push('active pointer imageDigest 必须是不可变 digest');
  if (value.frontendImageDigest !== undefined && !isImageDigest(value.frontendImageDigest)) errors.push('active pointer frontendImageDigest 无效');
  if (value.images?.frontend !== undefined && !isImageDigest(value.images.frontend)) errors.push('active pointer images.frontend 无效');
  if (!isSha256(value.manifestSha256 ?? value.manifest?.sha256)) errors.push('active pointer manifestSha256 缺失或无效');
  const manifestPath = value.manifestPath ?? value.manifest?.path;
  if (!manifestPath || isPlaceholder(manifestPath)) errors.push('active pointer manifestPath 缺失');
  try { validateTimestamp(value.updatedAt ?? value.registeredAt, 'active pointer updatedAt'); }
  catch (error) { errors.push(error.message); }
  try { validateExpiry(value.expiresAt, 'active pointer expiresAt'); }
  catch (error) { errors.push(error.message); }
  checks.push({name: 'active-pointer-shape', passed: errors.length === 0});
}

function validateExpectedManifest(value) {
  if (!['GA', 'DEMO_ONLY', 'RC'].includes(String(value.releaseClass))) errors.push('expected manifest releaseClass 无效');
  if (!['GA', 'DEMO_ONLY', 'RC'].includes(String(value.status))) errors.push('expected manifest status 不允许作为 active release');
  if (value.releaseClass === 'GA' && !['GA', 'RC'].includes(String(value.status))) errors.push('GA manifest status 必须为 GA 或 RC');
  if (value.releaseClass === 'DEMO_ONLY' && value.status !== 'DEMO_ONLY') errors.push('DEMO_ONLY manifest status 不一致');
  if (expectedReleaseId && value.releaseId !== expectedReleaseId) errors.push('expected manifest releaseId 不匹配');
  if (expectedClasses.length > 0 && !expectedClasses.includes(String(value.releaseClass))) errors.push('expected manifest releaseClass 不在要求集合');
  if (expectedStatuses.length > 0 && !expectedStatuses.includes(String(value.status))) errors.push('expected manifest status 不在要求集合');
  try { validateTimestamp(value.generatedAt, 'expected manifest generatedAt'); }
  catch (error) { errors.push(error.message); }
  try { validateExpiry(value.expiresAt, 'expected manifest expiresAt'); }
  catch (error) { errors.push(error.message); }
  if (expectedRegistry && value.registry && !sameRef(value.registry, expectedRegistry)) errors.push('expected manifest registry 不匹配');
}

function bindPointerToManifest(value, candidate, candidateInfo) {
  const pointerPath = value.manifestPath ?? value.manifest?.path;
  if (!pointerPath || !pathsEquivalent(pointerPath, candidateInfo.relative)) errors.push('active pointer manifestPath 与 expected manifest 不一致');
  try {
    const resolved = resolvePointerFile(root, value, 'active pointer manifest');
    if (!samePath(resolved.real, candidateInfo.real)) errors.push('active pointer manifestPath 未解析到 expected manifest');
  } catch (error) { errors.push(`active pointer manifestPath: ${safeText(error.message)}`); }
  const pointerHash = normalizeHash(value.manifestSha256 ?? value.manifest?.sha256);
  if (pointerHash !== hashInfo(candidateInfo).sha256) errors.push('active pointer manifestSha256 与 expected manifest 不一致');
  if (value.releaseId !== candidate.releaseId) errors.push('active pointer/manifest releaseId 不一致');
  if (value.releaseClass !== candidate.releaseClass) errors.push('active pointer/manifest releaseClass 不一致');
  if (value.environment !== candidate.environment) errors.push('active pointer/manifest environment 不一致');
  if (value.registry && candidate.registry && !sameRef(value.registry, candidate.registry)) errors.push('active pointer/manifest registry 不一致');
  const candidateStatus = String(candidate.status);
  const pointerStatus = effectiveReleaseStatus(value);
  if (pointerStatus && pointerStatus !== candidateStatus) errors.push('active pointer/manifest status 不一致');
  if (expectedStatuses.length > 0 && !expectedStatuses.includes(candidateStatus)) errors.push('expected manifest status 不在要求集合');
  if (expectedClasses.length > 0 && !expectedClasses.includes(String(candidate.releaseClass))) errors.push('expected manifest releaseClass 不在要求集合');
  try {
    const pointerDate = validateTimestamp(value.updatedAt ?? value.registeredAt, 'active pointer updatedAt');
    const manifestDate = validateTimestamp(candidate.generatedAt, 'manifest generatedAt');
    if (pointerDate.getTime() < manifestDate.getTime()) errors.push('active pointer 时间早于 manifest generatedAt');
    if (value.expiresAt !== undefined && candidate.expiresAt !== undefined
        && new Date(value.expiresAt).getTime() !== new Date(candidate.expiresAt).getTime()) {
      errors.push('active pointer/manifest expiresAt 不一致');
    }
  } catch (error) { errors.push(error.message); }
  try {
    if ((value.imageDigest ?? value.images?.backend) !== digestFromManifest(candidate, 'backend')) errors.push('active pointer/backend digest 不一致');
    const frontendPointer = value.frontendImageDigest ?? value.images?.frontend;
    if (frontendPointer !== undefined && frontendPointer !== digestFromManifest(candidate, 'frontend')) {
      errors.push('active pointer/frontend digest 不一致');
    }
  } catch (error) { errors.push(error.message); }
  checks.push({name: 'active-pointer-manifest-binding', passed: errors.length === 0});
}

function pointerSlot(value, name) {
  const aliases = name === 'active' ? ['active', 'activePointer', 'pointer'] : [name];
  const present = aliases.filter((key) => Object.prototype.hasOwnProperty.call(value ?? {}, key));
  if (present.length > 1) {
    const first = JSON.stringify(value[present[0]]);
    if (present.some((key) => JSON.stringify(value[key]) !== first)) errors.push(`${name} pointer 别名内容不一致`);
  }
  const selected = present.find((key) => value[key] !== null && value[key] !== undefined);
  return selected ? value[selected] : null;
}

function checkOutputCollision() {
  if (!root || (!isPathArg(args.output) && !isPathArg(args.report))) return;
  const destinations = [];
  for (const [name, value, flag] of [
    ['output', args.output, 'output'],
    ['report', args.report, 'report']
  ]) {
    if (!isPathArg(value)) continue;
    try {
      destinations.push({name, flag, info: outputFile(root, value, name === 'output' ? 'active release resolution' : 'active release report')});
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
    ['registry', isPathArg(args['registry']) ? args.registry : null],
    ['expected-manifest', args['expected-manifest']],
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
    } catch { /* the normal input loader reports missing/invalid paths */ }
  }
}

function pointerVersion(value) { return Number(value?.pointerVersion ?? value?.registryVersion ?? NaN); }
function effectiveReleaseStatus(value) {
  return String(value?.releaseStatus ?? value?.manifestStatus ?? (['GA', 'DEMO_ONLY', 'RC'].includes(String(value?.status)) ? value.status : ''));
}
function safeDigest(value) {
  try { return value ? digestFromManifest(value, 'backend') : null; } catch { return null; }
}
function safeFrontendDigest(value) {
  try { return value ? digestFromManifest(value, 'frontend') : null; } catch { return null; }
}
function sameRef(left, right) { return redactRegistryRef(left) === redactRegistryRef(right); }
function registryReference() {
  const cli = isLocalRegistryPath(args.registry) ? null : args.registry;
  return redactRegistryRef(cli ?? pointer?.registry ?? registry?.registry ?? registry?.registryUri);
}
function isLocalRegistryPath(value) {
  if (!isPathArg(value) || !root) return false;
  try { inputFile(root, value, 'registry'); return true; } catch { return false; }
}
function isFlag(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function isPathArg(value) { return value !== undefined && value !== null && value !== true && value !== false && String(value).trim() !== ''; }
function list(value) {
  if (value === undefined || value === null || value === '' || value === true || value === false) return [];
  return (Array.isArray(value) ? value : [value]).flatMap((item) => String(item).split(',')).map((item) => item.trim()).filter(Boolean);
}
function scalar(value) { return value === undefined || value === true || value === false ? null : String(value).trim() || null; }
function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
