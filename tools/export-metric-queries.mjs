#!/usr/bin/env node

/**
 * Export the versioned metric-query contract from the single v1.1 dictionary
 * source. This producer is offline and redacted: it never calls a metrics
 * backend and never writes credentials from environment variables.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  readJson,
  repoRelative,
  resolvePath,
  sha256File,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const knownOptions = new Set([
  'dictionary', 'schema', 'output', 'hash', 'hash-output', 'report',
  'owner', 'no-clobber', 'overwrite'
]);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const dictionaryPath = scalar(args.dictionary) ?? 'evaluation/v1.1/metric-dictionary.json';
const output = scalar(args.output) ?? 'artifacts/v1.1/eval/metric-queries.json';
const hashOutput = scalar(args['hash-output']) ?? scalar(args.hash) ?? `${output}.sha256.json`;
const reportOutput = scalar(args.report) ?? 'artifacts/v1.1/eval/metric-queries-build.json';
const noClobber = args.overwrite !== true && args['no-clobber'] !== false;
const outputPaths = [output, hashOutput, reportOutput].map((value) => {
  try {
    const absolute = resolvePath(value);
    repoRelative(absolute);
    return {value, absolute};
  } catch (error) {
    errors.push(`输出路径无效: ${safeText(error.message)}`);
    return null;
  }
}).filter(Boolean);
if (new Set(outputPaths.map((item) => item.absolute.toLowerCase())).size !== outputPaths.length) {
  errors.push('metric query 输出路径不能相互覆盖');
}
const outputPathInvalid = outputPaths.length !== 3
  || new Set(outputPaths.map((item) => item.absolute.toLowerCase())).size !== outputPaths.length;

let dictionary;
let dictionarySha256 = null;
try {
  dictionary = readJson(dictionaryPath, 'v1.1 metric dictionary');
  dictionarySha256 = sha256File(resolvePath(dictionaryPath));
} catch (error) {
  errors.push(safeText(error.message));
}
if (args.schema) {
  try {
    const schema = readJson(args.schema, 'metric query schema');
    if (!schema || typeof schema !== 'object' || Array.isArray(schema)) errors.push('metric query schema 必须是 JSON 对象');
    else if (schema.$schema && !String(schema.$schema).includes('json-schema.org')) errors.push('metric query schema $schema 不是 JSON Schema');
  } catch (error) { errors.push(safeText(error.message)); }
}

const metrics = Array.isArray(dictionary?.metrics) ? dictionary.metrics : [];
if (dictionary?.schemaVersion !== 'v1.1' || dictionary?.dictionaryVersion !== 'v1.1') {
  errors.push('dictionary schemaVersion/dictionaryVersion 必须为 v1.1');
}
if (!['draft', 'ready', 'blocked'].includes(dictionary?.status)) errors.push('dictionary.status 无效');
if (dictionary?.status === 'blocked') errors.push('dictionary.status=blocked');
if (metrics.length === 0) errors.push('dictionary.metrics 不能为空');
if (dictionary?.status === 'draft') warnings.push('dictionary.status=draft，query contract 保持 pending-review');

const ids = new Set();
const queries = [];
for (const [index, metric] of metrics.entries()) {
  const prefix = `metrics[${index}]`;
  const id = String(metric?.metricId ?? '');
  if (!/^[a-z][a-z0-9_]{2,127}$/.test(id)) errors.push(`${prefix}.metricId 格式无效`);
  if (ids.has(id)) errors.push(`metricId 重复: ${id || 'unknown'}`);
  ids.add(id);
  for (const field of ['query', 'formula', 'denominator', 'window', 'thresholdVersion', 'owner', 'alertThreshold', 'rollbackCondition']) {
    if (!nonEmpty(metric?.[field])) errors.push(`${id || prefix}: ${field} 不能为空`);
    if (hasSensitiveText(metric?.[field])) errors.push(`${id || prefix}: ${field} 含敏感值`);
  }
  const minRequests = Number(metric?.minRequests);
  if (!Number.isInteger(minRequests) || minRequests <= 0) errors.push(`${id || prefix}: minRequests 必须为正整数`);
  if (metric?.severity !== undefined && !['blocker', 'warning', 'info'].includes(metric.severity)) errors.push(`${id || prefix}: severity 无效`);
  queries.push({
    metricId: id,
    query: String(metric?.query ?? ''),
    formula: String(metric?.formula ?? ''),
    denominator: String(metric?.denominator ?? ''),
    window: String(metric?.window ?? ''),
    minRequests: Number.isInteger(minRequests) ? minRequests : 0,
    minimumSamples: Number.isInteger(Number(metric?.minimumSamples)) ? Number(metric.minimumSamples) : (Number.isInteger(minRequests) ? minRequests : 0),
    thresholdVersion: String(metric?.thresholdVersion ?? ''),
    alertThreshold: String(metric?.alertThreshold ?? ''),
    rollbackCondition: String(metric?.rollbackCondition ?? ''),
    owner: String(metric?.owner ?? ''),
    queryOwner: String(metric?.queryOwner ?? metric?.owner ?? ''),
    severity: metric?.severity ?? null,
    evaluationPhase: metric?.evaluationPhase ?? 'n6a',
    blocking: metric?.blocking !== false
  });
}
queries.sort((left, right) => left.metricId.localeCompare(right.metricId));

const commit = currentCommit();
const passed = errors.length === 0;
const status = !passed ? 'blocked' : dictionary?.status === 'ready' ? 'ready' : 'pending-review';
const redaction = {
  sensitiveValuesPersisted: false,
  rules: ['只导出查询/阈值元数据，不执行查询', '禁止写入 bearer、api_key、password、secret、token 原文']
};
const payload = {
  schemaVersion: 'v1.1',
  producer: 'export-metric-queries',
  generatedAt: generatedAt(),
  commit,
  gitCommit: commit,
  status,
  dictionary: repoPath(dictionaryPath),
  dictionarySha256,
  queries,
  redacted: true,
  redaction,
  errors: unique(errors),
  warnings: unique(warnings)
};
const report = {
  schemaVersion: 'v1.1',
  producer: 'export-metric-queries',
  generatedAt: payload.generatedAt,
  commit,
  gitCommit: commit,
  output: repoPath(output),
  hashOutput: repoPath(hashOutput),
  passed,
  status,
  queryCount: queries.length,
  redacted: true,
  errors: unique(errors),
  warnings: unique(warnings)
};

let noClobberCollision = false;
if (noClobber) for (const item of outputPaths) if (fs.existsSync(item.absolute)) {
  noClobberCollision = true;
  errors.push(`输出已存在（默认 no-clobber）: ${item.value}`);
}
payload.errors = unique(errors);
report.errors = unique(errors);
if (errors.length > 0) { payload.status = 'blocked'; report.status = 'blocked'; report.passed = false; }

try {
  if (outputPathInvalid) throw new Error('invalid output path; no files were written');
  if (noClobberCollision) throw new Error('no-clobber collision; no files were written');
  writeJson(output, payload, {noClobber: false});
  const outputSha256 = sha256File(resolvePath(output));
  writeJson(hashOutput, {
    schemaVersion: 'v1.1',
    producer: 'export-metric-queries',
    generatedAt: payload.generatedAt,
    commit,
    file: repoPath(output),
    algorithm: 'SHA-256',
    sha256: outputSha256
  }, {noClobber: false});
  writeJson(reportOutput, report, {noClobber: false});
} catch (error) {
  errors.push(`输出失败: ${safeText(error.message)}`);
  report.passed = false;
  report.status = 'blocked';
  report.errors = unique(errors);
  process.exitCode = 1;
}
console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;

function nonEmpty(value) { return value !== undefined && value !== null && String(value).trim() !== ''; }
function hasSensitiveText(value) { return /bearer\s+|(?:api[-_ ]?key|password|secret|token)\s*[:=]/i.test(String(value ?? '')); }
function scalar(value) { return value === undefined || value === null || value === true || value === false ? null : String(value).trim() || null; }
function repoPath(value) { try { return repoRelative(resolvePath(value)); } catch { return String(value ?? '').replaceAll('\\', '/'); } }
function safeText(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 500); }
function unique(values) { return [...new Set(values.map((value) => String(value)))]; }
