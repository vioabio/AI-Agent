import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Shared, dependency-free helpers for the v1.1 release manifest and GA gate.
 *
 * The helpers intentionally expose metadata only.  They never return the
 * contents of evidence files, and callers should keep reports to paths,
 * hashes and reason codes.  Evidence is always supplied explicitly; there is
 * no directory globbing or fallback to a v1.0 artifact.
 */

const SHA256 = /^(?:sha256:)?[a-f0-9]{64}$/i;
const IMAGE_DIGEST = /^.+@sha256:[a-f0-9]{64}$/i;
const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$/;
const SECRET_KEY = /(password|passwd|api[_-]?key|access[_-]?key|secret|token|private[_-]?key|client[_-]?secret)/i;
const REDACTED = /^(?:\[?redacted\]?|\*{3,}|<redacted>|null|""|'')$/i;

export function normalizeSlashes(value) {
  return String(value ?? '').replaceAll('\\', '/').replace(/[\r\n]/g, '');
}

export function safeText(value) {
  return normalizeSlashes(value).slice(0, 240);
}

export function isPlaceholder(value) {
  if (value === undefined || value === null) return true;
  const text = String(value).trim();
  if (!text) return true;
  return /^(?:<[^>]+>|your[-_]|replace[-_]?me|change[-_]?me|set[-_]?at[-_]|not[-_]?built|not[-_]?published|pending|tbd|unknown|none|n\/a|null|undefined)$/i.test(text)
    || /(?:^|[.@/])example\.invalid(?:$|[/:])/i.test(text)
    || /(?:^|[-_])pending$/i.test(text);
}

export function isSha256(value) {
  return SHA256.test(String(value ?? '').trim());
}

export function normalizeHash(value) {
  return String(value ?? '').trim().replace(/^sha256:/i, '').toLowerCase();
}

export function isImageDigest(value) {
  return IMAGE_DIGEST.test(String(value ?? '').trim()) && !isPlaceholder(value);
}

export function isReleaseId(value) {
  return ID.test(String(value ?? '').trim()) && !isPlaceholder(value);
}

export function samePath(left, right) {
  const a = path.resolve(left);
  const b = path.resolve(right);
  return process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b;
}

