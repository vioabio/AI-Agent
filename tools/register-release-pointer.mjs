#!/usr/bin/env node

import fs from 'node:fs';

/**
 * Produce a release-pointer artifact from explicit local inputs.
 *
 * The command is intentionally a producer, not a registry client: it never
 * mutates a remote or local registry. `--registry-file` is a reviewed,
 * hashable snapshot used only for optimistic-concurrency checks. A pointer is
 * emitted as `pending` whenever a precondition is missing or What-if is set.
 */
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
  parseHashDocument,
  pathsEquivalent,
  readSafeJson,
  redactRegistryRef,
  registrySource,
  relative,
  resolveRoot,
  resolvePointerFile,
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
const whatIf = boolArg(args['what-if']) || boolArg(args['dry-run']);
const known = new Set([
  'repo-root', 'registry', 'registry-file', 'environment', 'release-class',
  'manifest', 'manifest-hash', 'expected-previous', 'previous-output',
  'output', 'report', 'schema', 'expected-registry', 'atomic-replace',
  'allow-create', 'what-if', 'dry-run', 'no-clobber'
]);

for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!known.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

let root = null;
let registry = null;
let registryInfo = null;
let active = null;
let manifest = null;
let manifestInfo = null;
let previousManifest = null;
let previousInfo = null;
let activeValid = false;
let manifestValid = false;
let outputBlocked = false;
let reportBlocked = false;
let previousOutputBlocked = false;

try {
  root = resolveRoot(args['repo-root'] ?? '.');
} catch (error) {
  errors.push(`repo-root: ${safeText(error.message)}`);
}

const environment = scalar(args.environment);
const releaseClass = scalar(args['release-class']);
const expectedRegistry = scalar(args['expected-registry']);
const atomicReplace = isFlag(args['atomic-replace']);
const allowCreate = isFlag(args['allow-create']);

if (!['prod', 'staging'].includes(environment)) errors.push('--environment 必须是 prod 或 staging');
if (!['GA', 'DEMO_ONLY', 'RC'].includes(releaseClass)) errors.push('--release-class 无效');
if (!atomicReplace) errors.push('必须显式提供 --atomic-replace');
if (!isPathArg(args.manifest)) errors.push('--manifest 必须显式提供');
if (!isPathArg(args['manifest-hash'])) errors.push('--manifest-hash 必须显式提供');
if (!isPathArg(args.output)) errors.push('--output 必须显式提供');
for (const name of ['registry-file', 'manifest', 'manifest-hash', 'expected-previous', 'previous-output', 'output', 'report', 'schema']) {
  if (args[name] === true || args[name] === false) errors.push(`--${name} 必须是显式路径`);
}
if (!allowCreate) warnings.push('未提供 --allow-create；没有现有 active pointer 时将阻断');
checkOutputCollisions();

if (root && errors.length === 0) {
  loadRegistry();
  loadManifest();
  loadExpectedPrevious();
  if (active) validateCurrentPointer(active);
  validateRegistrationPreconditions();
  loadSchema();
}

const pending = whatIf || errors.length > 0;
if (whatIf) {
  warnings.push('What-if/dry-run：未执行 registry mutation，pointer 保持 pending');
  errors.push('WHAT_IF_NOT_REGISTERED');
}
const passed = errors.length === 0;
const manifestDigest = safeDigest(manifest);
const manifestHash = manifestInfo ? hashInfo(manifestInfo).sha256 : null;
const pointerTimestamp = pending ? null : new Date().toISOString();
const pointer = {
  schemaVersion: 'v1.1',
  producer: 'register-release-pointer',
  pointerVersion: nextPointerVersion(active, registry),
  registryVersion: nextPointerVersion(active, registry),
  registry: registryReference(),
  environment,
  releaseClass,
  releaseId: manifest?.releaseId ?? null,
  status: pending ? 'pending' : 'active',
  releaseStatus: manifest?.status ?? null,
  manifestStatus: manifest?.status ?? null,
  manifestPath: manifestInfo && root ? relative(root, manifestInfo.absolute) : null,
  manifestSha256: manifestHash,
  imageDigest: manifestDigest,
  frontendImageDigest: safeFrontendDigest(manifest),
  images: manifest ? {backend: manifestDigest, frontend: safeFrontendDigest(manifest)} : null,
  previous: previousManifest && previousInfo && root ? {
    releaseId: previousManifest.releaseId,
    releaseClass: previousManifest.releaseClass,
    environment: previousManifest.environment,
    releaseStatus: previousManifest.status,
    manifestPath: relative(root, previousInfo.absolute),
    manifestSha256: hashInfo(previousInfo).sha256,
    imageDigest: safeDigest(previousManifest),
    frontendImageDigest: safeFrontendDigest(previousManifest),
    images: {backend: safeDigest(previousManifest), frontend: safeFrontendDigest(previousManifest)},
    registeredAt: active?.registeredAt ?? active?.updatedAt ?? null,
    updatedAt: active?.updatedAt ?? active?.registeredAt ?? null,
    expiresAt: previousManifest.expiresAt ?? null
  } : null,
  expiresAt: manifest?.expiresAt ?? null,
  registeredAt: pointerTimestamp,
  updatedAt: pointerTimestamp,
  mutation: 'not-performed',
  redacted: true
};
pointer.manifest = pointer.manifestPath ? {path: pointer.manifestPath, sha256: pointer.manifestSha256} : null;

