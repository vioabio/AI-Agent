#!/usr/bin/env node

/**
 * Prepare a release-scoped dependency inventory from the N0 baseline and the
 * canonical image refs.  This is a merge/normalisation producer only; it does
 * not query registries, scan images, or infer a ready status.
 */
import fs from 'node:fs';
import path from 'node:path';
import {inputFile, outputFile, parseImageReference, sourceInfo} from './lib/supply-chain.mjs';
import {currentCommit, generatedAt, parseArgs, placeholder, readJson, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const knownOptions = new Set(['baseline', 'image-refs', 'dependency-ref', 'dependency-evidence', 'owner', 'output', 'report', 'no-clobber']);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const baselineInfo = loadInfo(args.baseline, 'baseline dependency inventory', errors);
const refsInfo = loadInfo(args['image-refs'], 'release image refs', errors);
let reportInfo = null;
let outputInfo = null;
try { outputInfo = outputFile(args.output ?? 'artifacts/v1.1/release/dependency-inventory.candidate.json', 'candidate inventory'); }
catch (error) { errors.push(`输出路径无效: ${safeText(error.message)}`); }
if (args.report !== undefined) {
  try { reportInfo = outputFile(args.report, 'inventory producer 报告'); }
  catch (error) { errors.push(`报告路径无效: ${safeText(error.message)}`); }
}
for (const info of [baselineInfo, refsInfo]) {
  if (info && outputInfo && samePath(info.absolute, outputInfo.absolute)) errors.push('输出不能覆盖输入');
  if (info && reportInfo && samePath(info.absolute, reportInfo.absolute)) errors.push('报告不能覆盖输入');
}
if (outputInfo && reportInfo && samePath(outputInfo.absolute, reportInfo.absolute)) errors.push('报告不能与输出同路径');

const baseline = readDocument(baselineInfo, 'baseline dependency inventory', errors);
const refs = readDocument(refsInfo, 'release image refs', errors);
validateBaseline(baseline, errors);
const releaseImages = normalizeReleaseImages(refs, errors);
const dependencies = cloneDependencies(baseline?.dependencies, errors);
applyDependencyRefs(dependencies, args['dependency-ref'], errors, warnings);
applyDependencyEvidence(dependencies, args['dependency-evidence'], errors, warnings);

if (dependencies.length === 0) errors.push('baseline inventory 必须包含非空 dependencies');
const structuralValid = errors.length === 0;
const payload = {
  schemaVersion: 'v1.1',
  producer: 'prepare-dependency-inventory',
  generatedAt: generatedAt(),
  gitCommit: currentCommit(),
  owner: scalar(args.owner) ?? baseline?.owner ?? null,
  status: structuralValid ? 'environment-pending' : 'blocked',
  passed: false,
  producerCompleted: structuralValid,
  exitCode: structuralValid ? 0 : 1,
  redacted: true,
  baseline: baselineInfo ? sourceInfo(baselineInfo) : null,
  imageRefs: refsInfo ? sourceInfo(refsInfo) : null,
  releaseImages,
  dependencies,
  evidencePolicy: {
    requiredForReady: ['repoDigest', 'sbom', 'signatureVerify', 'attestationVerify', 'scan'],
    registryVerified: false,
    scannerExecuted: false
  },
  errors,
  warnings,
  policy: '候选 inventory 只合并已声明的 digest/证据引用；缺 registry、SBOM、签名、扫描或探针时保持 environment-pending。'
};
try {
  if (!outputInfo) throw new Error('输出路径无效');
  writeJson(outputInfo.absolute, payload, {noClobber: boolArg(args['no-clobber'])});
} catch (error) {
  payload.status = 'blocked'; payload.passed = false; payload.producerCompleted = false; payload.exitCode = 1;
  payload.errors.push(`输出失败: ${safeText(error.message)}`);
}
const report = {
  schemaVersion: 'v1.1', producer: 'prepare-dependency-inventory', generatedAt: payload.generatedAt,
  output: outputInfo ? safePath(outputInfo.relative) : safePath(args.output), status: payload.status,
  passed: payload.passed, producerCompleted: payload.producerCompleted, exitCode: payload.exitCode,
  redacted: true, dependencyCount: dependencies.length,
  readyCount: dependencies.filter((item) => item.status === 'ready').length,
  pendingCount: dependencies.filter((item) => item.status !== 'ready').length,
  errors: payload.errors, warnings: payload.warnings
};
if (reportInfo) {
  try { writeJson(reportInfo.absolute, report, {noClobber: boolArg(args['no-clobber'])}); }
  catch (error) { report.errors.push(`报告写入失败: ${safeText(error.message)}`); report.exitCode = 1; }
}
console.log(JSON.stringify(report, null, 2));
if (payload.exitCode !== 0 || report.exitCode !== 0) process.exitCode = 1;

function validateBaseline(value, targetErrors) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) { targetErrors.push('baseline inventory 必须是 JSON 对象'); return; }
  if (value.schemaVersion !== 'v1.1') targetErrors.push('baseline inventory schemaVersion 必须为 v1.1');
  if (value.producer && value.producer !== 'export-dependency-inventory') targetErrors.push('baseline inventory producer 不匹配');
  if (!Array.isArray(value.dependencies) || value.dependencies.length === 0) targetErrors.push('baseline inventory dependencies 不能为空');
}

