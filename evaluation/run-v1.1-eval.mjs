#!/usr/bin/env node

/**
 * v1.1 evaluation runner.
 *
 * The runner deliberately separates "the evaluator ran" from "the release
 * passed".  It reads only the suite files named by the manifest and an
 * optional, explicit case-result file.  Missing results, missing release
 * metadata, and incomplete suites produce a pending/blocked report; no
 * synthetic pass values are generated.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  placeholder,
  readJson,
  repoRelative,
  resolvePath,
  sha256File,
  writeJson
} from '../tools/lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const allowed = new Set([
  'manifest', 'dictionary', 'evaluation-run-id', 'run-dir', 'output',
  'case-results', 'metric-results', 'code-commit', 'model-version',
  'prompt-version', 'data-release-id', 'knowledge-release-id',
  'index-release-id', 'environment', 'no-clobber',
  'baseline-evaluation-run-id', 'baseline-report-hash'
]);
const errors = [];
const warnings = [];
for (const key of Object.keys(args)) {
  if (key !== '_positional' && !allowed.has(key)) errors.push(`不支持的参数: --${key}`);
}
if (Array.isArray(args._positional) && args._positional.length > 0) {
  errors.push(`不支持的位置参数: ${args._positional.join(', ')}`);
}

const manifestPath = args.manifest ?? 'evaluation/v1.1/manifest.json';
const dictionaryPath = args.dictionary ?? 'evaluation/v1.1/metric-dictionary.json';
const evaluationRunId = String(args['evaluation-run-id'] ?? '').trim();
const output = args.output ?? (args['run-dir']
  ? path.posix.join(String(args['run-dir']).replaceAll('\\', '/'), 'metrics.json')
  : 'artifacts/v1.1/eval/metrics.json');
const runDir = args['run-dir'] ?? path.posix.dirname(String(output).replaceAll('\\', '/'));
const noClobber = true; // an evaluation run is immutable by contract

let manifest = null;
let dictionary = null;
try { manifest = readJson(manifestPath, 'v1.1 evaluation manifest'); }
catch (error) { errors.push(error.message); }
try { dictionary = readJson(dictionaryPath, 'v1.1 metric dictionary'); }
catch (error) { errors.push(error.message); }

if (!evaluationRunId) errors.push('缺少 --evaluation-run-id');
else if (!/^[A-Za-z0-9._-]{3,128}$/.test(evaluationRunId)) errors.push('evaluationRunId 格式无效');
const baselineEvaluationRunId = clean(args['baseline-evaluation-run-id']);
const baselineReportHash = clean(args['baseline-report-hash']);
if (baselineEvaluationRunId || baselineReportHash) {
  if (!/^[A-Za-z0-9._-]{3,128}$/.test(baselineEvaluationRunId ?? '')
      || baselineEvaluationRunId === evaluationRunId
      || !/^sha256:[a-f0-9]{64}$/.test(baselineReportHash ?? '')) {
    errors.push('baseline 必须绑定不同 evaluationRunId 和原始报告 sha256 摘要');
  }
}
if (manifest && (manifest.schemaVersion !== 'v1.1' || manifest.suiteVersion !== 'v1.1')) {
  errors.push('manifest schemaVersion/suiteVersion 必须为 v1.1');
}
if (dictionary && (dictionary.schemaVersion !== 'v1.1' || dictionary.dictionaryVersion !== 'v1.1')) {
  errors.push('dictionary schemaVersion/dictionaryVersion 必须为 v1.1');
}
for (const candidate of [output, runDir]) {
  try { repoRelative(resolvePath(candidate)); }
  catch (error) { errors.push(`输出路径无效: ${error.message}`); }
}

const metadata = {
  evaluationRunId: evaluationRunId || null,
  suiteVersion: 'v1.1',
  codeCommit: clean(args['code-commit'] ?? manifest?.codeCommit ?? currentCommit()),
  modelVersion: clean(args['model-version'] ?? manifest?.modelVersion),
  promptVersion: clean(args['prompt-version'] ?? manifest?.promptVersion),
  dataReleaseId: clean(args['data-release-id'] ?? manifest?.dataReleaseId),
  knowledgeReleaseId: clean(args['knowledge-release-id'] ?? manifest?.knowledgeReleaseId),
  indexReleaseId: clean(args['index-release-id'] ?? manifest?.indexReleaseId),
  environment: clean(args.environment ?? 'local')
};

const suites = Array.isArray(manifest?.suites) ? manifest.suites : [];
const suiteIds = suites.map((item) => item?.suiteId).filter(Boolean);
if (suites.length !== 7 || new Set(suiteIds).size !== suites.length) {
  errors.push('manifest 必须包含 7 个唯一 suite');
}

const casesBySuite = new Map();
const expectedByCaseId = new Map();
for (const suite of suites) {
  const suiteResult = {
    suiteId: suite?.suiteId ?? null,
    kind: suite?.kind ?? null,
    status: 'blocked',
    datasetFile: suite?.file ?? null,
    datasetSha256: null,
    minimumCases: Number(suite?.minimumCases ?? suite?.requiredCases ?? 0),
    datasetCaseCount: 0,
    sampleCount: 0,
    passedCount: 0,
    caseResults: [],
    metrics: [],
    errors: []
  };
  const file = resolveSuiteFile(suite?.file);
  if (!file) {
    suiteResult.errors.push('数据文件路径无效或不存在');
    errors.push(`${suiteResult.suiteId ?? 'suite'}: 数据文件路径无效或不存在`);
    casesBySuite.set(suiteResult.suiteId, suiteResult);
    continue;
  }
  suiteResult.datasetSha256 = safeHash(file);
  let lines = [];
  try {
    lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter((line) => line.trim());
  } catch (error) {
    suiteResult.errors.push(`数据文件读取失败: ${error.message}`);
    errors.push(`${suiteResult.suiteId}: 数据文件读取失败`);
    casesBySuite.set(suiteResult.suiteId, suiteResult);
    continue;
  }
  for (const [index, line] of lines.entries()) {
    try {
      const item = JSON.parse(line);
      if (!item || typeof item !== 'object' || Array.isArray(item) || !item.caseId) {
        throw new Error('caseId 缺失');
      }
      if (expectedByCaseId.has(String(item.caseId))) throw new Error(`caseId 重复: ${item.caseId}`);
      const expectedSuite = String(suite?.kind ?? suite?.suiteId ?? '');
      if (String(item.suite ?? '') !== expectedSuite) {
        throw new Error(`suite=${item.suite ?? ''} 与 kind=${expectedSuite} 不一致`);
      }
      expectedByCaseId.set(String(item.caseId), {suiteId: suite.suiteId, kind: suite.kind, item});
      suiteResult.datasetCaseCount += 1;
    } catch (error) {
      suiteResult.errors.push(`第 ${index + 1} 行: ${error.message}`);
    }
  }
  if (suiteResult.errors.length > 0) errors.push(`${suiteResult.suiteId}: 数据集契约错误`);
  casesBySuite.set(suiteResult.suiteId, suiteResult);
}

const suppliedResults = readCaseResults(args['case-results']);
const suppliedMetricResults = readMetricResults(args['metric-results']);
const seenResults = new Set();
for (const [index, raw] of suppliedResults.entries()) {
  const result = normalizeCaseResult(raw, index);
  if (!result) continue;
  const expected = expectedByCaseId.get(result.caseId);
  if (!expected) {
    errors.push(`case-result[${index + 1}]: 未知 caseId ${result.caseId}`);
    continue;
  }
  if (seenResults.has(result.caseId)) {
    errors.push(`case-result[${index + 1}]: caseId 重复 ${result.caseId}`);
    continue;
  }
  if (result.suiteId && result.suiteId !== expected.suiteId && result.suiteId !== expected.kind) {
    errors.push(`case-result[${index + 1}]: suite 与 manifest 不一致`);
    continue;
  }
  seenResults.add(result.caseId);
  const target = casesBySuite.get(expected.suiteId);
  if (!target) {
    errors.push(`case-result[${index + 1}]: suite 不存在 ${expected.suiteId}`);
    continue;
  }
  target.caseResults.push(result.safe);
  target.sampleCount += 1;
  if (result.passed) target.passedCount += 1;
}

if (!args['case-results']) {
  warnings.push('未提供 --case-results：只生成 pending 评测结果，不代表质量通过');
}
if (suppliedResults.length > 0 && seenResults.size < suppliedResults.length) {
  warnings.push('部分显式 case-result 未被接受，详见 errors');
}

const metricDefinitions = Array.isArray(dictionary?.metrics) ? dictionary.metrics : [];
validateMetricSuiteAlignment(metricDefinitions, suites);
const aggregateResults = buildMetricResults(metricDefinitions, casesBySuite, suppliedMetricResults);
for (const suiteResult of casesBySuite.values()) {
  suiteResult.metrics = aggregateResults.filter((metric) => metric.suiteId === suiteResult.suiteId)
    .map(({suiteId, ...metric}) => metric);
  const completeDataset = suiteResult.datasetCaseCount > 0
    && suiteResult.sampleCount === suiteResult.datasetCaseCount;
  const enough = suiteResult.sampleCount >= suiteResult.minimumCases;
  suiteResult.status = suiteResult.errors.length > 0 ? 'blocked'
    : (completeDataset && enough ? 'ready' : 'pending');
  if (!enough) suiteResult.errors.push(`样本不足 ${suiteResult.sampleCount}/${suiteResult.minimumCases}`);
}

const allSuitesReady = suites.length === 7 && [...casesBySuite.values()].every((suite) => suite.status === 'ready');
const metadataReady = Object.entries(metadata)
  .filter(([key]) => key !== 'evaluationRunId' && key !== 'environment')
  .every(([, value]) => usableMetadata(value));
const n6aMetrics = aggregateResults.filter((item) => item.evaluationPhase !== 'n6b');
const metricsReady = n6aMetrics.length > 0
  && n6aMetrics.every((item) => item.status === 'ready'
    && (item.blocking === false || item.passed === true));
const status = errors.length > 0 ? 'blocked' : (allSuitesReady && metadataReady && metricsReady ? 'ready' : 'pending');
if (status === 'pending') warnings.push('评测尚未满足完整样本、版本元数据或指标条件；release gate 应保持阻断');

const common = {
  schemaVersion: 'v1.1',
  producer: 'run-v1.1-eval',
  generatedAt: generatedAt(),
  status,
  ...metadata,
  ...(baselineEvaluationRunId ? {baselineEvaluationRunId, baselineReportHash} : {}),
  manifest: String(manifestPath).replaceAll('\\', '/'),
  manifestSha256: safeHash(resolvePath(manifestPath)),
  dictionary: String(dictionaryPath).replaceAll('\\', '/'),
  dictionarySha256: safeHash(resolvePath(dictionaryPath)),
  caseResults: args['case-results'] ? String(args['case-results']).replaceAll('\\', '/') : null,
  redacted: true
};

const aggregate = {
  ...common,
  sampleCount: [...casesBySuite.values()].reduce((sum, suite) => sum + suite.sampleCount, 0),
  datasetCaseCount: [...casesBySuite.values()].reduce((sum, suite) => sum + suite.datasetCaseCount, 0),
  securityRedTeamFailures: securityFailures(casesBySuite.get('security-redteam-v1')),
  suites: [...casesBySuite.values()].map((suite) => ({
    suiteId: suite.suiteId,
    kind: suite.kind,
    status: suite.status,
    datasetFile: suite.datasetFile,
    datasetSha256: suite.datasetSha256,
    datasetCaseCount: suite.datasetCaseCount,
    sampleCount: suite.sampleCount,
    passedCount: suite.passedCount,
    errors: suite.errors
  })),
  results: aggregateResults.map(({suiteId, ...metric}) => metric),
  errors,
  warnings
};

try {
  for (const suite of casesBySuite.values()) {
    const suitePath = path.posix.join(String(runDir).replaceAll('\\', '/'),
      `evaluation-run-${safeName(suite.suiteId)}-${safeName(evaluationRunId || 'missing')}.json`);
    writeJson(suitePath, {...common, suite: {
      suiteId: suite.suiteId,
      kind: suite.kind,
      status: suite.status,
      datasetFile: suite.datasetFile,
      datasetSha256: suite.datasetSha256,
      minimumCases: suite.minimumCases,
      datasetCaseCount: suite.datasetCaseCount,
      sampleCount: suite.sampleCount,
      passedCount: suite.passedCount,
      caseResults: suite.caseResults,
      metrics: suite.metrics,
      errors: suite.errors
    }}, {noClobber});
  }
  writeJson(output, aggregate, {noClobber});
} catch (error) {
  errors.push(`输出失败: ${error.message}`);
  aggregate.status = 'blocked';
  aggregate.errors = errors;
  // If the target itself could not be written, retain a useful stdout report;
  // never attempt to overwrite an existing immutable run.
  process.exitCode = 1;
}

console.log(JSON.stringify(aggregate, null, 2));
if (aggregate.status !== 'ready' || errors.length > 0) process.exitCode = 1;

function resolveSuiteFile(value) {
  if (typeof value !== 'string' || !value || value.includes('..') || value.startsWith('/') || value.startsWith('\\')) return null;
  const normalized = value.replaceAll('\\', '/');
  if (!normalized.startsWith('datasets/')) return null;
  const candidates = [
    resolvePath(path.posix.join('evaluation/v1.1', normalized)),
    resolvePath(path.posix.join(path.posix.dirname(String(manifestPath).replaceAll('\\', '/')), normalized))
  ];
  for (const candidate of candidates) {
    try {
      repoRelative(candidate);
      if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) return candidate;
    } catch { /* continue to the next explicit candidate */ }
  }
  return null;
}

