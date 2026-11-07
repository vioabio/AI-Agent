import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {
  isImageDigest,
  isPlaceholder,
  isSha256,
  normalizeHash,
  normalizeSlashes,
  readJsonFile,
  resolveRepoFile,
  safeText,
  scanSecretLikeText,
  sha256File,
  writeAtomicJson
} from './release-manifest.mjs';

const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$/;
const SECRET_KEY = /(password|passwd|api[_-]?key|access[_-]?key|secret|token|private[_-]?key|client[_-]?secret)/i;
const REDACTED_VALUE = /^(?:\[?redacted\]?|\*{3,}|<redacted>|null|undefined|none|n\/a|""|'')$/i;

export function safeId(value, label = 'id') {
  const text = String(value ?? '').trim();
  if (!ID.test(text) || isPlaceholder(text)) throw new Error(`${label} 缺失或格式无效`);
  return text;
}

export function within(root, target) {
  const rel = path.relative(path.resolve(root), path.resolve(target));
  return rel === '' || (rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel));
}

export function samePath(a, b) {
  const left = path.resolve(a); const right = path.resolve(b);
  return process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right;
}

export function relative(root, target) {
  return path.relative(root, target).split(path.sep).join('/') || '.';
}

export function resolveRoot(rootArg = '.') {
  const lexical = path.resolve(process.cwd(), String(rootArg));
  const real = fs.realpathSync(lexical);
  if (!fs.statSync(real).isDirectory()) throw new Error('repo-root 不是目录');
  return real;
}

export function inputFile(root, value, label = '输入文件') {
  return resolveRepoFile(root, value, label);
}

export function outputFile(root, value, label = '输出文件') {
  const raw = String(value ?? '').trim();
  if (!raw) throw new Error(`${label} 路径为空`);
  const absolute = path.isAbsolute(raw) ? path.normalize(raw) : path.resolve(root, raw);
  if (!within(root, absolute) || samePath(root, absolute)) throw new Error(`${label} 必须位于 repo-root 内`);
  let parent = path.dirname(absolute);
  while (!fs.existsSync(parent)) {
    const next = path.dirname(parent);
    if (next === parent) throw new Error(`${label} 父目录无效`);
    parent = next;
  }
  if (!within(root, fs.realpathSync(parent))) throw new Error(`${label} 父目录越界`);
  if (fs.existsSync(absolute)) {
    const real = fs.realpathSync(absolute);
    if (!within(root, real) || !fs.statSync(real).isFile()) throw new Error(`${label} 已存在但不是仓库内普通文件`);
  }
  return {absolute, relative: relative(root, absolute)};
}

export function readSafeJson(root, value, label = 'JSON') {
  const info = inputFile(root, value, label);
  // Scan the original bytes as well as the parsed representation.  Scanning
  // only JSON.stringify(data) misses secrets in compact JSON (the key is not
  // at the beginning of a line), which would make a release artifact unsafe
  // to publish.  We never return the raw content to callers.
  const raw = fs.readFileSync(info.real, 'utf8');
  const data = readJsonFile(info.real, label);
  const secretFindings = [
    ...scanSecretLikeText(raw),
    ...scanSecretLikeText(JSON.stringify(data)),
    ...scanSecretLikeData(data)
  ];
  if (secretFindings.length > 0) throw new Error(`${label} 含未脱敏 secret`);
  return {info, data};
}

export function hashInfo(info) {
  return {path: info.relative, sha256: sha256File(info.real)};
}

