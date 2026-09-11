#!/usr/bin/env node

/**
 * Validate the machine-readable v1.1 metric dictionary and emit the
 * normalized metric contract consumed by the quality gate.
 *
 * This is deliberately an offline producer: it never queries Prometheus or a
 * dashboard, never evaluates a metric, and never turns a draft dictionary
 * into a release-ready one. All writes are atomic and no-clobber by default.
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
  'dictionary', 'schema', 'output', 'report', 'validation-report',
  'hash', 'hash-output', 'no-clobber', 'overwrite'
]);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const dictionaryPath = scalar(args.dictionary) ?? 'evaluation/v1.1/metric-dictionary.json';
const schemaPath = scalar(args.schema);
const explicitOutput = scalar(args.output);
const requestedReport = scalar(args['validation-report']) ?? scalar(args.report);
// The original handbook used --report <.../metric-schema.json> for the
// normalized output. Keep that documented invocation compatible while
// retaining the conventional --report validation-report semantics elsewhere.
const handbookReportAsOutput = !explicitOutput && !scalar(args['validation-report'])
  && requestedReport && path.basename(requestedReport).toLowerCase() === 'metric-schema.json';
const output = explicitOutput ?? (handbookReportAsOutput ? requestedReport : 'artifacts/v1.1/eval/metric-schema.json');
const reportOutput = handbookReportAsOutput
  ? `${requestedReport}.validation.json`
  : (requestedReport ?? 'artifacts/v1.1/eval/metric-schema-validation.json');
const hashOutput = scalar(args['hash-output']) ?? scalar(args.hash);
const noClobber = args.overwrite !== true && args['no-clobber'] !== false;

const outputPaths = [output, reportOutput, hashOutput].filter(Boolean).map((value) => {
  try {
    const absolute = resolvePath(value);
    repoRelative(absolute);
    return {value, absolute};
  } catch (error) {
    errors.push(`输出路径无效: ${safeText(error.message)}`);
    return null;
  }
}).filter(Boolean);
const uniqueOutputPaths = new Set(outputPaths.map((item) => item.absolute.toLowerCase()));
if (uniqueOutputPaths.size !== outputPaths.length) errors.push('metric 输出路径不能相互覆盖');
const outputPathInvalid = outputPaths.length !== [output, reportOutput, hashOutput].filter(Boolean).length
  || uniqueOutputPaths.size !== outputPaths.length;

let dictionary;
let sourceHash = null;
try {
  dictionary = readJson(dictionaryPath, 'v1.1 metric dictionary');
  sourceHash = sha256File(resolvePath(dictionaryPath));
} catch (error) {
  errors.push(safeText(error.message));
}

if (schemaPath) {
  try {
    const contract = readJson(schemaPath, 'metric dictionary schema');
    validateSchemaShape(contract);
  } catch (error) {
    errors.push(safeText(error.message));
  }
}

validateDictionary(dictionary);
const normalized = normalizeMetrics(dictionary?.metrics);
const structurallyPassed = errors.length === 0;
const status = !structurallyPassed ? 'blocked' : dictionary?.status === 'ready' ? 'ready' : 'pending-review';
const commit = currentCommit();
const redaction = {
  sensitiveValuesPersisted: false,
  rules: [
    '仅保留指标定义、查询文本和版本字段；不读取实时监控响应',
    '拒绝 bearer/api_key/password/secret/token 等敏感值出现在字典字段中'
  ]
};
const schema = {
  schemaVersion: 'v1.1',
  producer: 'validate-metric-dictionary',
  generatedAt: generatedAt(),
  commit,
  gitCommit: commit,
  source: repoPath(dictionaryPath),
  sourceSha256: sourceHash,
  dictionaryVersion: dictionary?.dictionaryVersion ?? null,
  status,
  passed: structurallyPassed,
  producerCompleted: structurallyPassed,
  exitCode: structurallyPassed ? 0 : 1,
  immutable: structurallyPassed,
  redaction,
  metrics: normalized,
  errors: unique(errors),
  warnings: unique(warnings)
};
const report = {
  schemaVersion: 'v1.1',
  producer: 'validate-metric-dictionary',
  generatedAt: schema.generatedAt,
  commit,
  gitCommit: commit,
  dictionary: repoPath(dictionaryPath),
  schema: schemaPath ? repoPath(schemaPath) : null,
  output: repoPath(output),
  validationReport: repoPath(reportOutput),
  hashOutput: hashOutput ? repoPath(hashOutput) : null,
  passed: structurallyPassed,
  status,
  metricCount: normalized.length,
  redacted: true,
  errors: unique(errors),
  warnings: unique(warnings)
};

// Preflight no-clobber before writing any file, so a collision cannot leave a
// partially refreshed contract set.
let noClobberCollision = false;
if (noClobber) {
  for (const item of outputPaths) {
    if (fs.existsSync(item.absolute)) {
      noClobberCollision = true;
      errors.push(`输出已存在（默认 no-clobber）: ${item.value}`);
    }
  }
}
schema.errors = unique(errors);
report.errors = unique(errors);
if (errors.length > 0) {
  schema.status = 'blocked';
  schema.passed = false;
  schema.producerCompleted = false;
  schema.exitCode = 1;
  schema.immutable = false;
  report.status = 'blocked';
  report.passed = false;
}

try {
  // A missing/invalid dictionary still gets a blocked report when its output
  // path is writable; this makes the failure auditable without claiming data.
  if (outputPathInvalid) throw new Error('invalid output path; no files were written');
  if (noClobberCollision) throw new Error('no-clobber collision; no files were written');
  writeJson(output, schema, {noClobber: false});
  writeJson(reportOutput, report, {noClobber: false});
  if (hashOutput) {
    writeJson(hashOutput, {
      schemaVersion: 'v1.1',
      producer: 'validate-metric-dictionary',
      generatedAt: schema.generatedAt,
      commit,
      file: repoPath(output),
      algorithm: 'SHA-256',
      sha256: sha256File(resolvePath(output))
    }, {noClobber: false});
  }
} catch (error) {
  errors.push(`输出失败: ${safeText(error.message)}`);
  report.passed = false;
  report.status = 'blocked';
  report.errors = unique(errors);
  report.exitCode = 1;
  schema.status = 'blocked';
  schema.errors = unique(errors);
  process.exitCode = 1;
}
console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;

function validateDictionary(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    errors.push('dictionary 必须是 JSON 对象');
    return;
  }
  if (value.schemaVersion !== 'v1.1') errors.push('schemaVersion 必须为 v1.1');
  if (value.dictionaryVersion !== 'v1.1') errors.push('dictionaryVersion 必须为 v1.1');
  if (!['draft', 'ready', 'blocked'].includes(value.status)) errors.push('status 必须是 draft、ready 或 blocked');
  if (value.status === 'draft') warnings.push('dictionary.status=draft，尚未签收');
  if (value.status === 'blocked') errors.push('dictionary.status=blocked');
  if (!nonEmpty(value.owner)) errors.push('dictionary.owner 必须是非空字符串');
  if (value.generatedAt !== undefined && !validDate(value.generatedAt)) errors.push('dictionary.generatedAt 必须是 ISO-8601 时间戳');
  if (!Array.isArray(value.metrics) || value.metrics.length === 0) errors.push('metrics 不能为空');
}

function normalizeMetrics(values) {
  if (!Array.isArray(values)) return [];
  const ids = new Set();
  const result = [];
  const required = [
    'metricId', 'type', 'formula', 'denominator', 'exclusions', 'window', 'minRequests',
    'minimumSamples', 'version', 'thresholdVersion', 'owner', 'severity',
    'alertThreshold', 'rollbackCondition', 'query'
  ];
  const types = new Set(['product_kpi', 'diagnostic', 'slo', 'cost']);
  const severities = new Set(['blocker', 'warning', 'info']);
  for (const [index, metric] of values.entries()) {
    const prefix = `metrics[${index}]`;
    if (!metric || typeof metric !== 'object' || Array.isArray(metric)) {
      errors.push(`${prefix} 必须是对象`);
      continue;
    }
    for (const field of required) {
      if (field === 'exclusions') {
        if (!Array.isArray(metric[field])) errors.push(`${prefix} 缺少 exclusions`);
      } else if (!nonEmpty(metric[field])) {
        errors.push(`${prefix} 缺少 ${field}`);
      }
    }
    const id = String(metric.metricId ?? '');
    if (metric.schemaVersion !== undefined && metric.schemaVersion !== 'v1.1') errors.push(`${id || prefix}: schemaVersion 必须为 v1.1`);
    if (!/^[a-z][a-z0-9_]{2,127}$/.test(id)) errors.push(`${prefix}.metricId 格式无效`);
    if (ids.has(id)) errors.push(`metricId 重复: ${id}`);
    ids.add(id);
    if (!types.has(metric.type)) errors.push(`${id || prefix}: type 无效`);
    if (!severities.has(metric.severity)) errors.push(`${id || prefix}: severity 无效`);
    const minRequests = integerAtLeastOne(metric.minRequests);
    const minimumSamples = metric.minimumSamples === undefined
      ? minRequests : integerAtLeastOne(metric.minimumSamples);
    if (minRequests === null) errors.push(`${id || prefix}: minRequests 必须是正整数`);
    if (minimumSamples === null) errors.push(`${id || prefix}: minimumSamples 必须是正整数`);
    if (minRequests !== null && minimumSamples !== null && minRequests !== minimumSamples) {
      errors.push(`${id || prefix}: minRequests 与 minimumSamples 必须一致`);
    }
    for (const field of ['formula', 'denominator', 'query', 'window', 'thresholdVersion', 'owner', 'alertThreshold', 'rollbackCondition']) {
      if (!nonEmpty(metric[field]) || hasSensitiveText(metric[field])) errors.push(`${id || prefix}: ${field} 无效或包含敏感值`);
    }
    if (metric.eligibleRequests !== undefined && (!nonEmpty(metric.eligibleRequests) || hasSensitiveText(metric.eligibleRequests))) {
      errors.push(`${id || prefix}: eligibleRequests 无效`);
    }
    if (!Array.isArray(metric.exclusions) || metric.exclusions.some((item) => typeof item !== 'string')) {
      errors.push(`${id || prefix}: exclusions 必须是字符串数组`);
    }
    if (!validThreshold(metric.alertThreshold)) errors.push(`${id || prefix}: alertThreshold 格式无效`);
    if (metric.evaluationPhase !== undefined && !['n6a', 'n6b'].includes(metric.evaluationPhase)) errors.push(`${id || prefix}: evaluationPhase 无效`);
    result.push({
      metricId: id,
      type: metric.type,
      formula: String(metric.formula ?? ''),
      denominator: String(metric.denominator ?? ''),
      query: String(metric.query ?? ''),
      window: String(metric.window ?? ''),
      minRequests: minRequests ?? 0,
      minimumSamples: minimumSamples ?? 0,
      thresholdVersion: String(metric.thresholdVersion ?? ''),
      owner: String(metric.owner ?? ''),
      queryOwner: String(metric.queryOwner ?? metric.owner ?? ''),
      severity: metric.severity,
      evaluationPhase: metric.evaluationPhase ?? 'n6a',
      blocking: metric.blocking !== false,
      alertThreshold: String(metric.alertThreshold ?? ''),
      rollbackCondition: String(metric.rollbackCondition ?? ''),
      exclusions: Array.isArray(metric.exclusions) ? [...metric.exclusions] : []
    });
  }
  result.sort((left, right) => left.metricId.localeCompare(right.metricId));
  return result;
}

function validateSchemaShape(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) { errors.push('schema 必须是 JSON 对象'); return; }
  if (value.$schema && !String(value.$schema).includes('json-schema.org')) errors.push('schema $schema 不是 JSON Schema');
  const required = new Set(Array.isArray(value.required) ? value.required : []);
  for (const field of ['schemaVersion', 'dictionaryVersion', 'metrics']) if (!required.has(field)) warnings.push(`schema 未声明 required.${field}`);
  if (!value.properties || typeof value.properties !== 'object') errors.push('schema.properties 缺失');
}
function validThreshold(value) {
  const text = String(value ?? '').trim();
  return text === 'observation_only'
    || /^baseline\s*\+\s*\d+(?:\.\d+)?%$/i.test(text)
    || /^[<>]=?\s*-?(?:\d+(?:\.\d+)?|\.\d+)\s*(?:ms|s|%)?$/i.test(text);
}
function integerAtLeastOne(value) { const number = Number(value); return Number.isInteger(number) && number > 0 ? number : null; }
function nonEmpty(value) { return value !== undefined && value !== null && String(value).trim() !== ''; }
function validDate(value) { return typeof value === 'string' && !Number.isNaN(new Date(value).getTime()); }
function hasSensitiveText(value) { return /bearer\s+|(?:api[-_ ]?key|password|secret|token)\s*[:=]/i.test(String(value)); }
function scalar(value) { return value === undefined || value === null || value === true || value === false ? null : String(value).trim() || null; }
function repoPath(value) { try { return repoRelative(resolvePath(value)); } catch { return String(value ?? '').replaceAll('\\', '/'); } }
function safeText(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 500); }
function unique(values) { return [...new Set(values.map((value) => String(value)))]; }
