#!/usr/bin/env node

/**
 * Validate the immutable N6b feedback/re-answer lineage bundle.
 *
 * This tool is intentionally a validator, not a producer.  It never calls a
 * live API and never turns missing fields into `unknown`.  A bundle is
 * publishable only when all three explicitly supplied files agree on
 * answerId -> runId -> traceId -> old/new release -> evaluationRunId.
 */
import fs from 'node:fs';
import {
  generatedAt,
  parseArgs,
  placeholder,
  readJson,
  repoRelative,
  resolvePath,
  sha256File,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const allowed = new Set([
  'feedback', 'registry', 'reanswer', 'published-release', 'feedback-run-id',
  'report', 'no-clobber'
]);
const errors = [];
const warnings = [];
for (const key of Object.keys(args)) {
  if (key !== '_positional' && !allowed.has(key)) errors.push(`不支持的参数: --${key}`);
}
const feedbackPath = args.feedback;
const registryPath = args.registry;
const reanswerPath = args.reanswer;
const publishedRelease = clean(args['published-release']);
const feedbackRunId = clean(args['feedback-run-id']);
const reportPath = args.report ?? 'artifacts/v1.1/test/n6b-feedback-lineage-validation.json';
for (const value of [feedbackPath, registryPath, reanswerPath, reportPath]) {
  if (!value) continue;
  try { repoRelative(resolvePath(value)); } catch (error) { errors.push(`路径无效: ${error.message}`); }
}
if (!feedbackPath || !registryPath || !reanswerPath) errors.push('必须同时提供 --feedback、--registry、--reanswer');
if (!publishedRelease || isUnknown(publishedRelease)) errors.push('--published-release 缺失或为占位符');
if (!feedbackRunId || !/^[A-Za-z0-9._-]{3,128}$/.test(feedbackRunId)) errors.push('--feedback-run-id 格式无效');

const feedbackRows = readJsonl(feedbackPath, 'feedback closure');
const registry = readRegistry(registryPath);
const reanswerRows = readJsonl(reanswerPath, 're-answer verification');
const registryEntries = registry.entries;
const registryByAnswer = new Map();
for (const [index, raw] of registryEntries.entries()) {
  const item = normalizeLineage(raw, `registry[${index + 1}]`, true);
  if (!item) continue;
  if (registryByAnswer.has(item.answerId)) errors.push(`registry: answerId 重复 ${item.answerId}`);
  registryByAnswer.set(item.answerId, item);
}

const feedbackByAnswer = new Map();
for (const [index, raw] of feedbackRows.entries()) {
  const item = normalizeLineage(raw, `feedback[${index + 1}]`, false);
  if (!item) continue;
  validateRunBinding(item, `feedback[${index + 1}]`);
  if (feedbackByAnswer.has(item.answerId)) errors.push(`feedback: answerId 重复 ${item.answerId}`);
  feedbackByAnswer.set(item.answerId, item);
}

const reanswerByAnswer = new Map();
for (const [index, raw] of reanswerRows.entries()) {
  const item = normalizeLineage(raw, `reanswer[${index + 1}]`, false);
  if (!item) continue;
  validateRunBinding(item, `reanswer[${index + 1}]`);
  if (reanswerByAnswer.has(item.answerId)) errors.push(`reanswer: answerId 重复 ${item.answerId}`);
  reanswerByAnswer.set(item.answerId, item);
}

if (feedbackByAnswer.size === 0) errors.push('feedback closure 不能为空');
for (const [answerId, feedback] of feedbackByAnswer) {
  const registered = registryByAnswer.get(answerId);
  const reanswer = reanswerByAnswer.get(answerId);
  if (!registered) { errors.push(`${answerId}: registry 缺少答案血缘`); continue; }
  if (!reanswer) { errors.push(`${answerId}: re-answer verification 缺少记录`); continue; }
  compareField(feedback, registered, 'runId', answerId);
  compareField(feedback, registered, 'traceId', answerId);
  compareField(feedback, reanswer, 'runId', answerId);
  compareField(feedback, reanswer, 'traceId', answerId);
  compareField(feedback, reanswer, 'evaluationRunId', answerId);
  compareField(feedback, registered, 'evaluationRunId', answerId);
  if (registered.publishedReleaseId !== publishedRelease) {
    errors.push(`${answerId}: registry publishedReleaseId 与目标发布不一致`);
  }
  if (reanswer.publishedReleaseId !== publishedRelease) {
    errors.push(`${answerId}: reanswer publishedReleaseId 与目标发布不一致`);
  }
  if (reanswer.previousReleaseId !== feedback.previousReleaseId) {
    errors.push(`${answerId}: reanswer previousReleaseId 与反馈不一致`);
  }
  if (isForbiddenStatus(feedback.status) || isForbiddenStatus(reanswer.status)) {
    errors.push(`${answerId}: 不可追溯/未完成状态不能进入发布队列`);
  }
  if (!['VERIFIED', 'CLOSED', 'REANSWERED'].includes(String(reanswer.status).toUpperCase())) {
    errors.push(`${answerId}: reanswer 未达到 VERIFIED/CLOSED/REANSWERED`);
  }
}
for (const [answerId] of registryByAnswer) {
  if (!feedbackByAnswer.has(answerId)) warnings.push(`${answerId}: registry 尚无 feedback closure 记录`);
}

const runIds = new Set([...feedbackByAnswer.values(), ...reanswerByAnswer.values()]
  .map((item) => item.evaluationRunId));
if (runIds.size > 1) errors.push('bundle 内 evaluationRunId 不一致');
for (const item of [...registryByAnswer.values(), ...feedbackByAnswer.values(), ...reanswerByAnswer.values()]) {
  if (item.feedbackRunId !== feedbackRunId) errors.push(`${item.answerId}: feedbackRunId 不一致`);
}

const report = {
  schemaVersion: 'v1.1',
  producer: 'validate-feedback-lineage',
  generatedAt: generatedAt(),
  status: errors.length === 0 ? 'passed' : 'blocked',
  passed: errors.length === 0,
  feedbackRunId,
  publishedReleaseId: publishedRelease,
  evaluationRunId: runIds.size === 1 ? [...runIds][0] : null,
  answerCount: feedbackByAnswer.size,
  untraceableCount: countUntraceable([...feedbackRows, ...reanswerRows, ...registryEntries]),
  inputs: [feedbackPath, registryPath, reanswerPath].filter(Boolean).map((file) => ({
    path: String(file).replaceAll('\\', '/'),
    sha256: safeHash(file)
  })),
  errors,
  warnings,
  redacted: true
};
try {
  writeJson(reportPath, report, {noClobber: true});
} catch (error) {
  report.status = 'blocked';
  report.passed = false;
  report.errors.push(`报告写入失败: ${error.message}`);
  process.exitCode = 1;
}
console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;

function readJsonl(file, label) {
  if (!file) return [];
  try {
    const text = fs.readFileSync(resolvePath(file), 'utf8');
    const rows = [];
    for (const [index, line] of text.split(/\r?\n/).filter((line) => line.trim()).entries()) {
      try {
        const value = JSON.parse(line);
        if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('每行必须是对象');
        rows.push(value);
      } catch (error) { errors.push(`${label} JSONL 第 ${index + 1} 行: ${error.message}`); }
    }
    return rows;
  } catch (error) {
    errors.push(`${label} 读取失败: ${error.message}`);
    return [];
  }
}

function readRegistry(file) {
  if (!file) return {entries: []};
  try {
    const value = readJson(file, 'answer release registry');
    const entries = Array.isArray(value) ? value : (value.entries ?? value.answers ?? []);
    if (!Array.isArray(entries)) errors.push('answer release registry 必须包含 entries/answers 数组');
    return {entries: Array.isArray(entries) ? entries : []};
  } catch (error) {
    errors.push(error.message);
    return {entries: []};
  }
}

function normalizeLineage(raw, label, registryMode) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    errors.push(`${label} 必须是对象`);
    return null;
  }
  const item = {
    answerId: clean(raw.answerId),
    runId: clean(raw.runId),
    traceId: clean(raw.traceId),
    feedbackId: clean(raw.feedbackId),
    feedbackRunId: clean(raw.feedbackRunId ?? raw.n6bRunId),
    evaluationRunId: clean(raw.evaluationRunId),
    previousReleaseId: clean(raw.previousReleaseId ?? raw.oldReleaseId ?? raw.sourceReleaseId),
    publishedReleaseId: clean(raw.publishedReleaseId ?? raw.newReleaseId ?? raw.targetReleaseId),
    operatorId: clean(raw.operatorId ?? raw.operator ?? raw.actorId),
    status: clean(raw.status ?? raw.state),
    lineageStatus: clean(raw.lineageStatus)
  };
  for (const field of ['answerId', 'runId', 'traceId', 'feedbackRunId', 'evaluationRunId',
    'previousReleaseId', 'publishedReleaseId', 'operatorId', 'status']) {
    if (!item[field] || isUnknown(item[field])) errors.push(`${label}: 缺少或禁止占位字段 ${field}`);
  }
  if (!registryMode && !item.feedbackId) errors.push(`${label}: 缺少 feedbackId`);
  if (isUnknown(item.lineageStatus) || String(item.lineageStatus).toUpperCase() === 'UNTRACEABLE') {
    errors.push(`${label}: lineageStatus 不可为 unknown/UNTRACEABLE`);
  }
  return item;
}