const outputPayload = {
  ...baseResult('register-release-pointer', root),
  ...pointer,
  passed,
  exitCode: passed ? 0 : 1,
  registrySnapshot: registryInfo && root ? hashInfo(registryInfo) : null,
  errors: unique(errors),
  warnings: unique(warnings),
  checks
};

let previousOutputInfo = null;
let outputInfo = null;
// Only preserve the prior pointer after all preconditions pass. This avoids
// creating a misleading rollback artifact when registration is blocked.
if (root && passed && !whatIf && !outputBlocked && !previousOutputBlocked && isPathArg(args['previous-output']) && active) {
  try {
    previousOutputInfo = writeResult(root, args['previous-output'], active, {noClobber, label: 'previous pointer backup'});
    outputPayload.previousOutput = relative(root, previousOutputInfo.absolute);
  } catch (error) {
    outputPayload.errors.push(`previous pointer backup 失败: ${safeText(error.message)}`);
    outputPayload.passed = false;
    outputPayload.exitCode = 1;
    pointer.status = 'pending';
    pointer.registeredAt = null;
    pointer.updatedAt = null;
  }
}
if (root && isPathArg(args.output) && !outputBlocked) {
  try {
    outputInfo = writeResult(root, args.output, {...outputPayload, ...pointer, errors: unique(outputPayload.errors)}, {
      noClobber,
      label: 'release pointer'
    });
    outputPayload.output = relative(root, outputInfo.absolute);
  } catch (error) {
    outputPayload.errors.push(`pointer 输出失败: ${safeText(error.message)}`);
    outputPayload.passed = false;
    outputPayload.exitCode = 1;
  }
}

const report = {
  ...baseResult('register-release-pointer', root),
  status: outputPayload.passed && !whatIf ? 'registered-artifact' : (whatIf ? 'environment-pending' : 'blocked'),
  passed: outputPayload.passed && !whatIf,
  exitCode: outputPayload.passed && !whatIf ? 0 : 1,
  environment,
  releaseClass,
  releaseId: pointer.releaseId,
  releaseStatus: pointer.releaseStatus,
  pointerVersion: pointer.pointerVersion,
  registryUri: pointer.registry,
  output: outputInfo && root ? relative(root, outputInfo.absolute) : null,
  previousOutput: previousOutputInfo && root ? relative(root, previousOutputInfo.absolute) : null,
  registrySnapshot: outputPayload.registrySnapshot,
  errors: unique(outputPayload.errors),
  warnings: unique(outputPayload.warnings),
  checks
};
if (root && isPathArg(args.report) && !reportBlocked) {
  try {
    writeResult(root, args.report, report, {noClobber, label: 'pointer registration report'});
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
    if (!registry || typeof registry !== 'object' || Array.isArray(registry)) throw new Error('registry snapshot 必须是 JSON 对象');
    if (registry.schemaVersion !== 'v1.1') errors.push('registry snapshot schemaVersion 必须为 v1.1');
    if (registry.pointerVersion !== undefined && registry.registryVersion !== undefined
        && Number(registry.pointerVersion) !== Number(registry.registryVersion)) {
      errors.push('registry pointerVersion 与 registryVersion 不一致');
    }
    if (registry.registryVersion !== undefined
        && (!Number.isInteger(Number(registry.registryVersion)) || Number(registry.registryVersion) <= 0)) {
      errors.push('registry registryVersion 必须为正整数');
    }
    active = pointerSlot(registry, 'active');
    if (active !== null && (typeof active !== 'object' || Array.isArray(active))) errors.push('active pointer 必须是 JSON 对象');
    if (registry.registryVersion !== undefined && active?.registryVersion !== undefined
        && Number(active.registryVersion) !== Number(registry.registryVersion)) {
      errors.push('active pointer registryVersion 与 registry snapshot 不一致');
    }
    if (active?.registry && args.registry && !isLocalRegistryPath(args.registry) && !sameRef(active.registry, args.registry)) {
      errors.push('现有 active pointer registry 与 --registry 不匹配');
    }
  } catch (error) {
    errors.push(`registry: ${safeText(error.message)}`);
  }
}