function readCaseResults(value) {
  if (!value) return [];
  let text;
  try { text = fs.readFileSync(resolvePath(value), 'utf8'); }
  catch (error) { errors.push(`case-results 读取失败: ${error.message}`); return []; }
  if (!text.trim()) return [];
  try {
    const parsed = JSON.parse(text);
    if (Array.isArray(parsed)) return parsed;
    if (Array.isArray(parsed.cases)) return parsed.cases;
    if (Array.isArray(parsed.results)) return parsed.results;
    errors.push('case-results JSON 必须是数组，或包含 cases/results 数组');
    return [];
  } catch {
    const rows = [];
    for (const [index, line] of text.split(/\r?\n/).filter((line) => line.trim()).entries()) {
      try { rows.push(JSON.parse(line)); }
      catch (error) { errors.push(`case-results JSONL 第 ${index + 1} 行解析失败: ${error.message}`); }
    }
    return rows;
  }
}

function readMetricResults(value) {
  if (!value) return new Map();
  try {
    const parsed = readJson(value, 'metric results');
    const items = Array.isArray(parsed) ? parsed : (parsed.results ?? parsed.metrics ?? []);
    const map = new Map();
    for (const item of items) {
      if (item?.metricId) map.set(String(item.metricId), item);
    }
    return map;
  } catch (error) {
    errors.push(error.message);
    return new Map();
  }
}

