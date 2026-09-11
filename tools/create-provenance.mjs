#!/usr/bin/env node

/**
 * Build redacted, per-image provenance predicates from already captured
 * evidence.  This producer is intentionally offline: it does not build or
 * push images and does not invoke syft, trivy, cosign, Docker, or a registry.
 * Missing evidence produces a blocked predicate and a non-zero exit code.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  inputFile,
  outputFile,
  parseImageReference,
  readInput,
  sourceInfo
} from './lib/supply-chain.mjs';
import {currentCommit, generatedAt, parseArgs, placeholder, readJson, sha256Text, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const knownOptions = new Set([
  'image-refs', 'lockfiles', 'lockfile', 'context-hash', 'backend-context-hash',
  'frontend-context-hash', 'backend-sbom', 'frontend-sbom', 'backend-base-image',
  'frontend-base-image', 'base-image', 'git-sha', 'builder', 'build-command',
  'build-time', 'output-dir', 'report', 'no-clobber'
]);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const outputDirRaw = scalar(args['output-dir']) ?? 'artifacts/v1.1/release';
const imageRefsInfo = loadInfo(args['image-refs'], 'image refs', errors);
const lockfilesInfo = loadInfo(args.lockfiles ?? args.lockfile, 'lockfile hash manifest', errors);
const sbomInfos = {
  backend: loadInfo(args['backend-sbom'], 'backend SBOM', errors),
  frontend: loadInfo(args['frontend-sbom'], 'frontend SBOM', errors)
};
const contextInfos = collectKeyedInputs(args['context-hash']);
if (args['backend-context-hash'] !== undefined) contextInfos.backend = scalar(args['backend-context-hash']);
if (args['frontend-context-hash'] !== undefined) contextInfos.frontend = scalar(args['frontend-context-hash']);
for (const key of Object.keys(contextInfos)) if (!['backend', 'frontend'].includes(key)) errors.push(`未知 context hash 名称: ${safeText(key)}`);
for (const name of ['backend', 'frontend']) {
  if (!contextInfos[name]) errors.push(`缺少 ${name} context hash 输入（--context-hash ${name}=<path>）`);
}
const contextFiles = {};
for (const name of ['backend', 'frontend']) contextFiles[name] = loadInfo(contextInfos[name], `${name} context hash`, errors);

const gitSha = scalar(args['git-sha']);
if (!gitSha || placeholder(gitSha) || !/^[a-f0-9]{40}(?:[a-f0-9]{24})?$/i.test(gitSha)) errors.push('--git-sha 必须是 40 或 64 位 Git SHA');
const buildTime = scalar(args['build-time']) ?? generatedAt();
const parsedBuildTime = parseTimestamp(buildTime);
if (!parsedBuildTime) errors.push('--build-time 必须是合法 ISO-8601 时间戳');
const builder = scalar(args.builder) ?? 'v1.1-static-provenance-producer';
if (placeholder(builder)) errors.push('--builder 不能是占位符');

let imageRefs = null;
if (imageRefsInfo) {
  try { imageRefs = readJson(imageRefsInfo.real, 'image refs'); }
  catch (error) { errors.push(`image refs JSON 解析失败: ${safeText(error.message)}`); }
}
const imageObjects = normalizeImageRefs(imageRefs, gitSha, errors);
const lockfiles = validateLockfileManifest(lockfilesInfo, errors);
const contexts = {};
for (const name of ['backend', 'frontend']) contexts[name] = validateContextManifest(contextFiles[name], errors);
const sboms = {};
for (const name of ['backend', 'frontend']) sboms[name] = validateSbom(sbomInfos[name], errors);
const baseImages = collectBaseImages(args, errors);

let outputInfos = {};
let reportInfo = null;
try {
  for (const name of ['backend', 'frontend']) {
    const file = path.join(outputDirRaw, `provenance-${name}.json`);
    outputInfos[name] = outputFile(file, `${name} provenance`);
  }
} catch (error) { errors.push(`output-dir 无效: ${safeText(error.message)}`); }
if (args.report !== undefined) {
  try { reportInfo = outputFile(args.report, 'provenance 报告'); }
  catch (error) { errors.push(`报告路径无效: ${safeText(error.message)}`); }
}
for (const info of Object.values(outputInfos)) {
  if (imageRefsInfo && samePath(info.absolute, imageRefsInfo.absolute)) errors.push('provenance 输出不能覆盖 image refs 输入');
  if (lockfilesInfo && samePath(info.absolute, lockfilesInfo.absolute)) errors.push('provenance 输出不能覆盖 lockfile 输入');
  if (reportInfo && samePath(info.absolute, reportInfo.absolute)) errors.push('provenance 输出不能覆盖报告');
}
if (reportInfo && Object.values(outputInfos).some((info) => samePath(reportInfo.absolute, info.absolute))) errors.push('报告不能与 provenance 输出同一路径');

const status = errors.length > 0 ? 'blocked' : 'passed';
for (const name of ['backend', 'frontend']) {
  const predicate = buildPredicate(name, {
    status,
    gitSha,
    builder,
    buildTime: parsedBuildTime?.toISOString() ?? buildTime,
    imageRefsInfo,
    image: imageObjects[name],
    lockfiles,
    contexts: contexts[name],
    sbom: sboms[name],
    baseImage: baseImages[name],
    buildCommand: scalar(args['build-command'])
  }, errors);
  try {
    if (!outputInfos[name]) throw new Error('输出路径无效');
    writeJson(outputInfos[name].absolute, predicate, {noClobber: boolArg(args['no-clobber'])});
  } catch (error) {
    errors.push(`${name} provenance 写入失败: ${safeText(error.message)}`);
  }
}

const finalStatus = errors.length > 0 ? 'blocked' : 'passed';
const report = {
  schemaVersion: 'v1.1',
  producer: 'create-provenance',
  generatedAt: generatedAt(),
  gitCommit: currentCommit(),
  status: finalStatus,
  passed: finalStatus === 'passed',
  producerCompleted: finalStatus === 'passed',
  exitCode: finalStatus === 'passed' ? 0 : 1,
  redacted: true,
  outputs: Object.fromEntries(Object.entries(outputInfos).map(([name, info]) => [name, info ? safePath(info.relative) : null])),
  imageRefs: imageRefsInfo ? sourceInfo(imageRefsInfo) : null,
  lockfiles: lockfilesInfo ? sourceInfo(lockfilesInfo) : null,
  sbomInputs: Object.fromEntries(Object.entries(sbomInfos).map(([name, info]) => [name, info ? sourceInfo(info) : null])),
  contextInputs: Object.fromEntries(Object.entries(contextFiles).map(([name, info]) => [name, info ? sourceInfo(info) : null])),
  errors,
  warnings,
  policy: 'provenance 只绑定已捕获的 commit、RepoDigest、lockfile/context/SBOM hash；签名、attestation、registry 回读须由独立门禁完成。'
};
if (reportInfo) {
  try { writeJson(reportInfo.absolute, report, {noClobber: boolArg(args['no-clobber'])}); }
  catch (error) { report.errors.push(`报告写入失败: ${safeText(error.message)}`); report.passed = false; report.exitCode = 1; }
}
console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0) process.exitCode = 1;

function buildPredicate(name, values, targetErrors) {
  const image = values.image;
  const valid = values.status === 'passed' && Boolean(image?.repoDigest && values.lockfiles?.valid && values.contexts?.valid && values.sbom?.valid);
  if (!image?.repoDigest) targetErrors.push(`${name} provenance 缺少输出 RepoDigest`);
  if (!values.lockfiles?.valid) targetErrors.push(`${name} provenance lockfile hash manifest 未通过`);
  if (!values.contexts?.valid) targetErrors.push(`${name} provenance context hash 未通过`);
  if (!values.sbom?.valid) targetErrors.push(`${name} provenance SBOM 输入未通过`);
  return {
    schemaVersion: 'v1.1',
    producer: 'create-provenance',
    provenanceType: 'slsa-provenance-v1',
    generatedAt: generatedAt(),
    status: valid ? 'passed' : 'blocked',
    passed: valid,
    producerCompleted: valid,
    exitCode: valid ? 0 : 1,
    redacted: true,
    subject: image ? {name, repoDigest: image.repoDigest, digest: image.digest, sha256: image.sha256} : {name, repoDigest: null, digest: null, sha256: null},
    buildDefinition: {
      buildType: 'https://slsa.dev/provenance/v1',
      externalParameters: {
        gitSha: values.gitSha,
        buildCommand: redactCommand(values.buildCommand),
        imageName: name
      },
      internalParameters: {builder: values.builder}
    },
    runDetails: {
      builder: {id: values.builder},
      metadata: {startedAt: values.buildTime, finishedAt: values.buildTime, invocationId: `${name}-${values.gitSha ?? 'unknown'}`}
    },
    materials: {
      lockfiles: values.lockfiles?.descriptor ?? null,
      context: values.contexts?.descriptor ?? null,
      baseImage: values.baseImage ? safeImageDescriptor(values.baseImage) : (values.contexts?.baseImages ?? null),
      imageRefs: values.imageRefsInfo ? sourceInfo(values.imageRefsInfo) : null
    },
    outputs: {
      repoDigest: image?.repoDigest ?? null,
      sbom: values.sbom?.descriptor ?? null
    },
    attestations: {signature: 'pending', attestation: 'pending', registry: 'not-verified'},
    policy: '此文件为可审计 predicate，不代表 cosign 签名/验签已完成',
    errors: valid ? [] : [...new Set(targetErrors.map((item) => safeText(item)))]
  };
}

function normalizeImageRefs(value, gitSha, targetErrors) {
  const result = {};
  if (!value || typeof value !== 'object' || Array.isArray(value)) { targetErrors.push('image refs 必须是 JSON 对象'); return result; }
  if (value.schemaVersion !== 'v1.1') targetErrors.push('image refs schemaVersion 必须为 v1.1');
  const declaredCommit = scalar(value.requestedGitSha ?? value.gitSha ?? value.commit);
  if (declaredCommit && gitSha && declaredCommit.toLowerCase() !== gitSha.toLowerCase()) targetErrors.push('image refs commit 与 --git-sha 不一致');
  for (const name of ['backend', 'frontend']) {
    const raw = value.images?.[name] ?? value[name];
    const repoDigest = scalar(raw?.repoDigest ?? raw?.imageDigest ?? raw?.digestRef);
    const parsed = parseImageReference(repoDigest ?? '');
    if (!repoDigest || !parsed.valid || !parsed.immutable || !/^.+@sha256:[a-f0-9]{64}$/i.test(repoDigest)) {
      targetErrors.push(`${name} image refs 必须包含唯一 RepoDigest`);
      continue;
    }
    result[name] = {
      name,
      repoDigest,
      digest: parsed.digest,
      sha256: parsed.digest?.replace(/^sha256:/i, ''),
      registry: parsed.registry,
      repository: parsed.repository,
      tag: raw?.tag ?? null
    };
  }
  return result;
}

function validateLockfileManifest(info, targetErrors) {
  if (!info) return {valid: false, descriptor: null};
  let value;
  try { value = readJson(info.real, 'lockfile hash manifest'); }
  catch (error) { targetErrors.push(`lockfile hash manifest JSON 无法解析: ${safeText(error.message)}`); return {valid: false, descriptor: null}; }
  const files = Array.isArray(value) ? value : value?.files;
  if (!Array.isArray(files) || files.length === 0) { targetErrors.push('lockfile hash manifest files 不能为空'); return {valid: false, descriptor: null}; }
  const seen = new Set();
  const normalized = [];
  for (const item of files) {
    const relative = safeRelative(item?.path);
    const expected = scalar(item?.sha256);
    if (!relative || !/^[a-f0-9]{64}$/i.test(expected ?? '') || seen.has(relative.toLowerCase())) {
      targetErrors.push(`lockfile manifest 含无效/重复条目: ${safeText(item?.path)}`); continue;
    }
    seen.add(relative.toLowerCase());
    try {
      const file = inputFile(relative, 'lockfile');
      if (file.sha256.toLowerCase() !== expected.toLowerCase()) targetErrors.push(`lockfile hash 不一致: ${relative}`);
      normalized.push({path: relative, sha256: expected.toLowerCase(), bytes: fs.statSync(file.real).size});
    } catch (error) { targetErrors.push(`lockfile 不可读取: ${relative}`); }
  }
  const aggregate = scalar(value.aggregateSha256);
  if (!aggregate || !/^[a-f0-9]{64}$/i.test(aggregate)) targetErrors.push('lockfile aggregateSha256 缺失或格式无效');
  const canonical = `${JSON.stringify([...normalized].sort((a, b) => a.path.localeCompare(b.path, 'en')), null, 2)}\n`;
  if (aggregate && /^[a-f0-9]{64}$/i.test(aggregate) && sha256Text(canonical).toLowerCase() !== aggregate.toLowerCase()) targetErrors.push('lockfile aggregateSha256 不一致');
  return {valid: normalized.length === files.length && Boolean(aggregate) && targetErrors.length === 0, descriptor: {path: info.relative, sha256: info.sha256, aggregateSha256: aggregate ?? null, files: normalized}};
}

function validateContextManifest(info, targetErrors) {
  if (!info) return {valid: false, descriptor: null};
  let value;
  try { value = readJson(info.real, 'context hash'); }
  catch (error) { targetErrors.push(`context hash JSON 无法解析: ${safeText(error.message)}`); return {valid: false, descriptor: null}; }
  const hash = scalar(value.contextSha256 ?? value.contextHash ?? value.aggregateSha256);
  if (value.passed !== undefined && value.passed !== true) targetErrors.push(`context hash producer 未通过: ${info.relative}`);
  if (!/^[a-f0-9]{64}$/i.test(hash ?? '')) targetErrors.push(`context hash 格式无效: ${info.relative}`);
  if (Array.isArray(value.files)) {
    for (const item of value.files) {
      const p = safeRelative(item?.path);
      const expected = scalar(item?.sha256);
      if (!p || !/^[a-f0-9]{64}$/i.test(expected ?? '')) { targetErrors.push(`context hash 含无效文件条目: ${info.relative}`); continue; }
      // Context paths are relative to the context directory and may not be
      // safely resolved without a declared context.  Validate shape here;
      // hash-build-context is the producer responsible for file-level checks.
    }
  }
  const baseImages = normalizeBaseImages(value.baseImages ?? value.baseImageDigests ?? value.fromImages, targetErrors);
  return {valid: Boolean(hash && /^[a-f0-9]{64}$/i.test(hash) && (value.passed === undefined || value.passed === true)), descriptor: {path: info.relative, sha256: info.sha256, contextSha256: hash}, baseImages};
}

function normalizeBaseImages(value, targetErrors) {
  if (value === undefined || value === null) return null;
  const list = Array.isArray(value) ? value : [value];
  const result = [];
  for (const item of list) {
    const raw = typeof item === 'string' ? item : item?.repoDigest ?? item?.image ?? item?.digest;
    const parsed = parseImageReference(raw ?? '');
    if (!raw || !parsed.valid || !parsed.immutable || !parsed.digest) { targetErrors.push('context baseImages 必须使用 immutable digest'); continue; }
    result.push({repoDigest: String(raw), digest: parsed.digest, registry: parsed.registry, repository: parsed.repository});
  }
  return result.length > 0 ? result : null;
}

function validateSbom(info, targetErrors) {
  if (!info) return {valid: false, descriptor: null};
  let value;
  try { value = readJson(info.real, 'SBOM'); }
  catch (error) { targetErrors.push(`SBOM JSON 无法解析: ${safeText(error.message)}`); return {valid: false, descriptor: null}; }
  if (!value || typeof value !== 'object' || Array.isArray(value)) targetErrors.push(`SBOM 必须是 JSON 对象: ${info.relative}`);
  return {valid: Boolean(value && typeof value === 'object' && !Array.isArray(value)), descriptor: {path: info.relative, sha256: info.sha256, format: value?.bomFormat ?? value?.spdxVersion ?? 'unknown', version: value?.specVersion ?? null}};
}

function collectBaseImages(cli, targetErrors) {
  const result = {};
  const entries = [];
  const raw = cli['base-image'];
  for (const value of Array.isArray(raw) ? raw : (raw === undefined ? [] : [raw])) entries.push(String(value));
  for (const name of ['backend', 'frontend']) if (cli[`${name}-base-image`] !== undefined) entries.push(`${name}=${cli[`${name}-base-image`]}`);
  for (const entry of entries) {
    const separator = entry.indexOf('=');
    if (separator < 1) { targetErrors.push('--base-image 必须使用 name=RepoDigest'); continue; }
    const name = entry.slice(0, separator).trim();
    const value = entry.slice(separator + 1).trim();
    if (!['backend', 'frontend'].includes(name)) { targetErrors.push(`未知 base image 名称: ${safeText(name)}`); continue; }
    const parsed = parseImageReference(value);
    if (!parsed.valid || !parsed.immutable) targetErrors.push(`${name} base image 必须使用 immutable digest`);
    result[name] = {repoDigest: value, digest: parsed.digest, registry: parsed.registry, repository: parsed.repository};
  }
  return result;
}

function collectKeyedInputs(value) {
  const result = {};
  const list = Array.isArray(value) ? value : (value === undefined ? [] : [value]);
  for (const item of list) {
    const text = String(item ?? '');
    const separator = text.indexOf('=');
    if (separator > 0) result[text.slice(0, separator).trim()] = text.slice(separator + 1).trim();
  }
  return result;
}

function loadInfo(value, label, targetErrors) {
  const text = scalar(value);
  if (!text) { targetErrors.push(`${label} 必须显式提供`); return null; }
  try { return inputFile(text, label); }
  catch (error) { targetErrors.push(`${label}: ${safeText(error.message)}`); return null; }
}

function safeImageDescriptor(value) { return value ? {repoDigest: value.repoDigest, digest: value.digest, registry: value.registry, repository: value.repository} : null; }
function safeRelative(value) { const text = String(value ?? '').replaceAll('\\', '/').replace(/^\.\//, ''); return !text || text === '..' || text.startsWith('../') || path.isAbsolute(String(value ?? '')) ? null : text; }
function parseTimestamp(value) { const date = new Date(value); return Number.isNaN(date.getTime()) ? null : date; }
function scalar(value) { return value === undefined || value === true || value === false ? null : String(value).trim() || null; }
function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function safePath(value) { return String(value ?? '').replaceAll('\\', '/').replace(/[\r\n]/g, ''); }
function safeText(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 500); }
function redactCommand(value) { if (!value) return null; return String(value).replace(/(--?(?:token|key|secret|password)\s+)([^\s]+)/gi, '$1[redacted]').replace(/([A-Z0-9_]*(?:TOKEN|KEY|SECRET|PASSWORD)[A-Z0-9_]*=)([^\s]+)/gi, '$1[redacted]').slice(0, 500); }
function samePath(a, b) { return path.resolve(a).toLowerCase() === path.resolve(b).toLowerCase(); }
