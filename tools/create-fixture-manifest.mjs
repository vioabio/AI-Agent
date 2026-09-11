#!/usr/bin/env node

/**
 * Build a deterministic, source-auditable manifest for the isolated v1.1
 * Pokémon fixture.  This producer never promotes fixture data to production;
 * it only records redacted metadata and hashes of explicitly safe files.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  parseJsonBlock,
  redactEndpoint,
  repoOutputInfo,
  repoPathInfo,
  sha256File,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const noClobber = args['no-clobber'] === true;
const allowedOptions = new Set(['input-dir', 'support-matrix', 'output', 'source-audit', 'report', 'allow-pending', 'no-clobber']);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!allowedOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const inputArg = args['input-dir'] ?? 'data/v1.1-fixture';
const supportArg = args['support-matrix'] ?? 'artifacts/v1.1/baseline/support-matrix.md';
const outputArg = args.output ?? 'artifacts/v1.1/baseline/fixture-manifest.json';
const auditArg = args['source-audit'] ?? 'artifacts/v1.1/baseline/fixture-source-audit.json';
const reportArg = args.report;
const inputInfo = directory(inputArg, 'fixture input-dir');
const fixtureRootRelative = inputInfo?.relative ?? 'data/v1.1-fixture';
const supportInfo = inputFile(supportArg, 'support matrix');
const outputInfo = destination(outputArg, 'fixture manifest 输出');
const auditInfo = destination(auditArg, 'fixture source audit 输出');
const reportInfo = reportArg === undefined ? null : destination(reportArg, 'fixture producer 报告');
let destinationBlocked = false;
for (const [label, info] of [['manifest', outputInfo], ['audit', auditInfo], ['report', reportInfo]]) {
  if (!info && (label !== 'report' || reportArg !== undefined)) destinationBlocked = true;
  if (info && noClobber && fs.existsSync(info.absolute)) { errors.push(`${label} 已存在（--no-clobber）: ${info.relative}`); destinationBlocked = true; }
}
for (const [leftLabel, left, rightLabel, right] of [
  ['manifest', outputInfo, 'audit', auditInfo],
  ['manifest', outputInfo, 'report', reportInfo],
  ['audit', auditInfo, 'report', reportInfo]
]) {
  if (left && right && samePath(left.absolute, right.absolute)) { errors.push(`${leftLabel} 与 ${rightLabel} 不能是同一路径`); destinationBlocked = true; }
}
if (inputInfo && outputInfo && isWithin(inputInfo.real, outputInfo.absolute)) {
  errors.push('manifest 输出不能位于 fixture input-dir 内（避免自包含 hash）');
  destinationBlocked = true;
}
if (inputInfo && auditInfo && isWithin(inputInfo.real, auditInfo.absolute)) {
  errors.push('source audit 输出不能位于 fixture input-dir 内');
  destinationBlocked = true;
}

let fixture = {};
if (inputInfo) {
  const fixtureFile = path.join(inputInfo.real, 'fixture.json');
  try {
    const fixtureFileInfo = repoPathInfo(fixtureFile, {label: 'fixture.json', mustExist: true, kind: 'file'});
    fixture = JSON.parse(fs.readFileSync(fixtureFileInfo.real, 'utf8'));
  } catch (error) {
    errors.push(`fixture.json 解析失败: ${safeError(error)}`);
  }
}
let support = {};
if (supportInfo) {
  try { support = parseJsonBlock(fs.readFileSync(supportInfo.real, 'utf8')); }
  catch (error) { errors.push(`support matrix 解析失败: ${safeError(error)}`); }
}

if (fixture.schemaVersion !== 'v1.1') errors.push('fixture schemaVersion 必须为 v1.1');
if (!scalar(fixture.fixtureId) || !fixture.canonicalVersions || typeof fixture.canonicalVersions !== 'object') errors.push('fixture 缺少 fixtureId/canonicalVersions');
if (support.game_release_id && fixture.canonicalVersions?.gameReleaseId !== support.game_release_id) errors.push('game_release_id 与 support matrix 不一致');
if (support.data_release_id && fixture.canonicalVersions?.dataReleaseId !== support.data_release_id) errors.push('data_release_id 与 support matrix 不一致');
for (const [key, label] of [['gameReleaseId', 'gameReleaseId'], ['dlcId', 'dlcId'], ['language', 'language'], ['dataReleaseId', 'dataReleaseId'], ['battleFormatId', 'battleFormatId'], ['rulesetVersionId', 'rulesetVersionId']]) {
  if (!scalar(fixture.canonicalVersions?.[key])) errors.push(`canonicalVersions.${label} 缺失`);
}
if (!Array.isArray(fixture.source?.urls) || fixture.source.urls.length === 0) errors.push('fixture 必须记录来源 URL');
const sourceUrls = sanitizeUrls(fixture.source?.urls, warnings);
const sourceApproved = fixture.source?.reviewStatus === 'approved'
  && scalar(fixture.source?.reviewer)
  && scalar(fixture.source?.reviewedAt)
  && scalar(fixture.source?.license)
  && !/待|pending|unknown|example\.invalid/i.test(String(fixture.source.license));
if (!sourceApproved) warnings.push('来源/许可尚未审核，fixture 只能保持 candidate/pending');

const files = [];
if (inputInfo) walk(inputInfo.real, '', files, fixtureRootRelative);
files.sort((left, right) => left.path.localeCompare(right.path, 'en'));
const aggregate = crypto.createHash('sha256');
for (const item of files) aggregate.update(`${item.path}\0${item.sha256}\0${item.bytes}\n`, 'utf8');
const counts = {};
for (const [key, value] of Object.entries(fixture)) if (Array.isArray(value)) counts[key] = value.length;
const status = errors.length > 0 ? 'blocked' : (sourceApproved ? 'ready-for-review' : 'candidate');
const manifest = {
  schemaVersion: 'v1.1', producer: 'create-fixture-manifest', generatedAt: generatedAt(), gitCommit: currentCommit(),
  fixtureId: scalar(fixture.fixtureId), status, productionEligible: false, scope: fixture.scope ?? 'isolated smoke only',
  canonicalVersions: fixture.canonicalVersions ?? null,
  source: {
    sourceId: scalar(fixture.source?.sourceId), urls: sourceUrls,
    license: scalar(fixture.source?.license), reviewStatus: fixture.source?.reviewStatus ?? 'missing',
    reviewer: scalar(fixture.source?.reviewer), reviewedAt: scalar(fixture.source?.reviewedAt), redacted: true
  },
  files, fileCount: files.length, overallSha256: aggregate.digest('hex'), counts,
  redacted: true, errors, warnings
};
const audit = {
  schemaVersion: 'v1.1', producer: 'create-fixture-manifest', generatedAt: manifest.generatedAt, gitCommit: manifest.gitCommit,
  fixtureId: manifest.fixtureId, redacted: true, sourceReview: manifest.source,
  recordCounts: counts, overallSha256: manifest.overallSha256,
  status: errors.length > 0 ? 'blocked' : (sourceApproved ? 'ready-for-review' : 'pending'),
  policy: '仅记录脱敏来源、记录数和 hash；未完成来源/许可审核不得进入 active release。'
};

if (!destinationBlocked && outputInfo && auditInfo) {
  try {
    writeJson(outputInfo.absolute, manifest, {noClobber});
    writeJson(auditInfo.absolute, audit, {noClobber});
  } catch (error) {
    errors.push(`producer 输出失败: ${safeError(error)}`);
  }
}
const pendingAllowed = args['allow-pending'] === true;
const passed = errors.length === 0 && sourceApproved;
const report = {
  schemaVersion: 'v1.1', producer: 'create-fixture-manifest', generatedAt: manifest.generatedAt, gitCommit: manifest.gitCommit,
  output: outputInfo?.relative ?? '[invalid-path]', sourceAudit: auditInfo?.relative ?? '[invalid-path]',
  status: errors.length > 0 ? 'blocked' : (sourceApproved ? 'ready-for-review' : 'candidate'),
  passed, exitCode: passed || (errors.length === 0 && pendingAllowed) ? 0 : 1,
  redacted: true, fileCount: files.length, overallSha256: manifest.overallSha256, errors, warnings
};
if (reportInfo && !(noClobber && fs.existsSync(reportInfo.absolute))) {
  try { writeJson(reportInfo.absolute, report, {noClobber}); } catch (error) { errors.push(`报告写入失败: ${safeError(error)}`); }
}
console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0 || errors.length > 0) process.exitCode = 1;

function directory(value, label) {
  try { return repoPathInfo(value, {label, mustExist: true, kind: 'directory'}); }
  catch (error) { errors.push(`${label} 无效: ${safeError(error)}`); return null; }
}
function inputFile(value, label) {
  try { return repoPathInfo(value, {label, mustExist: true, kind: 'file'}); }
  catch (error) { errors.push(`${label} 无效: ${safeError(error)}`); return null; }
}
function destination(value, label) {
  try { return repoOutputInfo(value, label); }
  catch (error) { errors.push(`${label} 路径无效: ${safeError(error)}`); return null; }
}
function walk(directoryPath, relative, entries, rootRelative) {
  for (const entry of fs.readdirSync(directoryPath, {withFileTypes: true})) {
    const childRelative = relative ? `${relative}/${entry.name}` : entry.name;
    const child = path.join(directoryPath, entry.name);
    if (entry.isDirectory()) { walk(child, childRelative, entries, rootRelative); continue; }
    if (!entry.isFile()) { errors.push(`fixture 文件类型不受支持: ${childRelative}`); continue; }
    const normalized = childRelative.replaceAll('\\', '/');
    if (isSensitiveFixturePath(normalized)) { errors.push(`fixture 禁止包含 Secret 文件: ${normalized}`); continue; }
    try {
      const info = repoPathInfo(child, {label: `fixture/${normalized}`, mustExist: true, kind: 'file'});
      entries.push({path: `${rootRelative}/${normalized}`, sha256: sha256File(info.real), bytes: fs.statSync(info.real).size});
    } catch (error) {
      errors.push(`fixture 文件无法读取: ${normalized} (${safeError(error)})`);
    }
  }
}
function isSensitiveFixturePath(value) {
  return /(^|\/)(?:\.env(?:\.[^/]+)?|application-local\.(?:yml|yaml|properties)|mcp-servers\.json|.*\.(?:pem|key|p12|jks|pfx|keystore))$/i.test(value);
}
function sanitizeUrls(values, targetWarnings) {
  if (!Array.isArray(values)) return [];
  return values.map((raw) => {
    try {
      const parsed = new URL(String(raw));
      if (!['http:', 'https:'].includes(parsed.protocol)) { targetWarnings.push('fixture source URL 仅允许 http/https'); return null; }
      if (parsed.username || parsed.password) { targetWarnings.push('fixture source URL 含凭据，已拒绝'); return null; }
      return redactEndpoint(parsed.toString());
    } catch { targetWarnings.push('fixture source URL 格式无效'); return null; }
  }).filter(Boolean);
}
function scalar(value) {
  if (value === undefined || value === null || value === true || value === false) return null;
  const text = String(value).trim(); return text || null;
}
function isWithin(root, target) {
  const relative = path.relative(path.resolve(root), path.resolve(target));
  return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}
function samePath(left, right) {
  const a = path.resolve(left); const b = path.resolve(right);
  return process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b;
}
function safeError(error) { return String(error?.message ?? error).replace(/[\r\n]/g, ' ').slice(0, 500); }