function normalizeCaseResult(raw, index) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    errors.push(`case-result[${index + 1}] 必须是对象`);
    return null;
  }
  const caseId = clean(raw.caseId);
  if (!caseId) {
    errors.push(`case-result[${index + 1}] 缺少 caseId`);
    return null;
  }
  if (typeof raw.passed !== 'boolean') {
    errors.push(`case-result[${index + 1}] passed 必须是 boolean`);
    return null;
  }
  const safe = {
    caseId,
    suiteId: clean(raw.suiteId ?? raw.suite),
    passed: raw.passed,
    metrics: sanitizeMetrics(raw.metrics ?? raw.metricValues),
    blocked: typeof raw.blocked === 'boolean' ? raw.blocked : undefined,
    evidence: sanitizeEvidence(raw.evidence),
    traceId: safeId(raw.traceId),
    lineage: sanitizeLineage(raw.lineage ?? raw.versions),
    ttftMs: finiteNonNegative(raw.ttftMs ?? raw.timeToFirstTokenMs),
    durationMs: finiteNonNegative(raw.durationMs ?? raw.runDurationMs),
    inputTokens: finiteNonNegative(raw.inputTokens),
    outputTokens: finiteNonNegative(raw.outputTokens),
    costUsd: finiteNonNegative(raw.costUsd),
    notes: typeof raw.notes === 'string' ? raw.notes.slice(0, 500) : undefined
  };
  Object.keys(safe).forEach((key) => safe[key] === undefined && delete safe[key]);
  return {caseId, suiteId: safe.suiteId, passed: safe.passed, safe};
}

