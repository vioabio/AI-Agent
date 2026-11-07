#!/usr/bin/env node

/**
 * v1.1 评测/契约门禁。
 *
 * ci 模式只验证 schema、路径和可以在没有真实依赖时执行的结构性约束；
 * release 模式要求 reviewer 签收的 evaluation manifest、真实评测结果、
 * OTel/指标/安全/契约证据以及完整版本血缘。任何缺失或占位值都 fail-closed。
 * 本脚本只读取显式传入的文件，不使用 glob，也不会把 v1.0 报告当作回退输入。
 */
import fs from 'node:fs';
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
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const mode = String(args.mode ?? 'ci').toLowerCase();
const errors = [];
const warnings = [];
const checks = [];
const loaded = new Map();
let expectedRejection = false;

if (!['ci', 'release', 'negative'].includes(mode)) {
  errors.push(`不支持的 gate mode: ${mode}`);
}

if (args['invalid-fixture']) {
  runNegativeFixture(String(args['invalid-fixture']));
} else {
  runGate();
}

// Negative mode intentionally exits non-zero: the caller is proving that the
// release gate rejects a malformed lineage.  `expectedRejection` makes that
// non-zero result machine-readable instead of looking like a process crash.
const passed = errors.length === 0 && !expectedRejection;
const report = {
  schemaVersion: 'v1.1',
  producer: 'v1.1-quality-gate',
  generatedAt: generatedAt(),
  gitCommit: currentCommit(),
  mode,
  passed,
  status: passed ? 'passed' : 'blocked',
  reasonCode: passed ? null : (mode === 'negative' ? 'INVALID_RELEASE_LINEAGE' : 'MISSING_OR_INVALID_EVIDENCE'),
  expectedRejection,
  redacted: true,
  exitCode: passed ? 0 : 1,
  errors,
  warnings,
  checks,
  inputHashes: collectHashes()
};

const output = args.report ?? args.output;
if (output) {
  try {
    repoRelative(resolvePath(output));
    writeJson(output, report, {noClobber: args['no-clobber'] === true});
  } catch (error) {
    errors.push(`报告写入失败: ${error.message}`);
    report.passed = false;
    report.status = 'blocked';
    report.reasonCode = 'REPORT_WRITE_FAILED';
    report.exitCode = 1;
    report.errors = errors;
    process.exitCode = 1;
  }
}
console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;

function runGate() {
  const manifestPath = String(args.manifest ?? (mode === 'release'
    ? 'evaluation/v1.1/manifest.ready.json'
    : 'evaluation/v1.1/manifest.json'));
  const dictionaryPath = String(args.dictionary ?? 'evaluation/v1.1/metric-dictionary.json');
  const manifest = load(manifestPath, 'manifest');
  const dictionary = load(dictionaryPath, 'dictionary');
  if (!manifest) errors.push('缺少或无法读取 v1.1 evaluation manifest');
  if (!dictionary) errors.push('缺少或无法读取 v1.1 metric dictionary');
  if (manifest) validateManifest(manifest, manifestPath);
  if (dictionary) validateDictionary(dictionary);
  if (manifest && dictionary) validateMetricSuiteAlignment(manifest, dictionary);

  // Optional evidence is checked whenever supplied. Release requires every item.
  const evidenceKeys = [
    'metrics', 'run-manifest', 'run-hashes', 'metric-schema', 'metric-queries',
    'otel-schema', 'otel-queries', 'otel-trace-sample', 'security-report',
    'contract-report', 'readiness-evidence', 'feedback-report'
  ];
  for (const key of evidenceKeys) {
    const value = args[key];
    const requiredForRelease = ['metrics', 'run-manifest', 'run-hashes', 'metric-schema',
      'metric-queries', 'otel-schema', 'otel-queries', 'otel-trace-sample',
      'security-report', 'contract-report'].includes(key);
    if (value === undefined || value === true || value === false || String(value).trim() === '') {
      if (mode === 'release' && requiredForRelease) errors.push(`release gate 缺少 --${key}`);
      continue;
    }
    const valueObj = load(String(value), key);
    if (!valueObj) {
      errors.push(`${key} 无法读取`);
      continue;
    }
    validateEvidence(key, valueObj, String(value));
  }

  if (mode === 'release') validateReleaseEvidence(manifest, dictionary);
}