export function within(root, target) {
  const relative = path.relative(path.resolve(root), path.resolve(target));
  return relative === '' || (relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}

/** Resolve a root without allowing a symlink to move the gate outside it. */
export function resolveRoot(rootArg = '.') {
  const lexical = path.resolve(process.cwd(), String(rootArg));
  let real;
  try { real = fs.realpathSync(lexical); }
  catch (error) { throw new Error(`repo-root 不存在或不可读取: ${safeText(rootArg)} (${error.message})`); }
  if (!fs.statSync(real).isDirectory()) throw new Error('repo-root 不是目录');
  return real;
}

/** Resolve a repository input file and reject traversal/symlink escapes. */
export function resolveRepoFile(repoRoot, value, label = '输入文件') {
  const raw = String(value ?? '').trim();
  if (!raw || raw.includes('\0')) throw new Error(`${label} 路径为空或无效`);
  const lexical = path.isAbsolute(raw) ? path.normalize(raw) : path.resolve(repoRoot, raw);
  if (!within(repoRoot, lexical) || samePath(repoRoot, lexical)) throw new Error(`${label} 必须位于 repo-root 内`);
  let real;
  try { real = fs.realpathSync(lexical); }
  catch (error) { throw new Error(`${label} 不存在或不可读取: ${safeText(raw)}`); }
  if (!within(repoRoot, real) || samePath(repoRoot, real)) throw new Error(`${label} 通过符号链接越出 repo-root`);
  if (!fs.statSync(real).isFile()) throw new Error(`${label} 不是普通文件`);
  return {absolute: lexical, real, relative: path.relative(repoRoot, lexical).split(path.sep).join('/')};
}

/** Resolve a path that may be relative to artifactRoot (manifest evidence). */
export function resolveEvidenceFile(repoRoot, artifactRoot, value, label = 'evidence') {
  const raw = normalizeSlashes(value).trim();
  if (!raw || path.isAbsolute(raw) || isPlaceholder(raw)) throw new Error(`${label} path 缺失、绝对路径或占位符`);
  if (raw === '.' || raw === '..' || raw.includes('../') || raw.includes('/..')) throw new Error(`${label} path 含越界片段`);
  const rootRelative = normalizeSlashes(path.relative(repoRoot, artifactRoot));
  const lexical = raw === rootRelative || raw.startsWith(`${rootRelative}/`)
    ? path.resolve(repoRoot, raw)
    : path.resolve(artifactRoot, raw);
  if (!within(repoRoot, lexical) || !within(artifactRoot, lexical)) throw new Error(`${label} path 必须位于 artifactRoot 内`);
  let real;
  try { real = fs.realpathSync(lexical); }
  catch (error) {
    // A missing preflight file is allowed to remain pending, but an existing
    // symlinked parent must still be checked so a later file cannot appear
    // outside artifactRoot unnoticed.
    let parent = path.dirname(lexical);
    while (!fs.existsSync(parent)) {
      const next = path.dirname(parent);
      if (next === parent) throw new Error(`${label} 父目录不存在或不可解析`);
      parent = next;
    }
    const realParent = fs.realpathSync(parent);
    if (!within(repoRoot, realParent) || !within(artifactRoot, realParent)) throw new Error(`${label} 父目录通过符号链接越出 artifactRoot`);
    return {absolute: lexical, real: null, relative: path.relative(repoRoot, lexical).split(path.sep).join('/')};
  }
  if (!within(repoRoot, real) || !within(artifactRoot, real)) throw new Error(`${label} 通过符号链接越出 artifactRoot`);
  return {absolute: lexical, real, relative: path.relative(repoRoot, lexical).split(path.sep).join('/')};
}

export function sha256File(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

export function readJsonFile(file, label = 'JSON') {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); }
  catch (error) { throw new Error(`${label} JSON 解析失败: ${error.message}`); }
}

export function readJsonLines(file, label = 'JSONL') {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter((line) => line.trim() !== '');
  const rows = [];
  for (let index = 0; index < lines.length; index += 1) {
    try { rows.push(JSON.parse(lines[index])); }
    catch (error) { throw new Error(`${label} 第 ${index + 1} 行 JSON 解析失败: ${error.message}`); }
  }
  return rows;
}

/**
 * Find path/hash evidence objects without exposing their values.  Pointer
 * entries are allowed to omit sha256 to avoid manifest↔pointer hash cycles.
 */
export function collectEvidenceEntries(value, location = 'evidence', result = []) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => collectEvidenceEntries(item, `${location}[${index}]`, result));
    return result;
  }
  if (!value || typeof value !== 'object') return result;
  if (typeof value.path === 'string') {
    result.push({
      location,
      path: value.path,
      sha256: value.sha256 ?? null,
      requireHash: value.sha256 !== undefined && value.sha256 !== null,
      schemaVersion: value.schemaVersion ?? null
    });
  }
  for (const [key, child] of Object.entries(value)) {
    if (key === 'path') continue;
    collectEvidenceEntries(child, `${location}.${key}`, result);
  }
  return result;
}

