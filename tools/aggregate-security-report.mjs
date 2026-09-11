#!/usr/bin/env node

/** Aggregate only redacted pass/fail fields from the N3 evidence index. */
import fs from 'node:fs';
import path from 'node:path';
import {
  generatedAt,
  parseArgs,
  readJson,
  repoRelative,
  resolvePath,
  sha256File,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const inputsPath = args['inputs-file'] ?? args.input;
const output = args.output;
const errors = [];
if (!inputsPath) errors.push('缺少 --inputs-file');
if (!output) errors.push('缺少 --output');
let index = null;
try { if (inputsPath) index = readJson(inputsPath, 'N3 evidence index'); }
catch (error) { errors.push(error.message); }
if (index) {
  if (index.schemaVersion !== 'v1.1' || index.status !== 'ready') errors.push('inputs-file 必须是 v1.1 ready index');
  if (!index.runId) errors.push('inputs-file 缺少 runId');
  if (!Array.isArray(index.files) || index.files.length === 0) errors.push('inputs-file.files 不能为空');
}

const categories = new Map();
let totalChecks = 0;
let failedChecks = 0;
let unknownChecks = 0;
for (const item of index?.files ?? []) {
  const filePath = item?.path;
  if (!filePath || item.runId !== index.runId || !/^[a-f0-9]{64}$/i.test(String(item.sha256 ?? ''))) {
    errors.push(`index entry 无效: ${String(filePath ?? '')}`); continue;
  }
  const root = index.root ? resolvePath(index.root) : resolvePath('artifacts/v1.1/test');
  let absolute;
  try { absolute = resolvePath(filePath, root); repoRelative(absolute); }
  catch { errors.push(`证据路径越界: ${filePath}`); continue; }
  try {
    if (sha256File(absolute) !== String(item.sha256).toLowerCase()) { errors.push(`证据 hash 不匹配: ${filePath}`); continue; }
    const rows = readRows(absolute);
    if (rows.length === 0) { errors.push(`证据为空: ${filePath}`); continue; }
    const category = categoryOf(filePath);
    const bucket = categories.get(category) ?? {category, files: 0, checks: 0, passed: 0, failed: 0, unknown: 0};
    bucket.files += 1;
    for (const row of rows) {
      totalChecks += 1; bucket.checks += 1;
      if (typeof row.passed !== 'boolean') { unknownChecks += 1; bucket.unknown += 1; continue; }
      if (row.passed) bucket.passed += 1; else { bucket.failed += 1; failedChecks += 1; }
    }
    categories.set(category, bucket);
  } catch (error) { errors.push(`${filePath}: ${error.message}`); }
}

const report = {
  schemaVersion: 'v1.1', producer: 'aggregate-security-report', generatedAt: generatedAt(),
  runId: index?.runId ?? null, sourceIndex: inputsPath ? String(inputsPath).replaceAll('\\', '/') : null,
  sourceIndexSha256: inputsPath ? safeHash(inputsPath) : null,
  totalChecks, passedChecks: totalChecks - failedChecks - unknownChecks, failedChecks, unknownChecks,
  categories: [...categories.values()].sort((a, b) => a.category.localeCompare(b.category)),
  // A summary is publishable only when every raw check explicitly reports a
  // boolean result and no check failed. Missing/unknown records stay blocked.
  passed: errors.length === 0 && totalChecks > 0 && failedChecks === 0 && unknownChecks === 0,
  status: errors.length === 0 && totalChecks > 0 && failedChecks === 0 && unknownChecks === 0 ? 'passed' : 'blocked',
  errors, redacted: true
};
try { if (output) writeJson(output, report, {noClobber: args['no-clobber'] === true}); }
catch (error) { report.passed = false; report.status = 'blocked'; report.errors.push(`输出失败: ${error.message}`); process.exitCode = 1; }
if (!report.passed) process.exitCode = 1;
console.log(JSON.stringify(report, null, 2));

function readRows(file) {
  const text = fs.readFileSync(file, 'utf8');
  if (file.toLowerCase().endsWith('.json')) {
    const parsed = JSON.parse(text);
    return Array.isArray(parsed) ? parsed : [parsed];
  }
  return text.split(/\r?\n/).filter((line) => line.trim()).map((line) => JSON.parse(line));
}
function categoryOf(value) {
  const name = path.posix.basename(String(value).replaceAll('\\', '/'));
  const match = name.match(/^n3-([A-Za-z0-9_-]+)/);
  return match ? match[1] : 'uncategorized';
}
function safeHash(value) { try { return sha256File(resolvePath(value)); } catch { return null; } }