function validateManifest(manifest, source) {
  const okSchema = manifest.schemaVersion === 'v1.1' && manifest.suiteVersion === 'v1.1';
  checks.push({name: 'manifest-schema', passed: okSchema});
  if (!okSchema) errors.push('evaluation manifest schemaVersion/suiteVersion 必须为 v1.1');
  if (!['draft', 'ready', 'blocked'].includes(manifest.status)) {
    errors.push(`manifest.status 无效: ${manifest.status ?? 'missing'}`);
  }
  const normalized = String(source).replaceAll('\\', '/');
  if (mode === 'release' && (!normalized.endsWith('/manifest.ready.json') || manifest.status !== 'ready')) {
    errors.push('release gate 只接受 status=ready 的 evaluation/v1.1/manifest.ready.json');
  }
  if (!Array.isArray(manifest.suites) || manifest.suites.length !== 7) {
    errors.push('evaluation manifest 必须包含完整的 7 个 suite');
    return;
  }
  const ids = new Set();
  for (const suite of manifest.suites) {
    if (!suite || typeof suite !== 'object') {
      errors.push('suite 必须是对象');
      continue;
    }
    if (!suite.suiteId || ids.has(suite.suiteId)) errors.push(`suiteId 缺失或重复: ${suite.suiteId ?? 'missing'}`);
    ids.add(suite.suiteId);
    if (suite.schemaVersion !== 'v1.1') errors.push(`${suite.suiteId}: schemaVersion 必须为 v1.1`);
    if (!suite.file || !String(suite.file).startsWith('datasets/') || String(suite.file).includes('..')) {
      errors.push(`${suite.suiteId}: suite file 必须位于 evaluation/v1.1/datasets`);
      continue;
    }
    const file = resolvePath(`evaluation/v1.1/${suite.file}`);
    if (!fs.existsSync(file)) {
      errors.push(`${suite.suiteId}: 数据文件不存在`);
      continue;
    }
    const count = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean).length;
    const minimum = Number(suite.minimumCases ?? suite.requiredCases ?? 0);
    if (mode === 'release' && (!Number.isInteger(minimum) || minimum <= 0 || count < minimum)) {
      errors.push(`${suite.suiteId}: 样本不足 ${count}/${minimum}`);
    } else if (count < minimum) {
      warnings.push(`${suite.suiteId}: 样本不足 ${count}/${minimum}（ci 仅提示）`);
    }
    if (mode === 'release' && suite.status !== 'ready') errors.push(`${suite.suiteId}: status 必须为 ready`);
  }
  const requiredLineage = ['codeCommit', 'modelVersion', 'promptVersion', 'dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId'];
  for (const field of requiredLineage) {
    if (mode === 'release' && placeholder(manifest[field])) errors.push(`manifest.${field} 缺失或仍是占位符`);
  }
}

function validateDictionary(dictionary) {
  const okSchema = dictionary.schemaVersion === 'v1.1' && dictionary.dictionaryVersion === 'v1.1';
  checks.push({name: 'dictionary-schema', passed: okSchema});
  if (!okSchema) errors.push('metric dictionary schemaVersion/dictionaryVersion 必须为 v1.1');
  if (!Array.isArray(dictionary.metrics) || dictionary.metrics.length === 0) {
    errors.push('metric dictionary 缺少 metrics');
    return;
  }
  if (mode === 'release' && dictionary.status !== 'ready') errors.push(`metric dictionary status=${dictionary.status ?? 'missing'}，release 要求 ready`);
  const ids = new Set();
  for (const metric of dictionary.metrics) {
    const id = String(metric?.metricId ?? '');
    if (!id || ids.has(id)) errors.push(`metricId 缺失或重复: ${id || 'missing'}`);
    ids.add(id);
    for (const field of ['formula', 'denominator', 'window', 'minRequests', 'query', 'thresholdVersion', 'owner', 'severity']) {
      if (metric?.[field] === undefined || metric[field] === null || String(metric[field]).trim() === '') {
        errors.push(`${id || 'metric'} 缺少 ${field}`);
      }
    }
    if (!Number.isInteger(Number(metric?.minRequests)) || Number(metric.minRequests) <= 0) {
      errors.push(`${id || 'metric'}: minRequests 必须是正整数`);
    }
  }
}

