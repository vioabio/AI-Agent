#!/usr/bin/env node

/**
 * Finalise a release dependency inventory after scanner/signature evidence has
 * been captured.  The implementation only joins explicit files and hashes;
 * it never infers that a missing report is clean.
 */
import fs from 'node:fs';
import path from 'node:path';
import {inputFile, outputFile, parseImageReference, sourceInfo} from './lib/supply-chain.mjs';
import {currentCommit, generatedAt, parseArgs, readJson, sha256File, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const knownOptions = new Set(['candidate', 'scan-report', 'supply-chain-dir', 'vulnerability-validation', 'output', 'report', 'allow-pending', 'no-clobber']);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);
const candidateInfo = loadInfo(args.candidate, 'candidate inventory', errors);
const scanInfo = loadInfo(args['scan-report'], 'dependency scan report', errors);
const validationInfo = args['vulnerability-validation'] !== undefined ? loadInfo(args['vulnerability-validation'], 'vulnerability validation', errors) : null;
const evidenceDir = resolveDir(args['supply-chain-dir'], errors);
let outputInfo = null; let reportInfo = null;
try { outputInfo = outputFile(args.output ?? 'artifacts/v1.1/release/dependency-inventory.json', 'final inventory'); }
catch (error) { errors.push(`输出路径无效: ${safeText(error.message)}`); }
if (args.report !== undefined) { try { reportInfo = outputFile(args.report, 'final inventory report'); } catch (error) { errors.push(`报告路径无效: ${safeText(error.message)}`); } }
for (const info of [candidateInfo, scanInfo, validationInfo]) if (info && outputInfo && samePath(info.absolute, outputInfo.absolute)) errors.push('输出不能覆盖输入');
if (outputInfo && reportInfo && samePath(outputInfo.absolute, reportInfo.absolute)) errors.push('报告不能与输出同路径');

const candidate = readDocument(candidateInfo, 'candidate inventory', errors);
const scan = readDocument(scanInfo, 'dependency scan report', errors);
const vulnerabilityValidation = readDocument(validationInfo, 'vulnerability validation', errors);
if (!candidate || candidate.schemaVersion !== 'v1.1') errors.push('candidate inventory schemaVersion 必须为 v1.1');
if (!Array.isArray(candidate?.dependencies) || candidate.dependencies.length === 0) errors.push('candidate inventory dependencies 不能为空');
if (!scan || scan.schemaVersion !== 'v1.1') errors.push('dependency scan report schemaVersion 必须为 v1.1');
const scanByName = new Map((Array.isArray(scan?.items) ? scan.items : []).map((item) => [String(item?.name ?? ''), item]));
const dependencies = [];
for (const raw of candidate?.dependencies ?? []) dependencies.push(finalizeDependency(raw, scanByName.get(String(raw?.name ?? '')), evidenceDir, warnings));
const allReady = dependencies.length > 0 && dependencies.every((item) => item.status === 'ready');
if (!allReady && errors.length === 0) warnings.push('至少一个依赖缺少现场 scan/SBOM/签名或探针，final inventory 保持 pending');
if (vulnerabilityValidation && vulnerabilityValidation.passed !== true) warnings.push('vulnerability validation 尚未 passed，不得将 inventory 标为 ready');
const status = errors.length > 0 ? 'blocked' : allReady && (!vulnerabilityValidation || vulnerabilityValidation.passed === true) ? 'ready' : 'environment-pending';
const payload = {
  schemaVersion: 'v1.1', producer: 'finalize-dependency-inventory', generatedAt: generatedAt(), gitCommit: currentCommit(),
  owner: candidate?.owner ?? null, status, passed: status === 'ready', producerCompleted: errors.length === 0, exitCode: status === 'ready' || (status === 'environment-pending' && boolArg(args['allow-pending'])) ? 0 : 1,
  redacted: true, sourceCandidate: candidateInfo ? sourceInfo(candidateInfo) : null, scanReport: scanInfo ? sourceInfo(scanInfo) : null,
  vulnerabilityValidation: validationInfo ? sourceInfo(validationInfo) : null, evidenceDir: evidenceDir ? safePath(evidenceDir.relative) : null,
  dependencies, errors, warnings,
  policy: '只有每项依赖的精确 digest、可回读扫描/SBOM/签名和探针均通过，且漏洞校验通过，才可标记 ready。'
};
try { if (!outputInfo) throw new Error('输出路径无效'); writeJson(outputInfo.absolute, payload, {noClobber: boolArg(args['no-clobber'])}); }
catch (error) { payload.status = 'blocked'; payload.passed = false; payload.producerCompleted = false; payload.exitCode = 1; payload.errors.push(`输出失败: ${safeText(error.message)}`); }
const report = {
  schemaVersion: 'v1.1', producer: 'finalize-dependency-inventory', generatedAt: payload.generatedAt, output: outputInfo ? safePath(outputInfo.relative) : safePath(args.output),
  status: payload.status, passed: payload.passed, producerCompleted: payload.producerCompleted, exitCode: payload.exitCode, redacted: true,
  dependencyCount: dependencies.length, readyCount: dependencies.filter((item) => item.status === 'ready').length,
  pendingCount: dependencies.filter((item) => item.status !== 'ready').length, errors: payload.errors, warnings: payload.warnings
};
if (reportInfo) { try { writeJson(reportInfo.absolute, report, {noClobber: boolArg(args['no-clobber'])}); } catch (error) { report.errors.push(`报告写入失败: ${safeText(error.message)}`); report.exitCode = 1; } }
console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0) process.exitCode = 1;