function buildMetricResults(definitions, suiteMap, explicit) {
  const output = [];
  for (const definition of definitions) {
    const metricId = String(definition?.metricId ?? '');
    if (!metricId) continue;
    const explicitValue = explicit.get(metricId);
    const suiteId = metricSuite(metricId);
    const suite = suiteId ? suiteMap.get(suiteId) : null;
    let value = null;
    let sampleCount = 0;
    let notes = '';
    let source = 'case-results';
    const evaluationPhase = definition.evaluationPhase ?? 'n6a';
    const blocking = definition.blocking !== false;
    if (explicitValue && Number.isFinite(Number(explicitValue.value))) {
      value = Number(explicitValue.value);
      sampleCount = Number(explicitValue.sampleCount ?? explicitValue.samples ?? 0);
      source = 'explicit-metric-results';
      notes = '显式 metric-results 由评测器/人工审核 producer 提供，未在此重新推断';
    } else if (metricId === 'tokens_consumed_total' || metricId === 'cost_usd_total') {
      const values = allCaseResults(suiteMap).map((row) => {
        if (metricId === 'tokens_consumed_total') {
          if (row.inputTokens === undefined && row.outputTokens === undefined) return NaN;
          return (row.inputTokens ?? 0) + (row.outputTokens ?? 0);
        }
        return row.costUsd;
      }).filter(Number.isFinite);
      sampleCount = values.length;
      value = values.length ? values.reduce((sum, item) => sum + item, 0) : null;
    } else if (metricId.endsWith('_p50_ms') || metricId.endsWith('_p95_ms')) {
      const field = metricId.startsWith('run_ttft') ? 'ttftMs' : 'durationMs';
      const values = allCaseResults(suiteMap).map((row) => row[field]).filter(Number.isFinite).sort((a, b) => a - b);
      sampleCount = values.length;
      value = percentile(values, metricId.endsWith('_p50_ms') ? 0.5 : 0.95);
    } else if (metricId === 'cancel_effective_p95_ms') {
      notes = '取消延迟需要运行时 cancel.accepted/cancelled 观测；当前 case schema 未提供时保持 pending';
    } else if (metricId === 'correction_p0_on_time_rate' || metricId === 'correction_closure_rate') {
      notes = 'N6b 发布后反馈指标，不在 N6a 评测中计算';
    } else if (suite) {
      const rows = metricRows(metricId, suite.caseResults);
      sampleCount = rows.length;
      value = rows.length ? rows.reduce((sum, row) => sum + row, 0) / rows.length : null;
    }
    const minimum = Number(definition.minRequests ?? definition.minimumSamples ?? 1);
    const enough = value !== null && sampleCount >= minimum;
    const observationOnly = String(definition.alertThreshold ?? '').trim() === 'observation_only';
    const passed = enough && (observationOnly || thresholdSatisfied(value, definition, explicitValue));
    const status = evaluationPhase === 'n6b' ? 'not_applicable'
      : (!enough ? 'pending' : (passed || !blocking ? 'ready' : 'blocked'));
    output.push({
      suiteId,
      metricId,
      value,
      sampleCount,
      passed,
      status,
      threshold: definition.alertThreshold ?? null,
      thresholdVersion: definition.thresholdVersion ?? null,
      evaluationPhase,
      blocking,
      source,
      notes: notes || undefined
    });
  }
  return output;
}

