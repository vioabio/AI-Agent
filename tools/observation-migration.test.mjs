import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const validate = path.join(root, 'tools', 'validate-observation.mjs');
const aggregate = path.join(root, 'tools', 'aggregate-observation.mjs');
const migration = path.join(root, 'tools', 'check-migration-compatibility.mjs');

function run(script, args) {
  return spawnSync(process.execPath, [script, ...args], {cwd: root, encoding: 'utf8'});
}

function windowRow(id, start, end, count = 2) {
  return {schemaVersion: 'v1.1', windowId: id, releaseId: 'v1.1-test-release', environment: 'staging',
    windowStart: start, windowEnd: end, requestCount: count,
    status: 'passed', passed: true, source: 'live-http-and-prometheus',
    sourceChannels: ['http-readiness', 'prometheus-metrics'],
    sourceEvidence: {
      http: {kind: 'http-readiness', observedAt: start},
      prometheus: {kind: 'prometheus-metrics', observedAt: end}
    }};
}

test('observation validator accepts explicit complete staging windows', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-observation-'));
  try {
    const input = path.join(dir, 'windows.jsonl');
    const report = path.join(dir, 'report.json');
    fs.writeFileSync(input, [
      windowRow('w1', '2026-09-01T00:00:00Z', '2026-09-01T01:00:00Z'),
      windowRow('w2', '2026-09-01T01:00:00Z', '2026-09-01T02:00:00Z')
    ].map(JSON.stringify).join('\n') + '\n');
    const result = run(validate, ['--input', input, '--output', report, '--release-id', 'v1.1-test-release',
      '--environment', 'staging', '--min-hours', '1', '--min-requests', '4', '--min-requests-per-window', '2']);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(parsed.status, 'passed');
    assert.equal(parsed.totalRequests, 4);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('observation validator fails closed on insufficient production evidence', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-observation-'));
  try {
    const input = path.join(dir, 'windows.jsonl');
    const report = path.join(dir, 'report.json');
    fs.writeFileSync(input, JSON.stringify({...windowRow('w1', '2026-09-01T00:00:00Z', '2026-09-01T00:10:00Z', 1), environment: 'prod', source: 'staging'}) + '\n');
    const result = run(validate, ['--input', input, '--output', report, '--release-id', 'v1.1-test-release',
      '--environment', 'prod', '--min-hours', '1', '--min-requests', '10', '--min-requests-per-window', '2']);
    assert.notEqual(result.status, 0);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(parsed.status, 'blocked');
    assert.ok(parsed.errors.some((item) => item.includes('staging') || item.includes('不足')));
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('observation validator rejects pending rows even when the denominator is large', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-observation-'));
  try {
    const input = path.join(dir, 'windows.jsonl');
    const report = path.join(dir, 'report.json');
    fs.writeFileSync(input, JSON.stringify({
      ...windowRow('pending', '2026-09-01T00:00:00Z', '2026-09-01T02:00:00Z', 500),
      status: 'environment-pending', passed: false, source: 'not-executed'
    }) + '\n');
    const result = run(validate, ['--input', input, '--output', report, '--release-id', 'v1.1-test-release',
      '--environment', 'staging', '--min-hours', '1', '--min-requests', '500', '--min-requests-per-window', '20']);
    assert.notEqual(result.status, 0);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(parsed.status, 'blocked');
    assert.ok(parsed.errors.some((item) => item.includes('现场探针')));
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('observation validator rejects a single self-reported 72h/500-request row', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-observation-'));
  try {
    const input = path.join(dir, 'windows.jsonl');
    const report = path.join(dir, 'report.json');
    // The summary fields look sufficient, but the timestamped interval is
    // only five minutes and there is no verifiable window sequence.
    fs.writeFileSync(input, JSON.stringify({
      ...windowRow('fake', '2026-09-01T00:00:00Z', '2026-09-01T00:05:00Z', 500),
      observationHours: 72
    }) + '\n');
    const result = run(validate, ['--input', input, '--output', report, '--release-id', 'v1.1-test-release',
      '--environment', 'staging', '--min-hours', '72', '--min-requests', '500', '--min-requests-per-window', '20']);
    assert.notEqual(result.status, 0);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(parsed.status, 'blocked');
    assert.ok(parsed.errors.some((item) => item.includes('单条自报') || item.includes('实际窗口跨度')));
    assert.ok(parsed.errors.some((item) => item.includes('观察时长不足')));
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('observation validator rejects a gap and a single-source window', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-observation-'));
  try {
    const input = path.join(dir, 'windows.jsonl');
    const report = path.join(dir, 'report.json');
    const first = windowRow('w1', '2026-09-01T00:00:00Z', '2026-09-01T01:00:00Z', 20);
    const second = {...windowRow('w2', '2026-09-01T02:00:00Z', '2026-09-01T03:00:00Z', 20), source: 'live-http',
      sourceChannels: ['http'], sourceEvidence: {http: {kind: 'http-readiness', observedAt: '2026-09-01T02:00:00Z'}}};
    fs.writeFileSync(input, [first, second].map(JSON.stringify).join('\n') + '\n');
    const result = run(validate, ['--input', input, '--output', report, '--release-id', 'v1.1-test-release',
      '--environment', 'staging', '--min-hours', '2', '--min-requests', '40', '--min-requests-per-window', '20']);
    assert.notEqual(result.status, 0);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.ok(parsed.errors.some((item) => item.includes('不连续')));
    assert.ok(parsed.errors.some((item) => item.includes('独立') || item.includes('source')));
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('observation validator tolerates a sub-minute scheduler boundary gap but uses covered time', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-observation-'));
  try {
    const input = path.join(dir, 'windows.jsonl');
    const report = path.join(dir, 'report.json');
    const first = windowRow('w1', '2026-09-01T00:00:00Z', '2026-09-01T01:00:00Z', 20);
    // 500 ms is within the collector's boundary tolerance.
    const second = windowRow('w2', '2026-09-01T01:00:00.500Z', '2026-09-01T02:00:00.500Z', 20);
    fs.writeFileSync(input, [first, second].map(JSON.stringify).join('\n') + '\n');
    const result = run(validate, ['--input', input, '--output', report, '--release-id', 'v1.1-test-release',
      '--environment', 'staging', '--min-hours', '1.9', '--min-requests', '40', '--min-requests-per-window', '20']);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(parsed.status, 'passed');
    assert.equal(parsed.gapCount, 1);
    assert.equal(parsed.maxGapSeconds, 0.5);
    assert.ok(parsed.coveredDurationHours >= 1.9);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('observation validator rejects telemetry evidence whose denominator disagrees with the window', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-observation-'));
  try {
    const input = path.join(dir, 'windows.jsonl');
    const report = path.join(dir, 'report.json');
    const first = windowRow('w1', '2026-09-01T00:00:00Z', '2026-09-01T01:00:00Z', 20);
    const second = {...windowRow('w2', '2026-09-01T01:00:00Z', '2026-09-01T02:00:00Z', 20),
      sourceEvidence: {
        http: {kind: 'http-readiness', observedAt: '2026-09-01T01:00:00Z'},
        prometheus: {kind: 'prometheus-metrics', observedAt: '2026-09-01T02:00:00Z', requestCount: 19}
      }};
    fs.writeFileSync(input, [first, second].map(JSON.stringify).join('\n') + '\n');
    const result = run(validate, ['--input', input, '--output', report, '--release-id', 'v1.1-test-release',
      '--environment', 'staging', '--min-hours', '1', '--min-requests', '40', '--min-requests-per-window', '20']);
    assert.notEqual(result.status, 0);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.ok(parsed.errors.some((item) => item.includes('分母不一致')));
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('observation validator rejects overlapping or unsorted windows', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-observation-'));
  try {
    const input = path.join(dir, 'windows.jsonl');
    const report = path.join(dir, 'report.json');
    fs.writeFileSync(input, [
      windowRow('w1', '2026-09-01T01:00:00Z', '2026-09-01T03:00:00Z', 20),
      windowRow('w2', '2026-09-01T02:00:00Z', '2026-09-01T04:00:00Z', 20)
    ].map(JSON.stringify).join('\n') + '\n');
    const result = run(validate, ['--input', input, '--output', report, '--release-id', 'v1.1-test-release',
      '--environment', 'staging', '--min-hours', '1', '--min-requests', '40', '--min-requests-per-window', '20']);
    assert.notEqual(result.status, 0);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(parsed.status, 'blocked');
    assert.ok(parsed.errors.some((item) => item.includes('重叠') || item.includes('排序')));
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('observation aggregator deterministically sorts multiple inputs', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-observation-'));
  try {
    const first = path.join(dir, 'first.jsonl');
    const second = path.join(dir, 'second.jsonl');
    const output = path.join(dir, 'merged.jsonl');
    const report = path.join(dir, 'report.json');
    fs.writeFileSync(first, JSON.stringify(windowRow('w2', '2026-09-01T01:00:00Z', '2026-09-01T02:00:00Z')) + '\n');
    fs.writeFileSync(second, JSON.stringify(windowRow('w1', '2026-09-01T00:00:00Z', '2026-09-01T01:00:00Z')) + '\n');
    const result = run(aggregate, ['--input', first, '--input', second, '--output', output, '--report', report,
      '--release-id', 'v1.1-test-release', '--environment', 'staging']);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const ids = fs.readFileSync(output, 'utf8').trim().split(/\r?\n/).map((line) => JSON.parse(line).windowId);
    assert.deepEqual(ids, ['w1', 'w2']);
    assert.equal(JSON.parse(fs.readFileSync(report, 'utf8')).passed, true);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('observation aggregator refuses pending source rows', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-observation-'));
  try {
    const input = path.join(dir, 'pending.jsonl');
    const output = path.join(dir, 'merged.jsonl');
    const report = path.join(dir, 'report.json');
    fs.writeFileSync(input, JSON.stringify({
      ...windowRow('pending', '2026-09-01T00:00:00Z', '2026-09-01T01:00:00Z', 100),
      status: 'environment-pending', passed: false, source: 'not-executed'
    }) + '\n');
    const result = run(aggregate, ['--input', input, '--output', output, '--report', report,
      '--release-id', 'v1.1-test-release', '--environment', 'staging']);
    assert.notEqual(result.status, 0);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(parsed.status, 'blocked');
    assert.ok(parsed.errors.some((item) => item.includes('现场探针')));
    assert.equal(fs.existsSync(output), false);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('observation aggregator refuses a single summary row even with 72h fields', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-observation-'));
  try {
    const input = path.join(dir, 'summary.jsonl');
    const output = path.join(dir, 'merged.jsonl');
    const report = path.join(dir, 'report.json');
    fs.writeFileSync(input, JSON.stringify({
      ...windowRow('summary', '2026-09-01T00:00:00Z', '2026-09-01T00:05:00Z', 500),
      observationHours: 72
    }) + '\n');
    const result = run(aggregate, ['--input', input, '--output', output, '--report', report,
      '--release-id', 'v1.1-test-release', '--environment', 'staging']);
    assert.notEqual(result.status, 0);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(parsed.status, 'blocked');
    assert.equal(fs.existsSync(output), false);
    assert.ok(parsed.errors.some((item) => item.includes('单条自报') || item.includes('至少需要')));
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('migration compatibility gate emits explicit environment-pending status', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-migration-'));
  try {
    const report = path.join(dir, 'report.json');
    const result = run(migration, ['--matrix', 'config/migration-compatibility-v1.1.example.json', '--output', report, '--allow-pending']);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(parsed.status, 'environment-pending');
    assert.equal(parsed.passed, false);
    assert.equal(parsed.checks.find((item) => item.name === 'execution-adapter').passed, false);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('migration compatibility execute mode remains fail closed', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-migration-'));
  try {
    const report = path.join(dir, 'report.json');
    const result = run(migration, ['--matrix', 'config/migration-compatibility-v1.1.example.json', '--output', report, '--execute', '--allow-pending']);
    assert.notEqual(result.status, 0);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(parsed.status, 'blocked');
    assert.ok(parsed.errors.some((item) => item.includes('适配器')));
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});
