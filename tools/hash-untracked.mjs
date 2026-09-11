#!/usr/bin/env node

/** Hash non-secret untracked files so a dirty candidate is reproducible. */
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
import {currentCommit, generatedAt, parseArgs, repoRelative, resolvePath, sha256File, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const root = resolvePath(args['repo-root'] ?? '.');
const output = args.output ?? 'artifacts/v1.1/baseline/untracked-hashes.json';
const errors = [];
const entries = [];
let raw = '';
try { raw = execFileSync('git', ['-C', root, 'ls-files', '--others', '--exclude-standard', '-z'], {encoding: 'utf8'}); }
catch (error) { errors.push(`git ls-files 失败: ${error.message}`); }
for (const item of raw.split('\0').filter(Boolean)) {
  const relative = item.replaceAll('\\', '/');
  if (isSecret(relative)) continue;
  const file = resolvePath(relative, root);
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) continue;
  try { entries.push({path: repoRelative(file, root), sha256: sha256File(file), bytes: fs.statSync(file).size}); }
  catch (error) { errors.push(`${relative}: hash 失败 (${error.message})`); }
}
entries.sort((a, b) => a.path.localeCompare(b.path, 'en'));
const report = {schemaVersion: 'v1.1', producer: 'hash-untracked', generatedAt: generatedAt(), gitCommit: currentCommit(), root: '.', entries, count: entries.length, excludedSecretPaths: true, passed: errors.length === 0, errors};
writeJson(output, report, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify({output, passed: report.passed, count: entries.length, errors}, null, 2));
if (errors.length > 0) process.exitCode = 1;
function isSecret(value) { return /(^|\/)(?:\.env(?:\.|$)|application-local\.(?:yml|yaml|properties)(?:\.|$)|mcp-servers\.json(?:\.|$))/i.test(value); }
