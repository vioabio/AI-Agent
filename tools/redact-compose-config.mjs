#!/usr/bin/env node

/** Produce a public, secret-redacted projection of `docker compose config`. */
import fs from 'node:fs';
import path from 'node:path';
import {
  generatedAt, parseArgs, repoRelative, resolvePath, sha256File, writeAtomic
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const inputArg = scalar(args.input);
const outputArg = scalar(args.output);
const reportArg = scalar(args.report);
const errors = [];
let inputPath = null;
let outputPath = null;
let source = '';
if (!inputArg) errors.push('缺少 --input');
if (!outputArg) errors.push('缺少 --output');
if (!reportArg) errors.push('缺少 --report');
try {
  if (inputArg) { inputPath = resolvePath(inputArg); repoRelative(inputPath); if (!fs.statSync(inputPath).isFile()) throw new Error('不是普通文件'); source = fs.readFileSync(inputPath, 'utf8'); }
} catch (error) { errors.push(`input 无效: ${safe(error.message)}`); }
try {
  if (outputArg) { outputPath = resolvePath(outputArg); repoRelative(outputPath); }
  if (inputPath && outputPath && same(inputPath, outputPath)) errors.push('output 不能覆盖 input');
} catch (error) { errors.push(`output 无效: ${safe(error.message)}`); }
try { if (reportArg) { repoRelative(resolvePath(reportArg)); } } catch (error) { errors.push(`report 无效: ${safe(error.message)}`); }

const findings = [];
const redacted = source.split(/\r?\n/).map((line, index) => redactLine(line, index + 1)).join('\n');
// Do not allow common credential-bearing URL forms to survive a projection.
const finalText = redacted
  .replace(/(https?:\/\/)([^\s/@]+):([^\s/@]+)@/gi, '$1[REDACTED]@')
  .replace(/([?&](?:token|api[_-]?key|secret|password|authorization)=)[^&\s]*/gi, '$1[REDACTED]')
  .replace(/(Bearer\s+)[A-Za-z0-9._~+\/-]+/gi, '$1[REDACTED]');
if (source.length > 2_000_000) errors.push('input 过大，拒绝生成不可审计的配置投影');
if (finalText.includes('\0')) errors.push('input 含不可见 NUL 字节');
if (/\b(?:sk-[A-Za-z0-9]{12,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,})\b/.test(finalText)) errors.push('输出仍包含 token-shaped 值');

const result = {
  schemaVersion: 'v1.1', producer: 'redact-compose-config', generatedAt: generatedAt(),
  input: inputPath ? safeRelative(inputPath) : inputArg ?? null,
  output: outputPath ? safeRelative(outputPath) : outputArg ?? null,
  inputSha256: inputPath && fs.existsSync(inputPath) ? sha256File(inputPath) : null,
  outputSha256: null, redactionCount: findings.length,
  findings: findings.map(({line, reasonCode}) => ({line, reasonCode})),
  status: errors.length === 0 ? 'ready' : 'blocked', passed: errors.length === 0,
  errors: [...new Set(errors)], redacted: true
};
if (result.passed) {
  try {
    writeAtomic(outputPath, finalText.endsWith('\n') ? finalText : `${finalText}\n`, {noClobber: args['no-clobber'] === true});
    result.outputSha256 = sha256File(outputPath);
  } catch (error) { result.passed = false; result.status = 'blocked'; result.errors.push(`投影写入失败: ${safe(error.message)}`); }
}
if (reportArg) {
  try {
    const reportPath = resolvePath(reportArg);
    writeAtomic(reportPath, `${JSON.stringify(result, null, 2)}\n`, {noClobber: args['no-clobber'] === true});
  } catch (error) { result.passed = false; result.status = 'blocked'; result.errors.push(`报告写入失败: ${safe(error.message)}`); }
}
console.log(JSON.stringify(result, null, 2));
if (!result.passed) process.exitCode = 1;

function redactLine(line, lineNo) {
  let resultLine = line;
  // YAML keys and dotenv-style assignments.  Keep the key name for audit,
  // never keep its value.
  const match = line.match(/^(\s*(?:[-]?\s*)?["']?([A-Za-z0-9_.-]*(?:password|passwd|api[_-]?key|access[_-]?key|secret|token|private[_-]?key|authorization)[A-Za-z0-9_.-]*)["']?\s*[:=]\s*)(.*)$/i);
  if (match && match[3].trim() !== '' && !/^\[?redacted\]?$/i.test(match[3].trim())) {
    findings.push({line: lineNo, reasonCode: 'SECRET_KEY_VALUE'});
    resultLine = `${match[1]}[REDACTED]`;
  }
  if (/\b(?:password|api[_-]?key|secret|token|authorization)\s*[:=]\s*[^\s\]}>,]+/i.test(resultLine)
      && !/\[REDACTED\]/i.test(resultLine)) {
    findings.push({line: lineNo, reasonCode: 'SECRET_PATTERN'});
    resultLine = resultLine.replace(/(\b(?:password|api[_-]?key|secret|token|authorization)\s*[:=]\s*)[^\s\]}>,]+/ig, '$1[REDACTED]');
  }
  return resultLine;
}
function scalar(value) {
  if (Array.isArray(value)) return value.length === 1 ? String(value[0]).trim() : null;
  if (value === undefined || value === null || value === true || value === false) return null;
  return String(value).trim();
}
function same(a, b) { const left = path.resolve(a); const right = path.resolve(b); return process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right; }
function safeRelative(value) { return repoRelative(value); }
function safe(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 240); }
