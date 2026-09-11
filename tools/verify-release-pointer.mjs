#!/usr/bin/env node

/** Verify a local release pointer against its immutable manifest. */
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
  resolvePointerFile,
  resolveRoot,
  safeId,
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
const known = new Set([
  'repo-root', 'pointer', 'manifest', 'environment', 'require-environment',
  'require-status', 'require-release-class', 'expected-registry',
  'expected-previous', 'schema', 'report', 'no-clobber'
]);

for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!known.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

let root = null;
let pointer = null;
let pointerInfo = null;
let manifest = null;
let manifestInfo = null;
let pointerValid = false;
let manifestValid = false;
let outputBlocked = false;

try {
  root = resolveRoot(args['repo-root'] ?? '.');
} catch (error) {
  errors.push(`repo-root: ${safeText(error.message)}`);
}

const environment = scalar(args['require-environment']) ?? scalar(args.environment);
const statuses = list(args['require-status']);
const classes = list(args['require-release-class']);
const expectedRegistry = scalar(args['expected-registry']);

if (!environment || !['prod', 'staging'].includes(environment)) errors.push('--environment 必须是 prod 或 staging');
if (!isPathArg(args.pointer)) errors.push('--pointer 必须显式提供');
if (!isPathArg(args.manifest)) errors.push('--manifest 必须显式提供');
for (const value of ['pointer', 'manifest', 'expected-previous', 'schema', 'report']) {
  if (args[value] === true || args[value] === false) errors.push(`--${value} 必须是显式路径`);
}
if (statuses.some((value) => !['GA', 'DEMO_ONLY', 'RC', 'BLOCKED'].includes(value))) errors.push('--require-status 含无效值');
if (classes.some((value) => !['GA', 'DEMO_ONLY', 'RC'].includes(value))) errors.push('--require-release-class 含无效值');
checkOutputCollision();

if (root && errors.length === 0) {
  loadPointer();
  loadManifest();
  if (pointerValid && manifestValid) bindPointerToManifest();
  if (isPathArg(args['expected-previous']) && pointerValid) validatePrevious();
  loadSchema();
}

const passed = errors.length === 0;
const report = {
  ...baseResult('verify-release-pointer', root),
  status: passed ? 'passed' : 'blocked',
  passed,
  exitCode: passed ? 0 : 1,
  environment: environment ?? null,
  releaseId: pointer?.releaseId ?? null,
  releaseClass: pointer?.releaseClass ?? null,
  releaseStatus: effectiveStatus(pointer) || manifest?.status || null,
  pointerVersion: pointerVersion(pointer),
  registryVersion: pointerVersion(pointer),
  registryUri: redactRegistryRef(pointer?.registry ?? args['expected-registry']),
  pointer: pointerInfo && root ? hashInfo(pointerInfo) : null,
  manifest: manifestInfo && root ? hashInfo(manifestInfo) : null,
  errors: unique(errors),
  warnings: unique(warnings),
  checks
};

if (root && isPathArg(args.report) && !outputBlocked) {
  try {
    writeResult(root, args.report, report, {noClobber, label: 'pointer validation report'});
  } catch (error) {
    report.errors.push(`报告输出失败: ${safeText(error.message)}`);
    report.passed = false;
    report.status = 'blocked';
    report.exitCode = 1;
  }
}

console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;

function loadPointer() {
  try {
    const loaded = readSafeJson(root, args.pointer, 'release pointer');
    pointerInfo = loaded.info;
    pointer = loaded.data;
    const before = errors.length;
    validatePointer(pointer);
    pointerValid = errors.length === before;
  } catch (error) {
    errors.push(`pointer: ${safeText(error.message)}`);
  }
}

function loadManifest() {
  try {
    const loaded = readSafeJson(root, args.manifest, 'release manifest');
    manifestInfo = loaded.info;
    manifest = loaded.data;
    validateManifestBinding(manifest, {environment});
    validateManifestFields(manifest);
    manifestValid = true;
  } catch (error) {
    errors.push(`manifest: ${safeText(error.message)}`);
  }
}

function loadSchema() {
  if (!isPathArg(args.schema)) return;
  try { validateSchemaDocument(readSafeJson(root, args.schema, 'manifest schema').data); }
  catch (error) { errors.push(`schema: ${safeText(error.message)}`); }
}

