#!/usr/bin/env node
import fs from 'node:fs';
import {generatedAt, parseArgs, readJson, repoRelative, resolvePath, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const manifestPath = args.manifest ?? 'evaluation/v1.1/manifest.json';
const dictionaryPath = args.dictionary ?? 'evaluation/v1.1/metric-dictionary.json';
const output = args.report ?? 'artifacts/v1.1/baseline/evaluation-v1.1-validation.json';
const manifest = readJson(manifestPath, 'v1.1 evaluation manifest');
const dictionary = readJson(dictionaryPath, 'v1.1 metric dictionary');
const errors = [];
const warnings = [];
const suites = [];
const seen = new Set();
if (manifest.schemaVersion !== 'v1.1' || manifest.suiteVersion !== 'v1.1') errors.push('manifest schemaVersion/suiteVersion 必须为 v1.1');
if (!['draft', 'ready', 'blocked'].includes(manifest.status)) errors.push(`manifest.status 无效: ${manifest.status}`);
if (dictionary.schemaVersion !== 'v1.1' || dictionary.dictionaryVersion !== 'v1.1') errors.push('dictionary schemaVersion/dictionaryVersion 必须为 v1.1');
const expectedSuiteIds = ['fact-v1', 'calculation-v1', 'legality-v1', 'rag-v1', 'no-answer-v1', 'agent-tool-v1', 'security-redteam-v1'];
const manifestSuiteIds = (manifest.suites ?? []).map((suite) => suite.suiteId);
if (manifestSuiteIds.length !== expectedSuiteIds.length || expectedSuiteIds.some((id) => !manifestSuiteIds.includes(id))) errors.push('manifest 必须包含完整的 7 个 v1.1 suite');
if (!Array.isArray(dictionary.metrics) || dictionary.metrics.length === 0) errors.push('dictionary.metrics 不能为空');
for (const suite of manifest.suites ?? []) {
  const suitePath = String(suite.file ?? '').replaceAll('\\', '/');
  let file = null;
  try {
    if (!suitePath.startsWith('datasets/') || suitePath.includes('..') || suitePath.startsWith('/')) throw new Error('路径必须位于 evaluation/v1.1/datasets 内');
    file = resolvePath(`evaluation/v1.1/${suitePath}`);
    repoRelative(file);
  } catch (error) { errors.push(`${suite.suiteId}: ${error.message}`); }
  const result = {suiteId: suite.suiteId, file: suite.file, caseCount: 0, minimumCases: suite.minimumCases, passed: true};
  if (!file || !fs.existsSync(file)) { errors.push(`${suite.suiteId}: 数据文件不存在`); result.passed = false; suites.push(result); continue; }
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean);
  for (let index = 0; index < lines.length; index += 1) {
    try {
      const item = JSON.parse(lines[index]);
      result.caseCount += 1;
      if (!item.caseId || seen.has(item.caseId)) { errors.push(`${suite.suiteId}:${index + 1}: caseId 缺失或重复`); result.passed = false; }
      else seen.add(item.caseId);
      const versions = item.versions ?? {};
      if (!versions.gameReleaseId || !versions.dataReleaseId || !versions.rulesetVersionId) { errors.push(`${suite.suiteId}:${index + 1}: 缺少 game/data/ruleset 版本血缘`); result.passed = false; }
    } catch (error) { errors.push(`${suite.suiteId}:${index + 1}: JSONL 解析失败 (${error.message})`); result.passed = false; }
  }
  if (result.caseCount < Number(suite.minimumCases ?? 0)) warnings.push(`${suite.suiteId}: ${result.caseCount}/${suite.minimumCases}，样本不足`);
  suites.push(result);
}
for (const metric of dictionary.metrics ?? []) {
  for (const field of ['metricId', 'formula', 'denominator', 'query', 'window', 'minRequests', 'thresholdVersion', 'severity', 'owner']) {
    if (!(field in metric)) errors.push(`指标 ${metric.metricId ?? 'unknown'} 缺少 ${field}`);
  }
}
validateMetricSuiteAlignment(manifest, dictionary);
const report = {schemaVersion: 'v1.1', producer: 'validate-evaluation-v1.1', generatedAt: generatedAt(), manifest: manifestPath, dictionary: dictionaryPath, sourceStatus: manifest.status, passed: errors.length === 0, structurallyValid: errors.length === 0, errors, warnings, suites};
writeJson(output, report, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify(report, null, 2));
if (errors.length > 0) process.exitCode = 1;

function validateMetricSuiteAlignment(manifestValue, dictionaryValue) {
  const suiteMinimums = new Map((manifestValue?.suites ?? []).map((suite) => [
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
  for (const metric of dictionaryValue?.metrics ?? []) {
    const suiteId = metricSuites.get(String(metric?.metricId ?? ''));
    if (!suiteId) continue;
    const suiteMinimum = suiteMinimums.get(suiteId);
    const metricMinimum = Number(metric?.minRequests ?? metric?.minimumSamples ?? 0);
    if (!Number.isInteger(suiteMinimum) || suiteMinimum <= 0 || metricMinimum !== suiteMinimum) {
      errors.push(`${metric.metricId}: minRequests=${metricMinimum} 必须与 ${suiteId}.minimumCases=${suiteMinimum ?? 'missing'} 一致`);
    }
  }
}
