import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const node = process.execPath;

test('N8 pointer producers resolve, register and verify a hash-bound release chain', () => {
  const fixture = createFixture();
  try {
    const activeOutput = path.join(fixture.root, 'out', 'active-resolution.json');
    const activeReport = path.join(fixture.root, 'out', 'active-resolution-report.json');
    let result = runTool('resolve-active-release.mjs', [
      '--repo-root', fixture.root, '--registry-file', fixture.registry,
      '--environment', 'prod', '--active-pointer', '--require-environment', 'prod',
      '--require-release-class', 'GA', '--require-status', 'GA',
      '--expected-release-id', 'current-ga', '--expected-manifest', fixture.current,
      '--schema', fixture.schema, '--output', activeOutput, '--report', activeReport,
      '--no-clobber'
    ]);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(read(activeOutput).status, 'resolved');
    assert.equal(read(activeOutput).releaseId, 'current-ga');
    assert.equal(read(activeReport).passed, true);

    const previousOutput = path.join(fixture.root, 'out', 'previous-manifest.json');
    const previousReport = path.join(fixture.root, 'out', 'previous-report.json');
    result = runTool('resolve-previous-release.mjs', [
      '--repo-root', fixture.root, '--registry-file', fixture.registry,
      '--environment', 'prod', '--active-pointer', '--require-release-class', 'GA',
      '--require-status', 'GA', '--schema', fixture.schema, '--candidate', fixture.candidate,
      '--output', previousOutput, '--report', previousReport, '--no-clobber'
    ]);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(read(previousOutput).releaseId, 'previous-ga');
    assert.equal(read(previousReport).passed, true);

    const registerRegistry = path.join(fixture.root, 'registry-register.json');
    write(registerRegistry, JSON.stringify({schemaVersion: 'v1.1', active: fixture.oldPointer}));
    const pointerOutput = path.join(fixture.root, 'out', 'active-pointer.json');
    const pointerReport = path.join(fixture.root, 'out', 'register-report.json');
    const pointerBackup = path.join(fixture.root, 'out', 'active-pointer.previous.json');
    result = runTool('register-release-pointer.mjs', [
      '--repo-root', fixture.root, '--registry-file', registerRegistry,
      '--registry', 'registry.example/vio', '--environment', 'prod', '--release-class', 'GA',
      '--manifest', fixture.final, '--manifest-hash', fixture.finalHash,
      '--expected-previous', fixture.previous, '--previous-output', pointerBackup,
      '--output', pointerOutput, '--report', pointerReport, '--atomic-replace',
      '--allow-create', '--no-clobber'
    ]);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    const pointer = read(pointerOutput);
    assert.equal(pointer.status, 'active');
    assert.equal(pointer.releaseStatus, 'GA');
    assert.equal(pointer.releaseId, 'final-ga');
    assert.equal(read(pointerBackup).releaseId, 'previous-ga');

    const verifyReport = path.join(fixture.root, 'out', 'verify-report.json');
    result = runTool('verify-release-pointer.mjs', [
      '--repo-root', fixture.root, '--pointer', pointerOutput, '--manifest', fixture.final,
      '--environment', 'prod', '--require-status', 'GA', '--require-release-class', 'GA',
      '--expected-registry', 'registry.example/vio', '--expected-previous', fixture.previous,
      '--schema', fixture.schema, '--report', verifyReport, '--no-clobber'
    ]);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(read(verifyReport).passed, true);

    const before = fs.readFileSync(pointerOutput, 'utf8');
    result = runTool('register-release-pointer.mjs', [
      '--repo-root', fixture.root, '--registry-file', registerRegistry,
      '--environment', 'prod', '--release-class', 'GA', '--manifest', fixture.final,
      '--manifest-hash', fixture.finalHash, '--expected-previous', fixture.previous,
      '--output', pointerOutput, '--report', path.join(fixture.root, 'out', 'no-clobber-report.json'),
      '--atomic-replace', '--allow-create', '--no-clobber'
    ]);
    assert.notEqual(result.status, 0);
    assert.equal(fs.readFileSync(pointerOutput, 'utf8'), before);

    const originalFinal = fs.readFileSync(fixture.final, 'utf8');
    fs.writeFileSync(fixture.final, `${originalFinal}\n`, 'utf8');
    result = runTool('verify-release-pointer.mjs', [
      '--repo-root', fixture.root, '--pointer', pointerOutput, '--manifest', fixture.final,
      '--environment', 'prod', '--require-status', 'GA', '--require-release-class', 'GA',
      '--report', path.join(fixture.root, 'out', 'tampered-report.json')
    ]);
    assert.notEqual(result.status, 0);
    assert.equal(read(path.join(fixture.root, 'out', 'tampered-report.json')).passed, false);
    fs.writeFileSync(fixture.final, originalFinal, 'utf8');

    const whatIfOutput = path.join(fixture.root, 'out', 'what-if-pointer.json');
    result = runTool('register-release-pointer.mjs', [
      '--repo-root', fixture.root, '--registry-file', registerRegistry,
      '--environment', 'prod', '--release-class', 'GA', '--manifest', fixture.final,
      '--manifest-hash', fixture.finalHash, '--expected-previous', fixture.previous,
      '--output', whatIfOutput, '--report', path.join(fixture.root, 'out', 'what-if-report.json'),
      '--atomic-replace', '--allow-create', '--what-if'
    ]);
    assert.notEqual(result.status, 0);
    assert.equal(read(whatIfOutput).status, 'pending');
    assert.equal(read(whatIfOutput).mutation, 'not-performed');
  } finally {
    fs.rmSync(fixture.root, {recursive: true, force: true});
  }
});

