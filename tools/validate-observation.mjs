#!/usr/bin/env node

/**
 * Validate a release-scoped observation JSONL file.
 *
 * This is intentionally a local, evidence-only validator.  It never calls a
 * monitoring endpoint and never turns missing samples into a pass.  The
 * collector that produced the rows must be run separately in the target
 * environment.  A summary observationHours/requestCount field is never used
 * as a substitute for a timestamped multi-window sequence.
 */
import fs from 'node:fs';
import path from 'node:path';
import {parseArgs, resolvePath, requireFile, sha256File, writeJson, currentCommit, generatedAt, repoRoot, repoRelative} from './lib/io.mjs';
import {analyzeObservationRows} from './lib/observation-contract.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const input = scalar(args.input);
const output = scalar(args.output ?? args.report);
const releaseId = scalar(args['release-id']);
const environment = scalar(args.environment);
const minHours = numberArg(args['min-hours'], 72);
const minRequests = numberArg(args['min-requests'], 500);
const minPerWindow = numberArg(args['min-requests-per-window'], 20);
const noClobber = args['no-clobber'] === true;

if (!input) errors.push('--input 必须显式提供 JSONL 文件');
if (!output) errors.push('--output/--report 必须显式提供');
if (!releaseId || placeholder(releaseId)) errors.push('--release-id 缺失或为占位符');
if (!['prod', 'staging'].includes(environment)) errors.push('--environment 必须是 prod 或 staging');
if (!Number.isFinite(minHours) || minHours <= 0) errors.push('--min-hours 必须为正数');
if (!Number.isFinite(minRequests) || minRequests <= 0) errors.push('--min-requests 必须为正数');
if (!Number.isFinite(minPerWindow) || minPerWindow <= 0) errors.push('--min-requests-per-window 必须为正数');

let rows = [];
let inputPath = null;
if (input) {
  try {
    inputPath = requireFile(input, 'observation');
    repoRelative(inputPath);
    const text = fs.readFileSync(inputPath, 'utf8');
    const lines = text.split(/\r?\n/).filter((line) => line.trim() !== '');
    if (lines.length === 0) errors.push('observation JSONL 为空');
    rows = lines.map((line, index) => {
      try { return JSON.parse(line); }
      catch (error) { errors.push(`第 ${index + 1} 行 JSON 无法解析`); return null; }
    });
  } catch (error) { errors.push(`observation 读取失败: ${safe(error.message)}`); }
}

// Structural checks (window IDs, timestamps, continuity and independent
// telemetry channels) are shared with the final release gate.  In
// particular, declared observationHours/requestCount never contribute to the
// measured duration; only the timestamped sequence does.
const analyzed = analyzeObservationRows(rows, {
  releaseId,
  environment,
  minPerWindow,
  minWindows: minHours > 1 ? 2 : 1,
  requireContinuous: true
});
errors.push(...analyzed.errors);
const totalRequests = analyzed.totalRequests;
const durationHours = analyzed.durationHours;
if (totalRequests < minRequests) errors.push(`总请求数不足：${totalRequests} < ${minRequests}`);
if (durationHours < minHours || analyzed.coveredDurationHours < minHours) {
  errors.push(`观察时长不足：可观测区间 ${durationHours.toFixed(3)}h、窗口覆盖 ${analyzed.coveredDurationHours.toFixed(3)}h，要求至少 ${minHours}h`);
}
if (!analyzed.continuous) errors.push('观察窗口序列未形成连续可验证覆盖');
if (!analyzed.independentSource) errors.push('观察证据缺少两个独立 telemetry source');

const report = {
  schemaVersion: 'v1.1',
  producer: 'validate-observation',
  generatedAt: generatedAt(),
  gitCommit: currentCommit(),
  input: inputPath ? safeRelative(inputPath) : null,
  inputSha256: inputPath ? sha256File(inputPath) : null,
  releaseId: releaseId || null,
  environment: environment || null,
  thresholds: {minHours, minRequests, minRequestsPerWindow: minPerWindow},
  windowCount: analyzed.windowCount,
  totalRequests,
  durationHours: Number(durationHours.toFixed(3)),
  coveredDurationHours: Number(analyzed.coveredDurationHours.toFixed(3)),
  firstWindowStart: analyzed.firstWindowStart,
  lastWindowEnd: analyzed.lastWindowEnd,
  minWindows: analyzed.minWindows,
  continuous: analyzed.continuous,
  gapCount: analyzed.gapCount,
  maxGapSeconds: analyzed.maxGapSeconds,
  sourceChannels: analyzed.sourceChannels,
  passed: errors.length === 0,
  status: errors.length === 0 ? 'passed' : 'blocked',
  errors: [...new Set(errors)],
  redacted: true
};

if (output) {
  try {
    const resolved = resolvePath(output);
    repoRelative(resolved);
    if (inputPath && path.resolve(resolved) === path.resolve(inputPath)) throw new Error('output 不能覆盖 input');
    writeJson(resolved, report, {noClobber});
  } catch (error) {
    errors.push(`report 写入失败: ${safe(error.message)}`);
    report.passed = false; report.status = 'blocked'; report.errors = [...new Set(errors)]; report.exitCode = 1;
  }
}
report.exitCode = report.passed ? 0 : 1;
console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;

function scalar(value) {
  if (Array.isArray(value)) return value.length === 1 ? String(value[0]).trim() : null;
  if (value === undefined || value === null || value === true || value === false) return null;
  return String(value).trim();
}
function numberArg(value, fallback) {
  const parsed = scalar(value);
  return parsed === null ? fallback : Number(parsed);
}
function placeholder(value) { return !value || /^(?:unknown|pending|tbd|<[^>]+>|null)$/i.test(String(value).trim()); }
function safe(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 240); }
function safeRelative(file) {
  try { return repoRelative(file, repoRoot); } catch { return null; }
}
