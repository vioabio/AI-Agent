import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {repoRoot} from './lib/io.mjs';

const validator = path.join(repoRoot, 'tools', 'validate-fault-plan.mjs');
const template = path.join(repoRoot, 'config', 'fault-injection-v1.1.json');

function run(input, extra = []) {
  const dir = fs.mkdtempSync(path.join(repoRoot, 'tmp', 'fault-plan-test-'));
  const inputPath = path.join(dir, 'plan.json');
  const reportPath = path.join(dir, 'report.json');
  fs.writeFileSync(inputPath, JSON.stringify(input, null, 2));
  const result = spawnSync(process.execPath, [validator, '--input', inputPath, '--schema', 'config/fault-injection-v1.1.schema.json', '--report', reportPath, '--no-clobber', ...extra], {cwd: repoRoot, encoding: 'utf8'});
  const report = fs.existsSync(reportPath) ? JSON.parse(fs.readFileSync(reportPath, 'utf8')) : null;
  fs.rmSync(dir, {recursive: true, force: true});
  return {result, report};
}

test('pending plan can be structurally checked without becoming a pass', () => {
  const plan = JSON.parse(fs.readFileSync(template, 'utf8'));
  const {result, report} = run(plan, ['--allow-pending']);
  assert.equal(result.status, 0);
  assert.equal(report.status, 'environment-pending');
  assert.equal(report.passed, false);
  assert.equal(report.scenarioCount, 4);
});

test('missing fault type fails closed', () => {
  const plan = JSON.parse(fs.readFileSync(template, 'utf8'));
  plan.scenarios = plan.scenarios.slice(0, 3);
  const {result, report} = run(plan, ['--allow-pending']);
  assert.notEqual(result.status, 0);
  assert.equal(report.status, 'blocked');
  assert.equal(report.passed, false);
});

test('report path cannot escape repository', () => {
  const plan = JSON.parse(fs.readFileSync(template, 'utf8'));
  const dir = fs.mkdtempSync(path.join(repoRoot, 'tmp', 'fault-plan-boundary-'));
  const inputPath = path.join(dir, 'plan.json');
  fs.writeFileSync(inputPath, JSON.stringify(plan));
  const result = spawnSync(process.execPath, [validator, '--input', inputPath, '--report', path.join(dir, '..', '..', 'outside-fault-report.json')], {cwd: repoRoot, encoding: 'utf8'});
  assert.notEqual(result.status, 0);
  fs.rmSync(dir, {recursive: true, force: true});
});