function validateMetricSuiteAlignment(manifest, dictionary) {
  const suiteMinimums = new Map((manifest?.suites ?? []).map((suite) => [
    String(suite?.suiteId ?? ''), Number(suite?.minimumCases ?? suite?.requiredCases ?? 0)
  ]));
  const metricSuites = new Map([
    ['fact_task_success_rate', 'fact-v1'],
    ['deterministic_calculation_accuracy', 'calculation-v1'],
    ['rules_task_success_rate', 'legality-v1'],
    ['evidence_task_success_rate', 'rag-v1'],
    ['rag_recall_at_5', 'rag-v1'],
    ['citation_correctness', 'rag-v1'],
    ['groundedness', 'rag-v1'],
    ['no_answer_handling_rate', 'no-answer-v1'],
    ['tool_selection_parameter_accuracy', 'agent-tool-v1']
  ]);
  for (const metric of dictionary?.metrics ?? []) {
    const suiteId = metricSuites.get(String(metric?.metricId ?? ''));
    if (!suiteId) continue;
    const suiteMinimum = suiteMinimums.get(suiteId);
    const metricMinimum = Number(metric?.minRequests ?? metric?.minimumSamples ?? 0);
    if (!Number.isInteger(suiteMinimum) || suiteMinimum <= 0 || metricMinimum !== suiteMinimum) {
      errors.push(`${metric.metricId}: minRequests=${metricMinimum} 必须与 ${suiteId}.minimumCases=${suiteMinimum ?? 'missing'} 一致`);
    }
  }
}

function validateEvidence(key, value, source) {
  const before = errors.length;
  const schema = value?.schemaVersion;
  if (schema !== undefined && schema !== 'v1.1') errors.push(`${key}: schemaVersion 必须为 v1.1`);
  if (value?.status === 'blocked' && mode === 'release') errors.push(`${key}: status=blocked`);
  if (key === 'metrics') validateMetrics(value);
  if (key === 'run-manifest') validateRunManifest(value);
  if (key === 'run-hashes') validateHashManifest(value, key);
  if (key === 'metric-schema' && (!Array.isArray(value.metrics) || value.metrics.length === 0)) errors.push('metric-schema.metrics 不能为空');
  if (key === 'metric-queries' && (!Array.isArray(value.queries) || value.queries.length === 0)) errors.push('metric-queries.queries 不能为空');
  if (key === 'otel-schema' && (!Array.isArray(value.spans) || value.spans.length === 0)) errors.push('otel-schema.spans 不能为空');
  if (key === 'otel-queries' && (!Array.isArray(value.queries) || value.queries.length === 0)) errors.push('otel-queries.queries 不能为空');
  if (key === 'otel-trace-sample' && Array.isArray(value) && value.length === 0) errors.push('OTel trace sample 为空');
  if (key === 'security-report' && value.passed !== true && value.status !== 'passed') errors.push('security-report 未通过');
  if (key === 'contract-report' && value.passed !== true && value.status !== 'passed') errors.push('contract-report 未通过');
  if (key === 'readiness-evidence' && mode === 'release' && value.passed !== true) errors.push('readiness-evidence 未通过');
  if (key === 'feedback-report' && mode === 'release' && Number(value.untraceableCount ?? 0) > 0) errors.push('feedback-report 包含不可追溯记录');
  checks.push({name: key, passed: errors.length === before, source: source.replaceAll('\\', '/')});
}

