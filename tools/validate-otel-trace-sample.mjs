#!/usr/bin/env node

/**
 * Validate a redacted OTel JSON/JSONL sample against the v1.1 contract.
 * Runtime trace collection belongs to N7; this command is an offline,
 * fail-closed validator and never prints raw span attributes.
 */
import fs from 'node:fs';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  readJson,
  readText,
  repoRelative,
  resolvePath,
  sha256File,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const knownOptions = new Set([
  'input', 'sample', 'schema', 'contract', 'report', 'run-id', 'trace-id',
  'evaluation-run-id', 'require-run-span', 'no-clobber', 'overwrite'
]);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const samplePath = scalar(args.sample) ?? scalar(args.input);
const contractPath = scalar(args.contract) ?? scalar(args.schema) ?? 'artifacts/v1.1/eval/otel-schema.json';
const output = scalar(args.report) ?? 'artifacts/v1.1/eval/otel-trace-validation.json';
const noClobber = args.overwrite !== true && args['no-clobber'] !== false;
let outputAbsolute;
try { outputAbsolute = resolvePath(output); repoRelative(outputAbsolute); }
catch (error) { errors.push(`报告路径无效: ${safeText(error.message)}`); }
const outputPathInvalid = !outputAbsolute;
let noClobberCollision = false;
if (noClobber && outputAbsolute && fs.existsSync(outputAbsolute)) {
  noClobberCollision = true;
  errors.push(`输出已存在（默认 no-clobber）: ${output}`);
}

let contract;
let contractAbsolute;
try {
  contractAbsolute = resolvePath(contractPath);
  contract = readJson(contractAbsolute, 'OTel contract');
} catch (error) { errors.push(safeText(error.message)); }
if (!contract || typeof contract !== 'object' || Array.isArray(contract)) errors.push('OTel contract 必须是 JSON 对象');
else {
  if (contract.schemaVersion !== 'v1.1') errors.push('OTel contract schemaVersion 必须为 v1.1');
  if (contract.producer !== 'export-otel-contract') errors.push('OTel contract producer 不匹配');
  if (!Array.isArray(contract.spans) || contract.spans.length === 0) errors.push('OTel contract.spans 不能为空');
  if (!contract.redaction || contract.redaction.sensitiveValuesPersisted !== false) errors.push('OTel contract 必须声明 sensitiveValuesPersisted=false');
}
if (!samplePath) errors.push('缺少 --sample/--input；没有现场 Trace 样本不能通过');

const parsedSample = samplePath ? parseSample(samplePath) : {spans: [], wrapper: null, hash: null};
errors.push(...parsedSample.errors);
const spans = parsedSample.spans;
if (spans.length === 0) errors.push('Trace 样本为空');
const expectedRunId = scalar(args['run-id']) ?? scalar(parsedSample.wrapper?.runId);
const expectedTraceId = scalar(args['trace-id']) ?? scalar(parsedSample.wrapper?.traceId);
const expectedEvaluationRunId = scalar(args['evaluation-run-id']) ?? scalar(parsedSample.wrapper?.evaluationRunId);
const definitions = new Map((contract?.spans ?? []).map((span) => [span.name, span]));
const traceIds = new Set();
const runIds = new Set();
const evaluationIds = new Set();
let runSpanCount = 0;
for (const [index, span] of spans.entries()) {
  const prefix = `span[${index}]`;
  if (!span || typeof span !== 'object' || Array.isArray(span)) { errors.push(`${prefix} 必须是对象`); continue; }
  for (const field of ['schemaVersion', 'traceId', 'spanId', 'name', 'startTime', 'endTime', 'attributes']) if (!(field in span)) errors.push(`${prefix} 缺少 ${field}`);
  if (span.schemaVersion !== contract?.schemaVersion) errors.push(`${prefix}: schemaVersion 与 contract 不一致`);
  if (typeof span.traceId !== 'string' || !/^[a-f0-9]{16,128}$/i.test(span.traceId)) errors.push(`${prefix}: traceId 格式无效`);
  else traceIds.add(span.traceId.toLowerCase());
  if (typeof span.spanId !== 'string' || !/^[a-f0-9]{8,64}$/i.test(span.spanId)) errors.push(`${prefix}: spanId 格式无效`);
  if (typeof span.name !== 'string' || !definitions.has(span.name)) errors.push(`${prefix}: 未登记 span name`);
  if (!validDate(span.startTime) || !validDate(span.endTime)) errors.push(`${prefix}: 时间戳无效`);
  if (validDate(span.startTime) && validDate(span.endTime) && new Date(span.endTime) < new Date(span.startTime)) errors.push(`${prefix}: endTime 早于 startTime`);
  const attributes = span.attributes && typeof span.attributes === 'object' && !Array.isArray(span.attributes) ? span.attributes : {};
  // Some collectors put correlation IDs on the sample envelope rather than
  // repeating them on every span.  The envelope is accepted as a read-only
  // fallback, while contract.requiredAttributes still governs per-span keys.
  const runId = attr(attributes, 'run_id', 'runId') ?? scalar(span.runId) ?? scalar(parsedSample.wrapper?.runId);
  const traceId = attr(attributes, 'trace_id', 'traceId') ?? scalar(span.traceId) ?? scalar(parsedSample.wrapper?.traceId);
  const evaluationId = attr(attributes, 'evaluation_run_id', 'evaluationRunId') ?? scalar(span.evaluationRunId) ?? scalar(parsedSample.wrapper?.evaluationRunId);
  if (runId) runIds.add(runId);
  if (evaluationId) evaluationIds.add(evaluationId);
  if (span.name === 'vio.agent.run') runSpanCount += 1;
  if (!expectedTraceId || traceId !== expectedTraceId) {
    if (expectedTraceId) errors.push(`${prefix}: traceId 与请求不一致`);
  }
  if (expectedRunId && runId !== expectedRunId) errors.push(`${prefix}: runId 与请求不一致`);
  if (expectedEvaluationRunId && evaluationId !== expectedEvaluationRunId) errors.push(`${prefix}: evaluationRunId 与请求不一致`);
  const definition = definitions.get(span.name);
  if (definition) {
    for (const key of definition.requiredAttributes ?? []) {
      if (!nonEmpty(attributes[key])) errors.push(`${prefix}: 缺少必需 attribute ${key}`);
    }
  }
  inspectRedaction(span, attributes, contract?.redaction, prefix, errors);
}
if (args['require-run-span'] !== false && runSpanCount === 0) errors.push('Trace 样本必须包含 vio.agent.run span');
if (traceIds.size > 1) errors.push('Trace 样本包含多个 traceId');
if (expectedTraceId && traceIds.size === 1 && !traceIds.has(expectedTraceId.toLowerCase())) errors.push('样本 traceId 与请求不一致');
if (runIds.size > 1) errors.push('Trace 样本包含多个 runId');
if (evaluationIds.size > 1) errors.push('Trace 样本包含多个 evaluationRunId');
if (traceIds.size === 0) warnings.push('样本没有可回关联的 traceId');

