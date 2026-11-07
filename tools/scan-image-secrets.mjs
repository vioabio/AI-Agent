#!/usr/bin/env node

/**
 * Emit a redacted v1.1 image-layer secret-scan contract.
 *
 * This producer is deliberately a boundary adapter, not a scanner runner. It
 * never invokes Docker, a registry client, or a scanner binary. It accepts
 * only immutable image references and emits environment-pending evidence until
 * an independently reviewed scanner adapter supplies a real result.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  inputFile,
  outputFile,
  parseImageReference,
  sourceInfo
} from './lib/supply-chain.mjs';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  readJson,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const generated = generatedAt();
const errors = [];
const warnings = [];
const KNOWN_IMAGE_DEPENDENCIES = new Set([
  'postgresql-pgvector', 'postgres', 'pgvector', 'redis', 'prometheus', 'grafana',
  'alertmanager', 'oidc-provider', 'mcp-sandbox', 'mcp-sandbox-runtime',
  'otel-collector', 'backup-store'
]);
const knownOptions = new Set([
  'image', 'inventory', 'output', 'report', 'scanner', 'scanner-version',
  'schema', 'allow-pending', 'no-clobber', 'allowlist', 'redact'
]);

for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!knownOptions.has(key)) errors.push('未知参数: --' + key);
}
if (args._positional.length > 0) {
  errors.push('不支持的位置参数: ' + args._positional.map((value) => safeText(value)).join(' '));
}

// parseArgs represents "--no-allowlist" as allowlist=false. A positive
// allowlist, and an explicit attempt to disable redaction, are rejected.
if (args.allowlist === true || (args.allowlist !== undefined && args.allowlist !== false)) {
  errors.push('镜像层扫描禁止启用 allowlist；不得复用 N0 工作树 allowlist');
}
if (args.redact === false || (args.redact !== undefined && args.redact !== true && String(args.redact).toLowerCase() !== 'true')) {
  errors.push('镜像层扫描必须保持 --redact；禁止关闭脱敏');
}

const scannerVersion = resolveScannerVersion(args, errors);
// Evidence is immutable by policy. The --no-clobber flag is accepted for
// command-line compatibility, but the producer enforces it even when omitted.
const noClobber = true;
const outputArg = singleScalar(args.output) || 'artifacts/v1.1/release/image-secret-scan.json';
const reportArg = singleScalar(args.report);
const outputInfo = resolveDestination(outputArg, '镜像 secret scan 输出', errors);
const reportInfo = reportArg ? resolveDestination(reportArg, '镜像 secret scan 报告', errors) : null;
if (outputInfo && reportInfo && samePath(outputInfo.absolute, reportInfo.absolute)) {
  errors.push('报告不能与扫描输出使用同一路径');
}
if (outputInfo && fs.existsSync(outputInfo.absolute)) errors.push('扫描输出已存在；producer 强制 no-clobber');
if (reportInfo && fs.existsSync(reportInfo.absolute)) errors.push('扫描报告已存在；producer 强制 no-clobber');

const inventoryArg = singleScalar(args.inventory);
const inventoryInfo = inventoryArg ? loadInput(inventoryArg, 'dependency inventory', errors) : null;
if (!inventoryArg) errors.push('--inventory 必须显式提供');
if (inventoryInfo && outputInfo && samePath(inventoryInfo.absolute, outputInfo.absolute)) errors.push('输出不能覆盖 inventory 输入');
if (inventoryInfo && reportInfo && samePath(inventoryInfo.absolute, reportInfo.absolute)) errors.push('报告不能覆盖 inventory 输入');

const schemaArg = singleScalar(args.schema);
const schemaInfo = schemaArg ? loadInput(schemaArg, 'image secret scan schema', errors) : null;
if (schemaInfo && outputInfo && samePath(schemaInfo.absolute, outputInfo.absolute)) errors.push('输出不能覆盖 schema 输入');
if (schemaInfo && reportInfo && samePath(schemaInfo.absolute, reportInfo.absolute)) errors.push('报告不能覆盖 schema 输入');
let schemaDocument = null;
if (schemaInfo) {
  try {
    schemaDocument = readJson(schemaInfo.real, 'image secret scan schema');
    if (!schemaDocument || typeof schemaDocument !== 'object' || Array.isArray(schemaDocument)) {
      errors.push('image secret scan schema 必须是 JSON 对象');
    }
  } catch {
    errors.push('image secret scan schema JSON 无法解析');
  }
}

const explicitImages = parseExplicitImages(args.image, errors);
if (!explicitImages.has('backend')) errors.push('必须显式提供 --image backend=<repo>@sha256:<64hex>');
if (!explicitImages.has('frontend')) errors.push('必须显式提供 --image frontend=<repo>@sha256:<64hex>');

let inventory = null;
if (inventoryInfo) {
  try {
    inventory = readJson(inventoryInfo.real, 'dependency inventory');
    if (!inventory || typeof inventory !== 'object' || Array.isArray(inventory)) {
      errors.push('dependency inventory 必须是 JSON 对象');
      inventory = null;
    } else if (inventory.schemaVersion !== 'v1.1') {
      errors.push('dependency inventory schemaVersion 必须为 v1.1');
    }
  } catch {
    errors.push('dependency inventory JSON 无法解析');
  }
}

const images = {};
for (const descriptor of explicitImages.values()) {
  images[descriptor.name] = pendingImage(descriptor.name, descriptor.repoDigest, 'explicit-cli');
}

const dependencyRows = [];
const dependencies = Array.isArray(inventory && inventory.dependencies) ? inventory.dependencies : [];
if (inventory && dependencies.length === 0) errors.push('dependency inventory dependencies 不能为空');
const seenDependencyNames = new Set();
for (let index = 0; index < dependencies.length; index += 1) {
  const row = inspectDependency(dependencies[index], index, explicitImages, errors);
  const rowKey = row.name.toLowerCase();
  if (seenDependencyNames.has(rowKey)) {
    errors.push('inventory dependency 名称重复: ' + safeIdentifier(row.name));
    row.status = 'blocked';
    row.reason = 'duplicate-name';
    row.repoDigest = null;
    row.digest = null;
  }
  seenDependencyNames.add(rowKey);
  dependencyRows.push(row);
  if (row.repoDigest) {
    if (images[row.name] && String(images[row.name].repoDigest).toLowerCase() !== row.repoDigest.toLowerCase()) {
      errors.push('显式 image 与 inventory dependency ' + safeIdentifier(row.name) + ' 的 digest 不一致');
      row.status = 'blocked';
      row.reason = 'digest-conflict';
      row.repoDigest = null;
      row.digest = null;
    } else if (!images[row.name]) {
      images[row.name] = pendingImage(row.name, row.repoDigest, 'inventory');
    }
  }
}

const scanTargets = Object.values(images).map((image) => ({
  name: image.name,
  repoDigest: image.repoDigest,
  digest: image.digest,
  scannerVersion,
  scannerExecuted: false,
  findings: [],
  findingCount: 0,
  exitCode: 1,
  status: 'environment-pending',
  redacted: true
}));
const missingDependencyImages = dependencyRows.filter((row) => row.imageRequired && !row.repoDigest);
if (missingDependencyImages.length > 0) {
  warnings.push(missingDependencyImages.length + ' 个拓扑依赖缺少 image digest，未能纳入逐层扫描');
}
const blockedDependencies = dependencyRows.filter((row) => row.status === 'blocked');
if (blockedDependencies.length > 0) {
  warnings.push(blockedDependencies.length + ' 个 inventory 镜像引用无法作为 immutable digest 使用');
}

const status = errors.length > 0 ? 'blocked' : 'environment-pending';
const allowPending = args['allow-pending'] === true || String(args['allow-pending'] || '').toLowerCase() === 'true';
const exitCode = status === 'environment-pending' && allowPending ? 0 : 1;
const payload = {
  schemaVersion: 'v1.1',
  producer: 'scan-image-secrets',
  generatedAt: generated,
  gitCommit: currentCommit(),
  status,
  passed: false,
  producerCompleted: errors.length === 0,
  exitCode,
  redacted: true,
  secretValuesPersisted: false,
  dockerInvoked: false,
  registryContacted: false,
  scanner: {
    name: 'image-secret-scanner-adapter',
    version: scannerVersion,
    executed: false,
    commandInvoked: false,
    allowlistUsed: false,
    redaction: 'strict'
  },
  images,
  imageCount: Object.keys(images).length,
  scanTargets,
  findings: [],
  findingCount: 0,
  dependencyCoverage: {
    inventoryProvided: Boolean(inventoryInfo),
    dependencyCount: dependencyRows.length,
    imageRequiredCount: dependencyRows.filter((row) => row.imageRequired).length,
    digestBoundCount: dependencyRows.filter((row) => Boolean(row.repoDigest)).length,
    missingDigestCount: missingDependencyImages.length,
    scannedCount: 0,
    rows: dependencyRows
  },
  inputs: {
    inventory: inventoryInfo ? sourceInfo(inventoryInfo) : null,
    schema: schemaInfo ? sourceInfo(schemaInfo) : null,
    explicitImages: [...explicitImages.values()].map((item) => ({
      name: item.name,
      repoDigest: item.repoDigest
    }))
  },
  errors: unique(errors.map(safeText)),
  warnings: unique(warnings.map(safeText)),
  policy: {
    allowlistDisabled: true,
    noWorkingTreeAllowlist: true,
    findingsAlwaysPresent: true,
    pendingMeansNotScanned: true,
    executionBoundary: '本 producer 只校验 immutable digest 并生成脱敏 pending 证据；真实镜像层扫描须由受审计 adapter 在受控环境执行。'
  },
  schema: {
    id: 'https://vio.invalid/contracts/image-secret-scan-v1.1.json',
    supplied: Boolean(schemaInfo),
    valid: schemaInfo ? Boolean(schemaDocument) : true
  }
};

writeDestination(outputInfo, payload, errors, noClobber);
if (errors.length > 0) {
  payload.status = 'blocked';
  payload.passed = false;
  payload.producerCompleted = false;
  payload.exitCode = 1;
  payload.errors = unique(errors.map(safeText));
}
const report = {
  schemaVersion: 'v1.1',
  producer: 'scan-image-secrets',
  generatedAt: generated,
  output: outputInfo ? safePath(outputInfo.relative) : safePath(outputArg),
  status: payload.status,
  passed: false,
  producerCompleted: payload.producerCompleted,
  exitCode: errors.length > 0 ? 1 : payload.exitCode,
  redacted: true,
  scannerVersion,
  scannerExecuted: false,
  dockerInvoked: false,
  registryContacted: false,
  allowlistUsed: false,
  imageCount: payload.imageCount,
  dependencyCount: dependencyRows.length,
  findingCount: 0,
  findings: [],
  errors: unique(errors.map(safeText)),
  warnings: unique(warnings.map(safeText)),
  policy: '报告只包含 digest、状态、计数和脱敏元数据；空 findings=[] 表示扫描尚未执行，不表示镜像无 secret。'
};
if (reportInfo) writeDestination(reportInfo, report, report.errors, noClobber);
if (report.errors.length > 0) {
  report.status = 'blocked';
  report.passed = false;
  report.exitCode = 1;
}

console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0) process.exitCode = 1;

function parseExplicitImages(raw, targetErrors) {
  const values = Array.isArray(raw) ? raw : (raw === undefined ? [] : [raw]);
  const result = new Map();
  for (const value of values) {
    if (value === true || value === false || value === undefined || value === null) {
      targetErrors.push('--image 必须使用 name=<repo>@sha256:<64hex>');
      continue;
    }
    const text = String(value).trim();
    if (!text || text.includes(',') || (text.match(/=/g) || []).length !== 1) {
      targetErrors.push('--image 必须是单个 name=<repo>@sha256:<64hex>，禁止逗号拼接或多重等号');
      continue;
    }
    const separator = text.indexOf('=');
    const name = text.slice(0, separator).trim();
    const repoDigest = text.slice(separator + 1).trim();
    if (!/^[A-Za-z][A-Za-z0-9._-]{1,63}$/.test(name)) {
      targetErrors.push('--image 名称格式无效');
      continue;
    }
    const key = name.toLowerCase();
    if (result.has(key)) {
      targetErrors.push('--image 名称重复: ' + safeIdentifier(name));
      continue;
    }
    const parsed = parseImageReference(repoDigest);
    if (!parsed.valid || !parsed.immutable || !parsed.digest || !/^.+@sha256:[a-f0-9]{64}$/i.test(repoDigest)) {
      targetErrors.push('--image ' + safeIdentifier(name) + ' 必须是 immutable @sha256 digest');
      continue;
    }
    const canonicalName = key === 'backend' || key === 'frontend' ? key : name;
    result.set(key, {name: canonicalName, repoDigest: canonicalDigest(parsed), digest: parsed.digest});
  }
  return result;
}

function inspectDependency(item, index, explicitImages, targetErrors) {
  if (!item || typeof item !== 'object' || Array.isArray(item)) {
    targetErrors.push('inventory dependency[' + index + '] 必须是对象');
    return {
      name: 'dependency-' + (index + 1),
      imageRequired: true,
      repoDigest: null,
      digest: null,
      status: 'blocked',
      reason: 'invalid-row'
    };
  }
  const rawName = scalar(item.name);
  const name = rawName && /^[A-Za-z][A-Za-z0-9._-]{1,95}$/.test(rawName)
    ? rawName
    : 'dependency-' + (index + 1);
  if (!rawName || name !== rawName) targetErrors.push('inventory dependency[' + index + '] 名称无效');
  const explicit = explicitImages.get(name.toLowerCase());
  const candidates = [
    {value: explicit && explicit.repoDigest, kind: 'image'},
    {value: scalar(item.repoDigest), kind: 'image'},
    {value: scalar(item.imageDigest), kind: 'image'},
    {value: scalar(item.imageOrEndpointDigest), kind: 'endpoint-or-image'},
    {value: scalar(item.image), kind: 'image'},
    {value: scalar(item.imageRef), kind: 'image'},
    {value: scalar(item.containerImage), kind: 'image'}
  ].filter((entry) => Boolean(entry.value));
  const candidateEntry = candidates[0] || null;
  const candidate = candidateEntry && candidateEntry.value;
  const version = scalar(item.version);
  const versionLooksImage = Boolean(version && (
    version.includes('@') ||
    /(^|\/)[a-z0-9][a-z0-9._-]*:[a-z0-9_][a-z0-9_.-]*$/i.test(version)
  ));
  const imageRequired = Boolean(
    explicit || candidate || versionLooksImage || KNOWN_IMAGE_DEPENDENCIES.has(name.toLowerCase())
  );
  if (candidate && candidateEntry.kind === 'endpoint-or-image' && isEndpointReference(candidate) && !explicit) {
    if (KNOWN_IMAGE_DEPENDENCIES.has(name.toLowerCase())) {
      targetErrors.push('inventory dependency ' + safeIdentifier(name) + ' 被标记为镜像但只提供 endpoint；必须提供 @sha256 digest');
      return {
        name,
        imageRequired: true,
        repoDigest: null,
        digest: null,
        status: 'blocked',
        reason: 'endpoint-for-image'
      };
    }
    return {
      name,
      imageRequired: false,
      repoDigest: null,
      digest: null,
      status: 'not-an-image',
      reason: 'inventory endpoint'
    };
  }
  if (!imageRequired) {
    return {
      name,
      imageRequired: false,
      repoDigest: null,
      digest: null,
      status: 'not-an-image',
      reason: 'inventory endpoint-or-library'
    };
  }
  if (!candidate && versionLooksImage) {
    const versionParsed = parseImageReference(version);
    if (!versionParsed.valid || !versionParsed.immutable || !versionParsed.digest) {
      targetErrors.push('inventory dependency ' + safeIdentifier(name) + ' 的 version 是可变镜像引用；必须改为 @sha256 digest');
      return {
        name,
        imageRequired: true,
        repoDigest: null,
        digest: null,
        status: 'blocked',
        reason: 'mutable-version-image'
      };
    }
  }
  if (!candidate) {
    return {
      name,
      imageRequired: true,
      repoDigest: null,
      digest: null,
      status: 'environment-pending',
      reason: 'missing-image-digest'
    };
  }
  const parsed = parseImageReference(candidate);
  if (!parsed.valid || !parsed.immutable || !parsed.digest || !/^.+@sha256:[a-f0-9]{64}$/i.test(candidate)) {
    targetErrors.push('inventory dependency ' + safeIdentifier(name) + ' 必须使用 immutable @sha256 digest');
    return {
      name,
      imageRequired: true,
      repoDigest: null,
      digest: null,
      status: 'blocked',
      reason: 'mutable-or-invalid-image'
    };
  }
  return {
    name,
    imageRequired: true,
    repoDigest: canonicalDigest(parsed),
    digest: parsed.digest,
    status: 'environment-pending',
    reason: explicit ? 'explicit-cli-binding' : 'inventory-binding'
  };
}

function pendingImage(name, repoDigest, source) {
  const parsed = parseImageReference(repoDigest);
  return {
    name,
    repoDigest: canonicalDigest(parsed),
    digest: parsed.digest,
    source,
    scannerVersion,
    scannerExecuted: false,
    scanComplete: false,
    findings: [],
    findingCount: 0,
    exitCode: 1,
    status: 'environment-pending',
    passed: false,
    redacted: true
  };
}

function canonicalDigest(parsed) {
  if (!parsed || !parsed.valid || !parsed.immutable || !parsed.digest || !parsed.repository) return null;
  const raw = String(parsed.raw || '');
  const at = raw.lastIndexOf('@');
  const repository = (at > 0 ? raw.slice(0, at) : (
    parsed.registry && parsed.registry !== 'docker.io' ? parsed.registry + '/' : ''
  ) + parsed.repository).toLowerCase();
  return repository + '@' + parsed.digest;
}

function resolveScannerVersion(input, targetErrors) {
  const value = singleScalar(input['scanner-version']) || singleScalar(input.scanner) || 'unconfigured';
  if (!value || value.length > 128 || /[\r\n]/.test(value) || !/^[A-Za-z0-9][A-Za-z0-9._\/@:+-]*$/.test(value)) {
    targetErrors.push('--scanner-version 只能包含安全的版本标识字符');
    return 'unconfigured';
  }
  return value;
}

function loadInput(value, label, targetErrors) {
  try {
    const info = inputFile(value, label);
    if (hasSymlinkAncestor(info.absolute)) targetErrors.push(label + ' 不允许通过符号链接路径读取');
    return info;
  } catch (error) {
    targetErrors.push(label + ': ' + safeText(error.message));
    return null;
  }
}

function resolveDestination(value, label, targetErrors) {
  try {
    const info = outputFile(value, label);
    if (hasSymlinkAncestor(info.absolute)) targetErrors.push(label + ' 不允许写入符号链接路径');
    return info;
  } catch (error) {
    targetErrors.push(label + ': ' + safeText(error.message));
    return null;
  }
}

function writeDestination(info, value, targetErrors, enforceNoClobber) {
  if (!info) return;
  try {
    writeJson(info.absolute, value, {noClobber: enforceNoClobber});
  } catch (error) {
    targetErrors.push('输出写入失败: ' + safeText(error.message));
  }
}

function singleScalar(value) {
  if (Array.isArray(value)) return value.length === 1 ? scalar(value[0]) : null;
  return scalar(value);
}

function scalar(value) {
  return value === undefined || value === null || value === true || value === false
    ? null
    : String(value).trim() || null;
}

function safeIdentifier(value) {
  return String(value || '').replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 96) || 'unknown';
}

function safeText(value) {
  return String(value || '')
    .replace(/(password|passwd|api[_-]?key|access[_-]?key|client[_-]?secret|private[_-]?key|token|secret)\s*[:=]\s*[^\s,;}]*/gi, '$1=[redacted]')
    .replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, 'Bearer [redacted]')
    .replace(/https?:\/\/[^\s]+/gi, '[endpoint-redacted]')
    .replace(/[\r\n]/g, ' ')
    .slice(0, 500);
}

function safePath(value) {
  return String(value || '').replaceAll('\\', '/').replace(/[\r\n]/g, '');
}

function samePath(a, b) {
  return path.resolve(a).toLowerCase() === path.resolve(b).toLowerCase();
}

function isSymlink(value) {
  try { return fs.lstatSync(value).isSymbolicLink(); } catch { return false; }
}

function hasSymlinkAncestor(value) {
  let current = path.resolve(value);
  while (true) {
    if (isSymlink(current)) return true;
    const parent = path.dirname(current);
    if (parent === current) return false;
    current = parent;
  }
}

function isEndpointReference(value) {
  return /^(?:https?|grpc|grpcs|tcp|udp):\/\//i.test(String(value || '').trim());
}

function unique(values) {
  return [...new Set(values.filter(Boolean))];
}