function validatePointer(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('pointer 必须是 JSON 对象');
  if (value.schemaVersion !== 'v1.1') errors.push('pointer schemaVersion 必须为 v1.1');
  if (value.pointerVersion !== undefined && value.registryVersion !== undefined
      && Number(value.pointerVersion) !== Number(value.registryVersion)) errors.push('pointerVersion 与 registryVersion 不一致');
  const status = String(value.status ?? '');
  if (!['active', 'resolved', 'GA', 'DEMO_ONLY', 'RC'].includes(status)) errors.push('pointer status 无效或仍为 pending');
  if (value.environment !== environment) errors.push('pointer environment 不匹配');
  if (!['GA', 'DEMO_ONLY', 'RC'].includes(String(value.releaseClass))) errors.push('pointer releaseClass 无效');
  if (classes.length > 0 && !classes.includes(String(value.releaseClass))) errors.push('pointer releaseClass 不在要求集合');
  const releaseStatus = effectiveStatus(value);
  if (!['GA', 'DEMO_ONLY', 'RC'].includes(releaseStatus)) errors.push('pointer releaseStatus 缺失或无效');
  if (['GA', 'DEMO_ONLY', 'RC'].includes(status) && releaseStatus !== status) errors.push('pointer status 与 releaseStatus 不一致');
  if (releaseStatus && !['GA', 'DEMO_ONLY', 'RC'].includes(releaseStatus)) errors.push('pointer releaseStatus 无效');
  if (statuses.length > 0 && releaseStatus && !statuses.includes(releaseStatus)) errors.push('pointer release status 不在要求集合');
  if (value.releaseClass === 'GA' && releaseStatus && !['GA', 'RC'].includes(releaseStatus)) errors.push('GA pointer releaseStatus 不一致');
  if (value.releaseClass === 'DEMO_ONLY' && releaseStatus && releaseStatus !== 'DEMO_ONLY') errors.push('DEMO_ONLY pointer releaseStatus 不一致');
  if (expectedRegistry && (!value.registry || !sameRef(value.registry, expectedRegistry))) errors.push('pointer registry 不匹配');
  try { safeId(value.releaseId, 'pointer.releaseId'); } catch (error) { errors.push(error.message); }
  if (!Number.isInteger(pointerVersion(value)) || pointerVersion(value) <= 0) errors.push('pointerVersion 必须是正整数');
  if (!isSha256(value.manifestSha256 ?? value.manifest?.sha256)) errors.push('pointer manifestSha256 缺失或格式无效');
  if (!isImageDigest(value.imageDigest ?? value.images?.backend)) errors.push('pointer imageDigest 缺失或不是不可变 digest');
  if (value.frontendImageDigest !== undefined && !isImageDigest(value.frontendImageDigest)) errors.push('pointer frontendImageDigest 无效');
  if (value.images?.frontend !== undefined && !isImageDigest(value.images.frontend)) errors.push('pointer images.frontend 无效');
  if (!value.manifestPath && !value.manifest?.path) errors.push('pointer manifestPath 缺失');
  if (!value.registry) errors.push('pointer registry 缺失');
  try { validateTimestamp(value.registeredAt ?? value.updatedAt, 'pointer registeredAt/updatedAt'); } catch (error) { errors.push(error.message); }
  try { validateExpiry(value.expiresAt, 'pointer expiresAt'); } catch (error) { errors.push(error.message); }
  checks.push({name: 'pointer-shape', passed: errors.length === 0});
}

function validateManifestFields(value) {
  if (!['GA', 'DEMO_ONLY', 'RC'].includes(String(value.releaseClass))) errors.push('manifest releaseClass 无效');
  if (!['BLOCKED', 'RC', 'DEMO_ONLY', 'GA'].includes(String(value.status))) errors.push('manifest status 无效');
  if (value.releaseClass === 'GA' && !['GA', 'RC', 'BLOCKED'].includes(String(value.status))) errors.push('GA manifest status 不一致');
  if (value.releaseClass === 'DEMO_ONLY' && value.status !== 'DEMO_ONLY') errors.push('DEMO_ONLY manifest status 不一致');
  if (value.status === 'GA' && value.environment !== 'prod') errors.push('GA manifest 必须是 prod');
  if (statuses.length > 0 && !statuses.includes(String(value.status))) errors.push('manifest status 不在要求集合');
  if (classes.length > 0 && !classes.includes(String(value.releaseClass))) errors.push('manifest releaseClass 不在要求集合');
  if (expectedRegistry && value.registry && !sameRef(value.registry, expectedRegistry)) errors.push('manifest registry 不匹配');
  try { validateTimestamp(value.generatedAt, 'manifest generatedAt'); } catch (error) { errors.push(error.message); }
  try { validateExpiry(value.expiresAt, 'manifest expiresAt'); } catch (error) { errors.push(error.message); }
}

