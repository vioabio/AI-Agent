#!/usr/bin/env node

/** Aggregate Surefire XML and reject stale reports unless a run marker is supplied. */
import fs from 'node:fs';
import path from 'node:path';
import {currentCommit, generatedAt, parseArgs, repoRoot, resolvePath, writeAtomic, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const directory = resolvePath(args.surefire ?? 'target/surefire-reports');
const output = args.output ?? 'artifacts/v1.1/test/baseline-test-report.xml';
const summaryOutput = args.summary ?? 'artifacts/v1.1/test/baseline-test-summary.json';
const validationErrors = [];
const warnings = [];
const suites = [];
if (!fs.existsSync(directory) || !fs.statSync(directory).isDirectory()) validationErrors.push('Surefire 目录不存在');
if (validationErrors.length === 0) {
  for (const name of fs.readdirSync(directory).filter((item) => item.endsWith('.xml')).sort()) {
    const file = path.join(directory, name);
    const text = fs.readFileSync(file, 'utf8');
    if (!/<testsuite\b/.test(text)) { warnings.push(`${name}: 缺少 testsuite 节点`); continue; }
    suites.push({name, path: path.relative(repoRoot, file).replaceAll('\\', '/'), text, mtimeMs: fs.statSync(file).mtimeMs, attrs: parseAttrs(text.match(/<testsuite\b([^>]*)>/)?.[1] ?? '')});
  }
}
if (suites.length === 0 && validationErrors.length === 0) validationErrors.push('Surefire 目录没有 XML 报告');
const newest = suites.reduce((max, suite) => Math.max(max, suite.mtimeMs), 0);
const runStart = parseRunStart(args);
if (runStart !== null && newest < runStart) validationErrors.push('Surefire 报告早于本次 clean test 起始时间');
if (runStart === null && args['allow-stale'] !== true) warnings.push('未提供本次 clean test run marker；报告只能标 pending，不能作为 v1.1 干净构建证据');
const totals = suites.reduce((sum, suite) => {
  for (const key of ['tests', 'failures', 'errors', 'skipped']) sum[key] += Number(suite.attrs[key] ?? 0);
  return sum;
}, {tests: 0, failures: 0, errors: 0, skipped: 0});
const fresh = runStart !== null && validationErrors.length === 0;
const status = validationErrors.length > 0 ? 'failed' : (fresh ? 'captured' : 'pending');
const summary = {
  schemaVersion: 'v1.1', producer: 'aggregate-test-report', generatedAt: generatedAt(), gitCommit: currentCommit(),
  sourceDirectory: path.relative(repoRoot, directory).replaceAll('\\', '/'), sourceCommit: args['source-commit'] ?? null,
  runStart: runStart === null ? null : new Date(runStart).toISOString(), newestReportAt: newest ? new Date(newest).toISOString() : null,
  ...totals, reportFiles: suites.length, status,
  passed: validationErrors.length === 0 && warnings.length === 0,
  validationErrors, warnings
};
const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<testsuites tests="${totals.tests}" failures="${totals.failures}" errors="${totals.errors}" skipped="${totals.skipped}">\n${suites.map((suite) => indentSuite(suite.text)).join('\n')}\n</testsuites>\n`;
writeAtomic(output, xml, {noClobber: args['no-clobber'] === true});
writeJson(summaryOutput, summary, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify({output, summary: summaryOutput, status, passed: summary.passed,
  ...totals, validationErrors, warnings}, null, 2));
if (validationErrors.length > 0 || (warnings.length > 0 && args['allow-stale'] !== true)) process.exitCode = 1;

function parseAttrs(value) {
  const result = {};
  for (const match of value.matchAll(/([A-Za-z][\w.-]*)="([^"]*)"/g)) result[match[1]] = match[2];
  return result;
}
function indentSuite(text) { return text.split(/\r?\n/).map((line) => `  ${line}`).join('\n'); }
function parseRunStart(value) {
  const raw = args['run-start'] ?? value;
  if (!raw) return null;
  const date = Number.isFinite(Number(raw)) ? new Date(Number(raw)) : new Date(String(raw));
  return Number.isNaN(date.getTime()) ? null : date.getTime();
}
