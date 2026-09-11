#!/usr/bin/env node

/** Create an immutable v1.1 evaluation-run manifest after fail-closed checks. */
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
const suiteManifestPath = args.manifest ?? 'evaluation/v1.1/manifest.ready.json';
const datasetManifestPath = args['dataset-manifest'] ?? 'evaluation/v1.1/dataset-manifest.json';
const output = args.output ?? 'artifacts/v1.1/eval/evaluation-run-manifest.json';
const reportOutput = args.report ?? 'artifacts/v1.1/eval/evaluation-run-manifest-build.json';
const requiredArgs = {
  runId: args['run-id'],
  codeCommit: args['code-commit'] ?? currentCommit(),
  modelVersion: args['model-version'],
  promptVersion: args['prompt-version'],
  dataReleaseId: args['data-release-id'],
  knowledgeReleaseId: args['knowledge-release-id'],
  indexReleaseId: args['index-release-id']
};
const errors = [];
let suiteManifest;
let datasetManifest;
try { suiteManifest = readJson(suiteManifestPath, 'evaluation suite manifest'); }
catch (error) { errors.push(error.message); }
try { datasetManifest = readJson(datasetManifestPath, 'evaluation dataset manifest'); }
catch (error) { errors.push(error.message); }
for (const candidate of [output, reportOutput]) {
  try { repoRelative(resolvePath(candidate)); } catch (error) { errors.push(`输出路径无效: ${error.message}`); }
}
if (resolvePath(output) === resolvePath(suiteManifestPath) || resolvePath(output) === resolvePath(datasetManifestPath)) {
  errors.push('run manifest 不能覆盖输入 manifest');
}
if (suiteManifest?.schemaVersion !== 'v1.1') errors.push('suite manifest schemaVersion 必须为 v1.1');
if (suiteManifest?.status !== 'ready') errors.push(`suite manifest 必须为 ready，当前为 ${suiteManifest?.status ?? 'missing'}`);
if (datasetManifest?.schemaVersion !== 'v1.1') errors.push('dataset manifest schemaVersion 必须为 v1.1');
if (datasetManifest?.status !== 'ready') errors.push(`dataset manifest 必须为 ready，当前为 ${datasetManifest?.status ?? 'missing'}`);
for (const [name, value] of Object.entries(requiredArgs)) {
  if (placeholder(value) || !String(value).trim()) errors.push(`${name} 缺失或仍是占位符`);
  if (name === 'runId' && value && !/^[A-Za-z0-9._-]{3,128}$/.test(String(value))) errors.push('runId 格式无效');
}
const suiteIds = (suiteManifest?.suites ?? []).map((suite) => suite.suiteId);
const datasetIds = (datasetManifest?.suites ?? []).map((suite) => suite.suiteId);
if (suiteIds.length !== 7 || new Set(suiteIds).size !== suiteIds.length) errors.push('suite manifest 必须包含唯一的 7 个 suite');
if (suiteIds.some((id) => !datasetIds.includes(id))) errors.push('dataset manifest 未覆盖全部 suite');
const passed = errors.length === 0;
const payload = {
  schemaVersion: 'v1.1',
  producer: 'create-evaluation-run-manifest',
  generatedAt: generatedAt(),
  status: passed ? 'ready' : 'blocked',
  evaluationRunId: requiredArgs.runId ?? null,
  codeCommit: requiredArgs.codeCommit ?? null,
  modelVersion: requiredArgs.modelVersion ?? null,
  promptVersion: requiredArgs.promptVersion ?? null,
  dataReleaseId: requiredArgs.dataReleaseId ?? null,
  knowledgeReleaseId: requiredArgs.knowledgeReleaseId ?? null,
  indexReleaseId: requiredArgs.indexReleaseId ?? null,
  suiteManifest: String(suiteManifestPath).replaceAll('\\', '/'),
  suiteManifestSha256: safeHash(suiteManifestPath),
  datasetManifest: String(datasetManifestPath).replaceAll('\\', '/'),
  datasetManifestSha256: safeHash(datasetManifestPath),
  suites: (suiteManifest?.suites ?? []).map((suite) => ({
    suiteId: suite.suiteId,
    kind: suite.kind,
    file: suite.file,
    minimumCases: suite.minimumCases,
    assertion: suite.assertion,
    status: suite.status
  })),
  redacted: true,
  errors
};
const report = {
  schemaVersion: 'v1.1',
  producer: 'create-evaluation-run-manifest',
  generatedAt: generatedAt(),
  output: String(output).replaceAll('\\', '/'),
  passed,
  status: passed ? 'ready' : 'blocked',
  errors
};
try {
  writeJson(output, payload, {noClobber: args['no-clobber'] === true});
  writeJson(reportOutput, report, {noClobber: args['no-clobber'] === true});
} catch (error) {
  report.passed = false;
  report.status = 'blocked';
  report.errors.push(`输出失败: ${error.message}`);
  process.exitCode = 1;
}
console.log(JSON.stringify(report, null, 2));
if (!passed) process.exitCode = 1;

function safeHash(file) {
  try { return sha256File(resolvePath(file)); } catch { return null; }
}
