import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const runner = path.join(root, 'evaluation', 'run-v1.1-eval.mjs');

test('runner writes explicit pending outputs when case results are absent', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-eval-runner-'));
  try {
    const output = path.join(dir, 'metrics.json');
    const run = runRunner([
      '--evaluation-run-id', 'test-pending-001', '--run-dir', dir, '--output', output
    ]);
    assert.notEqual(run.status, 0);
    const stdout = run.stdout;
    const report = JSON.parse(fs.readFileSync(output, 'utf8'));
    assert.equal(report.status, 'pending');
    assert.equal(report.sampleCount, 0);
    assert.equal(report.suites.length, 7);
    assert.match(stdout, /未提供 --case-results/);
    assert.equal(report.results.some((item) => item.value !== null && item.sampleCount === 0), false);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('runner rejects unknown case ids instead of mixing suites', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-eval-runner-'));
  try {
    const caseFile = path.join(dir, 'case-results.json');
    const output = path.join(dir, 'metrics.json');
    fs.writeFileSync(caseFile, JSON.stringify([{caseId: 'not-in-manifest', passed: true}]) + '\n');
    const run = runRunner([
      '--evaluation-run-id', 'test-invalid-001', '--run-dir', dir, '--output', output,
      '--case-results', caseFile
    ]);
    assert.notEqual(run.status, 0);
    const stdout = run.stdout;
    const report = JSON.parse(fs.readFileSync(output, 'utf8'));
    assert.equal(report.status, 'blocked');
    assert.ok(report.errors.some((item) => item.includes('未知 caseId')));
    assert.match(stdout, /未知 caseId/);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('runner preserves accepted case lineage and computes only observed metrics', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-eval-runner-'));
  try {
    const caseFile = path.join(dir, 'case-results.json');
    const output = path.join(dir, 'metrics.json');
    fs.writeFileSync(caseFile, JSON.stringify([
      {caseId: 'fact-0001', suiteId: 'fact-v1', passed: true, traceId: 'trace-1',
        lineage: {gameReleaseId: 'pokemon-scarlet-violet', dataReleaseId: 'pokemon-scarlet-violet-data-v1'},
        evidence: {documentId: 'doc-1', chunkId: 'chunk-1'}, ttftMs: 100, durationMs: 500}
    ]) + '\n');
    const run = runRunner([
      '--evaluation-run-id', 'test-observed-001', '--run-dir', dir, '--output', output,
      '--case-results', caseFile, '--model-version', 'model-test', '--prompt-version', 'prompt-test',
      '--data-release-id', 'data-test', '--knowledge-release-id', 'knowledge-test',
      '--index-release-id', 'index-test'
    ]);
    assert.notEqual(run.status, 0);
    const stdout = run.stdout;
    const report = JSON.parse(fs.readFileSync(output, 'utf8'));
    const fact = report.suites.find((item) => item.suiteId === 'fact-v1');
    assert.equal(fact.sampleCount, 1);
    const factRun = JSON.parse(fs.readFileSync(path.join(dir, 'evaluation-run-fact-v1-test-observed-001.json'), 'utf8'));
    assert.equal(factRun.suite.caseResults[0].traceId, 'trace-1');
    assert.equal(report.status, 'pending');
    assert.match(stdout, /样本不足/);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

function runRunner(argumentsList) {
  const result = spawnSync(process.execPath, [runner, ...argumentsList], {
    cwd: root, encoding: 'utf8'
  });
  return {status: result.status, stdout: String(result.stdout ?? ''), stderr: String(result.stderr ?? '')};
}