function normalizeReleaseImages(value, targetErrors) {
  const result = {};
  if (!value || typeof value !== 'object' || Array.isArray(value)) { targetErrors.push('release image refs 必须是 JSON 对象'); return result; }
  if (value.schemaVersion !== 'v1.1') targetErrors.push('release image refs schemaVersion 必须为 v1.1');
  for (const name of ['backend', 'frontend']) {
    const raw = value.images?.[name] ?? value[name];
    const repoDigest = scalar(raw?.repoDigest ?? raw?.imageDigest ?? raw?.digestRef);
    const parsed = parseImageReference(repoDigest ?? '');
    if (!repoDigest || !parsed.valid || !parsed.immutable || !parsed.digest) { targetErrors.push(`${name} release image refs 缺少 immutable RepoDigest`); continue; }
    result[name] = {repoDigest, digest: parsed.digest.toLowerCase(), sha256: parsed.digest.replace(/^sha256:/i, '').toLowerCase(), source: raw?.sourceFile ?? null, sourceSha256: raw?.sourceSha256 ?? null, status: 'environment-pending'};
  }
  return result;
}

function cloneDependencies(value, targetErrors) {
  if (!Array.isArray(value)) return [];
  const names = new Set();
  return value.map((raw) => {
    const name = scalar(raw?.name);
    if (!name || names.has(name)) targetErrors.push(`依赖名称缺失或重复: ${safeText(name)}`);
    names.add(name ?? '');
    const item = {
      name: name ?? 'invalid-dependency',
      version: raw?.version ?? null,
      imageOrEndpointDigest: raw?.imageOrEndpointDigest ?? null,
      source: scalar(raw?.source) ?? 'baseline',
      healthProbe: scalar(raw?.healthProbe) ?? 'not-configured',
      networkBoundary: scalar(raw?.networkBoundary) ?? 'not-configured',
      requiredStage: scalar(raw?.requiredStage) ?? 'N7',
      status: scalar(raw?.status) ?? 'environment-pending'
    };
    if (raw?.repoDigest) item.repoDigest = raw.repoDigest;
    if (raw?.evidence && typeof raw.evidence === 'object') item.evidence = sanitizeEvidence(raw.evidence);
    for (const key of ['scan', 'sbom', 'signature', 'attestation', 'provenance']) if (raw?.[key] !== undefined) item[key] = sanitizeEvidence(raw[key]);
    if (item.status === 'ready') item.status = 'environment-pending';
    return item;
  });
}