test('N8 pointer resolution stays pending and redacted when registry is not a local snapshot', () => {
  const fixture = createFixture();
  try {
    const output = path.join(fixture.root, 'out', 'pending.json');
    const report = path.join(fixture.root, 'out', 'pending-report.json');
    const secretRegistryUrl = 'https://user:password@registry.example/vio?token=super-secret-value';
    const result = runTool('resolve-active-release.mjs', [
      '--repo-root', fixture.root, '--registry', secretRegistryUrl, '--environment', 'prod',
      '--active-pointer', '--expected-manifest', fixture.current, '--allow-pending',
      '--output', output, '--report', report
    ]);
    assert.notEqual(result.status, 0);
    const reportText = fs.readFileSync(report, 'utf8');
    assert.equal(reportText.includes('super-secret-value'), false);
    assert.equal(read(output).status, 'environment-pending');
  } finally {
    fs.rmSync(fixture.root, {recursive: true, force: true});
  }
});

function createFixture() {
  const root = fs.mkdtempSync(path.join(repoRoot, '.tmp-n8-pointers-'));
  const releaseDir = path.join(root, 'artifacts', 'v1.1', 'release');
  fs.mkdirSync(releaseDir, {recursive: true});
  const schema = path.join(root, 'contracts', 'release-manifest-v1.1.schema.json');
  fs.mkdirSync(path.dirname(schema), {recursive: true});
  fs.copyFileSync(path.join(repoRoot, 'contracts', 'release-manifest-v1.1.schema.json'), schema);
  const previous = writeManifest(releaseDir, 'previous-manifest.json', manifest('previous-ga', 'GA', 'GA', '2026-09-01T00:00:00Z', 'a'));
  const current = writeManifest(releaseDir, 'current-manifest.json', manifest('current-ga', 'GA', 'GA', '2026-09-02T00:00:00Z', 'c'));
  const candidate = writeManifest(releaseDir, 'candidate-manifest.json', manifest('candidate-rc', 'GA', 'RC', '2026-09-02T12:00:00Z', 'd'));
  const final = writeManifest(releaseDir, 'final-manifest.json', manifest('final-ga', 'GA', 'GA', '2026-09-03T00:00:00Z', 'e'));
  const oldPointer = pointer('previous-ga', previous, 'a', '2026-09-01T01:00:00Z', 7);
  const currentPointer = pointer('current-ga', current, 'c', '2026-09-02T01:00:00Z', 8);
  const registry = path.join(root, 'registry.json');
  write(registry, JSON.stringify({schemaVersion: 'v1.1', active: currentPointer, previous: oldPointer}));
  const finalHash = path.join(releaseDir, 'final-manifest.sha256.json');
  write(finalHash, JSON.stringify({schemaVersion: 'v1.1', files: [{path: rel(root, final), sha256: sha(final)}]}));
  return {root, schema, previous, current, candidate, final, finalHash, registry, oldPointer, currentPointer};
}

function manifest(releaseId, releaseClass, status, generatedAt, marker) {
  return {
    schemaVersion: 'v1.1', artifactRoot: 'artifacts/v1.1', repoRoot: '.',
    releaseClass, environment: 'prod', releaseId, generatedAt,
    gitCommit: marker.repeat(40), migrationVersion: `migration-${marker}`,
    modelVersion: `model-${marker}`, promptVersion: `prompt-${marker}`,
    dataReleaseId: `data-${marker}`, knowledgeReleaseId: `knowledge-${marker}`,
    indexReleaseId: `index-${marker}`, evaluationRunId: `eval-${marker}`,
    images: {
      backend: `registry.example/vio/backend@sha256:${marker.repeat(64)}`,
      frontend: `registry.example/vio/frontend@sha256:${String.fromCharCode(marker.charCodeAt(0) + 1).repeat(64)}`
    },
    embedding: {provider: 'fixture', model: 'embed-fixture', dimension: 1536, preprocessingProfile: 'p1', indexProfile: 'i1'},
    supportMatrix: {game_release_id: 'sv', language: 'zh-CN', battle_format_id: 'singles', ruleset_version_id: 'rules', data_release_id: `data-${marker}`, knowledge_release_id: `knowledge-${marker}`, index_release_id: `index-${marker}`},
    evidence: {}, rollback: {readinessRequired: true}, status
  };
}

function pointer(releaseId, manifestFile, marker, timestamp, pointerVersion) {
  return {
    schemaVersion: 'v1.1', producer: 'fixture', pointerVersion, registry: 'registry.example/vio',
    environment: 'prod', releaseClass: 'GA', releaseId, status: 'active', releaseStatus: 'GA',
    // Registry snapshots commonly store paths relative to artifacts/v1.1.
    manifestPath: `release/${path.basename(manifestFile)}`, manifestSha256: sha(manifestFile),
    imageDigest: `registry.example/vio/backend@sha256:${marker.repeat(64)}`,
    registeredAt: timestamp, updatedAt: timestamp, mutation: 'not-performed', redacted: true
  };
}

let currentRoot = null;
function writeManifest(dir, name, value) {
  const file = path.join(dir, name);
  // Set the root used by pointer() after the first path is known.
  currentRoot = path.resolve(dir, '..', '..', '..');
  write(file, JSON.stringify(value, null, 2));
  return file;
}
function write(file, content) { fs.mkdirSync(path.dirname(file), {recursive: true}); fs.writeFileSync(file, `${content}\n`, 'utf8'); return file; }
function read(file) { return JSON.parse(fs.readFileSync(file, 'utf8')); }
function sha(file) { return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'); }
function rel(root, file) { return path.relative(root, file).split(path.sep).join('/'); }
function runTool(script, args) { return spawnSync(node, [path.join(repoRoot, 'tools', script), ...args], {cwd: repoRoot, encoding: 'utf8', windowsHide: true}); }
