#!/usr/bin/env node

/**
 * Build an explicit, repository-relative list of N3 raw evidence files.
 *
 * This producer intentionally does not accept globs.  It only walks the
 * caller-supplied raw directory, rejects symlinks/paths outside the repo, and
 * writes a sorted list that a later manifest producer can hash immutably.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  repoRelative,
  resolvePath,
  writeJson,
  writeAtomic
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const inputDirArg = args['input-dir'];
const pathRootArg = args['path-root'] ?? 'artifacts/v1.1/test';
const runId = String(args['run-id'] ?? '').trim();
const allowPattern = String(args['allow-regex'] ?? '').trim();
const excludePattern = String(args['exclude-regex'] ?? '').trim();
const output = args.output;
const report = args.report;
const noClobber = args['no-clobber'] === true;

for (const [name, value] of [['input-dir', inputDirArg], ['output', output], ['report', report]]) {
  if (!value) errors.push(`缺少 --${name}`);
}
if (!/^[A-Za-z0-9._-]{3,128}$/.test(runId)) errors.push('--run-id 缺失或格式无效');
if (!allowPattern) errors.push('缺少 --allow-regex，必须显式声明允许的文件集合');
if (inputDirArg && /[*?{}[\]]/.test(String(inputDirArg))) errors.push('--input-dir 不接受 glob');
if (allowPattern && !compileRegex(allowPattern, errors, '--allow-regex')) { /* reported */ }
if (excludePattern && !compileRegex(excludePattern, errors, '--exclude-regex')) { /* reported */ }

const files = [];
let inputDir;
let root;
try {
  root = resolvePath(pathRootArg);
  repoRelative(root);
  if (inputDirArg) {
    inputDir = resolvePath(inputDirArg);
    const relativeToRoot = path.relative(root, inputDir).split(path.sep).join('/');
    if (relativeToRoot !== `${runId}/n3-raw`) {
      errors.push('--input-dir 必须是 <path-root>/<run-id>/n3-raw');
    }
    walk(inputDir, inputDir);
  }
} catch (error) {
  errors.push(`路径无效: ${error.message}`);
}

// The list is intentionally relative to --path-root (the validator receives
// the same root).  Keeping the root out of each line makes the run-id boundary
// explicit and avoids accidentally accepting a path from another evidence run.
const relativeFiles = files.map((file) => path.relative(root, file).split(path.sep).join('/')).sort();
const result = {
  schemaVersion: 'v1.1',
  producer: 'create-n3-evidence-file-list',
  generatedAt: generatedAt(),
  commit: currentCommit(),
  runId: runId || null,
  inputDir: inputDir && root ? path.relative(root, inputDir).split(path.sep).join('/') : null,
  fileCount: relativeFiles.length,
  files: relativeFiles,
  passed: errors.length === 0 && relativeFiles.length > 0,
  status: errors.length === 0 && relativeFiles.length > 0 ? 'ready' : 'blocked',
  errors,
  redacted: true
};

try {
  if (output) writeAtomic(output, relativeFiles.length ? `${relativeFiles.join('\n')}\n` : '', {noClobber});
  if (report) writeJson(report, result, {noClobber});
} catch (error) {
  result.passed = false;
  result.status = 'blocked';
  result.errors.push(`输出失败: ${error.message}`);
  process.exitCode = 1;
}
if (!result.passed) process.exitCode = 1;
console.log(JSON.stringify(result, null, 2));

function compileRegex(value, target, name) {
  try { return new RegExp(value); }
  catch (error) { target.push(`${name} 无效: ${error.message}`); return null; }
}

function walk(directory, base) {
  if (!directory || !fs.existsSync(directory)) {
    errors.push(`目录不存在: ${inputDirArg}`);
    return;
  }
  let stat;
  try { stat = fs.lstatSync(directory); } catch (error) { errors.push(`无法读取目录: ${error.message}`); return; }
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    errors.push('input-dir 必须是普通目录且不能是符号链接');
    return;
  }
  for (const entry of fs.readdirSync(directory, {withFileTypes: true}).sort((a, b) => a.name.localeCompare(b.name))) {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) { walk(full, base); continue; }
    if (!entry.isFile() || entry.isSymbolicLink()) {
      errors.push(`只允许普通文件: ${repoRelative(full)}`);
      continue;
    }
    const relative = repoRelative(full);
    if (excludePattern && new RegExp(excludePattern).test(relative)) continue;
    if (!new RegExp(allowPattern).test(relative)) continue;
    files.push(full);
  }
}