function finalizeDependency(raw, scanItem, evidence, targetWarnings) {
  // Project only the reviewed inventory contract; never copy arbitrary input
  // keys (which could contain credentials) into release evidence.
  const item = {
    name: scalar(raw?.name) ?? 'invalid-dependency',
    version: scalar(raw?.version),
    imageOrEndpointDigest: scalar(raw?.imageOrEndpointDigest),
    source: scalar(raw?.source) ?? 'candidate',
    healthProbe: scalar(raw?.healthProbe) ?? 'not-configured',
    networkBoundary: scalar(raw?.networkBoundary) ?? 'not-configured',
    requiredStage: scalar(raw?.requiredStage) ?? 'N7',
    status: scalar(raw?.status) ?? 'environment-pending',
    ...(scalar(raw?.repoDigest) ? {repoDigest: scalar(raw.repoDigest)} : {}),
    ...(raw?.evidence && typeof raw.evidence === 'object' && !Array.isArray(raw.evidence) ? {evidence: sanitizeEvidenceMap(raw.evidence)} : {})
  };
  const name = String(item.name ?? 'invalid-dependency');
  const digest = scalar(item.repoDigest ?? item.imageOrEndpointDigest);
  const parsed = parseImageReference(digest ?? '');
  const imageLike = Boolean(item.repoDigest || (digest && digest.includes('@')) || /(?:image|postgres|redis|prometheus|grafana|alertmanager|mcp|oidc)/i.test(name));
  let ready = String(item.status).toLowerCase() === 'ready';
  if (imageLike && (!digest || !parsed.valid || !parsed.immutable || !parsed.digest)) ready = false;
  const evidenceMap = {...(item.evidence ?? {})};
  if (scanItem?.path) evidenceMap.scan = descriptorFromPath(scanItem.path, scanItem, evidence, `${name} scan`);
  else if (scanItem) evidenceMap.scan = {status: scanItem.status ?? 'environment-pending', passed: scanItem.passed === true, scannerExecuted: scanItem.scannerExecuted === true};
  else { ready = false; targetWarnings.push(`${name}: 缺少依赖扫描记录`); }
  const discovered = discoverEvidence(name, evidence);
  for (const [key, value] of Object.entries(discovered)) if (evidenceMap[key] === undefined) evidenceMap[key] = value;
  const required = imageLike ? ['scan', 'sbom', 'signatureVerify'] : ['healthProbe', 'probe'];
  if (!imageLike && !evidenceMap.healthProbe && !evidenceMap.probe) {
    ready = false;
    targetWarnings.push(`${name}: 缺少 health probe evidence`);
  }
  for (const key of required) {
    const descriptor = evidenceMap[key];
    if (!descriptor || descriptor.valid === false || descriptor.passed === false || descriptor.status === 'environment-pending') ready = false;
    if (!descriptor) targetWarnings.push(`${name}: 缺少 ${key} evidence`);
  }
  if (imageLike && evidenceMap.scan?.scannerExecuted !== true && evidenceMap.scan?.executed !== true) ready = false;
  item.status = ready ? 'ready' : 'environment-pending';
  item.repoDigest = item.repoDigest ?? (parsed.immutable ? digest : undefined);
  item.evidence = sanitizeEvidenceMap(evidenceMap);
  for (const key of ['scan', 'sbom', 'signature', 'signatureVerify', 'attestation', 'attestationVerify', 'provenance']) if (item.evidence[key] !== undefined) item[key] = item.evidence[key];
  return item;
}

