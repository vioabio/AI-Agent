#!/usr/bin/env node

/** Build the N0 snapshot input list and its single aggregate hash. */
import fs from 'node:fs';
import path from 'node:path';
import {currentCommit, parseArgs, repoRelative, repoRoot, resolvePath, sha256File, sha256Text, writeAtomic, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const allowedOptions = new Set(['input', 'output', 'hash-output', 'report', 'no-clobber']);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!allowedOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);
const output = args.output ?? 'artifacts/v1.1/baseline/snapshot-input-hashes.json';
const hashOutput = args['hash-output'] ?? 'artifacts/v1.1/baseline/snapshot-hash.txt';
const reportOutput = args.report;
const defaultInputs = [
  'artifacts/v1.1/baseline/working-tree.patch',
  'artifacts/v1.1/baseline/status-ignored.txt',
  'artifacts/v1.1/baseline/untracked.txt',
  'artifacts/v1.1/baseline/ignored.txt',
  'artifacts/v1.1/baseline/untracked-and-ignored.txt',
  'artifacts/v1.1/baseline/untracked-hashes.json',
  'artifacts/v1.1/baseline/lockfile-hash.txt',
  'artifacts/v1.1/baseline/toolchain.txt',
  'artifacts/v1.1/baseline/lockfile-input-hashes.json',
  'artifacts/v1.1/baseline/backend-context-hashes.json',
  'artifacts/v1.1/baseline/frontend-context-hashes.json'
];
const inputs = (args.input ? (Array.isArray(args.input) ? args.input : [args.input]) : defaultInputs).flatMap((value) => String(value).split(',')).map((value) => value.trim()).filter(Boolean);
const entries = [];
const outputPath = validateOutput(output, 'output');
const hashPath = validateOutput(hashOutput, 'hash-output');
const reportPath = reportOutput ? validateOutput(reportOutput, 'report') : null;
if (outputPath && hashPath && sameKey(outputPath, hashPath)) errors.push('output 与 hash-output 不能相同');
if (outputPath && reportPath && sameKey(outputPath, reportPath)) errors.push('output 与 report 不能相同');
if (hashPath && reportPath && sameKey(hashPath, reportPath)) errors.push('hash-output 与 report 不能相同');
for (const [label, value, relative] of [['output', output, outputPath], ['hash-output', hashOutput, hashPath], ['report', reportOutput, reportPath]]) {
  if (relative && args['no-clobber'] === true && fs.existsSync(resolvePath(value))) errors.push(`${label} 输出已存在（--no-clobber）: ${relative}`);
}
const seen = new Set();
for (const input of inputs) {
  let file;
  let real;
  let relative;
  try {
    file = resolvePath(input);
    relative = repoRelative(file);
    real = fs.realpathSync(file);
    if (!isWithin(repoRoot, real) || samePath(repoRoot, real)) throw new Error('符号链接目标越出仓库');
    if (!fs.statSync(real).isFile()) throw new Error('不是普通文件');
  } catch (error) {
    errors.push(`输入无效 ${input}: ${error.message}`);
    continue;
  }
  const key = process.platform === 'win32' ? relative.toLowerCase() : relative;
  if (seen.has(key)) { errors.push(`输入重复: ${relative}`); continue; }
  seen.add(key);
  if (isSecretPath(relative)) { errors.push(`Secret 路径不能进入 snapshot: ${relative}`); continue; }
  if (sameKey(relative, outputPath) || sameKey(relative, hashPath) || sameKey(relative, reportPath)) {
    errors.push(`snapshot 输入不得包含自身/输出文件: ${relative}`);
    continue;
  }
  entries.push({path: relative, sha256: sha256File(real)});
}
entries.sort((left, right) => left.path.localeCompare(right.path, 'en'));
const canonical = `${JSON.stringify(entries, null, 2)}\n`;
if (errors.length === 0) {
  try {
    writeAtomic(output, canonical, {noClobber: args['no-clobber'] === true});
    writeAtomic(hashOutput, `${sha256Text(canonical)}\n`, {noClobber: args['no-clobber'] === true});
  } catch (error) {
    errors.push(`snapshot 输出写入失败: ${error.message}`);
  }
}
const report = {schemaVersion: 'v1.1', producer: 'create-snapshot-inputs', generatedAt: new Date().toISOString(), gitCommit: currentCommit(), output: outputPath ?? '[invalid-path]', hashOutput: hashPath ?? '[invalid-path]', inputCount: entries.length, snapshotHash: errors.length === 0 ? sha256Text(canonical) : null, passed: errors.length === 0, errors};
if (reportPath && (!args['no-clobber'] || !fs.existsSync(resolvePath(reportOutput)))) {
  try { writeJson(reportOutput, report, {noClobber: args['no-clobber'] === true}); }
  catch (error) { errors.push(`report 写入失败: ${error.message}`); }
}
console.log(JSON.stringify(report, null, 2));
if (errors.length > 0) process.exitCode = 1;

function isSecretPath(value) {
  if (/(^|\/)\.env(?:\.[^/]+)?\.example$/i.test(value) || /(^|\/)\.env\.example$/i.test(value)) return false;
  return /(^|\/)(?:\.env(?:\.|$)|application-local\.(?:yml|yaml|properties)(?:\.|$)|mcp-servers\.json(?:\.|$))/i.test(value);
}

function validateOutput(value, label) {
  try {
    const absolute = resolvePath(value);
    const relative = repoRelative(absolute);
    if (!relative || relative === '.') throw new Error('不能是仓库根目录');
    const parent = nearestExistingPath(path.dirname(absolute));
    if (!isWithin(repoRoot, fs.realpathSync(parent))) throw new Error('输出父目录越出仓库');
    if (fs.existsSync(absolute)) {
      const real = fs.realpathSync(absolute);
      if (!isWithin(repoRoot, real) || !fs.statSync(real).isFile()) throw new Error('现有输出不是仓库内普通文件');
    }
    return relative;
  } catch (error) {
    errors.push(`${label} 路径无效: ${error.message}`);
    return null;
  }
}

function nearestExistingPath(value) {
  let current = path.resolve(value);
  while (!fs.existsSync(current)) {
    const parent = path.dirname(current);
    if (parent === current) return current;
    current = parent;
  }
  return current;
}

function samePath(left, right) {
  const a = path.resolve(left);
  const b = path.resolve(right);
  return process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b;
}

function sameKey(left, right) {
  if (!left || !right) return false;
  return process.platform === 'win32' ? String(left).toLowerCase() === String(right).toLowerCase() : String(left) === String(right);
}

function isWithin(root, target) {
  const relative = path.relative(root, target);
  return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}