export function parseHashDocument(root, value, targetInfo, label = 'manifest hash') {
  const {info, data} = readSafeJson(root, value, label);
  const candidates = [];
  if (Array.isArray(data)) candidates.push(...data);
  else if (Array.isArray(data.files)) candidates.push(...data.files);
  else if (Array.isArray(data.entries)) candidates.push(...data.entries);
  else if (data.path || data.sha256) candidates.push(data);
  const targetRelative = normalizeSlashes(targetInfo.relative);
  const exact = candidates.filter((row) => normalizeSlashes(row?.path) === targetRelative);
  // Artifact hash files are sometimes authored relative to artifacts/v1.1.
  // Accept that explicit prefix difference only when it identifies exactly
  // one entry; never silently choose between same-basename files.
  const suffix = candidates.filter((row) => {
    const candidatePath = normalizeSlashes(row?.path);
    return candidatePath && (targetRelative.endsWith(`/${candidatePath}`) || candidatePath.endsWith(`/${targetRelative}`));
  });
  const matches = exact.length > 0 ? exact : suffix;
  if (matches.length !== 1) throw new Error(`${label} 缺少唯一目标 manifest 的有效 SHA-256`);
  const match = matches[0];
  if (!match || !isSha256(match.sha256)) throw new Error(`${label} 缺少目标 manifest 的有效 SHA-256`);
  if (match.algorithm !== undefined && !/^sha-?256$/i.test(String(match.algorithm).trim())) {
    throw new Error(`${label} algorithm 必须是 SHA-256`);
  }
  const actual = sha256File(targetInfo.real);
  if (normalizeHash(match.sha256) !== actual) throw new Error(`${label} 与 manifest SHA-256 不匹配`);
  return {info, hash: actual};
}

export function digestFromManifest(manifest, name = 'backend') {
  const raw = typeof manifest?.images?.[name] === 'string' ? manifest.images[name] : manifest?.images?.[name]?.repoDigest;
  if (!isImageDigest(raw)) throw new Error(`manifest.images.${name} 缺少不可变 digest`);
  return String(raw).trim();
}

export function validateManifestBinding(manifest, {environment, releaseClass, status} = {}) {
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) throw new Error('manifest 必须是 JSON 对象');
  if (manifest.schemaVersion !== 'v1.1') throw new Error('manifest schemaVersion 必须为 v1.1');
  if (!['prod', 'staging'].includes(String(manifest.environment))) throw new Error('manifest environment 无效');
  if (!['GA', 'DEMO_ONLY', 'RC'].includes(String(manifest.releaseClass))) throw new Error('manifest releaseClass 无效');
  if (!['BLOCKED', 'RC', 'DEMO_ONLY', 'GA'].includes(String(manifest.status))) throw new Error('manifest status 无效');
  if (environment && manifest.environment !== environment) throw new Error('manifest environment 不匹配');
  if (releaseClass && manifest.releaseClass !== releaseClass) throw new Error('manifest releaseClass 不匹配');
  if (status && manifest.status !== status) throw new Error('manifest status 不匹配');
  if (manifest.status === 'GA' && (manifest.releaseClass !== 'GA' || manifest.environment !== 'prod')) throw new Error('GA manifest 必须是 prod/GA');
  if (manifest.status === 'DEMO_ONLY' && (manifest.releaseClass !== 'DEMO_ONLY' || manifest.environment !== 'staging')) throw new Error('DEMO_ONLY manifest 必须是 staging/DEMO_ONLY');
  safeId(manifest.releaseId, 'manifest.releaseId');
  if (isPlaceholder(manifest.generatedAt)) throw new Error('manifest.generatedAt 缺失或占位');
  const date = new Date(manifest.generatedAt);
  if (Number.isNaN(date.getTime())) throw new Error('manifest.generatedAt 无效');
  digestFromManifest(manifest, 'backend');
  digestFromManifest(manifest, 'frontend');
  for (const field of ['dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId', 'migrationVersion']) {
    if (isPlaceholder(manifest[field])) throw new Error(`manifest.${field} 缺失或占位`);
  }
  return manifest;
}

export function registrySource(root, registryArg, registryFileArg) {
  const candidate = registryFileArg ?? registryArg;
  if (!candidate || candidate === true || candidate === false) throw new Error('必须提供 --registry-file（离线受信 registry 快照）');
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(String(candidate))) {
    throw new Error('registry snapshot 必须是仓库内本地 JSON 文件；网络地址被禁用');
  }
  try {
    const info = inputFile(root, candidate, 'registry snapshot');
    const raw = fs.readFileSync(info.real, 'utf8');
    const data = readJsonFile(info.real, 'registry snapshot');
    if ([...scanSecretLikeText(raw), ...scanSecretLikeText(JSON.stringify(data)), ...scanSecretLikeData(data)].length > 0) throw new Error('registry snapshot 含未脱敏 secret');
    return {info, data, offline: true};
  } catch (error) {
    if (registryFileArg) throw error;
    // A URI/name is metadata only; this implementation deliberately does not
    // perform network I/O.  The caller receives an explicit pending failure.
    throw new Error('registry 不是仓库内 JSON 快照；网络解析被禁用，请提供 --registry-file');
  }
}

