#!/usr/bin/env node

/**
 * Aggregate immutable, redacted supply-chain evidence descriptors.  It hashes
 * the supplied files but never copies their contents and never treats missing
 * signatures/scans as a clean release.
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {inputFile, outputFile, parseImageReference, sourceInfo} from './lib/supply-chain.mjs';
import {currentCommit, generatedAt, parseArgs, readJson, sha256Text, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const pending = [];
const warnings = [];
const knownOptions = new Set([
  'image-refs', 'backend-sbom', 'frontend-sbom', 'image-secret-scan', 'scan-dir', 'dependency-inventory',
  'exceptions', 'vulnerability-validation', 'provenance-backend', 'provenance-frontend', 'output', 'hash-output',
  'report', 'allow-pending', 'no-clobber'
]);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const inputValues = {
  imageRefs: args['image-refs'], backendSbom: args['backend-sbom'], frontendSbom: args['frontend-sbom'],
  imageSecretScan: args['image-secret-scan'], dependencyInventory: args['dependency-inventory'], exceptions: args.exceptions,
  vulnerabilityValidation: args['vulnerability-validation'], provenanceBackend: args['provenance-backend'], provenanceFrontend: args['provenance-frontend']
};
const infos = {};
for (const [key, value] of Object.entries(inputValues)) if (value !== undefined) infos[key] = loadInfo(value, key, errors);
for (const key of ['imageRefs', 'backendSbom', 'frontendSbom', 'imageSecretScan', 'dependencyInventory', 'exceptions']) if (!infos[key]) errors.push(`缺少 --${dash(key)}`);
const scanDir = resolveDir(args['scan-dir'], errors);
let outputInfo = null; let hashInfo = null; let reportInfo = null;
try { outputInfo = outputFile(args.output ?? 'artifacts/v1.1/release/supply-chain-evidence.json', 'supply-chain aggregate'); } catch (error) { errors.push(`输出路径无效: ${safeText(error.message)}`); }
const hashOutputPath = args['hash-output'] ?? path.join(path.dirname(args.output ?? 'artifacts/v1.1/release/supply-chain-evidence.json'), 'supply-chain-input-hashes.json');
try { hashInfo = outputFile(hashOutputPath, 'supply-chain hash manifest'); } catch (error) { errors.push(`hash 输出路径无效: ${safeText(error.message)}`); }
if (args.report !== undefined) { try { reportInfo = outputFile(args.report, 'supply-chain report'); } catch (error) { errors.push(`报告路径无效: ${safeText(error.message)}`); } }
for (const info of Object.values(infos)) if (info && outputInfo && samePath(info.absolute, outputInfo.absolute)) errors.push('aggregate 输出不能覆盖输入');
for (const info of Object.values(infos)) if (info && hashInfo && samePath(info.absolute, hashInfo.absolute)) errors.push('hash 输出不能覆盖输入');
if (hashInfo && outputInfo && samePath(hashInfo.absolute, outputInfo.absolute)) errors.push('hash 输出不能与 aggregate 同路径');
if (reportInfo && outputInfo && samePath(reportInfo.absolute, outputInfo.absolute)) errors.push('报告不能与 aggregate 同路径');

const documents = {};
for (const [key, info] of Object.entries(infos)) documents[key] = readDocument(info, key, errors);
const images = normalizeImages(documents.imageRefs, errors);
const descriptors = {};
for (const [key, info] of Object.entries(infos)) descriptors[key] = info ? descriptor(info, documents[key]) : null;
// The documented command only passes --scan-dir for the remaining release
// evidence.  Discover those exact filenames as a convenience, while still
// recording missing files as pending rather than silently omitting them.
if (!descriptors.vulnerabilityValidation) descriptors.vulnerabilityValidation = discoverDescriptor(scanDir, 'vulnerability-exceptions-validation-final.json', pending);
if (!descriptors.provenanceBackend) descriptors.provenanceBackend = discoverDescriptor(scanDir, 'provenance-backend.json', pending);
if (!descriptors.provenanceFrontend) descriptors.provenanceFrontend = discoverDescriptor(scanDir, 'provenance-frontend.json', pending);
const imageEvidence = {};
for (const name of ['backend', 'frontend']) {
  imageEvidence[name] = {
    imageRefs: descriptors.imageRefs,
    sbom: descriptorFor(infos[`${name}Sbom`], documents[`${name}Sbom`]),
    provenance: descriptors[name === 'backend' ? 'provenanceBackend' : 'provenanceFrontend'],
    vulnerabilityScan: discoverDescriptor(scanDir, `trivy-${name}.json`, pending),
    signature: discoverDescriptor(scanDir, `${name === 'backend' ? '' : 'frontend-'}signature.txt`, pending),
    signatureVerify: discoverDescriptor(scanDir, `${name === 'backend' ? '' : 'frontend-'}signature-verify.txt`, pending),
    attestation: discoverDescriptor(scanDir, `${name === 'backend' ? '' : 'frontend-'}attestation.txt`, pending),
    attestationVerify: discoverDescriptor(scanDir, `${name === 'backend' ? '' : 'frontend-'}attestation-verify.txt`, pending),
    imageSecretScan: descriptors.imageSecretScan
  };
  if (!imageEvidence[name].sbom || imageEvidence[name].sbom.passed !== true) pending.push(`${name} SBOM evidence missing or unverified`);
  if (!imageEvidence[name].provenance || imageEvidence[name].provenance.passed !== true) pending.push(`${name} provenance evidence missing or unverified`);
}
if (!scanDir) pending.push('scan-dir not provided; signature/attestation/scan evidence cannot be discovered');
if (documents.exceptions?.scanComplete !== true) pending.push('vulnerability exceptions scanComplete 未为 true');
if (!descriptors.vulnerabilityValidation || descriptors.vulnerabilityValidation.passed !== true) pending.push('vulnerability validation evidence missing or unverified');
if (documents.imageSecretScan && documents.imageSecretScan.passed === false) pending.push('image secret scan 未通过/未执行');
if (Object.keys(images).length !== 2) errors.push('image refs 必须包含 backend/frontend 两个 immutable digest');

const uniqueInputs = Object.values(infos).filter(Boolean).map(sourceInfo);
const status = errors.length > 0 ? 'blocked' : pending.length > 0 ? 'environment-pending' : 'passed';
const passed = status === 'passed';
const payload = {
  schemaVersion: 'v1.1', producer: 'aggregate-supply-chain-evidence', generatedAt: generatedAt(), gitCommit: currentCommit(),
  status, passed, producerCompleted: errors.length === 0, exitCode: passed || (status === 'environment-pending' && boolArg(args['allow-pending'])) ? 0 : 1,
  redacted: true, images: imageEvidence,
  imageRefs: descriptors.imageRefs, dependencyInventory: descriptors.dependencyInventory,
  vulnerabilityExceptions: descriptors.exceptions, vulnerabilityValidation: descriptors.vulnerabilityValidation ?? null,
  imageSecretScan: descriptors.imageSecretScan, inputs: uniqueInputs,
  pending: unique(pending), errors: unique(errors), warnings: unique(warnings),
  policy: 'aggregate 只记录 path/hash/status；不替代 registry digest 回读、SBOM/签名/attestation 验签或现场漏洞扫描。'
};
try { if (!outputInfo) throw new Error('输出路径无效'); writeJson(outputInfo.absolute, payload, {noClobber: boolArg(args['no-clobber'])}); }
catch (error) { payload.status = 'blocked'; payload.passed = false; payload.producerCompleted = false; payload.exitCode = 1; payload.errors.push(`输出失败: ${safeText(error.message)}`); }

if (hashInfo && outputInfo) {
  try {
    const entries = uniqueInputs.filter((item) => !samePath(path.resolve(process.cwd(), item.path), outputInfo.absolute) && !samePath(path.resolve(process.cwd(), item.path), hashInfo.absolute)).sort((a, b) => a.path.localeCompare(b.path, 'en'));
    const canonical = `${JSON.stringify(entries, null, 2)}\n`;
    writeJson(hashInfo.absolute, {schemaVersion: 'v1.1', producer: 'aggregate-supply-chain-evidence', generatedAt: payload.generatedAt, gitCommit: payload.gitCommit, algorithm: 'SHA-256', files: entries, aggregateSha256: crypto.createHash('sha256').update(canonical).digest('hex'), excludes: ['supply-chain-evidence.json', path.basename(hashInfo.relative)]}, {noClobber: boolArg(args['no-clobber'])});
  } catch (error) { payload.errors.push(`hash manifest 写入失败: ${safeText(error.message)}`); payload.status = 'blocked'; payload.passed = false; payload.producerCompleted = false; payload.exitCode = 1; }
}
const report = {schemaVersion: 'v1.1', producer: 'aggregate-supply-chain-evidence', generatedAt: payload.generatedAt, output: outputInfo ? safePath(outputInfo.relative) : safePath(args.output), status: payload.status, passed: payload.passed, producerCompleted: payload.producerCompleted, exitCode: payload.exitCode, redacted: true, inputCount: uniqueInputs.length, pending: payload.pending, errors: payload.errors, warnings: payload.warnings};
if (reportInfo) { try { writeJson(reportInfo.absolute, report, {noClobber: boolArg(args['no-clobber'])}); } catch (error) { report.errors.push(`报告写入失败: ${safeText(error.message)}`); report.passed = false; report.status = 'blocked'; report.exitCode = 1; } }
console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0) process.exitCode = 1;

function normalizeImages(value, targetErrors) {
  const result = {};
  for (const name of ['backend', 'frontend']) {
    const raw = value?.images?.[name] ?? value?.[name]; const ref = scalar(raw?.repoDigest ?? raw?.imageDigest ?? raw?.digestRef); const parsed = parseImageReference(ref ?? '');
    if (!ref || !parsed.valid || !parsed.immutable || !parsed.digest) targetErrors.push(`${name} image refs 无效`); else result[name] = {repoDigest: ref, digest: parsed.digest.toLowerCase()};
  }
  return result;
}
function descriptorFor(info, doc) { return info ? {...sourceInfo(info), status: doc?.status ?? (doc ? 'captured' : 'captured'), passed: doc?.passed ?? true} : null; }
function descriptor(info, doc) { return descriptorFor(info, doc); }
function discoverDescriptor(dir, name, targetPending) { if (!dir) return null; const file = path.resolve(dir.absolute, name); if (!within(dir.absolute, file) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { targetPending.push(`missing evidence: ${name}`); return null; } let doc = null; try { if (name.endsWith('.json')) doc = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { targetPending.push(`invalid evidence JSON: ${name}`); } return {path: path.relative(path.resolve(process.cwd()), file).split(path.sep).join('/'), sha256: sha256File(file), status: doc?.status ?? 'captured', passed: doc?.passed ?? false, verified: doc?.verified ?? false}; }
function resolveDir(value, targetErrors) { const text = scalar(value); if (!text) return null; const absolute = path.isAbsolute(text) ? path.normalize(text) : path.resolve(process.cwd(), text); if (!fs.existsSync(absolute) || !fs.statSync(absolute).isDirectory()) { targetErrors.push('scan-dir 不存在或不是目录'); return null; } const real = fs.realpathSync(absolute); const root = path.resolve(process.cwd()); const rel = path.relative(root, real); if (rel.startsWith('..') || path.isAbsolute(rel)) { targetErrors.push('scan-dir 必须位于仓库内'); return null; } return {absolute: real, relative: rel.split(path.sep).join('/')}; }
function readDocument(info, label, targetErrors) { if (!info) return null; try { return readJson(info.real, label); } catch (error) { targetErrors.push(`${label} JSON 无法解析`); return null; } }
function loadInfo(value, label, targetErrors) { const text = scalar(value); if (!text) return null; try { return inputFile(text, label); } catch (error) { targetErrors.push(`${label}: ${safeText(error.message)}`); return null; } }
function dash(value) { return String(value).replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`); }
function scalar(value) { return value === undefined || value === true || value === false ? null : String(value).trim() || null; }
function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function safePath(value) { return String(value ?? '').replaceAll('\\', '/').replace(/[\r\n]/g, ''); }
function safeText(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 500); }
function samePath(a, b) { return path.resolve(a).toLowerCase() === path.resolve(b).toLowerCase(); }
function within(root, target) { const rel = path.relative(path.resolve(root), path.resolve(target)); return rel === '' || (rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel)); }
function unique(values) { return [...new Set(values.map((value) => String(value)))]; }
