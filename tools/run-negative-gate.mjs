#!/usr/bin/env node

/** Execute a gate with a reviewed invalid fixture and prove it rejects. */
import fs from 'node:fs';
import {spawnSync} from 'node:child_process';
import path from 'node:path';
import {currentCommit, generatedAt, parseArgs, repoRoot, resolvePath, sha256File, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const gate = args.gate;
const fixture = args['invalid-fixture'];
const output = args.output ?? 'artifacts/v1.1/test/negative-gate-report.json';
const errors = [];
if (!gate || !fs.existsSync(resolvePath(gate))) errors.push('gate 文件缺失');
if (!fixture || !fs.existsSync(resolvePath(fixture))) errors.push('invalid fixture 文件缺失');
let child = null;
let gateReportPath = null;
if (errors.length === 0) {
  gateReportPath = path.join(path.dirname(resolvePath(output)), `.gate-child-${process.pid}.json`);
  const command = [resolvePath(gate), '--mode', args.mode ?? 'release', '--invalid-fixture', resolvePath(fixture), '--report', gateReportPath];
  child = spawnSync(process.execPath, command, {cwd: repoRoot, encoding: 'utf8', timeout: 120000, windowsHide: true});
  if (child.error) errors.push(`gate 启动失败: ${child.error.message}`);
}
let gateReport = null;
if (gateReportPath && fs.existsSync(gateReportPath)) {
  try { gateReport = JSON.parse(fs.readFileSync(gateReportPath, 'utf8')); } catch (error) { errors.push(`gate 报告解析失败: ${error.message}`); }
  try { fs.rmSync(gateReportPath, {force: true}); } catch { /* best effort cleanup */ }
} else if (errors.length === 0) errors.push('gate 未生成结构化报告');
const gateExit = child?.status ?? 1;
const expectedRejection = errors.length === 0 && gateExit !== 0 && gateReport?.passed === false && gateReport?.redacted === true;
const report = {
  schemaVersion: 'v1.1', producer: 'run-negative-gate', generatedAt: generatedAt(), gitCommit: currentCommit(),
  gate: String(gate).replaceAll('\\', '/'), invalidFixture: String(fixture ?? '').replaceAll('\\', '/'),
  invalidFixtureSha256: hash(fixture), gateExitCode: gateExit, expectedRejection,
  reasonCode: expectedRejection ? (gateReport.reasonCode ?? 'REJECTED') : 'NEGATIVE_GATE_DID_NOT_REJECT',
  redacted: true, passed: expectedRejection, errors,
  gateSummary: gateReport ? {status: gateReport.status, reasonCode: gateReport.reasonCode, errors: gateReport.errors?.slice(0, 10) ?? []} : null
};
writeJson(output, report, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify(report, null, 2));
// A non-zero exit is intentional: the wrapped gate must have rejected input.
if (!expectedRejection) process.exitCode = 1;
else process.exitCode = 2;

function hash(file) { try { return sha256File(resolvePath(file)); } catch { return null; } }
