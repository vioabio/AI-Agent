#!/usr/bin/env node

/** Produce immutable per-suite/per-sample hashes for a v1.1 evaluation set. */
import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import {currentCommit, generatedAt, parseArgs, readJson, resolvePath, sha256File, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const manifestPath = args.manifest ?? 'evaluation/v1.1/manifest.json';
const output = args.output ?? 'evaluation/v1.1/dataset-manifest.json';
const reportOutput = args.report;
const manifest = readJson(manifestPath, 'v1.1 evaluation manifest');
// Suite paths are relative to the manifest, not to the repository root.  This
// matters for v1.1, whose canonical datasets live below evaluation/v1.1/;
// resolving through `evaluation/${suite.file}` accidentally hashed the v1.0
// scaffold and could make the dataset manifest disagree with the validator.
const manifestDirectory = path.dirname(resolvePath(manifestPath));
const suites = [];
const errors = [];
for (const suite of manifest.suites ?? []) {
  const file = resolvePath(suite.file, manifestDirectory);
  if (!fs.existsSync(file)) { errors.push(`${suite.suiteId}: 文件不存在`); continue; }
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean);
  const samples = [];
  for (let index = 0; index < lines.length; index += 1) {
    const raw = `${lines[index]}\n`;
    try { samples.push({line: index + 1, caseId: JSON.parse(lines[index]).caseId ?? null, sha256: crypto.createHash('sha256').update(raw).digest('hex')}); }
    catch { errors.push(`${suite.suiteId}:${index + 1}: JSONL 解析失败`); }
  }
  suites.push({suiteId: suite.suiteId, kind: suite.kind, path: suite.file, count: samples.length, minimumCases: suite.minimumCases, sha256: sha256File(file), samples, license: '待来源审核', redacted: true, status: suite.status ?? 'draft'});
}
const payload = {schemaVersion: 'v1.1', producer: 'create-evaluation-dataset-manifest', generatedAt: generatedAt(), gitCommit: currentCommit(), sourceManifest: manifestPath, sourceManifestSha256: sha256File(resolvePath(manifestPath)), status: errors.length === 0 ? 'draft' : 'blocked', suites, errors};
writeJson(output, payload, {noClobber: args['no-clobber'] === true});
const report = {schemaVersion: 'v1.1', producer: payload.producer, generatedAt: generatedAt(), output, passed: errors.length === 0, suiteCount: suites.length, errors};
if (reportOutput) writeJson(reportOutput, report, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify(report, null, 2));
if (errors.length > 0) process.exitCode = 1;
