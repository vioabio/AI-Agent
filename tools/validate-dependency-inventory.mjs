#!/usr/bin/env node

/**
 * Validate the machine-readable dependency inventory contract.  Structural
 * validation is fully offline; final validation additionally requires every
 * image/endpoint evidence descriptor to be present and hashable.  No scanner,
 * registry, Docker or secret store is contacted.
 */
import fs from 'node:fs';
import path from 'node:path';
import {inputFile, outputFile, parseImageReference, sourceInfo} from './lib/supply-chain.mjs';
import {currentCommit, generatedAt, parseArgs, placeholder, readJson, sha256File, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const pending = [];
const warnings = [];
const knownOptions = new Set(['phase', 'input', 'schema', 'evidence-dir', 'report', 'allow-pending', 'no-clobber']);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);
const phase = scalar(args.phase) ?? 'final';
if (!['structural', 'final'].includes(phase)) errors.push('--phase 必须是 structural 或 final');
const inputInfo = loadInfo(args.input, 'dependency inventory', errors);
const schemaInfo = loadInfo(args.schema, 'dependency inventory schema', errors);
const evidenceDir = resolveEvidenceDir(args['evidence-dir'], errors);
let reportInfo = null;
if (args.report !== undefined) {
  try { reportInfo = outputFile(args.report, 'dependency inventory validation report'); }
  catch (error) { errors.push(`报告路径无效: ${safeText(error.message)}`); }
}
if (reportInfo && inputInfo && samePath(reportInfo.absolute, inputInfo.absolute)) errors.push('报告不能覆盖 inventory 输入');

const inventory = readDocument(inputInfo, 'dependency inventory', errors);
const schema = readDocument(schemaInfo, 'dependency inventory schema', errors);
validateSchema(schema, errors);
const dependencies = Array.isArray(inventory?.dependencies) ? inventory.dependencies : [];
if (!inventory || typeof inventory !== 'object' || Array.isArray(inventory)) errors.push('dependency inventory 必须是 JSON 对象');
else {
  if (inventory.schemaVersion !== 'v1.1') errors.push('dependency inventory schemaVersion 必须为 v1.1');
  if (!['export-dependency-inventory', 'prepare-dependency-inventory', 'finalize-dependency-inventory'].includes(String(inventory.producer ?? ''))) errors.push('dependency inventory producer 不匹配');
  if (inventory.redacted !== undefined && inventory.redacted !== true) errors.push('dependency inventory redacted 必须为 true');
}
if (dependencies.length === 0) errors.push('dependency inventory dependencies 不能为空');
const names = new Set();
for (const item of dependencies) validateDependency(item, names, phase, evidenceDir, errors, pending, warnings);

const hardBlocked = errors.length > 0;
const status = hardBlocked ? 'blocked' : pending.length > 0 ? 'environment-pending' : 'passed';
const passed = status === 'passed';
const report = {
  schemaVersion: 'v1.1',
  producer: 'validate-dependency-inventory',
  generatedAt: generatedAt(),
  gitCommit: currentCommit(),
  phase,
  status,
  passed,
  exitCode: passed || (status === 'environment-pending' && boolArg(args['allow-pending'])) ? 0 : 1,
  redacted: true,
  input: inputInfo ? sourceInfo(inputInfo) : null,
  schema: schemaInfo ? sourceInfo(schemaInfo) : null,
  evidenceDir: evidenceDir ? safePath(evidenceDir.relative) : null,
  dependencyCount: dependencies.length,
  readyCount: dependencies.filter((item) => String(item?.status).toLowerCase() === 'ready').length,
  pendingCount: pending.length,
  checks: {
    schema: errors.every((item) => !String(item).toLowerCase().includes('schema')),
    uniqueNames: names.size === dependencies.length,
    evidence: pending.length === 0 && errors.length === 0
  },
  errors: unique(errors),
  pending: unique(pending),
  warnings: unique(warnings),
  policy: '依赖必须有明确版本、digest、来源、探针和网络边界；final 阶段还需可回读 SBOM/签名/扫描证据，缺现场输入保持 pending。'
};
if (reportInfo) {
  try { writeJson(reportInfo.absolute, report, {noClobber: boolArg(args['no-clobber'])}); }
  catch (error) { report.errors.push(`报告写入失败: ${safeText(error.message)}`); report.passed = false; report.status = 'blocked'; report.exitCode = 1; }
}
console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0) process.exitCode = 1;

