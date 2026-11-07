#!/usr/bin/env node

/**
 * Build an immutable hash index for one v1.1 evaluation run.
 *
 * The run manifest is the source of truth for the suite ids.  We derive the
 * exact aggregate/suite filenames from those ids instead of walking the run
 * directory, so an old or injected file can never silently enter a release
 * report.  Missing inputs produce a redacted blocked report and a non-zero
 * exit code; no bytes are copied to the report.
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  readJson,
  repoRelative,
  resolvePath,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const runDirArg = scalar(args['run-dir']);
const manifestArg = scalar(args['run-manifest']);
const outputArg = scalar(args.output ?? args.report);
const noClobber = args['no-clobber'] === true;

if (!runDirArg) errors.push('缺少 --run-dir');
if (!manifestArg) errors.push('缺少 --run-manifest');
if (!outputArg) errors.push('缺少 --output');

let runDir;
let manifestPath;
let manifest = null;
if (runDirArg) {
  try {
    runDir = resolvePath(runDirArg);
    repoRelative(runDir);
    if (!fs.existsSync(runDir) || !fs.statSync(runDir).isDirectory()) errors.push('run-dir 不存在或不是目录');
  } catch (error) { errors.push(`run-dir 无效: ${safe(error.message)}`); }
}
if (manifestArg) {
  try {
    manifestPath = resolvePath(manifestArg);
    repoRelative(manifestPath);
    manifest = readJson(manifestPath, 'evaluation run manifest');
  } catch (error) { errors.push(`run-manifest 读取失败: ${safe(error.message)}`); }
}
if (outputArg) {
  try {
    const outputPath = resolvePath(outputArg);
    repoRelative(outputPath);
    if (manifestPath && same(outputPath, manifestPath)) errors.push('output 不能覆盖 run manifest');
  } catch (error) { errors.push(`output 无效: ${safe(error.message)}`); }
}

const evaluationRunId = clean(manifest?.evaluationRunId);
if (!manifest || manifest.schemaVersion !== 'v1.1') errors.push('run manifest schemaVersion 必须为 v1.1');
if (!evaluationRunId || !/^[A-Za-z0-9._-]{3,128}$/.test(evaluationRunId)) errors.push('evaluationRunId 缺失或格式无效');
if (!Array.isArray(manifest?.suites) || manifest.suites.length !== 7) errors.push('run manifest 必须包含 7 个 suite');

const files = [];
const seen = new Set();
function addFile(file, label) {
  if (!file) return;
  let absolute;
  try {
    absolute = resolvePath(file);
    repoRelative(absolute);
  } catch (error) {
    errors.push(`${label} 路径无效: ${safe(error.message)}`);
    return;
  }
  const key = process.platform === 'win32' ? absolute.toLowerCase() : absolute;
  if (seen.has(key)) {
    errors.push(`${label} 与已有输入重复`);
    return;
  }
  seen.add(key);
  files.push({absolute, label});
}

// The manifest itself is part of the immutable run bundle.
addFile(manifestPath, 'run-manifest');
if (runDir) {
  addFile(path.join(runDir, 'metrics.json'), 'aggregate metrics');
  for (const [index, suite] of (manifest?.suites ?? []).entries()) {
    const id = clean(suite?.suiteId);
    if (!id || !/^[A-Za-z0-9._-]{1,128}$/.test(id)) {
      errors.push(`suites[${index}].suiteId 缺失或包含非法字符`);
      continue;
    }
    addFile(path.join(runDir, `evaluation-run-${id}-${evaluationRunId || 'missing'}.json`), `suite ${id}`);
  }
}
for (const extra of listArg(args.input)) addFile(extra, 'explicit input');

for (const item of files) {
  if (!fs.existsSync(item.absolute)) {
    errors.push(`${item.label} 不存在`);
    continue;
  }
  try {
    if (!fs.statSync(item.absolute).isFile()) errors.push(`${item.label} 不是普通文件`);
  } catch (error) { errors.push(`${item.label} 无法读取: ${safe(error.message)}`); }
}

const manifestReady = manifest?.status === 'ready';
if (!manifestReady) errors.push(`run manifest status 必须为 ready，当前为 ${manifest?.status ?? 'missing'}`);
const passed = errors.length === 0;
const result = {
  schemaVersion: 'v1.1',
  producer: 'hash-evaluation-run',
  generatedAt: generatedAt(),
  commit: currentCommit(),
  evaluationRunId: evaluationRunId || null,
  runDir: runDir ? safeRelative(runDir) : runDirArg ?? null,
  runManifest: manifestPath ? safeRelative(manifestPath) : manifestArg ?? null,
  status: passed ? 'ready' : (manifest?.status === 'pending' ? 'pending' : 'blocked'),
  passed,
  files: [],
  errors: [...new Set(errors)],
  redacted: true
};

if (passed) {
  try {
    result.files = files.map((item) => ({path: safeRelative(item.absolute), sha256: hashFile(item.absolute), algorithm: 'SHA-256'}));
  } catch (error) {
    result.passed = false;
    result.status = 'blocked';
    result.errors.push(`hash 失败: ${safe(error.message)}`);
  }
}

if (outputArg) {
  try { writeJson(outputArg, result, {noClobber}); }
  catch (error) {
    result.passed = false;
    result.status = 'blocked';
    result.errors.push(`输出失败: ${safe(error.message)}`);
  }
}
console.log(JSON.stringify(result, null, 2));
if (!result.passed) process.exitCode = 1;

function scalar(value) {
  if (Array.isArray(value)) return value.length === 1 ? String(value[0]).trim() : null;
  if (value === undefined || value === null || value === true || value === false) return null;
  return String(value).trim();
}
function listArg(value) {
  if (value === undefined || value === null) return [];
  return (Array.isArray(value) ? value : [value]).flatMap((item) => String(item).split(',')).map((item) => item.trim()).filter(Boolean);
}
function clean(value) { return value === undefined || value === null ? '' : String(value).trim(); }
function safe(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 240); }
function safeRelative(value) {
  try { return repoRelative(value); } catch { return String(value).replaceAll('\\', '/'); }
}
function same(left, right) {
  const a = path.resolve(left); const b = path.resolve(right);
  return process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b;
}
function hashFile(file) { return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'); }
