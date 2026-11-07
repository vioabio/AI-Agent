#!/usr/bin/env node

/**
 * M6 评测契约门禁。
 *
 * ci 模式只验证数据契约、唯一 caseId、确定性样例和安全期望值；
 * release 模式另外要求完整黄金集、实测报告和阈值通过。这样不会把
 * 当前脱敏样例误报成正式质量成绩，也不会用 continue-on-error 吞掉失败。
 */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifestPath = path.join(root, 'evaluation', 'manifest.json');
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const args = parseArgs(process.argv.slice(2));
const mode = args.mode ?? 'ci';
const reportPath = args.report
  ? path.resolve(process.cwd(), args.report)
  : path.join(root, 'artifacts', 'v1.0', 'eval', `quality-gate-${mode}.json`);

const errors = [];
const warnings = [];
const suites = [];
const seenCaseIds = new Set();

if (!['ci', 'release'].includes(mode)) {
  errors.push(`未知模式: ${mode}，仅支持 ci 或 release`);
}
if (!Array.isArray(manifest.suites) || manifest.suites.length === 0) {
  errors.push('manifest.suites 不能为空');
}

for (const suite of manifest.suites ?? []) {
  const filePath = path.join(root, 'evaluation', suite.file);
  const result = {suiteId: suite.suiteId, kind: suite.kind, file: suite.file, caseCount: 0};
  if (!fs.existsSync(filePath)) {
    errors.push(`${suite.suiteId}: 文件不存在 ${suite.file}`);
    suites.push(result);
    continue;
  }
  const lines = fs.readFileSync(filePath, 'utf8').split(/\r?\n/).filter(Boolean);
  for (let index = 0; index < lines.length; index += 1) {
    let item;
    try {
      item = JSON.parse(lines[index]);
    } catch (error) {
      errors.push(`${suite.suiteId}:${index + 1}: JSONL 无法解析 (${error.message})`);
      continue;
    }
    result.caseCount += 1;
    validateCase(suite, item, index + 1);
  }
  if (result.caseCount < suite.minimumCases) {
    const message = `${suite.suiteId}: ${result.caseCount}/${suite.minimumCases} 条，样本不足`;
    if (mode === 'release') errors.push(message);
    else warnings.push(message);
  }
  if (suite.status !== 'ready') {
    const message = `${suite.suiteId}: status=${suite.status ?? 'missing'}，尚未标记为 ready`;
    if (mode === 'release') errors.push(message);
    else warnings.push(message);
  }
  suites.push(result);
}

if (mode === 'release') {
  if (manifest.status !== 'ready') {
    errors.push(`manifest.status=${manifest.status ?? 'missing'}，发布门禁要求 ready`);
  }
  const metricReport = args.metrics
    ? path.resolve(process.cwd(), args.metrics)
    : path.join(root, 'artifacts', 'v1.0', 'eval', 'release-report.json');
  if (!fs.existsSync(metricReport)) {
    errors.push(`缺少不可变评测报告: ${metricReport}`);
  } else {
    validateMetricReport(metricReport);
  }
}

const report = {
  schemaVersion: '1',
  generatedAt: new Date().toISOString(),
  mode,
  suiteVersion: manifest.suiteVersion,
  manifestStatus: manifest.status,
  suites,
  passed: errors.length === 0,
  errors,
  warnings,
  note: mode === 'ci'
    ? 'ci 模式仅证明契约和脱敏样例可运行，不代表生产质量门槛。'
    : 'release 模式通过后才允许候选发布。'
};

fs.mkdirSync(path.dirname(reportPath), {recursive: true});
fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
console.log(JSON.stringify({mode, passed: report.passed, report: reportPath, errors, warnings}, null, 2));
if (errors.length > 0) process.exitCode = 1;

function validateCase(suite, item, lineNumber) {
  const prefix = `${suite.suiteId}:${lineNumber}`;
  for (const field of ['caseId', 'suite', 'input', 'expected', 'assertion', 'versions']) {
    if (!(field in item)) errors.push(`${prefix}: 缺少字段 ${field}`);
  }
  if (item.suite !== suite.kind) {
    errors.push(`${prefix}: suite=${item.suite} 与 kind=${suite.kind} 不一致`);
  }
  if (typeof item.caseId !== 'string' || item.caseId.length === 0) {
    errors.push(`${prefix}: caseId 必须是非空字符串`);
  } else if (seenCaseIds.has(item.caseId)) {
    errors.push(`${prefix}: caseId 重复 ${item.caseId}`);
  } else {
    seenCaseIds.add(item.caseId);
  }
  if (!item.versions?.gameReleaseId || !item.versions?.dataReleaseId) {
    errors.push(`${prefix}: 必须携带 gameReleaseId 和 dataReleaseId`);
  }
  if (suite.kind === 'security_redteam' && item.expected?.blocked !== true) {
    errors.push(`${prefix}: 红队样例 expected.blocked 必须为 true`);
  }
  if (suite.assertion === 'deterministic' && (item.expected === null || item.expected === undefined)) {
    errors.push(`${prefix}: deterministic 样例必须有 expected`);
  }
}

function validateMetricReport(filePath) {
  let report;
  try {
    report = JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch (error) {
    errors.push(`评测报告无法解析: ${error.message}`);
    return;
  }
  for (const field of ['evaluationRunId', 'codeCommit', 'modelVersion', 'promptVersion',
    'dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId', 'sampleCount', 'results']) {
    if (!(field in report)) errors.push(`评测报告缺少字段 ${field}`);
  }
  if (!Array.isArray(report.results)) return;
  const values = new Map(report.results.map((item) => [item.metricId, item]));
  for (const [metricId, threshold] of Object.entries(manifest.releaseThresholds ?? {})) {
    const item = values.get(metricId);
    if (!item) {
      errors.push(`评测报告缺少指标 ${metricId}`);
      continue;
    }
    if (!Number.isFinite(item.value) || !Number.isInteger(item.sampleCount)) {
      errors.push(`${metricId}: value/sampleCount 格式错误`);
      continue;
    }
    if (item.sampleCount <= 0) errors.push(`${metricId}: sampleCount 必须大于 0`);
    const isLatency = metricId.endsWith('_ms');
    const passed = isLatency ? item.value <= threshold : item.value >= threshold;
    if (!passed || item.passed !== true) {
      errors.push(`${metricId}: ${item.value} 未达到阈值 ${threshold}`);
    }
  }
  if (report.securityRedTeamFailures !== 0) {
    errors.push('securityRedTeamFailures 必须为 0');
  }
}

function parseArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith('--')) continue;
    const [key, inline] = token.slice(2).split('=', 2);
    result[key] = inline ?? argv[++index];
  }
  return result;
}