function validateMetrics(value) {
  if (!value || typeof value !== 'object') {
    errors.push('metrics 必须是 JSON 对象');
    return;
  }
  for (const field of ['evaluationRunId', 'codeCommit', 'modelVersion', 'promptVersion', 'dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId']) {
    if (mode === 'release' && placeholder(value[field])) errors.push(`metrics.${field} 缺失或仍是占位符`);
  }
  const results = Array.isArray(value.results) ? value.results : (Array.isArray(value.metrics) ? value.metrics : []);
  if (mode === 'release' && results.length === 0) errors.push('metrics.results 不能为空');
  for (const item of results) {
    if (!item?.metricId) errors.push('metrics result 缺少 metricId');
    const pending = item?.status === 'pending' || item?.status === 'not_applicable';
    if (!pending && !Number.isFinite(Number(item.value))) errors.push(`${item?.metricId ?? 'metric'}: value 必须是数字`);
    if (mode === 'release' && item.status !== 'not_applicable' && item.passed !== true) {
      errors.push(`${item?.metricId ?? 'metric'}: passed 必须为 true`);
    }
    if (mode === 'release' && item.status !== 'not_applicable'
        && (!Number.isInteger(Number(item?.sampleCount)) || Number(item.sampleCount) <= 0)) {
      errors.push(`${item?.metricId ?? 'metric'}: sampleCount 必须为正整数`);
    }
  }
  if (mode === 'release') {
    const failures = value.securityRedTeamFailures ?? value.securityFailures;
    if (!Number.isInteger(Number(failures)) || Number(failures) !== 0) {
      errors.push('securityRedTeamFailures 必须是显式的 0');
    }
  }
}

function validateRunManifest(value) {
  if (mode === 'release' && value.status !== 'ready') errors.push(`run-manifest status=${value.status ?? 'missing'}，必须为 ready`);
  for (const field of ['evaluationRunId', 'codeCommit', 'modelVersion', 'promptVersion', 'dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId']) {
    if (mode === 'release' && placeholder(value?.[field])) errors.push(`run-manifest.${field} 缺失或仍是占位符`);
  }
  if (!Array.isArray(value?.suites) || value.suites.length !== 7) errors.push('run-manifest 必须列出 7 个 suite');
}

function validateHashManifest(value, key) {
  const entries = Array.isArray(value) ? value : (value.files ?? value.entries ?? value.hashes);
  if (!Array.isArray(entries) || entries.length === 0) {
    errors.push(`${key} 缺少 hash entries`);
    return;
  }
  for (const entry of entries) {
    if (!entry?.path && !entry?.file) errors.push(`${key}: hash entry 缺少 path/file`);
    if (!/^[a-f0-9]{64}$/i.test(String(entry?.sha256 ?? ''))) errors.push(`${key}: SHA-256 格式无效`);
  }
}

function validateReleaseEvidence(manifest, dictionary) {
  const metricSchema = loaded.get('metric-schema');
  const metricQueries = loaded.get('metric-queries');
  const otelSchema = loaded.get('otel-schema');
  const otelQueries = loaded.get('otel-queries');
  const metrics = loaded.get('metrics');
  const runManifest = loaded.get('run-manifest');
  if (metricSchema && dictionary) {
    const dictIds = new Set((dictionary.metrics ?? []).map((item) => item.metricId));
    const schemaIds = new Set((metricSchema.metrics ?? []).map((item) => item.metricId));
    if (dictIds.size !== schemaIds.size || [...dictIds].some((id) => !schemaIds.has(id))) errors.push('metric-schema 与 dictionary 指标集合不一致');
    if (metricSchema.sourceSha256 && args.dictionary) {
      const actual = safeHash(String(args.dictionary));
      if (actual && metricSchema.sourceSha256 !== actual) errors.push('metric-schema.sourceSha256 与 dictionary 不一致');
    }
  }
  if (metricQueries && metricSchema) {
    const schemaIds = new Set((metricSchema.metrics ?? []).map((item) => item.metricId));
    const queryIds = new Set((metricQueries.queries ?? []).map((item) => item.metricId));
    if (schemaIds.size !== queryIds.size || [...schemaIds].some((id) => !queryIds.has(id))) errors.push('metric-queries 与 metric-schema 指标集合不一致');
  }
  if (otelSchema && otelQueries && otelSchema.schemaVersion !== otelQueries.schemaVersion) errors.push('OTel schema/query schemaVersion 不一致');
  if (metrics && runManifest && metrics.evaluationRunId !== runManifest.evaluationRunId) errors.push('metrics 与 run-manifest evaluationRunId 不一致');
  if (manifest && runManifest) {
    for (const field of ['dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId']) {
      if (manifest[field] && runManifest[field] && manifest[field] !== runManifest[field]) errors.push(`manifest 与 run-manifest ${field} 不一致`);
    }
  }
  if (metrics && dictionary) {
    const resultMap = new Map((metrics.results ?? metrics.metrics ?? []).map((item) => [item.metricId, item]));
    for (const metric of dictionary.metrics ?? []) {
      const result = resultMap.get(metric.metricId);
      if (!result) {
        errors.push(`metrics 缺少指标 ${metric.metricId}`);
        continue;
      }
      if (metric.evaluationPhase === 'n6b') {
        if (result.status !== 'not_applicable') {
          errors.push(`${metric.metricId}: N6a 报告必须标记 not_applicable，由 N6b final gate 验收`);
        }
        continue;
      }
      if (result.thresholdVersion !== metric.thresholdVersion) {
        errors.push(`${metric.metricId}: thresholdVersion 与 dictionary 不一致`);
      }
      if (result.threshold !== undefined && result.threshold !== metric.alertThreshold) {
        errors.push(`${metric.metricId}: threshold 与 dictionary 不一致`);
      }
      const sampleCount = Number(result.sampleCount);
      if (!Number.isInteger(sampleCount) || sampleCount < Number(metric.minRequests)) errors.push(`${metric.metricId}: 样本数 ${result.sampleCount} 小于 minRequests ${metric.minRequests}`);
      if (metric.blocking !== false && !thresholdSatisfied(result, metric)) {
        errors.push(`${metric.metricId}: 未达到 dictionary 阈值 ${metric.alertThreshold}`);
      }
      if (metric.blocking === false && !Number.isFinite(Number(result.value))) {
        errors.push(`${metric.metricId}: 观测指标必须输出数值`);
      }
    }
  }
}