function loadManifest() {
  try {
    const loaded = readSafeJson(root, args.manifest, 'release manifest');
    manifestInfo = loaded.info;
    manifest = loaded.data;
    validateManifestBinding(manifest, {environment, releaseClass});
    validateManifestStatus(manifest);
    validateTimestamp(manifest.generatedAt, 'manifest generatedAt');
    validateExpiry(manifest.expiresAt, 'manifest expiresAt');
    if (expectedRegistry && manifest.registry && !sameRef(manifest.registry, expectedRegistry)) throw new Error('manifest registry 不匹配');
    if (args.registry && !isLocalRegistryPath(args.registry) && manifest.registry && !sameRef(manifest.registry, args.registry)) throw new Error('manifest registry 与 --registry 不匹配');
    manifestValid = true;
  } catch (error) {
    errors.push(`manifest: ${safeText(error.message)}`);
  }
  if (manifestInfo) {
    try { parseHashDocument(root, args['manifest-hash'], manifestInfo, 'manifest hash'); }
    catch (error) { errors.push(`manifest hash: ${safeText(error.message)}`); }
  }
}

function loadExpectedPrevious() {
  if (!isPathArg(args['expected-previous'])) return;
  try {
    const loaded = readSafeJson(root, args['expected-previous'], 'expected previous manifest');
    previousInfo = loaded.info;
    previousManifest = loaded.data;
    validateManifestBinding(previousManifest, {environment: 'prod', releaseClass: 'GA', status: 'GA'});
    validateTimestamp(previousManifest.generatedAt, 'previous manifest generatedAt');
    validateExpiry(previousManifest.expiresAt, 'previous manifest expiresAt');
    if (manifest && previousManifest.releaseId === manifest.releaseId) throw new Error('expected previous releaseId 不能与当前相同');
    if (manifest && new Date(previousManifest.generatedAt).getTime() >= new Date(manifest.generatedAt).getTime()) {
      throw new Error('expected previous generatedAt 必须早于当前 manifest');
    }
  } catch (error) {
    errors.push(`expected previous: ${safeText(error.message)}`);
  }
}

function validateManifestStatus(value) {
  if (releaseClass === 'GA' && (environment !== 'prod' || value.status !== 'GA')) throw new Error('GA pointer 要求 prod + manifest.status=GA');
  if (releaseClass === 'DEMO_ONLY' && (environment !== 'staging' || value.status !== 'DEMO_ONLY')) throw new Error('DEMO_ONLY pointer 要求 staging + manifest.status=DEMO_ONLY');
  if (releaseClass === 'RC' && !['RC', 'BLOCKED'].includes(String(value.status))) throw new Error('RC pointer 要求 manifest.status=RC 或 BLOCKED');
}

