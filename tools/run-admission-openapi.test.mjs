import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = relative => JSON.parse(fs.readFileSync(path.join(root, relative), 'utf8'));
const contract = read('contracts/openapi-v1.1.json');
const create = contract.paths['/chat/runs'].post;

test('Run admission publishes typed 429/503 errors and a positive Retry-After delay', () => {
  for (const status of ['429', '503']) {
    const response = create.responses[status];
    assert.ok(response, `Missing admission status ${status}`);
    assert.equal(response.content['application/json'].schema.$ref, '#/components/schemas/ErrorBody');
    assert.deepEqual(response.headers['Retry-After'].schema, {type: 'integer', minimum: 1, maximum: 3600});
    assert.match(response.headers['Retry-After'].description, /seconds/);
    assert.match(response.headers['Retry-After'].description, /Idempotency-Key/);
  }
  assert.deepEqual(contract.components.schemas.ErrorBody.required, ['code', 'message']);
});

test('admission descriptions distinguish early rejection from a persisted scheduling failure', () => {
  for (const code of ['RUN_CAPACITY_EXCEEDED', 'RUN_PRINCIPAL_LIMIT', 'RUN_TENANT_LIMIT']) {
    assert.ok(create.responses['429'].description.includes(code), `Missing rejection code ${code}`);
  }
  assert.match(create.responses['429'].description, /before creating a new Run/);
  assert.match(create.responses['503'].description, /RUN_EXECUTION_UNAVAILABLE/);
  assert.match(create.responses['503'].description, /FAILED Run/);
  assert.match(create.responses['503'].description, /same Idempotency-Key/);
  assert.equal(create['x-canonical-status'], 202);
  assert.equal(create['x-compatibility-status'], 201);
  assert.equal(create.responses['202'].content['application/json'].schema.$ref, '#/components/schemas/RunView');
});

test('generator reproduces the reviewed contract and frontend generation input without unrelated drift', () => {
  const directory = fs.mkdtempSync(path.join(root, '.tmp-run-admission-contract-'));
  try {
    const output = path.join(directory, 'openapi.json');
    const generated = spawnSync(process.execPath,
      ['tools/generate-openapi-v1.1.mjs', '--output', output], {cwd: root, encoding: 'utf8'});
    assert.equal(generated.status, 0, generated.stderr || generated.stdout);
    assert.deepEqual(JSON.parse(fs.readFileSync(output, 'utf8')), contract);
    const frontendOutput = path.join(directory, 'frontend-openapi.json');
    const synchronized = spawnSync(process.execPath,
      ['tools/sync-openapi-contract.mjs', '--source', output, '--target', frontendOutput],
      {cwd: root, encoding: 'utf8'});
    assert.equal(synchronized.status, 0, synchronized.stderr || synchronized.stdout);
    assert.deepEqual(JSON.parse(fs.readFileSync(frontendOutput, 'utf8')), contract);
    // The real client-generation input is ignored. A clean CI checkout may
    // not have it yet; when present locally, it must match the reviewed file.
    if (fs.existsSync(path.join(root, 'vio-ai-agent-frontend/src/api/openapi.json'))) {
      assert.deepEqual(read('vio-ai-agent-frontend/src/api/openapi.json'), contract);
    }
  } finally {
    fs.rmSync(directory, {recursive: true, force: true});
  }
});
