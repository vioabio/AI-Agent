import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import crypto from 'node:crypto';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const node = process.execPath;

test('N8 final gate promotes only a complete, hash-bound evidence bundle', () => {
  const fixture = createFixture();
  const result = runGate(fixture, false);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const output = JSON.parse(fs.readFileSync(fixture.output, 'utf8'));
  assert.equal(output.status, 'GA');
  assert.equal(output.releaseClass, 'GA');
  assert.equal(output.environment, 'prod');
  assert.equal(output.evidence.rpoRto.rpoSeconds, 10);
  assert.equal(output.evidence.rpoRto.rtoSeconds, 20);
  const report = JSON.parse(fs.readFileSync(fixture.report, 'utf8'));
  assert.equal(report.passed, true);
  assert.equal(report.redacted, true);
  assert.equal(JSON.stringify(report).includes('secret-value'), false);
  const validation = run(node, ['tools/validate-release-manifest.mjs', '--repo-root', fixture.root,
    '--manifest', fixture.output, '--schema', path.join(fixture.root, 'contracts/release-manifest-v1.1.schema.json'),
    '--allow-status', 'GA', '--require-status', 'GA', '--require-release-class', 'GA',
    '--require-environment', 'prod', '--report', fixture.validationReport]);
  assert.equal(validation.status, 0, validation.stdout + validation.stderr);
  cleanup(fixture.root);
});

test('N8 gate fails closed on tampered observation evidence and leaves no promoted manifest', () => {
  const fixture = createFixture();
  fs.appendFileSync(fixture.observation, '\n{"windowId":"tampered","requestCount":20,"windowStart":"2026-01-04T00:00:00Z","windowEnd":"2026-01-04T03:00:00Z","releaseId":"n8-test-ga","environment":"prod"}\n');
  const result = runGate(fixture, false);
  assert.notEqual(result.status, 0);
  assert.equal(fs.existsSync(fixture.output), false);
  const report = JSON.parse(fs.readFileSync(fixture.report, 'utf8'));
  assert.equal(report.passed, false);
  assert.equal(report.status, 'blocked');
  assert.equal(JSON.stringify(report).includes('secret-value'), false);
  cleanup(fixture.root);
});

test('N8 gate rejects hash-bound pending observation rows with a large denominator', () => {
  const fixture = createFixture();
  const rows = fs.readFileSync(fixture.observation, 'utf8').trim().split(/\r?\n/)
    .map((line) => ({...JSON.parse(line), status: 'environment-pending', passed: false, source: 'not-executed'}));
  fs.writeFileSync(fixture.observation, rows.map(JSON.stringify).join('\n') + '\n', 'utf8');
  const manifest = JSON.parse(fs.readFileSync(fixture.preflight, 'utf8'));
  manifest.evidence.observation.sha256 = hash(fixture.observation);
  fs.writeFileSync(fixture.preflight, JSON.stringify(manifest, null, 2), 'utf8');

  const result = runGate(fixture, false);
  assert.notEqual(result.status, 0);
  assert.equal(fs.existsSync(fixture.output), false);
  const report = JSON.parse(fs.readFileSync(fixture.report, 'utf8'));
  assert.equal(report.status, 'blocked');
  assert.ok(report.errors.some((item) => item.includes('现场探针')));
  cleanup(fixture.root);
});

test('N8 gate rejects a single self-reported observationHours/requestCount summary', () => {
  const fixture = createFixture();
  const first = JSON.parse(fs.readFileSync(fixture.observation, 'utf8').trim().split(/\r?\n/)[0]);
  const fake = {
    ...first,
    windowId: 'fake-summary',
    windowStart: '2026-01-01T00:00:00Z',
    windowEnd: '2026-01-01T00:05:00Z',
    requestCount: 500,
    observationHours: 72,
    status: 'passed',
    passed: true
  };
  fs.writeFileSync(fixture.observation, JSON.stringify(fake) + '\n', 'utf8');
  const manifest = JSON.parse(fs.readFileSync(fixture.preflight, 'utf8'));
  manifest.evidence.observation.sha256 = hash(fixture.observation);
  fs.writeFileSync(fixture.preflight, JSON.stringify(manifest, null, 2), 'utf8');

  const result = runGate(fixture, false);
  assert.notEqual(result.status, 0);
  assert.equal(fs.existsSync(fixture.output), false);
  const report = JSON.parse(fs.readFileSync(fixture.report, 'utf8'));
  assert.equal(report.status, 'blocked');
  assert.ok(report.errors.some((item) => item.includes('单条自报') || item.includes('实际窗口跨度')));
  assert.ok(report.errors.some((item) => item.includes('观察期时长') || item.includes('观察窗口')));
  cleanup(fixture.root);
});