function applyDependencyRefs(dependencies, raw, targetErrors, targetWarnings) {
  const values = Array.isArray(raw) ? raw : (raw === undefined ? [] : [raw]);
  for (const value of values) {
    const text = String(value ?? ''); const split = text.indexOf('=');
    if (split < 1) { targetErrors.push('--dependency-ref 必须使用 name=RepoDigest'); continue; }
    const name = text.slice(0, split).trim(); const digest = text.slice(split + 1).trim();
    const parsed = parseImageReference(digest);
    if (!parsed.valid || !parsed.immutable || !parsed.digest) { targetErrors.push(`依赖 ${safeText(name)} digest 无效`); continue; }
    let item = dependencies.find((candidate) => candidate.name === name);
    if (!item) { item = {name, version: null, imageOrEndpointDigest: null, source: 'release dependency-ref', healthProbe: 'pending', networkBoundary: 'pending', requiredStage: 'N7', status: 'environment-pending'}; dependencies.push(item); targetWarnings.push(`dependency-ref ${name} 不在 baseline，已作为候选条目记录`); }
    item.repoDigest = digest; item.imageOrEndpointDigest = digest; item.version = parsed.tag ?? null; item.status = 'environment-pending';
  }
}

function applyDependencyEvidence(dependencies, raw, targetErrors, targetWarnings) {
  const values = Array.isArray(raw) ? raw : (raw === undefined ? [] : [raw]);
  for (const value of values) {
    const text = String(value ?? ''); const split = text.indexOf('=');
    if (split < 1) { targetErrors.push('--dependency-evidence 必须使用 name=JSON 文件'); continue; }
    const name = text.slice(0, split).trim(); const file = loadInfo(text.slice(split + 1).trim(), `dependency ${name} evidence`, targetErrors);
    if (!file) continue;
    let doc; try { doc = readJson(file.real, `dependency ${name} evidence`); } catch (error) { targetErrors.push(`dependency ${name} evidence JSON 无法解析`); continue; }
    const item = dependencies.find((candidate) => candidate.name === name);
    if (!item) { targetWarnings.push(`dependency evidence ${name} 不在 baseline，未写入`); continue; }
    item.evidence = {...(item.evidence ?? {}), source: sourceInfo(file), descriptor: sanitizeEvidence(doc)};
    for (const key of ['repoDigest', 'sbom', 'signature', 'signatureVerify', 'attestation', 'attestationVerify', 'scan', 'provenance']) if (doc[key] !== undefined) item[key] = sanitizeEvidence(doc[key]);
  }
}

function sanitizeEvidence(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return typeof value === 'string' ? safePath(value) : value ?? null;
  const result = {};
  for (const key of ['path', 'sha256', 'status', 'repoDigest', 'digest', 'verified', 'passed', 'scanner', 'version', 'source']) {
    if (value[key] !== undefined) {
      if (['path', 'source'].includes(key) && typeof value[key] === 'string') result[key] = safePath(value[key]);
      else if (key === 'sha256' && !/^(?:sha256:)?[a-f0-9]{64}$/i.test(String(value[key]))) continue;
      else result[key] = value[key];
    }
  }
  return result;
}

function readDocument(info, label, targetErrors) { if (!info) return null; try { return readJson(info.real, label); } catch (error) { targetErrors.push(`${label} JSON 无法解析`); return null; } }
function loadInfo(value, label, targetErrors) { const text = scalar(value); if (!text) { targetErrors.push(`${label} 必须显式提供`); return null; } try { return inputFile(text, label); } catch (error) { targetErrors.push(`${label}: ${safeText(error.message)}`); return null; } }
function scalar(value) { return value === undefined || value === true || value === false ? null : String(value).trim() || null; }
function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function safePath(value) { return String(value ?? '').replaceAll('\\', '/').replace(/[\r\n]/g, ''); }
function safeText(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 500); }
function samePath(a, b) { return path.resolve(a).toLowerCase() === path.resolve(b).toLowerCase(); }
