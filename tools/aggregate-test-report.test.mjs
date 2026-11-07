import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const toolsRoot = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.dirname(toolsRoot);

test('Surefire aggregate keeps test errors numeric and validation errors separate', () => {
  const temporaryParent = path.join(repoRoot, 'tmp');
  fs.mkdirSync(temporaryParent, {recursive: true});
  const temporaryRoot = fs.mkdtempSync(path.join(temporaryParent, 'aggregate-test-report-'));
  const surefire = path.join(temporaryRoot, 'surefire');
  const output = path.join(temporaryRoot, 'aggregate.xml');
  const summary = path.join(temporaryRoot, 'summary.json');
  fs.mkdirSync(surefire, {recursive: true});
  fs.writeFileSync(path.join(surefire, 'TEST-first.xml'),
    '<testsuite name="first" tests="3" failures="0" errors="0" skipped="1"></testsuite>\n');
  fs.writeFileSync(path.join(surefire, 'TEST-second.xml'),
    '<testsuite name="second" tests="2" failures="0" errors="0" skipped="0"></testsuite>\n');

  try {
    const result = spawnSync(process.execPath, [
      path.join(toolsRoot, 'aggregate-test-report.mjs'),
      '--surefire', surefire,
      '--output', output,
      '--summary', summary,
      '--run-start', '2020-01-01T00:00:00Z'
    ], {cwd: repoRoot, encoding: 'utf8', windowsHide: true});
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    const report = JSON.parse(fs.readFileSync(summary, 'utf8'));
    assert.equal(report.status, 'captured');
    assert.equal(report.tests, 5);
    assert.equal(report.failures, 0);
    assert.equal(report.errors, 0);
    assert.equal(report.skipped, 1);
    assert.deepEqual(report.validationErrors, []);
    assert.equal(report.passed, true);
  } finally {
    fs.rmSync(temporaryRoot, {recursive: true, force: true});
  }
});