test('N8 preflight mode accepts only BLOCKED input and never writes a GA manifest', () => {
  const fixture = createFixture();
  const result = runGate(fixture, true);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.equal(fs.existsSync(fixture.output), false);
  const report = JSON.parse(fs.readFileSync(fixture.report, 'utf8'));
  assert.equal(report.mode, 'preflight');
  assert.equal(report.passed, true);
  cleanup(fixture.root);
});

function createFixture() {
  const root = fs.mkdtempSync(path.join(repoRoot, 'tmp', 'n8-gate-test-'));
  const artifact = path.join(root, 'artifacts', 'v1.1');
  const releaseId = 'n8-test-ga';
  const prod = path.join(artifact, 'prod', releaseId);
  const obs = path.join(prod, 'observability');
  const feedbackDir = path.join(artifact, 'test', 'feedback-001');
  const releaseDir = path.join(artifact, 'release');
  const baseline = path.join(artifact, 'baseline', 'manifest.json');
  const preflight = path.join(releaseDir, 'preflight.json');
  const output = path.join(releaseDir, 'final.json');
  const report = path.join(releaseDir, 'gate-report.json');
  const validationReport = path.join(releaseDir, 'manifest-validation.json');
  mkdirs(prod, obs, feedbackDir, releaseDir, path.dirname(baseline));
  mkdirs(path.join(root, 'contracts'));
  fs.copyFileSync(path.join(repoRoot, 'contracts', 'release-manifest-v1.1.schema.json'), path.join(root, 'contracts', 'release-manifest-v1.1.schema.json'));
  const backend = `registry.invalid/vio/backend@sha256:${'a'.repeat(64)}`;
  const frontend = `registry.invalid/vio/frontend@sha256:${'b'.repeat(64)}`;
  write(baseline, JSON.stringify({schemaVersion: 'v1.1', baselineId: 'n0-test'}, null, 2));
  const readiness = write(path.join(prod, 'readiness.json'), JSON.stringify({schemaVersion: 'v1.1', passed: true, status: 'ready', releaseId, environment: 'prod', imageDigest: backend}));
  const probes = write(path.join(prod, 'business-probes.jsonl'), [
    {probeId: 'spa', passed: true, releaseId, environment: 'prod'},
    {probeId: 'run', passed: true, releaseId, environment: 'prod'}
  ].map(JSON.stringify).join('\n') + '\n');
  const observationRows = [];
  for (let index = 0; index < 25; index += 1) {
    const start = new Date(Date.UTC(2026, 0, 1, index * 3, 0, 0));
    const end = new Date(start.getTime() + 3 * 3600000);
    observationRows.push(JSON.stringify({
      schemaVersion: 'v1.1', windowId: `w-${index}`, requestCount: 20,
      windowStart: start.toISOString(), windowEnd: end.toISOString(),
      releaseId, environment: 'prod', status: 'passed', passed: true,
      source: 'live-http-and-prometheus',
      sourceChannels: ['http-readiness', 'prometheus-metrics'],
      sourceEvidence: {
        http: {kind: 'http-readiness', observedAt: start.toISOString()},
        prometheus: {kind: 'prometheus-metrics', observedAt: end.toISOString()}
      }
    }));
  }
  const observation = write(path.join(prod, 'observation-72h.jsonl'), observationRows.join('\n') + '\n');
  const observationValidation = write(path.join(prod, 'observation-validation.json'), JSON.stringify({schemaVersion: 'v1.1', passed: true, status: 'passed', releaseId, environment: 'prod', totalRequests: 500, observationHours: 72}));
  for (const [name, content] of [
    ['targets-alerts-trace.md', '# redacted observability evidence\n'],
    ['prometheus-targets.json', '{"passed":true}\n'],
    ['prometheus-rules.json', '{"passed":true}\n'],
    ['alertmanager-events.json', '{"passed":true}\n'],
    ['grafana-health.json', '{"passed":true}\n'],
    ['fault-injection-timeline.md', '# rollback test\n'],
    ['fault-plan-validation.json', '{"passed":true}\n']
  ]) write(path.join(obs, name), content);
  const rollback = write(path.join(prod, 'rollback-drill.jsonl'), JSON.stringify({drillId: 'r1', passed: true, releaseId, environment: 'prod'}) + '\n');
  const config = write(path.join(prod, 'promote-config-snapshot.json'), 'releaseId: n8-test-ga\napiKey: [REDACTED]\n');
  const compose = write(path.join(prod, 'release-compose-config-effective.txt'), `services:\n  backend:\n    image: ${backend}\n`);
  const feedbackRow = {feedbackId: 'fb-1', answerId: 'answer-1', runId: 'run-1', traceId: 'trace-1', releaseId, environment: 'prod', dataReleaseId: 'data-1', knowledgeReleaseId: 'knowledge-1', indexReleaseId: 'index-1', status: 'verified'};
  const feedback = write(path.join(feedbackDir, 'n6b-reanswer-verification.jsonl'), JSON.stringify(feedbackRow) + '\n');
  const registry = write(path.join(feedbackDir, 'n6-answer-release-registry.json'), JSON.stringify({schemaVersion: 'v1.1', records: [feedbackRow]}));
  const lineage = write(path.join(feedbackDir, 'n6b-lineage-validation.json'), JSON.stringify({schemaVersion: 'v1.1', passed: true, untraceableCount: 0, publishedReleaseId: releaseId}));
  const feedbackHashes = write(path.join(feedbackDir, 'n6b-feedback-evidence-hashes.json'), JSON.stringify({files: [{path: path.relative(root, feedback).split(path.sep).join('/'), sha256: hash(feedback)}]}));
  const rpo = write(path.join(root, 'artifacts', 'v1.1', 'recovery-rpo.json'), JSON.stringify({passed: true, actualSeconds: 10, targetSeconds: 60}));
  const rto = write(path.join(root, 'artifacts', 'v1.1', 'recovery-rto.json'), JSON.stringify({passed: true, actualSeconds: 20, targetSeconds: 60}));
  const previous = write(path.join(releaseDir, 'previous.json'), JSON.stringify({schemaVersion: 'v1.1', releaseId: 'old-ga', status: 'GA'}));
  const previousResolution = write(path.join(releaseDir, 'previous-resolution.json'), JSON.stringify({schemaVersion: 'v1.1', status: 'resolved', releaseId: 'old-ga'}));
  const active = write(path.join(prod, 'active-release.json'), JSON.stringify({schemaVersion: 'v1.1', releaseId, environment: 'prod', imageDigest: backend}));
  const evalDir = path.join(artifact, 'eval', 'eval-1');
  const evaluationManifest = write(path.join(evalDir, 'evaluation-manifest.json'), '{"schemaVersion":"v1.1","status":"ready"}\n');
  const metricDictionary = write(path.join(evalDir, 'metric-dictionary.json'), '{"schemaVersion":"v1.1","status":"ready","metrics":[] }\n');
  const evaluationReport = write(path.join(evalDir, 'release-report.json'), '{"schemaVersion":"v1.1","status":"passed","passed":true}\n');
  const imageRefs = write(path.join(releaseDir, 'image-refs.json'), '{"schemaVersion":"v1.1","status":"ready"}\n');
  const backendSupply = write(path.join(releaseDir, 'backend-supply-chain.json'), '{"passed":true}\n');
  const frontendSupply = write(path.join(releaseDir, 'frontend-supply-chain.json'), '{"passed":true}\n');
  const dependencyInventory = write(path.join(releaseDir, 'dependency-inventory.json'), '{"schemaVersion":"v1.1","status":"ready"}\n');
  const vulnerabilityExceptions = write(path.join(releaseDir, 'vulnerability-exceptions.json'), '{"schemaVersion":"v1.1","status":"ready","scanComplete":true}\n');
  const vulnerabilityValidation = write(path.join(releaseDir, 'vulnerability-exceptions-validation.json'), '{"schemaVersion":"v1.1","passed":true}\n');
  const evidenceHashes = write(path.join(prod, 'evidence-hashes.json'), '{"schemaVersion":"v1.1","status":"ready"}\n');
  const evidence = {
    evaluationManifest: descriptor(root, evaluationManifest),
    metricDictionary: descriptor(root, metricDictionary),
    evaluationReport: descriptor(root, evaluationReport),
    images: {backend: {supplyChain: descriptor(root, backendSupply)}, frontend: {supplyChain: descriptor(root, frontendSupply)}, resolver: descriptor(root, imageRefs)},
    dependencyInventory: descriptor(root, dependencyInventory),
    vulnerabilityExceptions: descriptor(root, vulnerabilityExceptions),
    vulnerabilityExceptionsValidation: descriptor(root, vulnerabilityValidation),
    rpoRto: {rpoSeconds: null, rtoSeconds: null, targetRpoSeconds: 60, targetRtoSeconds: 60, machineReport: [descriptor(root, rpo), descriptor(root, rto)]},
    readiness: descriptor(root, readiness),
    observation: descriptor(root, observation),
    observationValidation: descriptor(root, observationValidation),
    businessProbes: descriptor(root, probes),
    rollback: descriptor(root, rollback),
    configSnapshot: descriptor(root, config),
    composeConfig: descriptor(root, compose),
    observability: descriptor(root, path.join(obs, 'targets-alerts-trace.md')),
    evidenceHashes: descriptor(root, evidenceHashes),
    feedback: descriptor(root, feedback),
    feedbackRegistry: descriptor(root, registry)
  };
  const manifest = {
    schemaVersion: 'v1.1', artifactRoot: 'artifacts/v1.1', repoRoot: '.', releaseClass: 'GA', environment: 'prod',
    observationWindowHours: 72, minRequests: 500, minRequestsPerWindow: 20, releaseId, generatedAt: '2026-09-03T00:00:00Z',
    gitCommit: 'c'.repeat(40), migrationVersion: 'V23', modelVersion: 'model-1', promptVersion: 'prompt-1',
    dataReleaseId: 'data-1', knowledgeReleaseId: 'knowledge-1', indexReleaseId: 'index-1', evaluationRunId: 'eval-1',
    images: {backend, frontend}, embedding: {provider: 'test', model: 'embed-1', dimension: 1536, preprocessingProfile: 'p1', indexProfile: 'i1'},
    transportMode: 'sse', supportMatrix: {game_release_id: 'pokemon-scarlet-violet', language: 'zh-CN', battle_format_id: 'casual-singles', ruleset_version_id: 'rules-1', data_release_id: 'data-1', knowledge_release_id: 'knowledge-1', index_release_id: 'index-1'},
    evidence,
    rollback: {readinessRequired: true, indexRollback: 'switch active pointer'}, status: 'BLOCKED'
  };
  write(preflight, JSON.stringify(manifest, null, 2));
  return {root, preflight, output, report, validationReport, readiness, probes, observation, observationValidation, rollback, config, compose, feedback, registry, lineage, feedbackHashes, rpo, rto, previous, previousResolution, active, releaseId, backend};
}

