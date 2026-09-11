#!/usr/bin/env node

/**
 * Compute machine-readable RPO/RTO from explicitly captured recovery drills.
 *
 * The input must contain real, passed drill records. Pending/dry-run records
 * are never converted to samples. This script performs no recovery action and
 * does not contact a database, Compose project or production endpoint.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  repoRelative,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const root = path.resolve(process.cwd());
const drillArg = scalar(args['drill-log'] ?? args.input);
const rpoOutputArg = scalar(args['output-rpo']);
const rtoOutputArg = scalar(args['output-rto']);
const rpoMarkdownArg = scalar(args['rpo-markdown']);
const rtoMarkdownArg = scalar(args['rto-markdown']);
const targetRpo = positiveNumber(args['target-rpo-seconds']);
const targetRto = positiveNumber(args['target-rto-seconds']);
const minSamplesArg = args['min-samples'];
const minSamples = minSamplesArg === undefined ? 3 : positiveNumber(minSamplesArg);
const noClobber = args['no-clobber'] === true;

if (!drillArg) errors.push('--drill-log 必须显式提供 JSONL');
if (!rpoOutputArg || !rtoOutputArg) errors.push('--output-rpo 与 --output-rto 必须显式提供且不能省略');
if (!rpoMarkdownArg || !rtoMarkdownArg) errors.push('--rpo-markdown 与 --rto-markdown 必须显式提供');
if (targetRpo === null) errors.push('--target-rpo-seconds 必须是正数');
if (targetRto === null) errors.push('--target-rto-seconds 必须是正数');
if (minSamples === null || !Number.isInteger(minSamples) || minSamples < 3) errors.push('--min-samples 必须是不小于 3 的整数');

let drillPath = null;
let sourceSha256 = null;
let sourceRelative = null;
const rows = [];
if (drillArg) {
  try {
    drillPath = resolveRepoInput(drillArg, 'drill-log');
    sourceRelative = safeRelative(drillPath);
    sourceSha256 = sha256File(drillPath);
    const lines = fs.readFileSync(drillPath, 'utf8').split(/\r?\n/).filter((line) => line.trim() !== '');
    if (lines.length === 0) errors.push('drill-log JSONL 为空');
    for (let index = 0; index < lines.length; index += 1) {
      try { rows.push({row: JSON.parse(lines[index]), line: index + 1}); }
      catch { errors.push('drill-log 第 ' + (index + 1) + ' 行 JSON 无法解析'); }
    }
  } catch (error) {
    errors.push('drill-log 读取失败: ' + safe(error.message));
  }
}

const seen = new Set();
const passedRows = [];
const observedFailureTypes = new Set();
for (const [index, item] of rows.entries()) {
  const row = item.row;
  const prefix = 'row[' + index + ']';
  if (!row || typeof row !== 'object' || Array.isArray(row)) {
    errors.push(prefix + ' 必须是对象');
    continue;
  }
  if (scanSecretLikeText(JSON.stringify(row)).length > 0) errors.push(prefix + ' 含未脱敏 secret');
  if (row.schemaVersion !== 'v1.1') errors.push(prefix + '.schemaVersion 必须为 v1.1');
  const drillId = text(row.drillId);
  if (!drillId || placeholder(drillId) || seen.has(drillId)) errors.push(prefix + '.drillId 缺失、占位或重复');
  seen.add(drillId);
  const failureType = text(row.failureType);
  if (!['container', 'database', 'index-or-dependency'].includes(failureType)) errors.push(prefix + '.failureType 无效');
  else observedFailureTypes.add(failureType);
  if (text(row.environment) !== 'staging') errors.push(prefix + '.environment 必须是 staging');
  if (text(row.projectName) === '' || !/^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$/.test(text(row.projectName)) || /(^|[-_.])(prod|production|default)([-_.]|$)/i.test(text(row.projectName))) {
    errors.push(prefix + '.projectName 不是隔离项目名');
  }
  if (row.freshIsolatedProject !== true) errors.push(prefix + '.freshIsolatedProject 必须为 true');
  if (row.approvalIdPresent !== true) errors.push(prefix + '.approvalIdPresent 必须为 true');
  if (!/^[a-f0-9]{64}$/i.test(text(row.backupSha256))) errors.push(prefix + '.backupSha256 无效');
  if (!/^[a-f0-9]{64}$/i.test(text(row.previousManifestSha256))) errors.push(prefix + '.previousManifestSha256 无效');
  const status = text(row.status).toLowerCase();
  const isPassed = row.passed === true && ['passed', 'verified', 'recovered'].includes(status);
  const rpo = nonNegativeNumber(row.actualRpoSeconds);
  const rto = nonNegativeNumber(row.actualRtoSeconds);
  const rowTargetRpo = positiveNumber(row.targetRpoSeconds);
  const rowTargetRto = positiveNumber(row.targetRtoSeconds);
  if (isPassed) {
    if (rpo === null || rto === null) errors.push(prefix + ' passed 记录缺少 actualRpoSeconds/actualRtoSeconds');
    if (rowTargetRpo === null || rowTargetRto === null) errors.push(prefix + ' passed 记录缺少 targetRpoSeconds/targetRtoSeconds');
    if (targetRpo !== null && rowTargetRpo !== null && rowTargetRpo !== targetRpo) errors.push(prefix + ' targetRpoSeconds 与命令行不一致');
    if (targetRto !== null && rowTargetRto !== null && rowTargetRto !== targetRto) errors.push(prefix + ' targetRtoSeconds 与命令行不一致');
    if (rpo !== null && targetRpo !== null && rpo > targetRpo) errors.push(prefix + ' RPO 超出目标');
    if (rto !== null && targetRto !== null && rto > targetRto) errors.push(prefix + ' RTO 超出目标');
    const clock = text(row.clockSource);
    if (!clock || clock === 'not-executed' || clock === 'unknown') errors.push(prefix + '.clockSource 未绑定真实时钟');
    for (const field of ['backupTimestamp', 'lastCommittedAt', 'restorePointAt', 'readinessAt']) {
      if (dateValue(row[field]) === null) errors.push(prefix + '.' + field + ' 缺失或时间无效');
    }
    if (rpo !== null && rto !== null && rowTargetRpo !== null && rowTargetRto !== null) {
      passedRows.push({row, rpo, rto, rowTargetRpo, rowTargetRto});
    }
  } else if (!['environment-pending', 'blocked'].includes(status)) {
    errors.push(prefix + ' 未声明合法的 pending/blocked 或 passed 状态');
  }
}

if (passedRows.length < minSamples) errors.push('真实通过演练样本不足：' + passedRows.length + ' < ' + minSamples);
for (const required of ['container', 'database', 'index-or-dependency']) {
  if (!observedFailureTypes.has(required)) errors.push('缺少故障类型样本: ' + required);
}

const rpoOutput = resolveOutput(rpoOutputArg, 'output-rpo');
const rtoOutput = resolveOutput(rtoOutputArg, 'output-rto');
const rpoMarkdown = resolveOutput(rpoMarkdownArg, 'rpo-markdown');
const rtoMarkdown = resolveOutput(rtoMarkdownArg, 'rto-markdown');
const outputs = [rpoOutput, rtoOutput, rpoMarkdown, rtoMarkdown].filter(Boolean);
const duplicateOutputs = new Set(outputs.map((item) => canonical(item))).size !== outputs.length;
if (duplicateOutputs) errors.push('四个输出路径必须互不相同');
if (drillPath && outputs.some((item) => samePath(item, drillPath))) errors.push('输出不能覆盖 drill-log');
const existingOutputs = outputs.filter((output) => fs.existsSync(output));
for (const output of outputs) {
  if (fs.existsSync(output)) errors.push('输出已存在（证据不可覆盖）: ' + safeRelative(output));
}
const outputCollision = duplicateOutputs || existingOutputs.length > 0 || Boolean(drillPath && outputs.some((item) => samePath(item, drillPath)));

const actualRpo = passedRows.length ? Math.max(...passedRows.map((item) => item.rpo)) : null;
const actualRto = passedRows.length ? Math.max(...passedRows.map((item) => item.rto)) : null;
const clockSources = [...new Set(passedRows.map((item) => text(item.row.clockSource)).filter(Boolean))].sort();
const passed = errors.length === 0 && passedRows.length >= minSamples && actualRpo !== null && actualRto !== null;
const status = passed ? 'passed' : (errors.length > 0 ? 'blocked' : 'environment-pending');
const common = {
  schemaVersion: 'v1.1',
  producer: 'measure-recovery-slo',
  generatedAt: generatedAt(),
  gitCommit: currentCommit(),
  sourceLog: sourceRelative,
  sourceLogSha256: sourceSha256,
  aggregation: 'worst-case over passed drills',
  sampleCount: passedRows.length,
  inputRowCount: rows.length,
  minSamples,
  observedFailureTypes: [...observedFailureTypes].sort(),
  clockSource: clockSources.length === 1 ? clockSources[0] : (clockSources.length ? clockSources : null),
  passed,
  status,
  errors: unique(errors),
  redacted: true
};
const rpoReport = {
  ...common,
  metric: 'RPO',
  actualSeconds: actualRpo,
  targetSeconds: targetRpo,
  actualRpoSeconds: actualRpo,
  targetRpoSeconds: targetRpo,
  rpoSeconds: actualRpo,
  target: {seconds: targetRpo},
  actual: {seconds: actualRpo}
};
const rtoReport = {
  ...common,
  metric: 'RTO',
  actualSeconds: actualRto,
  targetSeconds: targetRto,
  actualRtoSeconds: actualRto,
  targetRtoSeconds: targetRto,
  rtoSeconds: actualRto,
  target: {seconds: targetRto},
  actual: {seconds: actualRto}
};

let writeFailed = false;
if (!outputCollision) {
  for (const [target, report, label] of [[rpoOutput, rpoReport, 'RPO JSON'], [rtoOutput, rtoReport, 'RTO JSON']]) {
    if (!target) continue;
    try { writeImmutableJson(target, report, noClobber); }
    catch (error) { errors.push(label + ' 写入失败: ' + safe(error.message)); writeFailed = true; }
  }
  for (const [target, report, label] of [[rpoMarkdown, rpoReport, 'RPO Markdown'], [rtoMarkdown, rtoReport, 'RTO Markdown']]) {
    if (!target) continue;
    try { writeImmutableText(target, markdown(report), noClobber); }
    catch (error) { errors.push(label + ' 写入失败: ' + safe(error.message)); writeFailed = true; }
  }
}
if (writeFailed || errors.length > 0) {
  rpoReport.errors = unique(errors); rtoReport.errors = unique(errors);
  rpoReport.passed = false; rtoReport.passed = false;
  rpoReport.status = 'blocked'; rtoReport.status = 'blocked';
}
rpoReport.exitCode = rpoReport.passed ? 0 : 1;
rtoReport.exitCode = rtoReport.passed ? 0 : 1;
console.log(JSON.stringify({rpo: rpoReport, rto: rtoReport}, null, 2));
if (!rpoReport.passed || !rtoReport.passed) process.exitCode = 1;

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
  if (!isWithin(root, lexical) || canonical(lexical) === canonical(root)) { errors.push(label + ' 必须是仓库内文件路径'); return null; }
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
function canonical(value) { return process.platform === 'win32' ? path.resolve(value).toLowerCase() : path.resolve(value); }
function samePath(left, right) { return canonical(left) === canonical(right); }
function sha256File(file) { return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'); }
function safeRelative(file) { try { return repoRelative(file, root); } catch { return null; } }
function writeImmutableJson(file, body, noOverwrite) {
  if (fs.existsSync(file)) throw new Error(noOverwrite ? '输出已存在（--no-clobber）' : '输出已存在（证据不可覆盖）');
  writeJson(file, body, {noClobber: true});
}
function writeImmutableText(file, content, noOverwrite) {
  if (fs.existsSync(file)) throw new Error(noOverwrite ? '输出已存在（--no-clobber）' : '输出已存在（证据不可覆盖）');
  fs.mkdirSync(path.dirname(file), {recursive: true});
  const temporary = path.join(path.dirname(file), '.' + path.basename(file) + '.' + process.pid + '.' + crypto.randomBytes(6).toString('hex') + '.tmp');
  fs.writeFileSync(temporary, content, {encoding: 'utf8', flag: 'wx'});
  try { fs.renameSync(temporary, file); } catch (error) { try { fs.rmSync(temporary, {force: true}); } catch {} throw error; }
}
function markdown(report) {
  const lines = [
    '# v1.1 ' + report.metric + ' Recovery SLO',
    '',
    '- status: ' + report.status,
    '- passed: ' + report.passed,
    '- actualSeconds (worst): ' + (report.actualSeconds ?? 'null'),
    '- targetSeconds: ' + (report.targetSeconds ?? 'null'),
    '- sampleCount: ' + report.sampleCount,
    '- clockSource: ' + JSON.stringify(report.clockSource),
    '- sourceLog: ' + (report.sourceLog ?? 'null'),
    '- sourceLogSha256: ' + (report.sourceLogSha256 ?? 'null'),
    '',
    'This machine-readable result uses the worst observed value over explicitly captured passed drills. Pending or dry-run rows are not samples.',
    '',
    'errors: ' + JSON.stringify(report.errors),
    'redacted: true'
  ];
  return lines.join('\n') + '\n';
}
function text(value) { return value === undefined || value === null ? '' : String(value).trim(); }
function scalar(value) { if (Array.isArray(value)) return value.length === 1 ? text(value[0]) : null; if (value === undefined || value === null || value === true || value === false) return null; return text(value); }
function placeholder(value) { return !text(value) || /^(?:unknown|pending|tbd|not[-_]?executed|<[^>]+>|null)$/i.test(text(value)); }
function dateValue(value) { if (value === undefined || value === null || value === '') return null; const date = new Date(String(value)); return Number.isNaN(date.getTime()) ? null : date; }
function nonNegativeNumber(value) { const number = Number(value); return Number.isFinite(number) && number >= 0 ? number : null; }
function positiveNumber(value) { if (value === undefined || value === null || value === '' || value === true || value === false) return null; const number = Number(value); return Number.isFinite(number) && number > 0 ? number : null; }
function unique(values) { return [...new Set(values)]; }
function scanSecretLikeText(value) {
  const findings = [];
  for (const line of String(value ?? '').split(/\r?\n/)) {
    if (/^\s*["']?(?:password|passwd|api[_-]?key|access[_-]?key|client[_-]?secret|private[_-]?key|token)["']?\s*[:=]\s*["']?(?!\$\{|\[?redacted\]?|\*{3,})[^"',;\s}]+/i.test(line)) findings.push('secret-key');
    if (/(?:sk-[A-Za-z0-9]{12,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,})/.test(line)) findings.push('token-shaped');
  }
  return findings;
}
function safe(value) { return text(value).replace(/[\r\n]/g, ' ').slice(0, 240); }
