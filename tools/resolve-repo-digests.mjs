#!/usr/bin/env node

/**
 * Resolve the canonical backend/frontend RepoDigest evidence produced by
 * `docker image inspect`.
 *
 * This is deliberately a parser, not a registry client.  It never invokes
 * Docker, contacts a registry, or prints inspect content.  A structurally
 * valid result proves that two immutable references were supplied; signature,
 * registry reachability and SBOM gates remain separate evidence.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  inputFile,
  outputFile,
  parseImageReference,
  sourceInfo,
  readInput
} from './lib/supply-chain.mjs';
import {currentCommit, generatedAt, parseArgs, placeholder, readJson, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const knownOptions = new Set([
  'backend-inspect', 'frontend-inspect', 'backend-tag', 'frontend-tag',
  'backend-repository', 'frontend-repository', 'registry', 'git-sha',
  'output', 'report', 'no-clobber', 'allow-pending'
]);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const registry = scalar(args.registry);
const gitSha = scalar(args['git-sha']);
if (!registry || placeholder(registry) || /[\s<>]/.test(registry)) errors.push('--registry 必须是非占位的 registry 前缀');
if (!gitSha || placeholder(gitSha) || !/^[a-f0-9]{40}(?:[a-f0-9]{24})?$/i.test(gitSha)) {
  errors.push('--git-sha 必须是 40 或 64 位 Git SHA');
}

const outputPath = args.output ?? 'artifacts/v1.1/release/image-refs.json';
let outputInfo = null;
let reportInfo = null;
try { outputInfo = outputFile(outputPath, 'image refs 输出'); }
catch (error) { errors.push(`输出路径无效: ${safePath(outputPath)} (${error.message})`); }
if (args.report !== undefined) {
  try { reportInfo = outputFile(args.report, 'resolver 报告'); }
  catch (error) { errors.push(`报告路径无效: ${safePath(args.report)} (${error.message})`); }
}

const inputs = {};
for (const name of ['backend', 'frontend']) {
  const option = `${name}-inspect`;
  const value = scalar(args[option]);
  if (!value) {
    errors.push(`--${option} 必须显式提供`);
    continue;
  }
  try {
    const info = inputFile(value, `${name} inspect`);
    inputs[name] = info;
    if (outputInfo && samePath(outputInfo.absolute, info.absolute)) errors.push(`输出不能覆盖 ${name} inspect 输入`);
    if (reportInfo && samePath(reportInfo.absolute, info.absolute)) errors.push(`报告不能覆盖 ${name} inspect 输入`);
  } catch (error) {
    errors.push(`${name} inspect: ${error.message}`);
  }
}
if (outputInfo && reportInfo && samePath(outputInfo.absolute, reportInfo.absolute)) errors.push('报告不能与输出使用同一路径');

const images = {};
for (const name of ['backend', 'frontend']) {
  if (!inputs[name]) continue;
  try {
    images[name] = resolveOne(name, inputs[name], {
      registry,
      expectedTag: scalar(args[`${name}-tag`]),
      expectedRepository: scalar(args[`${name}-repository`]),
      gitSha
    });
    errors.push(...images[name].errors);
    warnings.push(...images[name].warnings);
  } catch (error) {
    errors.push(`${name} inspect 解析失败: ${error.message}`);
  }
}

if (images.backend?.repoDigest && images.frontend?.repoDigest
    && images.backend.repoDigest.toLowerCase() === images.frontend.repoDigest.toLowerCase()) {
  errors.push('backend/frontend 不得复用相同 RepoDigest');
}

const status = errors.length > 0 ? 'blocked' : 'passed';
const payload = {
  schemaVersion: 'v1.1',
  producer: 'resolve-repo-digests',
  generatedAt: generatedAt(),
  gitCommit: currentCommit(),
  requestedGitSha: gitSha || null,
  registry: registry ? redactRegistry(registry) : null,
  status,
  passed: status === 'passed',
  producerCompleted: status === 'passed',
  exitCode: status === 'passed' ? 0 : 1,
  redacted: true,
  registryVerified: false,
  images: {
    backend: sanitizeImage(images.backend),
    frontend: sanitizeImage(images.frontend)
  },
  // Keep the canonical top-level aliases consumed by the release scripts
  // written before the `images` envelope was introduced.  Both views point
  // to the same immutable descriptors; neither contains raw inspect output.
  backend: sanitizeImage(images.backend),
  frontend: sanitizeImage(images.frontend),
  imageDigests: [images.backend?.repoDigest, images.frontend?.repoDigest].filter(Boolean),
  inputFiles: Object.values(inputs).map(sourceInfo),
  errors,
  warnings,
  policy: '仅解析 Docker inspect 的唯一 RepoDigest；不证明 registry 可达、签名/attestation、SBOM 或漏洞扫描。'
};

try {
  if (!outputInfo) throw new Error('输出路径无效');
  writeJson(outputInfo.absolute, payload, {noClobber: boolArg(args['no-clobber'])});
} catch (error) {
  payload.status = 'blocked'; payload.passed = false; payload.producerCompleted = false; payload.exitCode = 1;
  payload.errors.push(`输出失败: ${safeText(error.message)}`);
}

const report = {
  schemaVersion: 'v1.1',
  producer: 'resolve-repo-digests',
  generatedAt: payload.generatedAt,
  output: safePath(outputPath),
  status: payload.status,
  passed: payload.passed,
  producerCompleted: payload.producerCompleted,
  exitCode: payload.exitCode,
  redacted: true,
  imageCount: payload.imageDigests.length,
  inputFiles: payload.inputFiles,
  registryVerified: false,
  errors: payload.errors,
  warnings: payload.warnings
};
if (reportInfo) {
  try { writeJson(reportInfo.absolute, report, {noClobber: boolArg(args['no-clobber'])}); }
  catch (error) { report.errors.push(`报告写入失败: ${safeText(error.message)}`); report.passed = false; report.exitCode = 1; }
}

console.log(JSON.stringify(report, null, 2));
if (payload.exitCode !== 0 || report.exitCode !== 0) process.exitCode = 1;

function resolveOne(name, info, options) {
  const errorsForImage = [];
  const warningsForImage = [];
  const raw = readInput(info).trim();
  if (!raw) throw new Error('inspect 文件为空');
  let document;
  try { document = JSON.parse(raw); }
  catch (error) {
    // Some CI wrappers persist the formatted value without the surrounding
    // JSON array.  Accept one plain digest line only; arbitrary text remains
    // rejected so a truncated inspect cannot become a release input.
    if (/^\s*[^\s@]+@sha256:[a-f0-9]{64}\s*$/i.test(raw)) document = raw.trim();
    else throw new Error(`inspect JSON 无法解析: ${error.message}`);
  }

  const digests = extractDigests(document);
  if (digests.length !== 1) errorsForImage.push(`${name} inspect 必须恰好包含一个 RepoDigest（实际 ${digests.length}）`);
  const repoDigest = digests.length === 1 ? digests[0] : null;
  const parsed = repoDigest ? parseImageReference(repoDigest) : null;
  if (repoDigest && (!parsed.valid || !parsed.immutable || !/^.+@sha256:[a-f0-9]{64}$/i.test(repoDigest))) {
    errorsForImage.push(`${name} RepoDigest 格式无效`);
  }
  const normalizedRegistry = normalizeRegistry(options.registry);
  if (parsed?.valid && parsed.immutable && normalizedRegistry) {
    const rawRepository = String(repoDigest ?? '').split('@', 1)[0].replace(/^https?:\/\//i, '').replace(/\/+$/, '').toLowerCase();
    const actual = `${parsed.registry ?? ''}/${parsed.repository ?? ''}`.replace(/^docker\.io\//i, 'docker.io/').toLowerCase();
    const expected = normalizedRegistry.toLowerCase();
    if (!(rawRepository === expected || rawRepository.startsWith(`${expected}/`) || actual === expected || actual.startsWith(`${expected}/`))) errorsForImage.push(`${name} RepoDigest registry/repository 与 --registry 不匹配`);
  }
  const expectedRepository = options.expectedRepository;
  if (expectedRepository && parsed?.valid) {
    const actualRepository = `${parsed.registry ? `${parsed.registry}/` : ''}${parsed.repository ?? ''}`.toLowerCase();
    const expected = normalizeRepository(expectedRepository, options.registry).toLowerCase();
    if (actualRepository !== expected) errorsForImage.push(`${name} RepoDigest repository 与 --${name}-repository 不匹配`);
  }

  const inspectTags = extractTags(document);
  if (options.expectedTag && inspectTags.length > 0 && !inspectTags.some((tag) => tag === options.expectedTag)) {
    errorsForImage.push(`${name} inspect RepoTags 不包含声明的 --${name}-tag`);
  }
  if (inspectTags.length > 1) warningsForImage.push(`${name} inspect 含多个 RepoTags；仅以唯一 RepoDigest 作为发布输入`);
  return {
    name,
    repoDigest,
    digest: parsed?.digest ?? null,
    sha256: parsed?.digest?.replace(/^sha256:/i, '') ?? null,
    registry: parsed?.registry ?? null,
    repository: parsed?.repository ?? null,
    tag: options.expectedTag ?? parsed?.tag ?? null,
    gitSha: options.gitSha,
    inspect: sourceInfo(info),
    sourceFile: info.relative,
    sourceSha256: info.sha256,
    errors: errorsForImage,
    warnings: warningsForImage
  };
}

function extractDigests(document) {
  let values = [];
  if (typeof document === 'string') values = [document];
  else if (Array.isArray(document)) {
    // Docker's formatted output is an array of strings; a full
    // `docker image inspect` response is an array of objects.  Support both
    // without accepting an arbitrary nested value as a digest.
    for (const item of document) {
      if (typeof item === 'string') values.push(item);
      else if (item && typeof item === 'object') values.push(...extractDigests(item));
    }
  } else if (document && typeof document === 'object') {
    for (const key of ['RepoDigests', 'repoDigests', 'repoDigest', 'repo_digest']) {
      if (document[key] !== undefined) values = Array.isArray(document[key]) ? document[key] : [document[key]];
      if (values.length > 0) break;
    }
    // A full `docker image inspect` object has RepoDigests nested in an array.
    if (values.length === 0 && Array.isArray(document.images)) {
      for (const item of document.images) {
        if (Array.isArray(item?.RepoDigests)) values.push(...item.RepoDigests);
      }
    }
  }
  // Preserve duplicate entries: an inspect array with the same digest twice
  // is still ambiguous evidence and must not be silently collapsed.
  return values.map((value) => String(value ?? '').trim()).filter(Boolean);
}

function extractTags(document) {
  let values = [];
  const collect = (item) => {
    if (Array.isArray(item?.RepoTags)) values.push(...item.RepoTags.map((value) => String(value).trim()));
  };
  if (Array.isArray(document)) document.forEach((item) => {
    if (typeof item === 'object') collect(item);
  });
  else if (document && typeof document === 'object') { collect(document); if (Array.isArray(document.images)) document.images.forEach(collect); }
  return [...new Set(values.filter(Boolean))];
}

function sanitizeImage(image) {
  if (!image) return null;
  return {
    name: image.name,
    repoDigest: image.repoDigest,
    digest: image.digest,
    sha256: image.sha256,
    registry: image.registry,
    repository: image.repository,
    tag: image.tag,
    gitSha: image.gitSha,
    sourceFile: image.sourceFile,
    sourceSha256: image.sourceSha256,
    inspect: image.inspect,
    status: image.errors.length > 0 ? 'blocked' : 'passed'
  };
}

function normalizeRegistry(value) {
  const text = String(value ?? '').trim().replace(/\/+$/, '');
  return text.replace(/^https?:\/\//i, '');
}

function normalizeRepository(value, registryValue) {
  const text = String(value ?? '').trim().replace(/^https?:\/\//i, '').replace(/^\/+/, '').replace(/\/+$/, '');
  const registryPrefix = `${normalizeRegistry(registryValue)}/`;
  return text.toLowerCase().startsWith(registryPrefix.toLowerCase()) ? text : `${normalizeRegistry(registryValue)}/${text}`;
}

function redactRegistry(value) { return normalizeRegistry(value).replace(/[\r\n]/g, ''); }
function scalar(value) { return value === undefined || value === true || value === false ? null : String(value).trim() || null; }
function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function safePath(value) { return String(value ?? '').replaceAll('\\', '/').replace(/[\r\n]/g, ''); }
function safeText(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 500); }
function samePath(a, b) { return path.resolve(a).toLowerCase() === path.resolve(b).toLowerCase(); }