function validateCurrentPointer(value) {
  const before = errors.length;
  if (value.schemaVersion !== 'v1.1') errors.push('现有 active pointer schemaVersion 必须为 v1.1');
  if (value.pointerVersion !== undefined && value.registryVersion !== undefined
      && Number(value.pointerVersion) !== Number(value.registryVersion)) errors.push('现有 active pointer pointerVersion 与 registryVersion 不一致');
  if (value.environment && value.environment !== environment) errors.push('现有 active pointer environment 不匹配');
  if (!['active', 'resolved', 'GA', 'RC', 'DEMO_ONLY'].includes(String(value.status))) errors.push('现有 active pointer status 无效或仍为 pending');
  if (!['GA', 'DEMO_ONLY', 'RC'].includes(String(value.releaseClass))) errors.push('现有 active pointer releaseClass 无效');
  const releaseStatus = effectiveStatus(value);
  if (!['GA', 'DEMO_ONLY', 'RC'].includes(releaseStatus)) errors.push('现有 active pointer releaseStatus 缺失或无效');
  if (['GA', 'DEMO_ONLY', 'RC'].includes(String(value.status)) && releaseStatus !== String(value.status)) errors.push('现有 active pointer status 与 releaseStatus 不一致');
  if (value.releaseClass === 'GA' && releaseStatus && !['GA', 'RC'].includes(releaseStatus)) errors.push('现有 GA pointer releaseStatus 无效');
  if (value.releaseClass === 'DEMO_ONLY' && releaseStatus && releaseStatus !== 'DEMO_ONLY') errors.push('现有 DEMO_ONLY pointer releaseStatus 无效');
  if (!safeIdOrNull(value.releaseId)) errors.push('现有 active pointer releaseId 无效');
  if (!Number.isInteger(pointerVersion(value)) || pointerVersion(value) <= 0) errors.push('现有 active pointer pointerVersion 无效');
  if (!isImageDigest(value.imageDigest ?? value.images?.backend)) errors.push('现有 active pointer imageDigest 无效');
  if (value.frontendImageDigest !== undefined && !isImageDigest(value.frontendImageDigest)) errors.push('现有 active pointer frontendImageDigest 无效');
  if (value.images?.frontend !== undefined && !isImageDigest(value.images.frontend)) errors.push('现有 active pointer images.frontend 无效');
  if (!value.manifestPath && !value.manifest?.path) errors.push('现有 active pointer manifestPath 缺失');
  if (!value.registry) errors.push('现有 active pointer registry 缺失');
  if (value.manifestSha256 && !/^(?:sha256:)?[a-f0-9]{64}$/i.test(String(value.manifestSha256))) errors.push('现有 active pointer manifestSha256 无效');
  try { validateTimestamp(value.updatedAt ?? value.registeredAt, '现有 active pointer updatedAt'); } catch (error) { errors.push(error.message); }
  try { validateExpiry(value.expiresAt, '现有 active pointer expiresAt'); } catch (error) { errors.push(error.message); }
  activeValid = errors.length === before;
  checks.push({name: 'active-pointer-precondition', passed: activeValid});
}

function validateRegistrationPreconditions() {
  if (!manifestValid) return;
  if (active && !activeValid) return;
  if (!registryReference()) errors.push('必须提供可审计的 --registry 或 registry snapshot.registry');
  if (manifest?.registry && registryReference() && !sameRef(manifest.registry, registryReference())) errors.push('manifest registry 与注册源不一致');
  if (!active && !allowCreate) errors.push('registry 没有 active pointer 且未显式提供 --allow-create');
  if (active && manifest && active.releaseId === manifest.releaseId) errors.push('当前 manifest 已是 active pointer，禁止重复注册');
  if (active && releaseClass === 'GA' && !previousManifest) errors.push('替换 GA active pointer 时必须提供 --expected-previous');
  if (active && previousManifest && active.releaseId && active.releaseId !== previousManifest.releaseId) errors.push('现有 active pointer 与 expected-previous 不匹配');
  if (active && previousManifest && previousInfo) {
    const activePath = active.manifestPath ?? active.manifest?.path;
    if (!activePath || !pathsEquivalent(activePath, previousInfo.relative)) errors.push('现有 active pointer manifestPath 与 expected-previous 不匹配');
    if (activePath) {
      try {
        const resolved = resolvePointerFile(root, active, '现有 active pointer manifest');
        if (!samePath(resolved.real, previousInfo.real)) errors.push('现有 active pointer manifestPath 未解析到 expected-previous');
      } catch (error) { errors.push(`现有 active pointer manifestPath: ${safeText(error.message)}`); }
    }
    const activeHash = active.manifestSha256 ?? active.manifest?.sha256;
    if (!isSha256(activeHash) || normalizeHash(activeHash) !== hashInfo(previousInfo).sha256) errors.push('现有 active pointer manifestSha256 与 expected-previous 不匹配');
    if ((active.imageDigest ?? active.images?.backend) !== safeDigest(previousManifest)) errors.push('现有 active pointer digest 与 expected-previous 不匹配');
    const activeFrontend = active.frontendImageDigest ?? active.images?.frontend;
    if (activeFrontend !== undefined && activeFrontend !== safeFrontendDigest(previousManifest)) errors.push('现有 active pointer frontend digest 与 expected-previous 不匹配');
    if (active.expiresAt !== undefined && previousManifest.expiresAt !== undefined
        && new Date(active.expiresAt).getTime() !== new Date(previousManifest.expiresAt).getTime()) {
      errors.push('现有 active pointer/expected-previous expiresAt 不一致');
    }
  }
  if (previousManifest && releaseClass !== 'GA') warnings.push('非 GA pointer 忽略 expected-previous 的生产回滚语义');
  if (manifest && expectedRegistry && args.registry && !sameRef(args.registry, expectedRegistry)) errors.push('--registry 与 --expected-registry 不一致');
  checks.push({name: 'registration-preconditions', passed: errors.length === 0});
}

