#!/usr/bin/env node

/** Hash an already validated N3 evidence list into an immutable manifest. */
import fs from 'node:fs';
import path from 'node:path';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  readJson,
  readText,
  repoRelative,
  resolvePath,
  sha256File,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const runId = String(args['run-id'] ?? '').trim();
const filesArg = args.files ?? args.input;
const rootArg = args.root ?? 'artifacts/v1.1/test';
const output = args.output;
const reportPath = args.report;
const errors = [];
if (!/^[A-Za-z0-9._-]{3,128}$/.test(runId)) errors.push('--run-id 缺失或格式无效');
if (!filesArg) errors.push('缺少 --files');
if (!output) errors.push('缺少 --output');
if (!reportPath) errors.push('缺少 --report');

let root;
let fileList = [];
try {
  root = resolvePath(rootArg);
  repoRelative(root);
  if (filesArg) fileList = readText(filesArg, 'N3 evidence file list').split(/\r?\n/)
    .map((line) => line.trim()).filter(Boolean);
} catch (error) { errors.push(error.message); }

const seen = new Set();
const entries = [];
for (const value of fileList) {
  if (seen.has(value)) { errors.push(`重复证据路径: ${value}`); continue; }
  seen.add(value);
  if (/[*!?{}[\]]/.test(value) || pathLikeUnsafe(value, runId)) {
    errors.push(`证据路径不安全: ${value}`); continue;
  }
  let absolute;
  try { absolute = resolvePath(value, root); repoRelative(absolute); }
  catch (error) { errors.push(`路径越界: ${value}`); continue; }
  try {
    const stat = fs.lstatSync(absolute);
    if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('不是普通文件');
    const parsed = readRecords(absolute, value);
    if (parsed.errors.length) { errors.push(...parsed.errors); continue; }
    const schemas = [...new Set(parsed.records.map((item) => String(item.schemaVersion ?? '')) .filter(Boolean))];
    if (schemas.length > 1 || (schemas.length === 1 && schemas[0] !== 'v1.1')) {
      errors.push(`${value}: schemaVersion 必须统一为 v1.1`); continue;
    }
    entries.push({
      // Keep the same root-relative convention as the file-list producer;
      // consumers resolve this path against manifest.root exactly once.
      path: root ? path.relative(root, absolute).split(path.sep).join('/') : repoRelative(absolute),
      sha256: sha256File(absolute),
      bytes: stat.size,
      schemaVersion: schemas[0] ?? 'v1.1',
      runId,
      producer: parsed.records[0]?.producer ?? 'unknown'
    });
  } catch (error) { errors.push(`${value}: ${error.message}`); }
}

const manifest = {
  schemaVersion: 'v1.1',
  producer: 'create-n3-evidence-manifest',
  generatedAt: generatedAt(),
  commit: currentCommit(),
  runId: runId || null,
  root: root ? repoRelative(root) : null,
  fileCount: entries.length,
  files: entries,
  passed: errors.length === 0 && entries.length > 0,
  status: errors.length === 0 && entries.length > 0 ? 'ready' : 'blocked',
  errors,
  redacted: true
};
try {
  if (output) writeJson(output, manifest, {noClobber: args['no-clobber'] === true});
  if (reportPath) writeJson(reportPath, {
    schemaVersion: 'v1.1', producer: 'create-n3-evidence-manifest', generatedAt: generatedAt(),
    runId: runId || null, output: String(output ?? '').replaceAll('\\', '/'),
    fileCount: entries.length, passed: manifest.passed, status: manifest.status, errors, redacted: true
  }, {noClobber: args['no-clobber'] === true});
} catch (error) {
  manifest.passed = false; manifest.status = 'blocked'; manifest.errors.push(`输出失败: ${error.message}`); process.exitCode = 1;
}
if (!manifest.passed) process.exitCode = 1;
console.log(JSON.stringify(manifest, null, 2));

function pathLikeUnsafe(value, id) {
  return !value.startsWith(`${id}/n3-raw/`) || value.includes('..') || value.startsWith('/') || value.startsWith('\\');
}

function readRecords(file, label) {
  let text;
  try { text = fs.readFileSync(file, 'utf8'); } catch (error) { return {records: [], errors: [`${label}: 无法读取`]}; }
  const records = [];
  const errors = [];
  if (file.toLowerCase().endsWith('.json')) {
    try {
      const parsed = JSON.parse(text);
      if (Array.isArray(parsed)) records.push(...parsed);
      else if (parsed && typeof parsed === 'object') records.push(parsed);
      else errors.push(`${label}: JSON 顶层必须是对象/数组`);
    } catch (error) { errors.push(`${label}: JSON 解析失败`); }
  } else {
    for (const [index, line] of text.split(/\r?\n/).filter((line) => line.trim()).entries()) {
      try {
        const parsed = JSON.parse(line);
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('行必须是对象');
        records.push(parsed);
      } catch { errors.push(`${label}: JSONL 第 ${index + 1} 行无效`); }
    }
  }
  return {records, errors};
}