export function getActivePointer(registry) {
  return registry?.active ?? registry?.activePointer ?? registry?.pointer ?? null;
}

export function getPreviousPointer(registry) {
  return registry?.previous ?? registry?.previousPointer ?? registry?.previousRelease ?? null;
}

export function registryRecords(registry) {
  const rows = registry?.releases ?? registry?.records ?? registry?.entries ?? [];
  return Array.isArray(rows) ? rows : [];
}

export function pointerManifestPath(root, pointer, label = 'pointer manifest') {
  const value = pointer?.manifestPath ?? pointer?.manifest?.path;
  if (!value || isPlaceholder(value) || path.isAbsolute(String(value))) throw new Error(`${label} 缺少仓库内 manifestPath`);
  const info = inputFile(root, value, label);
  return info;
}

/**
 * Resolve a pointer's manifest path without directory discovery. Registries
 * in this project use either repo-relative paths or paths relative to the
 * canonical `artifacts/v1.1` root. Both candidates are explicit; if both
 * exist but point to different files the input is rejected as ambiguous.
 */
export function resolvePointerFile(root, pointer, label = 'pointer manifest', artifactRoot = 'artifacts/v1.1') {
  const raw = pointer?.manifestPath ?? pointer?.manifest?.path;
  if (!raw || isPlaceholder(raw) || path.isAbsolute(String(raw))) throw new Error(`${label} 缺少仓库内相对 manifestPath`);
  const value = normalizeSlashes(raw).replace(/^\.\//, '');
  if (value.startsWith('../') || value.includes('/../') || value === '.' || value === '..') throw new Error(`${label} path 含越界片段`);
  const candidates = [value];
  const artifact = normalizeSlashes(artifactRoot).replace(/^\.\//, '').replace(/\/$/, '');
  if (artifact && value !== artifact && !value.startsWith(`${artifact}/`)) candidates.push(`${artifact}/${value}`);
  const resolved = [];
  for (const candidate of candidates) {
    try { resolved.push(inputFile(root, candidate, label)); } catch { /* try the next explicit form */ }
  }
  if (resolved.length === 0) throw new Error(`${label} 不存在或不可读取`);
  const uniqueReal = [...new Map(resolved.map((item) => [path.resolve(item.real).toLowerCase(), item])).values()];
  if (uniqueReal.length > 1) throw new Error(`${label} 同时匹配多个文件，拒绝猜测`);
  return uniqueReal[0];
}

export function safePointerSummary(pointer, sourceInfo = null) {
  return {
    pointerVersion: Number.isInteger(pointer?.pointerVersion) ? pointer.pointerVersion : null,
    registryVersion: pointer?.registryVersion ?? null,
    releaseId: pointer?.releaseId ?? null,
    releaseClass: pointer?.releaseClass ?? null,
    status: pointer?.status ?? null,
    releaseStatus: pointer?.releaseStatus ?? pointer?.manifestStatus ?? null,
    environment: pointer?.environment ?? null,
    registry: redactRegistryRef(pointer?.registry),
    manifestPath: pointer?.manifestPath ?? pointer?.manifest?.path ?? null,
    manifestSha256: isSha256(pointer?.manifestSha256 ?? pointer?.manifest?.sha256) ? normalizeHash(pointer.manifestSha256 ?? pointer.manifest.sha256) : null,
    imageDigest: pointer?.imageDigest ?? null,
    updatedAt: pointer?.updatedAt ?? pointer?.registeredAt ?? null,
    expiresAt: pointer?.expiresAt ?? null,
    source: sourceInfo ? hashInfo(sourceInfo) : null
  };
}

/** Remove credentials/query secrets from a registry reference before it is
 * written to a report or pointer artifact.  Registry access itself is never
 * attempted by these producers. */
export function redactRegistryRef(value) {
  if (value === undefined || value === null || value === '') return null;
  const text = String(value).trim();
  try {
    const parsed = new URL(text);
    parsed.username = '';
    parsed.password = '';
    parsed.search = '';
    parsed.hash = '';
    return parsed.toString().replace(/\/$/, '');
  } catch {
    return text
      .replace(/\/\/[^/@\s]+@/g, '//[redacted]@')
      .replace(/[?&](?:token|key|secret|password|api[_-]?key)=[^&\s]*/gi, '')
      .replace(/[?&]+$/, '');
  }
}

/** Compare a pointer path with a root-relative file without accepting an
 * absolute path or a basename-only guess. */
export function pathsEquivalent(pointerPath, targetRelative) {
  const left = normalizeSlashes(pointerPath).replace(/^\.\//, '');
  const right = normalizeSlashes(targetRelative).replace(/^\.\//, '');
  if (!left || !right || path.isAbsolute(left) || left.includes('/../') || left.startsWith('../')) return false;
  if (left === right) return true;
  // Do not allow a basename-only pointer to bind an arbitrary nested file.
  if (!left.includes('/') || !right.includes('/')) return false;
  return right.endsWith(`/${left}`) || left.endsWith(`/${right}`);
}

export function validateSchemaDocument(schema) {
  if (!schema || typeof schema !== 'object' || Array.isArray(schema)) throw new Error('schema 必须是 JSON 对象');
  if (schema.$id && !String(schema.$id).includes('release-manifest-v1.1')) throw new Error('schema 不是 release-manifest-v1.1');
  if (schema.properties?.schemaVersion?.const !== 'v1.1') throw new Error('schema 未固定 schemaVersion=v1.1');
  const required = new Set(Array.isArray(schema.required) ? schema.required : []);
  for (const field of ['schemaVersion', 'releaseClass', 'environment', 'releaseId', 'generatedAt', 'images', 'status']) {
    if (!required.has(field)) throw new Error(`schema 缺少 required.${field}`);
  }
  return schema;
}

export function validateTimestamp(value, label, {futureSkewMs = 5 * 60 * 1000, now = Date.now()} = {}) {
  if (isPlaceholder(value)) throw new Error(`${label} 缺失或占位`);
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:?\d{2})$/.test(value.trim())) {
    throw new Error(`${label} 必须是 ISO-8601 时间`);
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) throw new Error(`${label} 无效`);
  if (date.getTime() > now + futureSkewMs) throw new Error(`${label} 不能显著晚于当前时间`);
  return date;
}

export function validateExpiry(value, label = 'expiresAt', now = Date.now()) {
  if (value === undefined || value === null || value === '') return null;
  const date = validateTimestamp(value, label, {futureSkewMs: Number.MAX_SAFE_INTEGER, now});
  if (date.getTime() <= now) throw new Error(`${label} 已过期`);
  return date;
}

export function writeResult(root, outputArg, payload, {noClobber = false, label = '输出'} = {}) {
  const info = outputFile(root, outputArg, label);
  writeAtomicJson(info.absolute, payload, {noClobber});
  return info;
}

export function baseResult(producer, root, inputHashes = {}) {
  return {schemaVersion: 'v1.1', producer, generatedAt: new Date().toISOString(), repoRoot: '.', redacted: true, inputHashes};
}

export function unique(values) { return [...new Set(values.map((value) => String(value)))]; }

export function hashText(value) { return crypto.createHash('sha256').update(String(value), 'utf8').digest('hex'); }

function scanSecretLikeData(value, location = '$', findings = []) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => scanSecretLikeData(item, `${location}[${index}]`, findings));
    return findings;
  }
  if (!value || typeof value !== 'object') return findings;
  for (const [key, child] of Object.entries(value)) {
    if (SECRET_KEY.test(key) && child !== null && typeof child !== 'object') {
      const text = String(child).trim();
      if (text && !REDACTED_VALUE.test(text) && !/^\$\{[^}]+\}$/.test(text)) {
        findings.push({location: `${location}.${key}`, reasonCode: 'UNREDACTED_SECRET_VALUE'});
      } else if (/^\$\{[^}]+\}$/.test(text)) {
        findings.push({location: `${location}.${key}`, reasonCode: 'UNRESOLVED_SECRET_INTERPOLATION'});
      }
    }
    scanSecretLikeData(child, `${location}.${key}`, findings);
  }
  return findings;
}

// Re-export the narrowly scoped manifest helpers used by the CLI producers.
// Keeping the import surface in one place makes it harder for a producer to
// accidentally depend on a network client or a secret-bearing config loader.
export {
  isImageDigest,
  isPlaceholder,
  isSha256,
  normalizeHash,
  normalizeSlashes,
  safeText
};