function validateRunBinding(item, label) {
  if (item.status && isForbiddenStatus(item.status)) errors.push(`${label}: 状态 ${item.status} 不可进入发布队列`);
}

function compareField(left, right, field, answerId) {
  if (left[field] !== right[field]) errors.push(`${answerId}: ${field} 在文件间不一致`);
}

function isForbiddenStatus(value) {
  const text = String(value ?? '').toUpperCase();
  return text === 'UNTRACEABLE' || text === 'UNKNOWN' || text === 'PENDING'
    || text === 'SUBMITTED' || text === 'TRIAGED' || text === 'IN_REVIEW'
    || text === 'REJECTED' || text === 'BLOCKED';
}

function countUntraceable(rows) {
  return rows.filter((row) => {
    const values = [row?.lineageStatus, row?.status, row?.state];
    return values.some((value) => ['unknown', 'untraceable'].includes(String(value ?? '').toLowerCase()));
  }).length;
}

function isUnknown(value) {
  return placeholder(value) || ['unknown', 'untraceable', 'trace-unknown'].includes(String(value ?? '').trim().toLowerCase());
}

function clean(value) {
  return value === undefined || value === null || String(value).trim() === '' ? null : String(value).trim();
}

function safeHash(file) {
  try { return sha256File(resolvePath(file)); } catch { return null; }
}