function bindPointerToManifest() {
  const pointerPath = pointer.manifestPath ?? pointer.manifest?.path;
  if (!pointerPath || !pathsEquivalent(pointerPath, manifestInfo.relative)) errors.push('pointer manifestPath 与 manifest 不一致');
  try {
    const resolved = resolvePointerFile(root, pointer, 'pointer manifest');
    if (!samePath(resolved.real, manifestInfo.real)) errors.push('pointer manifestPath 未解析到传入 manifest');
  } catch (error) { errors.push(`pointer manifestPath: ${safeText(error.message)}`); }
  if (normalizeHash(pointer.manifestSha256 ?? pointer.manifest?.sha256) !== hashInfo(manifestInfo).sha256) errors.push('pointer manifestSha256 与 manifest 文件不一致');
  if (pointer.releaseId !== manifest.releaseId) errors.push('pointer/manifest releaseId 不一致');
  if (pointer.releaseClass !== manifest.releaseClass) errors.push('pointer/manifest releaseClass 不一致');
  if (pointer.environment !== manifest.environment) errors.push('pointer/manifest environment 不一致');
  if (pointer.registry && manifest.registry && !sameRef(pointer.registry, manifest.registry)) errors.push('pointer/manifest registry 不一致');
  const pointerStatus = effectiveStatus(pointer);
  if (pointerStatus && pointerStatus !== manifest.status) errors.push('pointer/manifest releaseStatus 不一致');
  try {
    if ((pointer.imageDigest ?? pointer.images?.backend) !== digestFromManifest(manifest, 'backend')) errors.push('pointer/backend digest 不一致');
    const frontendPointer = pointer.frontendImageDigest ?? pointer.images?.frontend;
    if (frontendPointer !== undefined && frontendPointer !== digestFromManifest(manifest, 'frontend')) errors.push('pointer/frontend digest 不一致');
  } catch (error) { errors.push(error.message); }
  try {
    const pointerDate = validateTimestamp(pointer.registeredAt ?? pointer.updatedAt, 'pointer registeredAt/updatedAt');
    const manifestDate = validateTimestamp(manifest.generatedAt, 'manifest generatedAt');
    if (pointerDate.getTime() < manifestDate.getTime()) errors.push('pointer 时间早于 manifest generatedAt');
    if (pointer.expiresAt !== undefined && manifest.expiresAt !== undefined
        && new Date(pointer.expiresAt).getTime() !== new Date(manifest.expiresAt).getTime()) {
      errors.push('pointer/manifest expiresAt 不一致');
    }
  } catch (error) { errors.push(error.message); }
  checks.push({name: 'pointer-manifest-binding', passed: errors.length === 0});
}

