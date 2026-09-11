import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const validator = path.join(root, 'tools', 'validate-feedback-lineage.mjs');

test('feedback lineage validator accepts a consistent completed bundle', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-feedback-lineage-'));
  try {
    const common = {
      answerId: 'answer-1', runId: 'run-1', traceId: 'trace-1', feedbackId: 'fb-1',
      feedbackRunId: 'n6b-test-1', evaluationRunId: 'eval-1', previousReleaseId: 'rel-old',
      publishedReleaseId: 'rel-new', operatorId: 'ops-1', lineageStatus: 'COMPLETE'
    };
    const feedback = path.join(dir, 'feedback.jsonl');
    const registry = path.join(dir, 'registry.json');
    const reanswer = path.join(dir, 'reanswer.jsonl');
    const report = path.join(dir, 'report.json');
    fs.writeFileSync(feedback, JSON.stringify({...common, status: 'CLOSED'}) + '\n');
    fs.writeFileSync(registry, JSON.stringify({schemaVersion: 'v1.1', entries: [{...common, status: 'PUBLISHED'}]}) + '\n');
    fs.writeFileSync(reanswer, JSON.stringify({...common, status: 'VERIFIED'}) + '\n');
    const result = spawnSync(process.execPath, [validator, '--feedback', feedback, '--registry', registry,
      '--reanswer', reanswer, '--published-release', 'rel-new', '--feedback-run-id', 'n6b-test-1',
      '--report', report], {cwd: root, encoding: 'utf8'});
    assert.equal(result.status, 0, result.stderr || result.stdout);
    assert.equal(JSON.parse(fs.readFileSync(report, 'utf8')).passed, true);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('feedback lineage validator rejects unknown and cross-release records', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-feedback-lineage-'));
  try {
    const base = {answerId: 'answer-1', runId: 'run-1', traceId: 'trace-unknown', feedbackId: 'fb-1',
      feedbackRunId: 'n6b-test-2', evaluationRunId: 'eval-1', previousReleaseId: 'rel-old',
      publishedReleaseId: 'rel-other', operatorId: 'ops-1', status: 'UNTRACEABLE'};
    const files = {
      feedback: path.join(dir, 'feedback.jsonl'), registry: path.join(dir, 'registry.json'),
      reanswer: path.join(dir, 'reanswer.jsonl'), report: path.join(dir, 'report.json')
    };
    fs.writeFileSync(files.feedback, JSON.stringify(base) + '\n');
    fs.writeFileSync(files.registry, JSON.stringify({entries: [{...base, traceId: 'trace-1', status: 'PUBLISHED'}]}));
    fs.writeFileSync(files.reanswer, JSON.stringify({...base, traceId: 'trace-1', status: 'VERIFIED'}) + '\n');
    const result = spawnSync(process.execPath, [validator, '--feedback', files.feedback,
      '--registry', files.registry, '--reanswer', files.reanswer, '--published-release', 'rel-new',
      '--feedback-run-id', 'n6b-test-2', '--report', files.report], {cwd: root, encoding: 'utf8'});
    assert.notEqual(result.status, 0);
    const parsed = JSON.parse(fs.readFileSync(files.report, 'utf8'));
    assert.equal(parsed.passed, false);
    assert.ok(parsed.errors.some((item) => item.includes('占位') || item.includes('不一致')));
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});
