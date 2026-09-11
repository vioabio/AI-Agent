#!/usr/bin/env node

/** Validate an explicit N3 raw evidence file list before hashing it. */
import fs from 'node:fs';
import path from 'node:path';
import {
  generatedAt,
  parseArgs,
  readText,
  repoRelative,
  resolvePath,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const input = args.input;
const rootArg = args.root ?? 'artifacts/v1.1/test';
const runId = String(args['run-id'] ?? '').trim();
const output = args.report;
const errors = [];
if (!input) errors.push('缺少 --input');
if (!output) errors.push('缺少 --report');
if (!/^[A-Za-z0-9._-]{3,128}$/.test(runId)) errors.push('--run-id 缺失或格式无效');

let root;
let lines = [];
try {
  root = resolvePath(rootArg);
  repoRelative(root);
  if (input) lines = readText(input, 'N3 evidence file list').split(/\r?\n/)
    .map((line) => line.trim()).filter(Boolean);
} catch (error) {
  errors.push(error.message);
}

const seen = new Set();
for (const [index, value] of lines.entries()) {
  const prefix = `files[${index + 1}]`;
  if (seen.has(value)) { errors.push(`${prefix}: 重复路径 ${value}`); continue; }
  seen.add(value);
  if (/[*!?{}[\]]/.test(value)) { errors.push(`${prefix}: 不允许 glob ${value}`); continue; }
  if (path.isAbsolute(value) || value.includes('..') || !value.startsWith(`${runId}/n3-raw/`)) {
    errors.push(`${prefix}: 必须是 ${runId}/n3-raw 下的仓库相对路径`);
    continue;
  }
  if (!/^[-A-Za-z0-9_./]+\.(?:json|jsonl)$/.test(value)) {
    errors.push(`${prefix}: 只允许 .json/.jsonl 普通证据文件`);
    continue;
  }
  let absolute;
  try { absolute = resolvePath(value, root); repoRelative(absolute); } catch (error) {
    errors.push(`${prefix}: 路径越界 ${error.message}`); continue;
  }
  try {
    const stat = fs.lstatSync(absolute);
    if (!stat.isFile() || stat.isSymbolicLink()) errors.push(`${prefix}: 不是普通文件`);
    else if (fs.statSync(absolute).size === 0) errors.push(`${prefix}: 文件为空`);
  } catch (error) { errors.push(`${prefix}: 文件不存在或不可读`); }
}

const result = {
  schemaVersion: 'v1.1',
  producer: 'validate-n3-evidence-file-list',
  generatedAt: generatedAt(),
  runId: runId || null,
  input: input ? String(input).replaceAll('\\', '/') : null,
  root: root ? repoRelative(root) : null,
  fileCount: lines.length,
  passed: errors.length === 0 && lines.length > 0,
  status: errors.length === 0 && lines.length > 0 ? 'passed' : 'blocked',
  errors,
  redacted: true
};
try { if (output) writeJson(output, result, {noClobber: args['no-clobber'] === true}); }
catch (error) { result.passed = false; result.status = 'blocked'; result.errors.push(`输出失败: ${error.message}`); process.exitCode = 1; }
if (!result.passed) process.exitCode = 1;
console.log(JSON.stringify(result, null, 2));