/**
 * Suite-derived metrics must use the same minimum denominator as the frozen
 * dataset suite. Otherwise a metric could be marked ready while its owning
 * Golden Set is still incomplete (or remain pending after the suite is ready).
 */
function validateMetricSuiteAlignment(definitions, suiteDefinitions) {
  const minimumBySuite = new Map((suiteDefinitions ?? []).map((suite) => [
    String(suite?.suiteId ?? ''), Number(suite?.minimumCases ?? suite?.requiredCases ?? 0)
  ]));
  for (const definition of definitions ?? []) {
    const metricId = String(definition?.metricId ?? '');
    const suiteId = metricSuite(metricId);
    if (!suiteId) continue;
    const suiteMinimum = minimumBySuite.get(suiteId);
    const metricMinimum = Number(definition?.minRequests ?? definition?.minimumSamples ?? 0);
    if (!Number.isInteger(suiteMinimum) || suiteMinimum <= 0) {
      errors.push(`${metricId}: 对应 suite ${suiteId} 缺少有效 minimumCases`);
      continue;
    }
    if (!Number.isInteger(metricMinimum) || metricMinimum !== suiteMinimum) {
      errors.push(`${metricId}: minRequests=${metricMinimum} 必须与 ${suiteId}.minimumCases=${suiteMinimum} 一致`);
    }
  }
}

function metricSuite(metricId) {
  if (metricId === 'fact_task_success_rate') return 'fact-v1';
  if (metricId === 'deterministic_calculation_accuracy') return 'calculation-v1';
  if (metricId === 'rules_task_success_rate') return 'legality-v1';
  if (['rag_recall_at_5', 'citation_correctness', 'groundedness', 'evidence_task_success_rate'].includes(metricId)) return 'rag-v1';
  if (metricId === 'no_answer_handling_rate') return 'no-answer-v1';
  if (metricId === 'tool_selection_parameter_accuracy') return 'agent-tool-v1';
  if (metricId === 'security_redteam_failures') return 'security-redteam-v1';
  return null;
}

