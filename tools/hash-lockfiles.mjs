#!/usr/bin/env node

/** Hash dependency lockfiles as a separate, reproducible N0 input. */
import fs from 'node:fs';
import {currentCommit, generatedAt, parseArgs, repoRelative, resolvePath, sha256File, writeAtomic, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const output = args.output ?? 'artifacts/v1.1/baseline/lockfile-input-hashes.json';
const textOutput = args['text-output'] ?? 'artifacts/v1.1/baseline/lockfile-hash.txt';
const candidates = args.input ? (Array.isArray(args.input) ? args.input : [args.input]) : ['pom.xml', 'vio-ai-agent-frontend/package-lock.json'];
const errors = [];
const files = [];
for (const candidate of candidates.flatMap((item) => String(item).split(','))) {
  const file = resolvePath(candidate.trim());
  if (!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) { errors.push(`lockfile 不存在: ${candidate}`); continue; }
  files.push({path: repoRelative(file), sha256: sha256File(file), bytes: fs.statSync(file).size});
}
files.sort((a, b) => a.path.localeCompare(b.path, 'en'));
const canonical = `${JSON.stringify(files, null, 2)}\n`;
const aggregate = (await import('node:crypto')).createHash('sha256').update(canonical).digest('hex');
const report = {schemaVersion: 'v1.1', producer: 'hash-lockfiles', generatedAt: generatedAt(), gitCommit: currentCommit(), files, aggregateSha256: aggregate, passed: errors.length === 0, errors};
if (errors.length === 0) {
  writeJson(output, report, {noClobber: args['no-clobber'] === true});
  writeAtomic(textOutput, `${aggregate}\n`, {noClobber: args['no-clobber'] === true});
}
console.log(JSON.stringify({output, textOutput, passed: report.passed, aggregateSha256: aggregate, errors}, null, 2));
if (errors.length > 0) process.exitCode = 1;
