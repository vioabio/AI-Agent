import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const validator = path.join(root, 'tools', 'validate-evaluation-v1.1.mjs');

test('v1.1 evaluation contract keeps suite and metric sample floors aligned', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-eval-alignment-'));
  try {
    const report = path.join(dir, 'report.json');
    const result = runValidator([
      '--manifest', 'evaluation/v1.1/manifest.json',
      '--dictionary', 'evaluation/v1.1/metric-dictionary.json',
      '--report', report
    ]);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const value = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(value.passed, true);
    assert.equal(value.errors.length, 0);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('v1.1 evaluation contract rejects metric denominator drift', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-eval-alignment-'));
  try {
    const dictionary = JSON.parse(fs.readFileSync(
      path.join(root, 'evaluation', 'v1.1', 'metric-dictionary.json'), 'utf8'));
    const metric = dictionary.metrics.find((item) => item.metricId === 'no_answer_handling_rate');
    metric.minRequests = 100;
    metric.minimumSamples = 100;
    const dictionaryPath = path.join(dir, 'dictionary.json');
    const report = path.join(dir, 'report.json');
    fs.writeFileSync(dictionaryPath, `${JSON.stringify(dictionary, null, 2)}\n`, 'utf8');

    const result = runValidator([
      '--manifest', 'evaluation/v1.1/manifest.json',
      '--dictionary', dictionaryPath,
      '--report', report
    ]);
    assert.notEqual(result.status, 0);
    const value = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(value.passed, false);
    assert.ok(value.errors.some((item) => item.includes('no_answer_handling_rate')
      && item.includes('no-answer-v1.minimumCases=50')));
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

function runValidator(argumentsList) {
  return spawnSync(process.execPath, [validator, ...argumentsList], {
    cwd: root,
    encoding: 'utf8'
  });
}
