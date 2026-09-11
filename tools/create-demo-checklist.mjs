#!/usr/bin/env node

/** Create a redacted, hash-bound checklist for a DEMO_ONLY recording. */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {generatedAt, parseArgs, readJson, repoRelative, resolvePath, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const manifestArg = scalar(args.manifest);
const scriptArg = scalar(args.script);
const recordingArg = scalar(args.recording);
const screenshotsArg = scalar(args.screenshots);
const outputArg = scalar(args.output ?? args.report);
const errors = [];
let manifest = null;

if (!manifestArg) errors.push('缺少 --manifest');
if (!scriptArg) errors.push('缺少 --script');
if (!recordingArg) errors.push('缺少 --recording');
if (!screenshotsArg) errors.push('缺少 --screenshots');
if (!outputArg) errors.push('缺少 --output');
try { if (manifestArg) manifest = readJson(manifestArg, 'demo release manifest'); }
catch (error) { errors.push(`manifest 读取失败: ${safe(error.message)}`); }

if (manifest && (manifest.schemaVersion !== 'v1.1' || manifest.releaseClass !== 'DEMO_ONLY'
  || !['BLOCKED', 'DEMO_ONLY'].includes(manifest.status) || manifest.environment !== 'staging')) {
  errors.push('demo manifest 必须是 v1.1、DEMO_ONLY、staging，且状态为 BLOCKED/DEMO_ONLY');
}

const files = [];
const dirs = [];
function resolveRepo(value, label, directory = false) {
  if (!value) return null;
  try {
    const absolute = resolvePath(value);
    repoRelative(absolute);
    if (!fs.existsSync(absolute)) throw new Error('不存在');
    const stat = fs.statSync(absolute);
    if (directory ? !stat.isDirectory() : !stat.isFile()) throw new Error(directory ? '不是目录' : '不是普通文件');
    return absolute;
  } catch (error) { errors.push(`${label} 无效: ${safe(error.message)}`); return null; }
}
const manifestPath = resolveRepo(manifestArg, 'manifest');
const scriptPath = resolveRepo(scriptArg, 'script');
const recordingPath = resolveRepo(recordingArg, 'recording');
const screenshotDir = resolveRepo(screenshotsArg, 'screenshots', true);
for (const [pathValue, label] of [[manifestPath, 'manifest'], [scriptPath, 'script'], [recordingPath, 'recording']]) {
  if (pathValue) files.push({path: safeRelative(pathValue), label});
}
if (screenshotDir) {
  const entries = fs.readdirSync(screenshotDir, {withFileTypes: true}).filter((entry) => entry.isFile()).sort((a, b) => a.name.localeCompare(b.name));
  if (entries.length === 0) errors.push('screenshots 目录为空');
  for (const entry of entries) {
    const candidate = path.join(screenshotDir, entry.name);
    try {
      const real = fs.realpathSync(candidate);
      if (!within(screenshotDir, real)) throw new Error('符号链接越出 screenshots 目录');
      files.push({path: safeRelative(candidate), label: `screenshot:${entry.name}`});
    } catch (error) { errors.push(`screenshot ${entry.name} 无效: ${safe(error.message)}`); }
  }
}
if (files.length < 4) errors.push('演示证据至少需要 manifest、脚本、录像和一张截图');

const result = {
  schemaVersion: 'v1.1', producer: 'create-demo-checklist', generatedAt: generatedAt(),
  releaseId: manifest?.releaseId ?? null, releaseClass: manifest?.releaseClass ?? null,
  environment: manifest?.environment ?? null, status: errors.length === 0 ? 'ready' : 'blocked',
  passed: errors.length === 0, checklist: [
    {id: 'context', title: '版本/语言/赛制上下文可见', required: true},
    {id: 'facts', title: '事实卡含来源与 release 血缘', required: true},
    {id: 'calculation', title: '确定性计算显示规则版本', required: true},
    {id: 'team', title: '队伍分析与资源管理可操作', required: true},
    {id: 'feedback', title: '纠错与重答链路可回溯', required: true},
    {id: 'observability', title: 'Trace/指标/成本证据可见', required: true},
    {id: 'rollback', title: '故障阻断与回滚证据可见', required: true}
  ],
  files: [], errors: [...new Set(errors)], redacted: true
};
if (result.passed) {
  try {
    result.files = files.map((item) => ({...item, sha256: hashFile(resolvePath(item.path)), algorithm: 'SHA-256'}));
  } catch (error) { result.passed = false; result.status = 'blocked'; result.errors.push(`证据 hash 失败: ${safe(error.message)}`); }
}
if (outputArg) {
  try { writeJson(outputArg, result, {noClobber: args['no-clobber'] === true}); }
  catch (error) { result.passed = false; result.status = 'blocked'; result.errors.push(`输出失败: ${safe(error.message)}`); }
}
console.log(JSON.stringify(result, null, 2));
if (!result.passed) process.exitCode = 1;

function scalar(value) {
  if (Array.isArray(value)) return value.length === 1 ? String(value[0]).trim() : null;
  if (value === undefined || value === null || value === true || value === false) return null;
  return String(value).trim();
}
function safe(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 240); }
function safeRelative(value) { return repoRelative(value); }
function hashFile(value) { return crypto.createHash('sha256').update(fs.readFileSync(value)).digest('hex'); }
function within(root, target) {
  const rel = path.relative(path.resolve(root), path.resolve(target));
  return rel === '' || (rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel));
}