export function scanSecretLikeText(text) {
  const findings = [];
  const lines = String(text ?? '').split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    const keyMatch = line.match(/^\s*["']?([A-Za-z0-9_.-]+)["']?\s*[:=]\s*(.*?)\s*[,;]?\s*$/);
    if (keyMatch && SECRET_KEY.test(keyMatch[1])) {
      const value = String(keyMatch[2] ?? '').trim().replace(/^['"]|['"]$/g, '');
      if (value && !REDACTED.test(value) && !/^\$\{[^}]+\}$/.test(value)) {
        findings.push({line: index + 1, key: keyMatch[1], reasonCode: 'UNREDACTED_SECRET_VALUE'});
      }
      if (/^\$\{[^}]+\}$/.test(value)) findings.push({line: index + 1, key: keyMatch[1], reasonCode: 'UNRESOLVED_SECRET_INTERPOLATION'});
    }
    if (/(?:sk-[A-Za-z0-9]{12,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,})/.test(line)) {
      findings.push({line: index + 1, key: '[value]', reasonCode: 'TOKEN_SHAPED_VALUE'});
    }
  }
  return findings;
}

export function safeEvidenceHash(value) {
  if (!isSha256(value)) return null;
  return normalizeHash(value);
}

/** Basic checks shared by manifest validator and final gate. */
export function validateManifestStructure(manifest, options = {}) {
  const {
    repoRoot,
    manifestPath = null,
    preflightOnly = false,
    requireReleaseClass = null,
    requireEnvironment = null,
    allowStatuses = null,
    requireStatus = null,
    verifyEvidence = true
  } = options;
  const errors = [];
  const warnings = [];
  const checks = [];
  const add = (name, passed, reason = null) => {
    checks.push({name, passed, ...(!passed && reason ? {reason: safeText(reason)} : {})});
    if (!passed && reason) errors.push(`${name}: ${safeText(reason)}`);
  };

  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
    add('manifest-object', false, 'manifest 必须是 JSON 对象');
    return {errors, warnings, checks, evidence: []};
  }
  add('schema-version', manifest.schemaVersion === 'v1.1', 'schemaVersion 必须为 v1.1');
  const releaseClass = String(manifest.releaseClass ?? '').trim();
  const environment = String(manifest.environment ?? '').trim();
  const status = String(manifest.status ?? '').trim();
  add('release-class', ['GA', 'DEMO_ONLY', 'RC'].includes(releaseClass), 'releaseClass 必须为 GA、DEMO_ONLY 或 RC');
  add('environment', ['prod', 'staging'].includes(environment), 'environment 必须为 prod 或 staging');
  if (requireReleaseClass) add('required-release-class', releaseClass === String(requireReleaseClass), 'releaseClass 与 gate 要求不一致');
  if (requireEnvironment) add('required-environment', environment === String(requireEnvironment), 'environment 与 gate 要求不一致');
  if (allowStatuses && allowStatuses.length > 0) add('allowed-status', allowStatuses.includes(status), 'status 不在允许集合');
  if (requireStatus) add('required-status', status === String(requireStatus), 'status 与 gate 要求不一致');
  add('status-shape', ['BLOCKED', 'RC', 'DEMO_ONLY', 'GA'].includes(status), 'status 无效');
  if (releaseClass === 'GA') add('ga-environment', environment === 'prod' || (preflightOnly && environment === 'staging'), 'GA 只能绑定 prod（preflight 可暂存 staging）');
  if (releaseClass === 'DEMO_ONLY') add('demo-environment', environment === 'staging', 'DEMO_ONLY 必须绑定 staging');
  if (status === 'GA') add('ga-status-class', releaseClass === 'GA', '只有 GA releaseClass 可以使用 GA status');
  if (status === 'DEMO_ONLY') add('demo-status-class', releaseClass === 'DEMO_ONLY', '只有 DEMO_ONLY releaseClass 可以使用 DEMO_ONLY status');
  if (status === 'GA' && environment !== 'prod') add('ga-prod-status', false, 'GA status 禁止 staging');

  const required = ['artifactRoot', 'releaseId', 'generatedAt', 'gitCommit', 'migrationVersion', 'modelVersion', 'promptVersion', 'dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId', 'evaluationRunId', 'images', 'embedding', 'supportMatrix', 'evidence', 'rollback'];
  for (const field of required) {
    const present = manifest[field] !== undefined && manifest[field] !== null;
    add(`field-${field}`, present, '缺少必需字段');
  }
  for (const field of ['releaseId', 'gitCommit', 'migrationVersion', 'modelVersion', 'promptVersion', 'dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId', 'evaluationRunId']) {
    if (manifest[field] !== undefined) add(`non-placeholder-${field}`, !isPlaceholder(manifest[field]), '字段仍是占位符');
  }
  if (manifest.generatedAt !== undefined) {
    const generated = new Date(manifest.generatedAt);
    add('generated-at', !Number.isNaN(generated.getTime()), 'generatedAt 必须是合法 ISO-8601 时间');
  }
  if (manifest.releaseId !== undefined) add('release-id-shape', isReleaseId(manifest.releaseId), 'releaseId 格式无效');
  if (manifest.artifactRoot !== undefined) {
    const artifactRaw = normalizeSlashes(manifest.artifactRoot);
    add('artifact-root-shape', !path.isAbsolute(artifactRaw) && artifactRaw !== '.' && !artifactRaw.includes('../') && !artifactRaw.includes('/..'), 'artifactRoot 必须是仓库内相对目录');
  }
  const artifactRoot = repoRoot && manifest.artifactRoot ? path.resolve(repoRoot, manifest.artifactRoot) : null;
  if (repoRoot && artifactRoot) add('artifact-root-boundary', within(repoRoot, artifactRoot), 'artifactRoot 越出 repo-root');
  if (manifest.images && typeof manifest.images === 'object') {
    for (const name of ['backend', 'frontend']) {
      const raw = typeof manifest.images[name] === 'string' ? manifest.images[name] : manifest.images[name]?.repoDigest;
      add(`image-${name}`, isImageDigest(raw), '镜像必须是 @sha256:<64hex> 且不能是占位符');
    }
    const back = typeof manifest.images.backend === 'string' ? manifest.images.backend : manifest.images.backend?.repoDigest;
    const front = typeof manifest.images.frontend === 'string' ? manifest.images.frontend : manifest.images.frontend?.repoDigest;
    if (back && front) add('image-distinct', back !== front, 'backend/frontend 不得复用相同 digest');
  }
  if (manifest.embedding && typeof manifest.embedding === 'object') {
    add('embedding-dimension', Number.isInteger(manifest.embedding.dimension) && manifest.embedding.dimension > 0, 'embedding.dimension 必须是正整数');
    for (const field of ['provider', 'model', 'preprocessingProfile', 'indexProfile']) {
      if (manifest.embedding[field] !== undefined) add(`embedding-${field}`, !isPlaceholder(manifest.embedding[field]), 'embedding 字段仍是占位符');
    }
  }
  if (manifest.supportMatrix && typeof manifest.supportMatrix === 'object') {
    for (const field of ['game_release_id', 'language', 'battle_format_id', 'ruleset_version_id', 'data_release_id', 'knowledge_release_id', 'index_release_id']) {
      if (manifest.supportMatrix[field] !== undefined) add(`support-${field}`, !isPlaceholder(manifest.supportMatrix[field]), '支持矩阵字段缺失或占位');
    }
    for (const [matrixField, manifestField] of [['data_release_id', 'dataReleaseId'], ['knowledge_release_id', 'knowledgeReleaseId'], ['index_release_id', 'indexReleaseId']]) {
      if (manifest.supportMatrix[matrixField] !== undefined && manifest[manifestField] !== undefined) add(`support-lineage-${matrixField}`, manifest.supportMatrix[matrixField] === manifest[manifestField], '支持矩阵与 manifest 血缘不一致');
    }
  }
  if (!manifest.evidence || typeof manifest.evidence !== 'object') add('evidence-object', false, 'evidence 必须是对象');
  if (!manifest.rollback || typeof manifest.rollback !== 'object') add('rollback-object', false, 'rollback 必须是对象');

  const evidence = collectEvidenceEntries(manifest.evidence ?? {});
  if (verifyEvidence && repoRoot && artifactRoot) {
    for (const entry of evidence) {
      let resolved;
      try { resolved = resolveEvidenceFile(repoRoot, artifactRoot, entry.path, entry.location); }
      catch (error) { errors.push(`${entry.location}: ${safeText(error.message)}`); checks.push({name: `evidence-path-${entry.location}`, passed: false}); continue; }
      if (entry.schemaVersion !== null && entry.schemaVersion !== 'v1.1') add(`evidence-schema-${entry.location}`, false, 'evidence schemaVersion 必须为 v1.1');
      const pointerEntry = /(?:^|\.)releasePointer(?:$|\.)|(?:^|\.)pointer(?:$|\.)/i.test(entry.location);
      if (!pointerEntry && !isSha256(entry.sha256)) add(`evidence-hash-shape-${entry.location}`, false, '非 pointer evidence 必须提供 sha256');
      if (pointerEntry && entry.sha256 !== null && !isSha256(entry.sha256)) add(`evidence-hash-shape-${entry.location}`, false, 'pointer sha256 格式无效');
      if (!resolved.real) {
        if (preflightOnly && status === 'BLOCKED') warnings.push(`${entry.location}: 文件未就绪（preflight 保持 BLOCKED）`);
        else add(`evidence-exists-${entry.location}`, false, 'evidence 文件不存在');
        continue;
      }
      if (!fs.statSync(resolved.real).isFile()) {
        add(`evidence-file-${entry.location}`, false, 'evidence path 不是普通文件');
        continue;
      }
      if (entry.requireHash) {
        const actual = sha256File(resolved.real);
        add(`evidence-hash-${entry.location}`, normalizeHash(entry.sha256) === actual, 'evidence SHA-256 不匹配');
      }
    }
  }
  if (manifest.evidence && manifest.evidence.rpoRto) {
    const rpo = manifest.evidence.rpoRto;
    const numeric = (value) => typeof value === 'number' && Number.isFinite(value) && value >= 0;
    if (status === 'GA' && !preflightOnly) {
      add('rpo-actual', numeric(rpo.rpoSeconds), 'GA rpoSeconds 必须是数字');
      add('rto-actual', numeric(rpo.rtoSeconds), 'GA rtoSeconds 必须是数字');
      if (numeric(rpo.rpoSeconds) && numeric(rpo.targetRpoSeconds)) add('rpo-target', rpo.rpoSeconds <= rpo.targetRpoSeconds, 'RPO 超出目标');
      if (numeric(rpo.rtoSeconds) && numeric(rpo.targetRtoSeconds)) add('rto-target', rpo.rtoSeconds <= rpo.targetRtoSeconds, 'RTO 超出目标');
    }
  }
  if (manifest.releaseClass === 'GA' && manifest.environment === 'staging' && !preflightOnly) add('ga-staging-block', false, 'GA final manifest 禁止 staging');
  if (manifestPath) {
    const normalized = normalizeSlashes(path.relative(repoRoot ?? process.cwd(), manifestPath));
    if (/evaluation\/.*manifest|v1\.0/i.test(normalized)) warnings.push('manifest 路径看起来不是 release/v1.1 产物；请确认未混用 v1.0 evaluation manifest');
  }
  return {errors, warnings, checks, evidence, artifactRoot};
}