function discoverEvidence(name, evidence) {
  if (!evidence) return {};
  const base = `dependency-${safeFileName(name)}`;
  const candidates = {
    scan: [`${base}-scan.json`], sbom: [`${base}-sbom.json`, `${base}-sbom.txt`], signatureVerify: [`${base}-signature-verify.json`, `${base}-signature-verify.txt`], attestationVerify: [`${base}-attestation-verify.json`, `${base}-attestation-verify.txt`], provenance: [`${base}-provenance.json`], healthProbe: [`${base}-health.json`, `${base}-probe.json`]
  };
  const result = {};
  for (const [key, names] of Object.entries(candidates)) for (const relative of names) {
    const absolute = path.resolve(evidence.absolute, relative);
    if (!within(evidence.absolute, absolute) || !fs.existsSync(absolute) || !fs.statSync(absolute).isFile()) continue;
    result[key] = {path: relative, sha256: sha256File(absolute), status: 'captured', passed: true, valid: true}; break;
  }
  return result;
}

function descriptorFromPath(value, source, evidence, label) {
  const raw = String(value).replaceAll('\\', '/');
  if (!evidence || path.isAbsolute(raw) || raw.split('/').includes('..')) return {path: raw, status: source?.status ?? 'environment-pending', passed: false, valid: false};
  const repoRoot = path.resolve(process.cwd());
  const evidenceRelative = path.relative(repoRoot, evidence.absolute).split(path.sep).join('/');
  const absolute = raw === evidenceRelative || raw.startsWith(`${evidenceRelative}/`)
    ? path.resolve(repoRoot, raw)
    : path.resolve(evidence.absolute, raw);
  if (!within(evidence.absolute, absolute) || !fs.existsSync(absolute) || !fs.statSync(absolute).isFile()) return {path: raw, status: 'environment-pending', passed: false, valid: false};
  const relative = path.relative(evidence.absolute, absolute).split(path.sep).join('/');
  return {path: relative, sha256: sha256File(absolute), status: source?.status ?? 'captured', passed: source?.passed === true, scannerExecuted: source?.scannerExecuted === true, valid: source?.passed === true};
}

function sanitizeEvidenceMap(value) { const result = {}; for (const [key, descriptor] of Object.entries(value ?? {})) result[key] = sanitizeDescriptor(descriptor); return result; }
function sanitizeDescriptor(value) { if (!value || typeof value !== 'object') return value ?? null; const result = {}; for (const key of ['path', 'sha256', 'status', 'passed', 'valid', 'scannerExecuted', 'verified', 'repoDigest', 'digest']) if (value[key] !== undefined) result[key] = typeof value[key] === 'string' && key === 'path' ? safePath(value[key]) : value[key]; return result; }
function resolveDir(value, targetErrors) { const text = scalar(value); if (!text) return null; const absolute = path.isAbsolute(text) ? path.normalize(text) : path.resolve(process.cwd(), text); if (!fs.existsSync(absolute) || !fs.statSync(absolute).isDirectory()) { targetErrors.push('supply-chain-dir 不存在或不是目录'); return null; } const real = fs.realpathSync(absolute); const root = path.resolve(process.cwd()); const rel = path.relative(root, real); if (rel.startsWith('..') || path.isAbsolute(rel)) { targetErrors.push('supply-chain-dir 必须位于仓库内'); return null; } return {absolute: real, relative: rel.split(path.sep).join('/')}; }
function readDocument(info, label, targetErrors) { if (!info) return null; try { return readJson(info.real, label); } catch (error) { targetErrors.push(`${label} JSON 无法解析`); return null; } }
function loadInfo(value, label, targetErrors) { const text = scalar(value); if (!text) { targetErrors.push(`${label} 必须显式提供`); return null; } try { return inputFile(text, label); } catch (error) { targetErrors.push(`${label}: ${safeText(error.message)}`); return null; } }
function safeFileName(value) { return String(value ?? '').replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'dependency'; }
function scalar(value) { return value === undefined || value === true || value === false ? null : String(value).trim() || null; }
function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function safePath(value) { return String(value ?? '').replaceAll('\\', '/').replace(/[\r\n]/g, ''); }
function safeText(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 500); }
function samePath(a, b) { return path.resolve(a).toLowerCase() === path.resolve(b).toLowerCase(); }
function within(root, target) { const rel = path.relative(path.resolve(root), path.resolve(target)); return rel === '' || (rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel)); }
