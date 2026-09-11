#!/usr/bin/env node

/** Promote an evaluation manifest only when every structural and size gate passes. */
import fs from 'node:fs';
import {generatedAt, parseArgs, placeholder, readJson, resolvePath, sha256File, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const manifestPath = args.manifest ?? 'evaluation/v1.1/manifest.json';
const validationPath = args.validation ?? 'artifacts/v1.1/baseline/evaluation-v1.1-validation.json';
const datasetPath = args['dataset-manifest'] ?? 'evaluation/v1.1/dataset-manifest.json';
const dictionaryPath = args.dictionary ?? 'evaluation/v1.1/metric-dictionary.json';
const reviewer = args.reviewer ?? process.env.VIO_EVAL_REVIEWER;
const output = args.output ?? 'evaluation/v1.1/manifest.ready.json';
const reportOutput = args.report ?? 'artifacts/v1.1/baseline/evaluation-ready-review.json';
// Evaluation manifests and review reports are immutable evidence.  A caller
// must opt into a fresh output path (rather than silently overwriting an old
// review); `--no-clobber` remains accepted for readability in runbooks.
const noClobber = args.overwrite !== true;
const errors = [];
const manifest = readJson(manifestPath, 'evaluation manifest');
const validation = readJson(validationPath, 'evaluation validation');
const dataset = readJson(datasetPath, 'dataset manifest');
const dictionary = readJson(dictionaryPath, 'metric dictionary');
if (manifest.status !== 'draft') errors.push(`只允许从 draft 进入 ready，当前为 ${manifest.status}`);
if (validation.passed !== true) errors.push('结构校验未通过');
if (dataset.status !== 'draft') errors.push(`dataset manifest 状态无效: ${dataset.status}`);
if (placeholder(reviewer)) errors.push('缺少 reviewer');
else if (!/^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$/.test(String(reviewer).trim())) {
  errors.push('reviewer 只能是可审计的主体标识，禁止空白/路径/控制字符');
}
for (const suite of manifest.suites ?? []) {
  const actual = dataset.suites?.find((item) => item.suiteId === suite.suiteId);
  if (!actual || actual.count < Number(suite.minimumCases ?? 0)) errors.push(`${suite.suiteId}: 样本规模不足，不能 ready`);
}
if (dictionary.schemaVersion !== 'v1.1') errors.push('dictionary 未迁移到 v1.1');
const passed = errors.length === 0;
const report = {schemaVersion: 'v1.1', producer: 'mark-evaluation-ready', generatedAt: generatedAt(), reviewer: placeholder(reviewer) ? null : String(reviewer).trim(), passed, errors, manifestSha256: sha256File(resolvePath(manifestPath)), datasetSha256: sha256File(resolvePath(datasetPath)), dictionarySha256: sha256File(resolvePath(dictionaryPath))};
writeJson(reportOutput, report, {noClobber});
if (passed) {
  const ready = {...manifest, status: 'ready', reviewer: String(reviewer).trim(), reviewedAt: generatedAt(), datasetHash: report.datasetSha256, dictionaryHash: report.dictionarySha256};
  writeJson(output, ready, {noClobber});
} else {
  const blocked = {...manifest, status: 'blocked', blockedAt: generatedAt(), blockedReasons: errors};
  // A blocked copy is diagnostic only; never overwrite the draft manifest.
  const blockedOutput = `${output}.blocked.json`;
  writeJson(blockedOutput, blocked, {noClobber});
}
console.log(JSON.stringify({passed, output: passed ? output : `${output}.blocked.json`, report: reportOutput, errors}, null, 2));
if (!passed) process.exitCode = 1;
