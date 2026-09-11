#!/usr/bin/env node

/**
 * Merge explicitly supplied recovery-drill JSONL files.
 *
 * This producer is evidence-only: it never starts Compose, calls pg_restore,
 * changes a release pointer or contacts a service. Invalid/pending records
 * remain a blocking result; no row is upgraded to passed.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {
  currentCommit,
  generatedAt,
  listArg,
  parseArgs,
  repoRelative,
  writeAtomic,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const inputArgs = listArg(args.input);
const outputArg = scalar(args.output);
const reportArg = scalar(args.report);
const expectedReleaseId = scalar(args['release-id']);
const expectedEnvironment = scalar(args.environment) ?? 'staging';
const noClobber = args['no-clobber'] === true;
const root = path.resolve(process.cwd());

if (inputArgs.length === 0) errors.push('--input 至少重复提供一个 JSONL 文件；禁止目录 glob');
if (args.input !== undefined && !Array.isArray(args.input) && String(args.input).includes(',')) errors.push('--input 必须重复传入，禁止逗号拼接');
if (!outputArg) errors.push('--output 必须显式提供');
if (!reportArg) errors.push('--report 必须显式提供');
if (!['prod', 'staging'].includes(expectedEnvironment)) errors.push('--environment 必须是 prod 或 staging');
if (expectedReleaseId && placeholder(expectedReleaseId)) errors.push('--release-id 不能是占位符');

const rows = [];
const sources = [];
for (const rawInput of inputArgs) {
  let file;
  try {
    file = resolveRepoInput(rawInput, 'recovery input');
    const source = {
      path: safeRelative(file),
      sha256: sha256File(file),
      bytes: fs.statSync(file).size
    };
    sources.push(source);
    const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter((line) => line.trim() !== '');
    if (lines.length === 0) {
      errors.push(safe(rawInput) + ' JSONL 为空');
      continue;
    }
    for (let index = 0; index < lines.length; index += 1) {
      try {
        rows.push({row: JSON.parse(lines[index]), source: source.path, line: index + 1});
      } catch {
        errors.push(safe(rawInput) + ' 第 ' + (index + 1) + ' 行 JSON 无法解析');
      }
    }
  } catch (error) {
    errors.push(safe(rawInput) + ': ' + safe(error.message));
  }
}

const seenDrills = new Set();
const seenProjects = new Set();
const validRows = [];
const failureTypes = new Set();
for (const [index, item] of rows.entries()) {
  const row = item.row;
  const prefix = 'row[' + index + ']';
  if (!row || typeof row !== 'object' || Array.isArray(row)) {
    errors.push(prefix + ' 必须是对象');
    continue;
  }
  if (scanSecretLikeText(JSON.stringify(row)).length > 0) errors.push(prefix + ' 含未脱敏 secret');
  if (row.schemaVersion !== 'v1.1') errors.push(prefix + '.schemaVersion 必须为 v1.1');
  if (row.producer && row.producer !== 'run-v1.1-recovery-drill') errors.push(prefix + '.producer 不受支持');
  const drillId = text(row.drillId);
  if (!drillId || placeholder(drillId) || seenDrills.has(drillId)) errors.push(prefix + '.drillId 缺失、占位或重复');
  seenDrills.add(drillId);
  const failureType = text(row.failureType);
  if (!['container', 'database', 'index-or-dependency'].includes(failureType)) errors.push(prefix + '.failureType 无效');
  else failureTypes.add(failureType);
  const projectName = text(row.projectName);
  if (!projectName || !/^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$/.test(projectName) || /(^|[-_.])(prod|production|default)([-_.]|$)/i.test(projectName)) {
    errors.push(prefix + '.projectName 不是隔离项目名');
  }
  if (projectName && seenProjects.has(projectName)) errors.push(prefix + '.projectName 与其他演练复用');
  seenProjects.add(projectName);
  if (text(row.environment) !== expectedEnvironment) errors.push(prefix + '.environment 不匹配');
  if (expectedReleaseId && text(row.previousReleaseId) !== expectedReleaseId && text(row.releaseId) !== expectedReleaseId) {
    errors.push(prefix + '.releaseId/previousReleaseId 与命令行不匹配');
  }
  if (expectedEnvironment === 'prod') errors.push('恢复演练不得把 prod 作为执行环境');
  const backupHash = text(row.backupSha256);
  if (!/^[a-f0-9]{64}$/i.test(backupHash)) errors.push(prefix + '.backupSha256 无效');
  const manifestHash = text(row.previousManifestSha256);
  if (!/^[a-f0-9]{64}$/i.test(manifestHash)) errors.push(prefix + '.previousManifestSha256 无效');
  if (row.freshIsolatedProject !== true) errors.push(prefix + '.freshIsolatedProject 必须为 true');
  if (row.approvalIdPresent !== true) errors.push(prefix + '.approvalIdPresent 必须为 true');
  const status = text(row.status).toLowerCase();
  if (!['passed', 'verified', 'recovered', 'environment-pending', 'blocked'].includes(status)) errors.push(prefix + '.status 无效');
  if (row.passed !== true && ['passed', 'verified', 'recovered'].includes(status)) errors.push(prefix + ' status=passed 但 passed 不为 true');
  const rpo = nonNegativeNumber(row.actualRpoSeconds);
  const rto = nonNegativeNumber(row.actualRtoSeconds);
  const targetRpo = positiveNumber(row.targetRpoSeconds);
  const targetRto = positiveNumber(row.targetRtoSeconds);
  const executable = row.passed === true && ['passed', 'verified', 'recovered'].includes(status);
  if (executable && (rpo === null || rto === null || targetRpo === null || targetRto === null)) {
    errors.push(prefix + ' 通过记录必须包含 actual/target RPO/RTO');
  }
  if (executable && (rpo > targetRpo || rto > targetRto)) errors.push(prefix + ' RPO/RTO 超出目标');
  const clock = text(row.clockSource);
  if (executable && (!clock || clock === 'not-executed' || clock === 'unknown')) errors.push(prefix + '.clockSource 未绑定真实时钟');
  for (const field of ['backupTimestamp', 'lastCommittedAt', 'restorePointAt', 'readinessAt']) {
    if (row[field] !== null && row[field] !== undefined && dateValue(row[field]) === null) errors.push(prefix + '.' + field + ' 时间无效');
  }
  if (executable) validRows.push({row, source: item.source, line: item.line, rpo, rto, targetRpo, targetRto});
}

if (validRows.length < 3) errors.push('真实通过演练样本不足：' + validRows.length + ' < 3');
for (const required of ['container', 'database', 'index-or-dependency']) {
  if (!failureTypes.has(required)) errors.push('缺少故障类型样本: ' + required);
}

const sortedRows = validRows
  .map((entry) => entry.row)
  .sort((left, right) => {
    const a = dateValue(left.finishedAt ?? left.generatedAt ?? left.startedAt)?.getTime() ?? Number.POSITIVE_INFINITY;
    const b = dateValue(right.finishedAt ?? right.generatedAt ?? right.startedAt)?.getTime() ?? Number.POSITIVE_INFINITY;
    return a - b || text(left.drillId).localeCompare(text(right.drillId));
  });

const outputPath = resolveOutput(outputArg, 'output');
const reportPath = resolveOutput(reportArg, 'report');
if (outputPath && reportPath && samePath(outputPath, reportPath)) errors.push('output 与 report 不能使用同一路径');
if (outputPath && fs.existsSync(outputPath)) errors.push('output 已存在（证据不可覆盖）');
if (reportPath && fs.existsSync(reportPath)) errors.push('report 已存在（证据不可覆盖）');
if (outputPath && inputArgs.some((value) => {
  try { return samePath(outputPath, resolveRepoInput(value, 'input')); } catch { return false; }
})) errors.push('output 不能覆盖 input');
if (reportPath && inputArgs.some((value) => {
  try { return samePath(reportPath, resolveRepoInput(value, 'input')); } catch { return false; }
})) errors.push('report 不能覆盖 input');

const passed = errors.length === 0 && validRows.length >= 3;
let outputWritten = false;
if (passed && outputPath) {
  try {
    const content = sortedRows.map((row) => JSON.stringify(projectRow(row))).join('\n') + '\n';
    writeAtomic(outputPath, content, {noClobber});
    outputWritten = true;
  } catch (error) {
    errors.push('output 写入失败: ' + safe(error.message));
  }
}

const report = {
  schemaVersion: 'v1.1',
  producer: 'aggregate-recovery-drills',
  generatedAt: generatedAt(),
  gitCommit: currentCommit(),
  sources,
  output: outputPath ? safeRelative(outputPath) : null,
  outputSha256: outputWritten ? sha256File(outputPath) : null,
  sampleCount: validRows.length,
  inputRowCount: rows.length,
  observedFailureTypes: [...failureTypes].sort(),
  aggregation: 'worst-case over passed samples',
  worstRpoSeconds: validRows.length ? Math.max(...validRows.map((entry) => entry.rpo ?? 0)) : null,
  worstRtoSeconds: validRows.length ? Math.max(...validRows.map((entry) => entry.rto ?? 0)) : null,
  targetRpoSeconds: validRows.length ? Math.max(...validRows.map((entry) => entry.targetRpo ?? 0)) : null,
  targetRtoSeconds: validRows.length ? Math.max(...validRows.map((entry) => entry.targetRto ?? 0)) : null,
  passed: false,
  status: 'blocked',
  errors: unique(errors),
  redacted: true,
  policy: '不把 pending/dry-run/缺失 actual 的记录升级为通过；至少三类独立隔离演练'
};
report.passed = report.errors.length === 0 && validRows.length >= 3;
report.status = report.passed ? 'passed' : (report.errors.length > 0 ? 'blocked' : 'environment-pending');
report.exitCode = report.passed ? 0 : 1;

if (reportPath) {
  try {
    writeJson(reportPath, report, {noClobber});
  } catch (error) {
    report.errors.push('report 写入失败: ' + safe(error.message));
    report.passed = false;
    report.status = 'blocked';
    report.exitCode = 1;
  }
}
console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0) process.exitCode = 1;

function resolveRepoInput(value, label) {
  const raw = String(value ?? '').trim();
  const lexical = path.isAbsolute(raw) ? path.normalize(raw) : path.resolve(root, raw);
  if (!raw || !isWithin(root, lexical) || !fs.existsSync(lexical) || !fs.statSync(lexical).isFile()) throw new Error(label + ' 不存在或越出仓库');
  const real = fs.realpathSync(lexical);
  if (!isWithin(root, real) || !fs.statSync(real).isFile()) throw new Error(label + ' 通过符号链接越界');
  return real;
}
function resolveOutput(value, label) {
  if (!value) return null;
  const lexical = path.isAbsolute(value) ? path.normalize(value) : path.resolve(root, value);
  if (!isWithin(root, lexical) || path.resolve(root) === lexical) {
    errors.push(label + ' 必须是仓库内文件路径');
    return null;
  }
  let parent = path.dirname(lexical);
  while (parent && !fs.existsSync(parent)) {
    const next = path.dirname(parent);
    if (next === parent) break;
    parent = next;
  }
  if (parent && !isWithin(root, fs.realpathSync(parent))) errors.push(label + ' 父目录通过符号链接越界');
  return lexical;
}
function isWithin(parent, child) {
  const relative = path.relative(path.resolve(parent), path.resolve(child));
  return relative === '' || (relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative));
}
function samePath(left, right) {
  const a = path.resolve(left);
  const b = path.resolve(right);
  return process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b;
}
function sha256File(file) { return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'); }
function safeRelative(file) {
  try { return repoRelative(file, root); } catch { return null; }
}
function projectRow(row) {
  const fields = [
    'schemaVersion', 'producer', 'drillId', 'failureType', 'generatedAt', 'startedAt', 'finishedAt',
    'environment', 'baseUrl', 'projectName', 'approvalIdPresent', 'freshIsolatedProject', 'backupFile',
    'backupSha256', 'expectedSha256Present', 'checksumStatus', 'backupTimestamp', 'previousManifest',
    'previousManifestSha256', 'previousReleaseId', 'dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId',
    'lastCommittedAt', 'restorePointAt', 'readinessAt', 'actualRpoSeconds', 'actualRtoSeconds',
    'targetRpoSeconds', 'targetRtoSeconds', 'clockSource', 'status', 'passed', 'restoreOutput',
    'releasePointerOutput', 'reason', 'redacted'
  ];
  return Object.fromEntries(fields.filter((field) => Object.prototype.hasOwnProperty.call(row, field)).map((field) => [field, row[field]]));
}
function scanSecretLikeText(value) {
  const textValue = String(value ?? '');
  const findings = [];
  for (const line of textValue.split(/\r?\n/)) {
    if (/^\s*["']?(?:password|passwd|api[_-]?key|access[_-]?key|client[_-]?secret|private[_-]?key|token)["']?\s*[:=]\s*["']?(?!\$\{|\[?redacted\]?|\*{3,})[^"',;\s}]+/i.test(line)) findings.push('secret-key');
    if (/(?:sk-[A-Za-z0-9]{12,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,})/.test(line)) findings.push('token-shaped');
  }
  return findings;
}
function text(value) { return value === undefined || value === null ? '' : String(value).trim(); }
function scalar(value) { if (Array.isArray(value)) return value.length === 1 ? text(value[0]) : null; if (value === undefined || value === null || value === true || value === false) return null; return text(value); }
function placeholder(value) { return !text(value) || /^(?:unknown|pending|tbd|not[-_]?executed|<[^>]+>|null)$/i.test(text(value)); }
function dateValue(value) { if (value === undefined || value === null || value === '') return null; const date = new Date(String(value)); return Number.isNaN(date.getTime()) ? null : date; }
function nonNegativeNumber(value) { const number = Number(value); return Number.isFinite(number) && number >= 0 ? number : null; }
function positiveNumber(value) { const number = Number(value); return Number.isFinite(number) && number > 0 ? number : null; }
function unique(values) { return [...new Set(values)]; }
function safe(value) { return text(value).replace(/[\r\n]/g, ' ').slice(0, 240); }