function thresholdSatisfied(result, metric) {
  if (result.passed !== true) return false;
  const value = Number(result.value);
  if (!Number.isFinite(value)) return false;
  const threshold = String(metric.alertThreshold ?? '').trim();
  if (threshold === 'observation_only') return metric.blocking === false;
  const match = threshold.match(/^([<>]=?)\s*(-?(?:\d+(?:\.\d+)?|\.\d+))\s*(?:ms)?$/i);
  if (!match) {
    const relative = threshold.match(/^baseline\s*\+\s*(\d+(?:\.\d+)?)%$/i);
    const baseline = Number(result.baselineValue);
    if (!relative || !Number.isFinite(baseline) || baseline < 0) return false;
    return value <= baseline * (1 + Number(relative[1]) / 100);
  }
  const bound = Number(match[2]);
  // alertThreshold expresses the failure side.  A value passes on the
  // opposite side: "< 0.98" means value >= 0.98; "> 2500" means <= 2500.
  return match[1].startsWith('>') ? value <= bound : value >= bound;
}

function runNegativeFixture(file) {
  const value = load(file, 'invalid-fixture');
  if (!value) {
    errors.push('负例 fixture 不存在');
    return;
  }
  const missing = [];
  if (!value.releaseId) missing.push('RELEASE_ID');
  const versions = value.versions ?? value.versionLineage;
  if (!versions || !versions.gameReleaseId || !versions.dataReleaseId || !versions.rulesetVersionId) missing.push('VERSION_LINEAGE');
  if (missing.length === 0) errors.push('负例 fixture 意外包含完整 release shape');
  else {
    expectedRejection = true;
    errors.push(...missing.map((item) => `INVALID_FIXTURE_${item}`));
  }
  checks.push({name: 'invalid-fixture', passed: missing.length > 0, reasonCode: missing.length > 0 ? 'MISSING_RELEASE_LINEAGE' : 'UNEXPECTED_VALID_FIXTURE'});
}

function load(file, label) {
  try {
    const value = readJson(file, label);
    loaded.set(label, value);
    return value;
  } catch (error) {
    errors.push(error.message);
    return null;
  }
}

function safeHash(file) {
  try { return sha256File(resolvePath(file)); } catch { return null; }
}

function collectHashes() {
  const keys = ['manifest', 'dictionary', 'metrics', 'run-manifest', 'run-hashes', 'metric-schema',
    'metric-queries', 'otel-schema', 'otel-queries', 'otel-trace-sample', 'security-report',
    'contract-report', 'readiness-evidence', 'feedback-report'];
  return keys.flatMap((key) => {
    if (!args[key] || args[key] === true || args[key] === false) return [];
    const hash = safeHash(String(args[key]));
    return hash ? [{input: String(args[key]).replaceAll('\\', '/'), sha256: hash}] : [];
  });
}