function runGate(fixture, preflightOnly) {
  const args = ['tools/v1.1-release-gate.mjs', '--repo-root', fixture.root, '--baseline-manifest', path.join(fixture.root, 'artifacts/v1.1/baseline/manifest.json'), '--manifest', fixture.preflight, '--release-class', 'GA', '--environment', 'prod', '--readiness', fixture.readiness, '--observability-dir', path.join(fixture.root, 'artifacts/v1.1/prod', fixture.releaseId, 'observability'), '--business-probes', fixture.probes, '--observation', fixture.observation, '--observation-validation', fixture.observationValidation, '--feedback', fixture.feedback, '--feedback-registry', fixture.registry, '--feedback-lineage-validation', fixture.lineage, '--feedback-hashes', fixture.feedbackHashes, '--rollback', fixture.rollback, '--rpo-report', fixture.rpo, '--rto-report', fixture.rto, '--previous-manifest', fixture.previous, '--previous-resolution', fixture.previousResolution, '--active-release', fixture.active, '--config-snapshot', fixture.config, '--compose-config', fixture.compose, '--min-observation-hours', '72', '--min-observation-requests', '500', '--min-requests-per-window', '20', '--report', fixture.report, '--no-clobber'];
  if (!preflightOnly) args.push('--output-manifest', fixture.output);
  else args.push('--preflight-only');
  return run(node, args);
}

function run(command, args) {
  return spawnSync(command, args, {cwd: repoRoot, encoding: 'utf8', windowsHide: true});
}
function write(file, content) { fs.mkdirSync(path.dirname(file), {recursive: true}); fs.writeFileSync(file, content, 'utf8'); return file; }
function mkdirs(...dirs) { dirs.forEach((dir) => fs.mkdirSync(dir, {recursive: true})); }
function hash(file) { return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'); }
function descriptor(root, file) { return {path: path.relative(path.join(root, 'artifacts/v1.1'), file).split(path.sep).join('/'), sha256: hash(file)}; }
function cleanup(root) { fs.rmSync(root, {recursive: true, force: true}); }
