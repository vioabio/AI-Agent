import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const script = path.join(root, 'tools', 'scan-image-secrets.mjs');
const schema = path.join(root, 'contracts', 'image-secret-scan-v1.1.schema.json');
const digestA = 'a'.repeat(64);
const digestB = 'b'.repeat(64);
const digestC = 'c'.repeat(64);

test('explicit immutable images produce redacted pending evidence without Docker', () => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp', 'image-secret-scan-'));
  try {
    const inventory = write(dir, 'inventory.json', {
      schemaVersion: 'v1.1',
      dependencies: [
        {
          name: 'postgresql-pgvector',
          version: 'pgvector/pgvector',
          imageOrEndpointDigest: 'registry.example/pgvector@sha256:' + digestC,
          status: 'candidate'
        },
        {name: 'search-api', version: null, imageOrEndpointDigest: null, status: 'environment-pending'}
      ]
    });
    const output = path.join(dir, 'scan.json');
    const report = path.join(dir, 'scan.log');
    const result = run(
      '--image', 'backend=registry.example/vio/backend@sha256:' + digestA,
      '--image', 'frontend=registry.example/vio/frontend@sha256:' + digestB,
      '--inventory', inventory,
      '--schema', schema,
      '--no-allowlist',
      '--redact',
      '--scanner-version', 'fixture-adapter-v0',
      '--output', output,
      '--report', report,
      '--no-clobber',
      '--allow-pending'
    );
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const payload = JSON.parse(fs.readFileSync(output, 'utf8'));
    const reportPayload = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(payload.status, 'environment-pending');
    assert.equal(payload.passed, false);
    assert.equal(payload.findingCount, 0);
    assert.deepEqual(payload.findings, []);
    assert.equal(payload.dockerInvoked, false);
    assert.equal(payload.registryContacted, false);
    assert.equal(payload.scanner.executed, false);
    assert.equal(payload.dependencyCoverage.digestBoundCount, 1);
    assert.equal(payload.dependencyCoverage.missingDigestCount, 0);
    assert.equal(reportPayload.findings.length, 0);
    assert.equal(reportPayload.redacted, true);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('mutable image refs and positive allowlist fail closed without leaking values', () => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp', 'image-secret-scan-'));
  const secret = 'never-print-this-image-secret-7788';
  try {
    const inventory = write(dir, 'inventory.json', {
      schemaVersion: 'v1.1',
      dependencies: [
        {
          name: 'redis',
          version: 'redis:7',
          imageOrEndpointDigest: null,
          password: secret,
          status: 'candidate'
        }
      ]
    });
    const output = path.join(dir, 'scan.json');
    const result = run(
      '--image', 'backend=registry.example/vio/backend:latest',
      '--image', 'frontend=registry.example/vio/frontend@sha256:' + digestB,
      '--inventory', inventory,
      '--allowlist',
      '--output', output
    );
    assert.notEqual(result.status, 0);
    const combined = result.stdout + '\n' + result.stderr + '\n' + fs.readFileSync(output, 'utf8');
    assert.equal(combined.includes(secret), false);
    const payload = JSON.parse(fs.readFileSync(output, 'utf8'));
    assert.equal(payload.status, 'blocked');
    assert.equal(payload.passed, false);
    assert.ok(payload.errors.length > 0);
    assert.deepEqual(payload.findings, []);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('no-clobber and repository boundary are enforced', () => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp', 'image-secret-scan-'));
  try {
    const inventory = write(dir, 'inventory.json', {
      schemaVersion: 'v1.1',
      dependencies: []
    });
    const output = path.join(dir, 'scan.json');
    const args = [
      '--image', 'backend=registry.example/vio/backend@sha256:' + digestA,
      '--image', 'frontend=registry.example/vio/frontend@sha256:' + digestB,
      '--inventory', inventory,
      '--output', output,
      '--allow-pending'
    ];
    const first = run(...args);
    assert.notEqual(first.status, 0, 'empty dependency inventory must remain blocked');
    assert.equal(fs.existsSync(output), true);
    const second = run(...args);
    assert.notEqual(second.status, 0);
    assert.ok((second.stdout + second.stderr).includes('已存在'));
    const outside = run(
      '--image', 'backend=registry.example/vio/backend@sha256:' + digestA,
      '--image', 'frontend=registry.example/vio/frontend@sha256:' + digestB,
      '--inventory', inventory,
      '--output', path.join(dir, '..', '..', 'outside-image-secret-scan.json')
    );
    assert.notEqual(outside.status, 0);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

function write(dir, name, value) {
  const file = path.join(dir, name);
  fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n', 'utf8');
  return file;
}

function run(...args) {
  return spawnSync(process.execPath, [script, ...args], {cwd: root, encoding: 'utf8'});
}