const commit = currentCommit();
const report = {
  schemaVersion: 'v1.1',
  producer: 'validate-otel-trace-sample',
  generatedAt: generatedAt(),
  commit,
  gitCommit: commit,
  sample: samplePath ? repoPath(samplePath) : null,
  sampleSha256: parsedSample.hash,
  contract: repoPath(contractPath),
  contractSha256: contractAbsolute && fs.existsSync(contractAbsolute) ? sha256File(contractAbsolute) : null,
  expected: {runId: expectedRunId, traceId: expectedTraceId, evaluationRunId: expectedEvaluationRunId},
  correlation: {traceIds: [...traceIds], runIds: [...runIds], evaluationRunIds: [...evaluationIds]},
  spanCount: spans.length,
  traceCount: traceIds.size,
  runSpanCount,
  passed: errors.length === 0,
  status: errors.length === 0 ? 'passed' : 'blocked',
  redacted: true,
  errors: unique(errors),
  warnings: unique(warnings)
};
try {
  if (outputPathInvalid) throw new Error('invalid output path; no files were written');
  if (noClobberCollision) throw new Error('no-clobber collision; no files were written');
  writeJson(outputAbsolute, report, {noClobber: false});
} catch (error) {
  report.passed = false; report.status = 'blocked'; report.errors.push(`输出失败: ${safeText(error.message)}`); process.exitCode = 1;
}
console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;

function parseSample(file) {
  const result = {spans: [], wrapper: null, hash: null, errors: []};
  try {
    const absolute = resolvePath(file);
    const text = readText(absolute, 'OTel trace sample');
    result.hash = sha256File(absolute);
    const trimmed = text.trim();
    if (!trimmed) return result;
    let parsed = null;
    try { parsed = JSON.parse(trimmed); } catch { /* JSONL fallback below */ }
    if (parsed !== null) {
      if (Array.isArray(parsed)) result.spans.push(...parsed);
      else if (parsed && Array.isArray(parsed.spans)) { result.wrapper = parsed; result.spans.push(...parsed.spans); }
      else if (parsed && Array.isArray(parsed.sample)) { result.wrapper = parsed; result.spans.push(...parsed.sample); }
      else if (parsed?.data && Array.isArray(parsed.data.spans)) { result.wrapper = parsed; result.spans.push(...parsed.data.spans); }
      else if (parsed?.data && Array.isArray(parsed.data.sample)) { result.wrapper = parsed; result.spans.push(...parsed.data.sample); }
      else if (parsed && typeof parsed === 'object') result.spans.push(parsed);
      else result.errors.push('Trace sample JSON 顶层类型无效');
      return result;
    }
    for (const [index, line] of text.split(/\r?\n/).filter((item) => item.trim()).entries()) {
      try { result.spans.push(JSON.parse(line)); }
      catch (error) { result.errors.push(`JSONL 第 ${index + 1} 行解析失败`); }
    }
  } catch (error) { result.errors.push(safeText(error.message)); }
  return result;
}
function inspectRedaction(span, attributes, policy, prefix, targetErrors) {
  const forbiddenNames = new Set((policy?.forbiddenAttributeNames ?? []).map((item) => String(item).toLowerCase()));
  for (const key of Object.keys(attributes)) if (forbiddenNames.has(key.toLowerCase())) targetErrors.push(`${prefix}: 命中禁止的敏感 attribute`);
  const serialized = JSON.stringify({name: span.name, attributes});
  for (const pattern of policy?.forbiddenValuePatterns ?? []) {
    try {
      const normalized = String(pattern).replace(/^\(\?i\)/, '');
      if (new RegExp(normalized, 'i').test(serialized)) targetErrors.push(`${prefix}: 命中敏感值脱敏规则`);
    } catch { targetErrors.push('OTel contract 正则无效'); }
  }
}
function attr(attributes, ...keys) { for (const key of keys) if (attributes[key] !== undefined && attributes[key] !== null) return String(attributes[key]); return null; }
function validDate(value) { return typeof value === 'string' && !Number.isNaN(new Date(value).getTime()); }
function nonEmpty(value) { return value !== undefined && value !== null && String(value).trim() !== ''; }
function scalar(value) { return value === undefined || value === null || value === true || value === false ? null : String(value).trim() || null; }
function repoPath(value) { try { return repoRelative(resolvePath(value)); } catch { return String(value ?? '').replaceAll('\\', '/'); } }
function safeText(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 500); }
function unique(values) { return [...new Set(values.map((value) => String(value)))]; }
