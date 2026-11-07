#!/usr/bin/env node

/** Record and, when safe, remove a temporary raw OTel response. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {generatedAt, parseArgs, repoRelative, resolvePath, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const rawArg = scalar(args['raw-path']);
const outputArg = scalar(args.output ?? args.report);
const errors = [];
if (!rawArg) errors.push('缺少 --raw-path');
if (!outputArg) errors.push('缺少 --output');

let rawPath = null;
if (rawArg) {
  rawPath = path.isAbsolute(rawArg) ? path.normalize(rawArg) : path.resolve(process.cwd(), rawArg);
  const tempRoot = path.resolve(os.tmpdir());
  if (!within(tempRoot, rawPath) || rawPath === tempRoot) errors.push('raw-path 必须位于系统临时目录内且不能是目录根');
  if (rawPath.includes('..')) errors.push('raw-path 不允许包含越界片段');
}
if (outputArg) {
  try { repoRelative(resolvePath(outputArg)); } catch (error) { errors.push(`output 无效: ${safe(error.message)}`); }
}

const presentBefore = rawPath ? fs.existsSync(rawPath) : false;
let removed = false;
if (errors.length === 0 && presentBefore && args.delete !== false) {
  try {
    const stat = fs.lstatSync(rawPath);
    if (!stat.isFile()) throw new Error('raw-path 不是普通文件');
    fs.rmSync(rawPath, {force: true});
    removed = true;
  } catch (error) { errors.push(`raw OTel 文件清理失败: ${safe(error.message)}`); }
}
const presentAfter = rawPath ? fs.existsSync(rawPath) : false;
if (presentAfter) errors.push('raw OTel 文件仍存在');

const result = {
  schemaVersion: 'v1.1',
  producer: 'write-otel-raw-cleanup',
  generatedAt: generatedAt(),
  status: errors.length === 0 ? 'passed' : 'blocked',
  passed: errors.length === 0,
  rawFile: rawPath ? path.basename(rawPath) : null,
  rawPresentBefore: presentBefore,
  removed,
  rawPresentAfter: presentAfter,
  cleanupRequired: true,
  errors: [...new Set(errors)],
  redacted: true
};
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
function within(root, target) {
  const relative = path.relative(path.resolve(root), path.resolve(target));
  return relative === '' || (relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}
function safe(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 240); }
