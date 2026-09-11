import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const node = process.execPath;
const aggregate = path.join(root, 'tools', 'aggregate-recovery-drills.mjs');
const measure = path.join(root, 'tools', 'measure-recovery-slo.mjs');

test('recovery aggregation and RPO/RTO measurement require three independent passed drills', () => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp', 'recovery-slo-test-'));
  try {
    const inputs = [
      writeRow(dir, 'container.jsonl', 'container-01', 'container', 120, 600),
      writeRow(dir, 'database.jsonl', 'db-01', 'database', 240, 900),
      writeRow(dir, 'index.jsonl', 'index-01', 'index-or-dependency', 180, 700)
    ];
    const aggregateOutput = path.join(dir, 'recovery-drill.jsonl');
    const aggregateReport = path.join(dir, 'recovery-drill-aggregate.json');
    const merged = run(aggregate, '--input', inputs[0], '--input', inputs[1], '--input', inputs[2],
      '--output', aggregateOutput, '--report', aggregateReport, '--environment', 'staging', '--no-clobber');
    assert.equal(merged.status, 0, merged.stdout + merged.stderr);
    const mergedReport = read(aggregateReport);
    assert.equal(mergedReport.passed, true);
    assert.equal(mergedReport.sampleCount, 3);
    assert.equal(fs.readFileSync(aggregateOutput, 'utf8').trim().split(/\r?\n/).length, 3);

    const rpo = path.join(dir, 'drill-rpo.json');
    const rto = path.join(dir, 'drill-rto.json');
    const rpoMd = path.join(dir, 'drill-rpo.md');
    const rtoMd = path.join(dir, 'drill-rto.md');
    const measured = run(measure, '--drill-log', aggregateOutput,
      '--target-rpo-seconds', '900', '--target-rto-seconds', '1800',
      '--output-rpo', rpo, '--output-rto', rto,
      '--rpo-markdown', rpoMd, '--rto-markdown', rtoMd, '--no-clobber');
    assert.equal(measured.status, 0, measured.stdout + measured.stderr);
    assert.equal(read(rpo).actualSeconds, 240);
    assert.equal(read(rpo).targetSeconds, 900);
    assert.equal(read(rto).actualSeconds, 900);
    assert.equal(read(rto).sourceLogSha256.length, 64);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('pending/dry-run records never become successful recovery evidence', () => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp', 'recovery-slo-test-'));
  try {
    const pending = write(dir, 'pending.jsonl', JSON.stringify({
      schemaVersion: 'v1.1',
      producer: 'run-v1.1-recovery-drill',
      drillId: 'container-pending',
      failureType: 'container',
      environment: 'staging',
      projectName: 'vio-release-container-pending',
      approvalIdPresent: true,
      freshIsolatedProject: true,
      backupSha256: 'a'.repeat(64),
      previousManifestSha256: 'b'.repeat(64),
      status: 'environment-pending',
      passed: false,
      actualRpoSeconds: null,
      actualRtoSeconds: null,
      redacted: true
    }) + '\n');
    const output = path.join(dir, 'merged.jsonl');
    const report = path.join(dir, 'merged.json');
    const result = run(aggregate, '--input', pending, '--output', output, '--report', report, '--environment', 'staging');
    assert.notEqual(result.status, 0);
    const payload = read(report);
    assert.equal(payload.passed, false);
    assert.equal(payload.status, 'blocked');
    assert.equal(fs.existsSync(output), false);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('aggregate rejects secret-shaped input and never echoes the secret', () => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp', 'recovery-slo-test-'));
  const secret = 'recovery-secret-value-should-not-leak';
  try {
    const input = write(dir, 'secret.jsonl', JSON.stringify({
      schemaVersion: 'v1.1',
      drillId: 'container-secret',
      failureType: 'container',
      environment: 'staging',
      projectName: 'vio-release-container-secret',
      apiKey: secret
    }) + '\n');
    const report = path.join(dir, 'report.json');
    const result = run(aggregate, '--input', input, '--output', path.join(dir, 'out.jsonl'), '--report', report);
    assert.notEqual(result.status, 0);
    assert.equal((result.stdout + '\n' + result.stderr + '\n' + fs.readFileSync(report, 'utf8')).includes(secret), false);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

function writeRow(dir, name, drillId, failureType, rpo, rto) {
  const at = '2026-09-03T00:00:00.000Z';
  return write(dir, name, JSON.stringify({
    schemaVersion: 'v1.1',
    producer: 'run-v1.1-recovery-drill',
    drillId,
    failureType,
    generatedAt: at,
    startedAt: at,
    finishedAt: '2026-09-03T00:10:00.000Z',
    environment: 'staging',
    baseUrl: 'https://staging.example.invalid',
    projectName: 'vio-release-' + drillId,
    approvalIdPresent: true,
    freshIsolatedProject: true,
    backupFile: 'backups/staging.dump',
    backupSha256: 'a'.repeat(64),
    expectedSha256Present: true,
    checksumStatus: 'verified',
    backupTimestamp: at,
    previousManifest: 'artifacts/v1.1/release/previous-manifest.json',
    previousManifestSha256: 'b'.repeat(64),
    previousReleaseId: 'previous-ga',
    dataReleaseId: 'data-release',
    knowledgeReleaseId: 'knowledge-release',
    indexReleaseId: 'index-release',
    lastCommittedAt: at,
    restorePointAt: '2026-09-03T00:03:00.000Z',
    readinessAt: '2026-09-03T00:08:00.000Z',
    actualRpoSeconds: rpo,
    actualRtoSeconds: rto,
    targetRpoSeconds: 900,
    targetRtoSeconds: 1800,
    clockSource: 'utc-monotonic',
    status: 'passed',
    passed: true,
    reason: 'captured in isolated staging',
    redacted: true
  }) + '\n');
}
function write(dir, name, content) {
  const file = path.join(dir, name);
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, content, 'utf8');
  return file;
}
function read(file) { return JSON.parse(fs.readFileSync(file, 'utf8')); }
function run(script, ...args) { return spawnSync(node, [script, ...args], {cwd: root, encoding: 'utf8', windowsHide: true}); }
