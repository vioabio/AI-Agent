#!/usr/bin/env node

/** Merge explicitly supplied observation windows into one immutable JSONL. */
import fs from 'node:fs';
import path from 'node:path';
import {parseArgs, listArg, resolvePath, requireFile, sha256File, writeAtomic, writeJson, currentCommit, generatedAt, repoRoot, repoRelative} from './lib/io.mjs';
import {analyzeObservationRows} from './lib/observation-contract.mjs';

const args = parseArgs(process.argv.slice(2));
const inputs = listArg(args.input);
const output = scalar(args.output);
const reportPath = scalar(args.report);
const releaseId = scalar(args['release-id']);
const environment = scalar(args.environment);
const noClobber = args['no-clobber'] === true;
const errors = [];

if (inputs.length === 0) errors.push('至少提供一个 --input；禁止从目录 glob 读取');
if (args.input !== undefined && !Array.isArray(args.input) && String(args.input).includes(',')) errors.push('--input 必须重复传入，禁止逗号拼接');
if (!output) errors.push('--output 必须显式提供');
if (!releaseId || placeholder(releaseId)) errors.push('--release-id 缺失或为占位符');
if (!['prod', 'staging'].includes(environment)) errors.push('--environment 必须是 prod 或 staging');

const rows = [];
const sources = [];
for (const input of inputs) {
  try {
    const file = requireFile(input, 'observation input');
    const real = fs.realpathSync(file);
    const root = path.resolve(repoRoot);
    const rel = path.relative(root, real);
    if (rel.startsWith('..') || path.isAbsolute(rel)) throw new Error('输入必须位于仓库内');
    sources.push({path: rel.split(path.sep).join('/'), sha256: sha256File(real)});
    const lines = fs.readFileSync(real, 'utf8').split(/\r?\n/).filter((line) => line.trim());
    lines.forEach((line, index) => {
      try { rows.push(JSON.parse(line)); }
      catch { errors.push(`${input} 第 ${index + 1} 行 JSON 无法解析`); }
    });
  } catch (error) { errors.push(`${input}: ${safe(error.message)}`); }
}

const seen = new Set();
let previousEnd = null;
let totalRequests = 0;
let first = null;
let last = null;
rows.sort((a, b) => timeOrMax(a.windowStart ?? a.startedAt ?? a.start) - timeOrMax(b.windowStart ?? b.startedAt ?? b.start));
for (const [index, row] of rows.entries()) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) { errors.push(`第 ${index + 1} 行必须是对象`); continue; }
  if (row.schemaVersion !== 'v1.1') errors.push(`第 ${index + 1} 行 schemaVersion 必须为 v1.1`);
  if (String(row.releaseId ?? '') !== releaseId) errors.push(`第 ${index + 1} 行 releaseId 不匹配`);
  if (String(row.environment ?? '') !== environment) errors.push(`第 ${index + 1} 行 environment 不匹配`);
  // Aggregation must not promote a WhatIf/pending window.  Every source row
  // has to be a completed live probe; otherwise a large synthetic denominator
  // could make the merged observation appear healthy.
  if (row.passed !== true || String(row.status ?? '').toLowerCase() !== 'passed') {
    errors.push(`第 ${index + 1} 行未完成现场探针（passed/status 必须为 true/passed）`);
  }
  const source = String(row.source ?? '').toLowerCase();
  if (source === 'not-executed' || row.copiedFrom
      || (environment === 'prod' && source === 'staging')) {
    errors.push(`第 ${index + 1} 行来源不是允许的 ${environment} 现场采集`);
  }
  const id = String(row.windowId ?? row.id ?? '').trim();
  if (!id || seen.has(id)) errors.push(`第 ${index + 1} 行 windowId 缺失或重复`);
  seen.add(id);
  const start = dateValue(row.windowStart ?? row.startedAt ?? row.start);
  const end = dateValue(row.windowEnd ?? row.endedAt ?? row.end);
  if (!start || !end || end <= start) errors.push(`第 ${index + 1} 行时间窗口无效`);
  else {
    if (previousEnd && start < previousEnd) errors.push(`窗口不是严格按时间排序或发生重叠`);
    previousEnd = end;
    if (!first || start < first) first = start;
    if (!last || end > last) last = end;
  }
  const count = Number(row.requestCount ?? row.requests ?? row.totalRequests ?? row.denominator);
  if (Number.isInteger(count) && count >= 0) totalRequests += count;
  else errors.push(`第 ${index + 1} 行 requestCount 必须是非负整数`);
}
// Keep the aggregate itself fail-closed.  The final validator repeats these
// checks, but rejecting gaps, one-row summaries and single-source claims here
// prevents an invalid merged artifact from being mistaken for evidence by a
// downstream step.
const analyzed = analyzeObservationRows(rows, {
  releaseId,
  environment,
  minPerWindow: 0,
  minWindows: 2,
  requireContinuous: true
});
errors.push(...analyzed.errors);
const outputAbsolute = output ? resolvePath(output) : null;
if (outputAbsolute) {
  try { repoRelative(outputAbsolute); } catch (error) { errors.push(`output: ${safe(error.message)}`); }
}
if (outputAbsolute && inputs.some((input) => path.resolve(resolvePath(input)) === path.resolve(outputAbsolute))) errors.push('output 不能覆盖任一 input');
if (errors.length === 0 && outputAbsolute) {
  try {
    const content = rows.map((row) => JSON.stringify(row)).join('\n') + (rows.length ? '\n' : '');
    if (noClobber && fs.existsSync(outputAbsolute)) throw new Error('output 已存在（--no-clobber）');
    writeAtomic(outputAbsolute, content, {noClobber});
  } catch (error) { errors.push(`output 写入失败: ${safe(error.message)}`); }
}

