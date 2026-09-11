#!/usr/bin/env node

/** Offline contract tests for the v1.1 N0 metadata producers. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const toolsDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(toolsDir, '..');
const tempRoot = fs.mkdtempSync(path.join(repoRoot, '.tmp-n0-producers-'));
const env = {...process.env};
for (const name of ['DB_PASSWORD', 'SPRING_DATASOURCE_PASSWORD', 'VIO_API_KEY', 'AI_DASHSCOPE_API_KEY', 'SEARCH_API_KEY', 'VIO_MEMORY_ENCRYPTION_KEY']) delete env[name];
env.VIO_BASELINE_OWNER = 'n0-test-owner';

try {
  testSyntax();
  testSupportMatrix();
  testSwitchSnapshot();
  testDependencyInventory();
  testFixtureManifest();
  testEnvironmentContract();
  console.log('N0 producer tests: 6 passed');
} finally {
  fs.rmSync(tempRoot, {recursive: true, force: true});
}

function testSyntax() {
  for (const name of ['create-environment-contract.mjs', 'create-support-matrix.mjs', 'export-switch-snapshot.mjs', 'export-dependency-inventory.mjs', 'create-fixture-manifest.mjs']) {
    const result = run(['--check', path.join(toolsDir, name)]);
    assert.equal(result.status, 0, `${name} syntax failed: ${result.stderr}`);
  }
}

function testSupportMatrix() {
  const output = temp('matrix.md'); const hash = temp('matrix.sha.json'); const report = temp('matrix.report.json');
  let result = run(['tools/create-support-matrix.mjs', '--owner', 'n0-test-owner', '--official-patch', '1.0.0', '--output', output, '--hash-output', hash, '--report', report, '--no-clobber']);
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const markdown = fs.readFileSync(output, 'utf8');
  assert.match(markdown, /"schemaVersion": "v1\.1"/);
  assert.match(markdown, /"producer": "create-support-matrix"/);
  assert.ok(JSON.parse(fs.readFileSync(hash, 'utf8')).files[0].sha256);
  result = run(['tools/create-support-matrix.mjs', '--owner', 'n0-test-owner', '--official-patch', '1.0.0', '--output', output, '--no-clobber']);
  assert.notEqual(result.status, 0, 'no-clobber must reject an existing artifact');
}

function testSwitchSnapshot() {
  const output = temp('switches.json');
  const secret = 'n0-test-secret-must-not-persist';
  const childEnv = {...env, VIO_API_KEY: secret};
  const result = run(['tools/export-switch-snapshot.mjs', '--output', output], childEnv);
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const text = fs.readFileSync(output, 'utf8');
  assert.equal(text.includes(secret), false, 'switch snapshot leaked a secret value');
  const parsed = JSON.parse(text);
  assert.equal(parsed.redacted, true);
  assert.equal(parsed.switches.length, 13);
  const outside = path.join(repoRoot, '..', 'n0-outside-switches.json');
  const rejected = run(['tools/export-switch-snapshot.mjs', '--output', outside], env);
  assert.notEqual(rejected.status, 0, 'outside output path must be rejected');
}

function testDependencyInventory() {
  const output = temp('dependencies.json'); const hash = temp('dependencies.sha.json');
  const result = run(['tools/export-dependency-inventory.mjs', '--output', output, '--hash-output', hash, '--allow-pending'], env);
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const parsed = JSON.parse(fs.readFileSync(output, 'utf8'));
  assert.equal(parsed.redacted, true);
  assert.equal(parsed.status, 'environment-pending');
  assert.ok(parsed.dependencies.some((item) => item.name === 'postgresql-pgvector'));
  const outside = path.join(repoRoot, '..', 'n0-outside-dependencies.json');
  const rejected = run(['tools/export-dependency-inventory.mjs', '--output', outside, '--allow-pending'], env);
  assert.notEqual(rejected.status, 0, 'outside output path must be rejected');
}

function testFixtureManifest() {
  const matrix = temp('fixture-support.md');
  const matrixResult = run(['tools/create-support-matrix.mjs', '--owner', 'n0-test-owner', '--official-patch', '1.0.0', '--output', matrix], env);
  assert.equal(matrixResult.status, 0, matrixResult.stderr || matrixResult.stdout);
  const output = temp('fixture.json'); const audit = temp('fixture-audit.json');
  const result = run(['tools/create-fixture-manifest.mjs', '--input-dir', 'data/v1.1-fixture', '--support-matrix', matrix, '--output', output, '--source-audit', audit, '--allow-pending'], env);
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const parsed = JSON.parse(fs.readFileSync(output, 'utf8'));
  assert.equal(parsed.redacted, true);
  assert.equal(parsed.productionEligible, false);
  assert.equal(parsed.status, 'candidate');
  assert.ok(parsed.fileCount >= 4);
  // A sensitive filename is rejected instead of being hashed into evidence.
  const fixtureCopy = fs.mkdtempSync(path.join(tempRoot, 'fixture-'));
  fs.copyFileSync(path.join(repoRoot, 'data/v1.1-fixture', 'fixture.json'), path.join(fixtureCopy, 'fixture.json'));
  fs.writeFileSync(path.join(fixtureCopy, '.env'), 'TOKEN=must-not-be-read\n', 'utf8');
  const blockedOutput = temp('fixture-blocked.json'); const blockedAudit = temp('fixture-blocked-audit.json');
  const blocked = run(['tools/create-fixture-manifest.mjs', '--input-dir', fixtureCopy, '--support-matrix', matrix, '--output', blockedOutput, '--source-audit', blockedAudit], env);
  assert.notEqual(blocked.status, 0, 'fixture containing .env must fail closed');
  assert.match(fs.readFileSync(blockedOutput, 'utf8'), /Secret/);
}

function testEnvironmentContract() {
  const output = temp('environment.json'); const report = temp('environment.report.json');
  const result = run(['tools/create-environment-contract.mjs', '--owner', 'n0-test-owner', '--output', output, '--report', report, '--allow-pending'], env);
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const parsed = JSON.parse(fs.readFileSync(output, 'utf8'));
  assert.equal(parsed.policy.secretValuesNeverPersisted, true);
  assert.ok(parsed.variables.some((item) => item.name === 'VIO_API_KEY' && item.kind === 'secret'));
  for (const item of parsed.variables.filter((entry) => entry.kind === 'secret')) assert.equal('observedValue' in item, false);
}

function temp(name) { return path.join(tempRoot, name); }
function run(args, childEnv = env) {
  const command = args[0] === '--check' ? process.execPath : process.execPath;
  const argv = args[0] === '--check' ? args : [path.join(repoRoot, args[0]), ...args.slice(1)];
  return spawnSync(command, argv, {cwd: repoRoot, env: childEnv, encoding: 'utf8', maxBuffer: 1024 * 1024});
}