export function writeAtomicJson(file, payload, {noClobber = false} = {}) {
  if (noClobber && fs.existsSync(file)) throw new Error(`输出已存在（--no-clobber）: ${safeText(file)}`);
  if (fs.existsSync(file)) {
    let existing;
    try { existing = fs.realpathSync(file); } catch (error) { throw new Error(`输出路径不可解析: ${safeText(error.message)}`); }
    const parent = fs.realpathSync(path.dirname(file));
    if (!within(parent, existing)) throw new Error('输出路径通过符号链接越界');
    if (!fs.statSync(existing).isFile()) throw new Error('输出路径不是普通文件');
  }
  fs.mkdirSync(path.dirname(file), {recursive: true});
  const temporary = path.join(path.dirname(file), `.${path.basename(file)}.${process.pid}.${crypto.randomBytes(6).toString('hex')}.tmp`);
  const content = Buffer.from(`${JSON.stringify(payload, null, 2)}\n`, 'utf8');
  const descriptor = fs.openSync(temporary, 'wx');
  try {
    fs.writeFileSync(descriptor, content);
    fs.fsyncSync(descriptor);
  } finally {
    fs.closeSync(descriptor);
  }
  try { fs.renameSync(temporary, file); }
  catch (error) { try { fs.rmSync(temporary, {force: true}); } catch { /* best effort */ } throw error; }
  return file;
}

export function hashDescriptor(file, repoRoot) {
  const relative = path.relative(repoRoot, file).split(path.sep).join('/');
  return {path: relative, sha256: sha256File(file)};
}