function metricRows(metricId, rows) {
  return rows.map((row) => {
    if (metricId === 'rag_recall_at_5') return numericMetric(row, 'recallAt5', 'recall_at_5');
    if (metricId === 'citation_correctness') return numericMetric(row, 'citationCorrectness', 'citation_correctness');
    if (metricId === 'groundedness') return numericMetric(row, 'groundedness');
    if (metricId === 'evidence_task_success_rate') return numericMetric(row, 'evidenceTaskSuccess', 'evidence_task_success_rate');
    return row.passed ? 1 : 0;
  }).filter(Number.isFinite);
}

function numericMetric(row, ...keys) {
  for (const key of keys) {
    const value = row.metrics?.[key] ?? row[key];
    if (typeof value === 'boolean') return value ? 1 : 0;
    if (Number.isFinite(Number(value))) return Number(value);
  }
  return NaN;
}

function allCaseResults(suiteMap) {
  return [...suiteMap.values()].flatMap((suite) => suite.caseResults);
}

function percentile(values, quantile) {
  if (!values.length) return null;
  const rank = Math.max(1, Math.ceil(quantile * values.length));
  return values[rank - 1];
}

function thresholdSatisfied(value, definition, explicitValue) {
  const text = String(definition?.alertThreshold ?? '').trim();
  const match = text.match(/^([<>]=?)\s*(-?(?:\d+(?:\.\d+)?|\.\d+))\s*(?:ms)?$/i);
  if (!match) {
    const relative = text.match(/^baseline\s*\+\s*(\d+(?:\.\d+)?)%$/i);
    const baselineValue = Number(explicitValue?.baselineValue);
    if (!relative || !Number.isFinite(baselineValue) || baselineValue < 0) return false;
    return value <= baselineValue * (1 + Number(relative[1]) / 100);
  }
  const threshold = Number(match[2]);
  // The dictionary stores an *alert/failure* boundary (for example
  // "< 0.98" means alert when success falls below 0.98).  A result passes
  // when it is on the safe side of that boundary.
  return match[1].startsWith('>') ? value <= threshold : value >= threshold;
}

function securityFailures(suite) {
  if (!suite || suite.sampleCount === 0) return null;
  return suite.caseResults.filter((row) => row.passed !== true || row.blocked === false
    || row.metrics?.blocked === false).length;
}

function sanitizeMetrics(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const result = {};
  for (const [key, item] of Object.entries(value)) {
    if (!/^[A-Za-z][A-Za-z0-9_.-]{0,80}$/.test(key)) continue;
    if (typeof item === 'boolean' || Number.isFinite(Number(item))) result[key] = item;
  }
  return result;
}

function sanitizeEvidence(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const result = {};
  for (const key of ['documentId', 'documentVersion', 'chunkId', 'sourceUrl', 'releaseId']) {
    if (typeof value[key] === 'string' && value[key].length <= 256) result[key] = value[key];
  }
  return Object.keys(result).length ? result : undefined;
}

function sanitizeLineage(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const result = {};
  for (const key of ['gameReleaseId', 'dlcId', 'battleFormatId', 'rulesetVersionId', 'dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId', 'contractVersion']) {
    if (typeof value[key] === 'string' && value[key].length <= 256 && !placeholder(value[key])) result[key] = value[key];
  }
  return Object.keys(result).length ? result : undefined;
}

function finiteNonNegative(value) {
  if (value === undefined || value === null || value === '') return undefined;
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : undefined;
}

function safeId(value) {
  return typeof value === 'string' && /^[A-Za-z0-9._:-]{1,256}$/.test(value) ? value : undefined;
}

function clean(value) {
  return value === undefined || value === null || String(value).trim() === '' ? null : String(value).trim();
}

function usableMetadata(value) {
  if (placeholder(value)) return false;
  const normalized = String(value ?? '').trim().toLowerCase();
  return normalized !== 'unknown' && normalized !== 'set-by-ci' && normalized !== 'set-at-run';
}

function safeName(value) {
  return String(value ?? 'missing').replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 128);
}

function safeHash(file) {
  try { return file ? sha256File(file) : null; } catch { return null; }
}
