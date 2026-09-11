#!/usr/bin/env node

/**
 * Export the versioned, redacted OpenTelemetry contract used by v1.1 runs.
 * This command only writes static contract/query files; it never contacts an
 * OTLP, Prometheus, Alertmanager or Grafana endpoint.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  repoRelative,
  resolvePath,
  sha256File,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const knownOptions = new Set([
  'output-dir', 'output', 'queries', 'hash', 'report', 'schema-version',
  'owner', 'query-owner', 'no-clobber', 'overwrite'
]);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const schemaVersion = scalar(args['schema-version']) ?? 'v1.1';
if (schemaVersion !== 'v1.1') errors.push('--schema-version 必须为 v1.1');
const outputDir = scalar(args['output-dir']);
const output = scalar(args.output) ?? (outputDir ? path.join(outputDir, 'otel-schema.json') : 'artifacts/v1.1/eval/otel-schema.json');
const queriesOutput = scalar(args.queries) ?? (outputDir ? path.join(outputDir, 'otel-queries.json') : 'artifacts/v1.1/eval/otel-queries.json');
const hashOutput = scalar(args.hash) ?? (outputDir ? path.join(outputDir, 'otel-contract-hashes.json') : 'artifacts/v1.1/eval/otel-contract-hashes.json');
const reportOutput = scalar(args.report) ?? (outputDir ? path.join(outputDir, 'otel-contract-build.json') : 'artifacts/v1.1/eval/otel-contract-build.json');
const noClobber = args.overwrite !== true && args['no-clobber'] !== false;
const outputPaths = [output, queriesOutput, hashOutput, reportOutput].map((value) => {
  try {
    const absolute = resolvePath(value);
    repoRelative(absolute);
    return {value, absolute};
  } catch (error) {
    errors.push(`输出路径无效: ${safeText(error.message)}`);
    return null;
  }
}).filter(Boolean);
if (new Set(outputPaths.map((item) => item.absolute.toLowerCase())).size !== outputPaths.length) errors.push('OTel 输出路径不能相互覆盖');
const outputPathInvalid = outputPaths.length !== 4
  || new Set(outputPaths.map((item) => item.absolute.toLowerCase())).size !== outputPaths.length;

const commit = currentCommit();
const owner = scalar(args.owner) ?? 'platform';
const queryOwner = scalar(args['query-owner']) ?? owner;
if (!/^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$/.test(owner)) errors.push('owner 必须是稳定标识');
if (!/^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$/.test(queryOwner)) errors.push('query-owner 必须是稳定标识');
const redaction = {
  sensitiveValuesPersisted: false,
  forbiddenAttributeNames: ['api_key', 'authorization', 'password', 'token', 'secret', 'prompt_raw', 'tool_input_raw'],
  forbiddenValuePatterns: [
    'bearer\\s+',
    '(api[-_ ]?key|password|secret|token)\\s*[:=]'
  ],
  rules: ['属性只允许摘要、digest 和版本键', '禁止记录密钥、原始 prompt、原始 tool input']
};
const spans = [
  {name: 'vio.agent.run', requiredAttributes: ['run_id', 'trace_id', 'status', 'contract_version', 'scenario', 'model_version', 'prompt_version', 'data_release_id', 'knowledge_release_id', 'index_release_id']},
  {name: 'vio.agent.model', requiredAttributes: ['run_id', 'trace_id', 'model_version', 'prompt_version', 'status', 'input_tokens', 'output_tokens']},
  {name: 'vio.agent.retrieval', requiredAttributes: ['run_id', 'trace_id', 'knowledge_release_id', 'index_release_id', 'status', 'result_count']},
  {name: 'vio.agent.tool', requiredAttributes: ['run_id', 'trace_id', 'tool_name', 'tool_version', 'status', 'parameter_digest']},
  {name: 'vio.agent.http', requiredAttributes: ['run_id', 'trace_id', 'http.route', 'http.status_code', 'status']}
];
const metricQueries = [
  {queryId: 'run-ttft-p50', signal: 'span', spanName: 'vio.agent.run', expression: 'p50(first_answer_delta - run_started)', owner: queryOwner, queryOwner},
  {queryId: 'run-ttft-p95', signal: 'span', spanName: 'vio.agent.run', expression: 'p95(first_answer_delta - run_started)', owner: queryOwner, queryOwner},
  {queryId: 'run-duration-p50', signal: 'span', spanName: 'vio.agent.run', expression: 'p50(run_terminal - run_started)', owner: queryOwner, queryOwner},
  {queryId: 'run-duration-p95', signal: 'span', spanName: 'vio.agent.run', expression: 'p95(run_terminal - run_started)', owner: queryOwner, queryOwner},
  {queryId: 'tool-error-rate', signal: 'span', spanName: 'vio.agent.tool', expression: 'errors / total', owner: queryOwner, queryOwner},
  {queryId: 'trace-to-run-join', signal: 'span', spanName: '*', expression: 'group_by(trace_id,run_id)', owner: queryOwner, queryOwner}
];
const generated = generatedAt();
const contract = {
  schemaVersion,
  producer: 'export-otel-contract',
  generatedAt: generated,
  commit,
  gitCommit: commit,
  owner,
  queryOwner,
  status: errors.length === 0 ? 'ready' : 'blocked',
  sampling: {strategy: 'parent-based', productionRatio: 0.1, errorRatio: 1.0},
  correlation: ['traceId', 'runId', 'answerId', 'evaluationRunId'],
  traceToRun: {traceAttribute: 'trace_id', runAttribute: 'run_id', evaluationAttribute: 'evaluation_run_id', required: true},
  spans,
  redaction,
  errors: unique(errors)
};
const queries = {
  schemaVersion,
  producer: 'export-otel-contract',
  generatedAt: generated,
  commit,
  gitCommit: commit,
  owner,
  queryOwner,
  status: errors.length === 0 ? 'ready' : 'blocked',
  queries: metricQueries,
  redacted: true,
  redaction: {sensitiveValuesPersisted: false, rules: redaction.rules},
  errors: unique(errors)
};
const report = {
  schemaVersion: 'v1.1',
  producer: 'export-otel-contract',
  generatedAt: generated,
  commit,
  gitCommit: commit,
  output: repoPath(output),
  queriesOutput: repoPath(queriesOutput),
  hashOutput: repoPath(hashOutput),
  passed: errors.length === 0,
  status: errors.length === 0 ? 'ready' : 'blocked',
  redacted: true,
  errors: unique(errors)
};

let noClobberCollision = false;
if (noClobber) for (const item of outputPaths) if (fs.existsSync(item.absolute)) {
  noClobberCollision = true;
  errors.push(`输出已存在（默认 no-clobber）: ${item.value}`);
}
contract.errors = unique(errors);
queries.errors = unique(errors);
report.errors = unique(errors);
if (errors.length > 0) { contract.status = 'blocked'; queries.status = 'blocked'; report.status = 'blocked'; report.passed = false; }

try {
  if (outputPathInvalid) throw new Error('invalid output path; no files were written');
  if (noClobberCollision) throw new Error('no-clobber collision; no files were written');
  writeJson(output, contract, {noClobber: false});
  writeJson(queriesOutput, queries, {noClobber: false});
  const files = [
    {file: repoPath(output), sha256: sha256File(resolvePath(output)), algorithm: 'SHA-256'},
    {file: repoPath(queriesOutput), sha256: sha256File(resolvePath(queriesOutput)), algorithm: 'SHA-256'}
  ];
  writeJson(hashOutput, {schemaVersion: 'v1.1', producer: 'export-otel-contract', generatedAt: generated, commit, files}, {noClobber: false});
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

function scalar(value) { return value === undefined || value === null || value === true || value === false ? null : String(value).trim() || null; }
function repoPath(value) { try { return repoRelative(resolvePath(value)); } catch { return String(value ?? '').replaceAll('\\', '/'); } }
function safeText(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 500); }
function unique(values) { return [...new Set(values.map((value) => String(value)))]; }