function loadSchema() {
  if (!isPathArg(args.schema)) return;
  try { validateSchemaDocument(readSafeJson(root, args.schema, 'manifest schema').data); }
  catch (error) { errors.push(`schema: ${safeText(error.message)}`); }
}

function checkOutputCollisions() {
  if (!root) return;
  const paths = [];
  for (const [name, value] of [['output', args.output], ['report', args.report], ['previous-output', args['previous-output']]]) {
    if (!isPathArg(value)) continue;
    try { paths.push({name, info: outputFile(root, value, name)}); }
    catch (error) {
      errors.push(`${name}: ${safeText(error.message)}`);
      if (name === 'output') outputBlocked = true;
      else if (name === 'report') reportBlocked = true;
      else previousOutputBlocked = true;
    }
  }
  for (let i = 0; i < paths.length; i += 1) {
    for (let j = i + 1; j < paths.length; j += 1) {
      if (samePath(paths[i].info.absolute, paths[j].info.absolute)) {
        errors.push(`${paths[i].name} 与 ${paths[j].name} 不能是同一文件`);
        for (const item of [paths[i], paths[j]]) {
          if (item.name === 'output') outputBlocked = true;
          else if (item.name === 'report') reportBlocked = true;
          else previousOutputBlocked = true;
        }
      }
    }
  }
  for (const [name, value] of [['registry-file', args['registry-file']], ['registry', isPathArg(args.registry) ? args.registry : null], ['manifest', args.manifest], ['manifest-hash', args['manifest-hash']], ['expected-previous', args['expected-previous']], ['schema', args.schema]]) {
    if (!isPathArg(value)) continue;
    try {
      const input = inputFile(root, value, name);
      for (const item of paths) {
        if (samePath(item.info.absolute, input.absolute) || samePath(item.info.absolute, input.real)) {
          errors.push(`输出不能覆盖 ${name}`);
          if (item.name === 'output') outputBlocked = true;
          else if (item.name === 'report') reportBlocked = true;
          else previousOutputBlocked = true;
        }
      }
    } catch { /* normal input validation reports missing/invalid paths */ }
  }
  if (noClobber) {
    for (const item of paths) {
      if (fs.existsSync(item.info.absolute)) {
        errors.push(`${item.name} 已存在（--no-clobber）`);
        if (item.name === 'output') outputBlocked = true;
        else if (item.name === 'report') reportBlocked = true;
        else previousOutputBlocked = true;
      }
    }
  }
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

function pointerVersion(value) { return Number(value?.pointerVersion ?? value?.registryVersion ?? NaN); }
function nextPointerVersion(value, snapshot = null) {
  const pointerCurrent = pointerVersion(value);
  const registryCurrent = Number(snapshot?.registryVersion);
  const current = Math.max(
    Number.isInteger(pointerCurrent) && pointerCurrent > 0 ? pointerCurrent : 0,
    Number.isInteger(registryCurrent) && registryCurrent > 0 ? registryCurrent : 0
  );
  return current + 1;
}
function safeDigest(value) { try { return value ? digestFromManifest(value, 'backend') : null; } catch { return null; } }
function safeFrontendDigest(value) { try { return value ? digestFromManifest(value, 'frontend') : null; } catch { return null; } }
function effectiveStatus(value) { return String(value?.releaseStatus ?? value?.manifestStatus ?? (['GA', 'DEMO_ONLY', 'RC'].includes(String(value?.status)) ? value.status : '')); }
function safeIdOrNull(value) { try { safeId(value, 'releaseId'); return true; } catch { return false; } }
function sameRef(left, right) { return redactRegistryRef(left) === redactRegistryRef(right); }
function registryReference() {
  const cli = isLocalRegistryPath(args.registry) ? null : args.registry;
  return redactRegistryRef(cli ?? active?.registry ?? registry?.registry ?? registry?.registryUri);
}
function isLocalRegistryPath(value) {
  if (!isPathArg(value) || !root) return false;
  try { inputFile(root, value, 'registry'); return true; } catch { return false; }
}
function isFlag(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function isPathArg(value) { return value !== undefined && value !== null && value !== true && value !== false && String(value).trim() !== ''; }
function scalar(value) { return value === undefined || value === true || value === false ? null : String(value).trim() || null; }
function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
