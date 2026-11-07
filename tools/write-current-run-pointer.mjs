#!/usr/bin/env node

/** Atomically update a repo-relative current-run pointer without clobbering history. */
import fs from 'node:fs';
import path from 'node:path';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  repoRelative,
  resolvePath,
  writeAtomic,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const repoRootArg = args['repo-root'] ?? '.';
const pointerArg = args.pointer;
const valueArg = args.value;
const previousOutput = args['previous-output'];
const errors = [];
if (!pointerArg) errors.push('缺少 --pointer');
if (!valueArg) errors.push('缺少 --value');
if (valueArg && (path.isAbsolute(String(valueArg)) || String(valueArg).includes('..')
  || /[*?{}[\]]/.test(String(valueArg)))) errors.push('--value 必须是仓库相对、无 glob 的路径');
if (!args['atomic-replace']) errors.push('必须显式传入 --atomic-replace');

let root;
let pointer;
let previous = null;
try {
  root = resolvePath(repoRootArg);
  repoRelative(root);
  if (pointerArg) { pointer = resolvePath(pointerArg, root); repoRelative(pointer, root); }
  if (pointer && fs.existsSync(pointer)) previous = fs.readFileSync(pointer, 'utf8').trim() || null;
} catch (error) { errors.push(`路径/指针读取失败: ${error.message}`); }

const normalizedValue = valueArg ? String(valueArg).replaceAll('\\', '/').trim() : null;
const result = {
  schemaVersion: 'v1.1',
  producer: 'write-current-run-pointer',
  generatedAt: generatedAt(),
  commit: currentCommit(),
  pointer: pointer && root ? repoRelative(pointer, root) : pointerArg ?? null,
  value: normalizedValue,
  previousValue: previous,
  replaced: false,
  passed: errors.length === 0,
  status: errors.length === 0 ? 'ready' : 'blocked',
  errors,
  redacted: true
};

try {
  if (result.passed) {
    writeAtomic(pointer, `${normalizedValue}\n`, {noClobber: args['no-clobber'] === true && !args['atomic-replace']});
    result.replaced = previous !== null;
    if (previousOutput) writeJson(previousOutput, {
      schemaVersion: 'v1.1', producer: 'write-current-run-pointer', generatedAt: result.generatedAt,
      pointer: result.pointer, previousValue: previous, newValue: normalizedValue, redacted: true
    }, {noClobber: args['no-clobber'] === true});
  }
} catch (error) {
  result.passed = false; result.status = 'blocked'; result.errors.push(`指针写入失败: ${error.message}`); process.exitCode = 1;
}
if (!result.passed) process.exitCode = 1;
console.log(JSON.stringify(result, null, 2));