const report = {
  schemaVersion: 'v1.1', producer: 'aggregate-observation', generatedAt: generatedAt(), gitCommit: currentCommit(),
  releaseId: releaseId || null, environment: environment || null, sources, output: outputAbsolute ? safeRelative(outputAbsolute) : null,
  outputSha256: outputAbsolute && fs.existsSync(outputAbsolute) ? sha256File(outputAbsolute) : null,
  windowCount: rows.length, totalRequests, firstWindowStart: first?.toISOString() ?? null, lastWindowEnd: last?.toISOString() ?? null,
  durationHours: Number(analyzed.durationHours.toFixed(3)), coveredDurationHours: Number(analyzed.coveredDurationHours.toFixed(3)),
  continuous: analyzed.continuous, gapCount: analyzed.gapCount, maxGapSeconds: analyzed.maxGapSeconds,
  sourceChannels: analyzed.sourceChannels, minWindows: analyzed.minWindows,
  passed: errors.length === 0 && rows.length > 0, status: errors.length === 0 && rows.length > 0 ? 'passed' : 'blocked',
  errors: [...new Set(errors)], redacted: true
};
if (reportPath) {
  try { const resolvedReport = resolvePath(reportPath); repoRelative(resolvedReport); writeJson(resolvedReport, report, {noClobber}); }
  catch (error) { report.errors.push(`report 写入失败: ${safe(error.message)}`); report.passed = false; report.status = 'blocked'; }
}
report.exitCode = report.passed ? 0 : 1;
console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;

function scalar(value) { if (Array.isArray(value)) return value.length === 1 ? String(value[0]).trim() : null; if (value === undefined || value === null || value === true || value === false) return null; return String(value).trim(); }
function dateValue(value) { if (value === undefined || value === null || value === '') return null; const d = new Date(String(value)); return Number.isNaN(d.getTime()) ? null : d; }
function placeholder(value) { return !value || /^(?:unknown|pending|tbd|<[^>]+>|null)$/i.test(String(value).trim()); }
function safe(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 240); }
function safeRelative(file) { const rel = path.relative(repoRoot, path.resolve(file)); return rel && !rel.startsWith('..') && !path.isAbsolute(rel) ? rel.split(path.sep).join('/') : null; }
function timeOrMax(value) { const date = dateValue(value); return date ? date.getTime() : Number.POSITIVE_INFINITY; }