function validateDependency(item, names, phaseValue, evidence, targetErrors, targetPending, targetWarnings) {
  if (!item || typeof item !== 'object' || Array.isArray(item)) { targetErrors.push('依赖条目必须是对象'); return; }
  const name = scalar(item.name);
  if (!name || !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(name)) targetErrors.push('依赖名称格式无效');
  if (name && names.has(name)) targetErrors.push(`依赖名称重复: ${name}`);
  if (name) names.add(name);
  for (const field of ['version', 'imageOrEndpointDigest', 'source', 'healthProbe', 'networkBoundary', 'requiredStage', 'status']) if (!(field in item)) targetErrors.push(`依赖 ${name ?? '[invalid]'} 缺少字段 ${field}`);
  const status = String(item.status ?? '').toLowerCase();
  if (!['ready', 'candidate', 'environment-pending', 'not-configured', 'blocked'].includes(status)) targetErrors.push(`依赖 ${name ?? '[invalid]'} status 无效`);
  if (['environment-pending', 'not-configured', 'candidate'].includes(status)) targetPending.push(`${name ?? '[invalid]'} status=${status}`);
  if (status === 'blocked') targetErrors.push(`依赖 ${name ?? '[invalid]'} 已标记 blocked`);
  for (const field of ['source', 'healthProbe', 'networkBoundary', 'requiredStage']) if (!scalar(item[field])) targetErrors.push(`依赖 ${name ?? '[invalid]'} ${field} 不能为空`);

  const digestValue = scalar(item.repoDigest ?? item.imageOrEndpointDigest);
  const version = scalar(item.version);
  const imageLike = Boolean(item.repoDigest || (typeof item.imageOrEndpointDigest === 'string' && (item.imageOrEndpointDigest.includes('@') || /(?:image|postgres|redis|prometheus|grafana|alertmanager|mcp|oidc)/i.test(name ?? ''))));
  if (digestValue) {
    if (/^https?:\/\//i.test(digestValue)) {
      try {
        const endpoint = new URL(digestValue);
        if (endpoint.username || endpoint.password || endpoint.search || endpoint.hash) targetErrors.push(`依赖 ${name ?? '[invalid]'} endpoint 不得包含凭据/query/hash`);
      } catch { targetErrors.push(`依赖 ${name ?? '[invalid]'} endpoint URL 无效`); }
    } else {
      const parsed = parseImageReference(digestValue);
      if (!parsed.valid || !parsed.immutable || !parsed.digest) targetErrors.push(`依赖 ${name ?? '[invalid]'} digest 必须是 repo@sha256:<64hex>`);
    }
  } else if (imageLike) {
    targetPending.push(`${name ?? '[invalid]'} 缺少 immutable image digest`);
  }
  if (version && /(?:^|[/:])latest(?:$|[.@])/i.test(version)) targetErrors.push(`依赖 ${name ?? '[invalid]'} 禁止 latest 浮动 tag`);
  if (version && /\$\{|<[^>]+>|your[-_]|replace[-_]/i.test(version)) targetErrors.push(`依赖 ${name ?? '[invalid]'} version 含占位符`);

  const evidenceFields = ['scan', 'sbom', 'signature', 'signatureVerify', 'attestation', 'attestationVerify', 'provenance'];
  for (const field of evidenceFields) {
    if (item[field] === undefined) continue;
    const descriptor = validateEvidenceDescriptor(item[field], evidence, `${name ?? '[invalid]'}.${field}`, targetErrors, targetWarnings);
    if (phaseValue === 'final' && !descriptor.valid) targetPending.push(`${name ?? '[invalid]'} ${field} evidence unavailable`);
  }
  if (item.evidence && typeof item.evidence === 'object' && !Array.isArray(item.evidence)) {
    for (const [field, value] of Object.entries(item.evidence)) {
      if (!['scan', 'sbom', 'signature', 'signatureVerify', 'attestation', 'attestationVerify', 'provenance', 'healthProbe', 'probe'].includes(field)) continue;
      const descriptor = validateEvidenceDescriptor(value, evidence, `${name ?? '[invalid]'}.evidence.${field}`, targetErrors, targetWarnings);
      if (phaseValue === 'final' && !descriptor.valid) targetPending.push(`${name ?? '[invalid]'} ${field} evidence unavailable`);
    }
  }
  if (item.healthProbeEvidence !== undefined) {
    const descriptor = validateEvidenceDescriptor(item.healthProbeEvidence, evidence, `${name ?? '[invalid]'}.healthProbeEvidence`, targetErrors, targetWarnings);
    if (phaseValue === 'final' && !descriptor.valid) targetPending.push(`${name ?? '[invalid]'} health probe evidence unavailable`);
  }
  if (phaseValue === 'final') {
    if (status !== 'ready') targetPending.push(`${name ?? '[invalid]'} final status 不是 ready`);
    if (imageLike) {
      for (const field of ['scan', 'sbom', 'signatureVerify']) if (!item[field] && !item.evidence?.[field]) targetPending.push(`${name ?? '[invalid]'} 缺少 ${field} evidence`);
    } else if (!item.healthProbeEvidence && !item.evidence?.healthProbe && !item.evidence?.probe) {
      targetPending.push(`${name ?? '[invalid]'} 缺少 health probe evidence`);
    }
    if (imageLike && !item.repoDigest) targetPending.push(`${name ?? '[invalid]'} final 缺少 repoDigest`);
    if (!evidence) targetPending.push(`${name ?? '[invalid]'} 未提供 --evidence-dir`);
  }
}

function validateEvidenceDescriptor(value, evidence, label, targetErrors, targetWarnings) {
  if (!value) return {valid: false};
  const descriptor = typeof value === 'string' ? {path: value} : (typeof value === 'object' && !Array.isArray(value) ? value : null);
  if (!descriptor) { targetErrors.push(`${label} evidence descriptor 无效`); return {valid: false}; }
  const rawPath = scalar(descriptor.path ?? descriptor.file);
  if (!rawPath || placeholder(rawPath) || path.isAbsolute(rawPath) || rawPath.replaceAll('\\', '/').split('/').includes('..')) {
    targetErrors.push(`${label} evidence path 越界/占位`); return {valid: false};
  }
  if (!evidence) return {valid: false};
  const absolute = path.resolve(evidence.absolute, rawPath);
  if (!within(evidence.absolute, absolute)) { targetErrors.push(`${label} evidence path 越出 evidence-dir`); return {valid: false}; }
  if (!fs.existsSync(absolute) || !fs.statSync(absolute).isFile()) { targetWarnings.push(`${label} evidence 文件不存在`); return {valid: false}; }
  const expected = scalar(descriptor.sha256);
  if (expected && !/^(?:sha256:)?[a-f0-9]{64}$/i.test(expected)) { targetErrors.push(`${label} evidence sha256 格式无效`); return {valid: false}; }
  if (expected && sha256File(absolute).toLowerCase() !== expected.replace(/^sha256:/i, '').toLowerCase()) { targetErrors.push(`${label} evidence sha256 不匹配`); return {valid: false}; }
  if (descriptor.passed === false || ['blocked', 'failed', 'error'].includes(String(descriptor.status ?? '').toLowerCase())) return {valid: false};
  return {valid: true};
}

function validateSchema(value, targetErrors) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) { targetErrors.push('schema 必须是 JSON 对象'); return; }
  if (value.$schema && !String(value.$schema).includes('json-schema.org')) targetErrors.push('schema $schema 不匹配');
}
function resolveEvidenceDir(value, targetErrors) {
  const text = scalar(value); if (!text) return null;
  const absolute = path.isAbsolute(text) ? path.normalize(text) : path.resolve(process.cwd(), text);
  if (!fs.existsSync(absolute) || !fs.statSync(absolute).isDirectory()) { targetErrors.push('evidence-dir 不存在或不是目录'); return null; }
  const real = fs.realpathSync(absolute); const root = path.resolve(process.cwd()); const relative = path.relative(root, real);
  if (relative.startsWith('..') || path.isAbsolute(relative)) { targetErrors.push('evidence-dir 必须位于仓库内'); return null; }
  return {absolute: real, relative: relative.split(path.sep).join('/')};
}
function readDocument(info, label, targetErrors) { if (!info) return null; try { return readJson(info.real, label); } catch (error) { targetErrors.push(`${label} JSON 无法解析`); return null; } }
function loadInfo(value, label, targetErrors) { const text = scalar(value); if (!text) { targetErrors.push(`${label} 必须显式提供`); return null; } try { return inputFile(text, label); } catch (error) { targetErrors.push(`${label}: ${safeText(error.message)}`); return null; } }
function scalar(value) { return value === undefined || value === null || value === true || value === false ? null : String(value).trim() || null; }
function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function safePath(value) { return String(value ?? '').replaceAll('\\', '/').replace(/[\r\n]/g, ''); }
function safeText(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 500); }
function samePath(a, b) { return path.resolve(a).toLowerCase() === path.resolve(b).toLowerCase(); }
function within(root, target) { const relative = path.relative(path.resolve(root), path.resolve(target)); return relative === '' || (relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative)); }
function unique(values) { return [...new Set(values.map((value) => String(value)))]; }