function validatePrevious() {
  try {
    const loaded = readSafeJson(root, args['expected-previous'], 'expected previous manifest');
    const previous = loaded.data;
    validateManifestBinding(previous, {environment: 'prod', releaseClass: 'GA', status: 'GA'});
    validateTimestamp(previous.generatedAt, 'previous manifest generatedAt');
    validateExpiry(previous.expiresAt, 'previous manifest expiresAt');
    if (!pointer.previous) throw new Error('pointer.previous 缺失');
    if (pointer.previous.releaseId !== previous.releaseId) throw new Error('pointer.previous releaseId 不匹配');
    if (pointer.previous.environment && pointer.previous.environment !== previous.environment) throw new Error('pointer.previous environment 不匹配');
    if (pointer.previous.releaseClass && pointer.previous.releaseClass !== previous.releaseClass) throw new Error('pointer.previous releaseClass 不匹配');
    if (pointer.previous.releaseStatus && pointer.previous.releaseStatus !== previous.status) throw new Error('pointer.previous releaseStatus 不匹配');
    if (!pointer.previous.manifestPath) throw new Error('pointer.previous manifestPath 缺失');
    if (!isImageDigest(pointer.previous.imageDigest ?? pointer.previous.images?.backend)) throw new Error('pointer.previous imageDigest 缺失或无效');
    if (!isSha256(pointer.previous.manifestSha256) || normalizeHash(pointer.previous.manifestSha256) !== hashInfo(loaded.info).sha256) throw new Error('pointer.previous manifest hash 不匹配');
    if (pointer.previous.manifestPath && !pathsEquivalent(pointer.previous.manifestPath, loaded.info.relative)) throw new Error('pointer.previous manifestPath 不匹配');
    if (pointer.previous.manifestPath) {
      const resolved = resolvePointerFile(root, pointer.previous, 'pointer.previous manifest');
      if (!samePath(resolved.real, loaded.info.real)) throw new Error('pointer.previous manifestPath 未解析到 expected previous');
    }
    if (pointer.previous.imageDigest && pointer.previous.imageDigest !== digestFromManifest(previous, 'backend')) throw new Error('pointer.previous imageDigest 不匹配');
    const previousFrontend = pointer.previous.frontendImageDigest ?? pointer.previous.images?.frontend;
    if (previousFrontend !== undefined && previousFrontend !== digestFromManifest(previous, 'frontend')) throw new Error('pointer.previous frontend imageDigest 不匹配');
    if (pointer.previous.expiresAt !== undefined && previous.expiresAt !== undefined
        && new Date(pointer.previous.expiresAt).getTime() !== new Date(previous.expiresAt).getTime()) throw new Error('pointer.previous expiresAt 不匹配');
    if (pointer.previous.updatedAt !== undefined || pointer.previous.registeredAt !== undefined) {
      const previousPointerDate = validateTimestamp(pointer.previous.updatedAt ?? pointer.previous.registeredAt, 'pointer.previous updatedAt');
      if (previousPointerDate.getTime() < new Date(previous.generatedAt).getTime()) throw new Error('pointer.previous 时间早于 previous manifest generatedAt');
    }
    if (new Date(previous.generatedAt).getTime() >= new Date(manifest.generatedAt).getTime()) throw new Error('previous generatedAt 必须早于当前 manifest');
    checks.push({name: 'previous-pointer-binding', passed: true});
  } catch (error) {
    errors.push(`expected previous: ${safeText(error.message)}`);
  }
}

function checkOutputCollision() {
  if (!root) return;
  let reportPath = null;
  if (isPathArg(args.report)) {
    try { reportPath = outputFile(root, args.report, 'pointer validation report'); }
    catch (error) { errors.push(`报告路径: ${safeText(error.message)}`); outputBlocked = true; }
  }
  const inputs = [['pointer', args.pointer], ['manifest', args.manifest], ['expected-previous', args['expected-previous']], ['schema', args.schema]];
  const inputInfos = [];
  for (const [name, value] of inputs) {
    if (!isPathArg(value)) continue;
    try { inputInfos.push({name, info: inputFile(root, value, name)}); }
    catch { /* normal input validation reports missing/invalid paths */ }
  }
  for (let i = 0; i < inputInfos.length; i += 1) {
    for (let j = i + 1; j < inputInfos.length; j += 1) {
      if (samePath(inputInfos[i].info.real, inputInfos[j].info.real)) {
        errors.push(`--${inputInfos[i].name} 与 --${inputInfos[j].name} 不能是同一文件`);
      }
    }
  }
  if (reportPath) {
    for (const item of inputInfos) {
      if (samePath(reportPath.absolute, item.info.absolute) || samePath(reportPath.absolute, item.info.real)) {
        errors.push(`--report 不能覆盖 --${item.name}`);
        outputBlocked = true;
      }
    }
  }
}
function pointerVersion(value) { return Number(value?.pointerVersion ?? value?.registryVersion ?? NaN); }
function effectiveStatus(value) { return String(value?.releaseStatus ?? value?.manifestStatus ?? (['GA', 'DEMO_ONLY', 'RC'].includes(String(value?.status)) ? value.status : '')); }
function sameRef(left, right) { return redactRegistryRef(left) === redactRegistryRef(right); }
function isPathArg(value) { return value !== undefined && value !== null && value !== true && value !== false && String(value).trim() !== ''; }
function scalar(value) { return value === undefined || value === true || value === false ? null : String(value).trim() || null; }
function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function list(value) {
  if (value === undefined || value === null || value === '' || value === true || value === false) return [];
  return (Array.isArray(value) ? value : [value]).flatMap((item) => String(item).split(',')).map((item) => item.trim()).filter(Boolean);
}
