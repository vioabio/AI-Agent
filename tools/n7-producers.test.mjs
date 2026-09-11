import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const digestA = `sha256:${'a'.repeat(64)}`;
const digestB = `sha256:${'b'.repeat(64)}`;
const gitSha = 'c'.repeat(40);
const scripts = (name) => path.join(root, 'tools', name);

test('RepoDigest resolver emits two distinct canonical immutable refs', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n7-producers-'));
  try {
    const backend = write(dir, 'backend.inspect.json', JSON.stringify([`registry.example/vio/backend@${digestA}`]));
    const frontend = write(dir, 'frontend.inspect.json', JSON.stringify({RepoDigests: [`registry.example/vio/frontend@${digestB}`]}));
    const output = path.join(dir, 'image-refs.json');
    const result = run('resolve-repo-digests.mjs', '--backend-inspect', backend, '--frontend-inspect', frontend, '--registry', 'registry.example/vio', '--git-sha', gitSha, '--output', output);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const payload = read(output);
    assert.equal(payload.passed, true);
    assert.equal(payload.images.backend.repoDigest, `registry.example/vio/backend@${digestA}`);
    assert.equal(payload.images.frontend.sha256, 'b'.repeat(64));
    assert.equal(payload.backend.repoDigest, payload.images.backend.repoDigest);
    assert.equal(payload.registryVerified, false);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('RepoDigest resolver accepts full docker image inspect object arrays', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n7-producers-'));
  try {
    const backend = write(dir, 'backend.inspect.json', JSON.stringify([{RepoDigests: [`registry.example/vio/backend@${digestA}`], RepoTags: ['registry.example/vio/backend:c']} ]));
    const frontend = write(dir, 'frontend.inspect.json', JSON.stringify([{RepoDigests: [`registry.example/vio/frontend@${digestB}`], RepoTags: ['registry.example/vio/frontend:c']} ]));
    const output = path.join(dir, 'image-refs.json');
    const result = run('resolve-repo-digests.mjs', '--backend-inspect', backend, '--frontend-inspect', frontend, '--registry', 'registry.example/vio', '--git-sha', gitSha, '--output', output);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    assert.equal(read(output).images.backend.digest, digestA);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('RepoDigest resolver rejects multiple digests and registry mismatch', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n7-producers-'));
  try {
    const backend = write(dir, 'backend.inspect.json', JSON.stringify([`registry.example/vio/backend@${digestA}`, `registry.example/vio/backend@${digestB}`]));
    const frontend = write(dir, 'frontend.inspect.json', JSON.stringify([`other.example/vio/frontend@${digestB}`]));
    const output = path.join(dir, 'image-refs.json');
    const result = run('resolve-repo-digests.mjs', '--backend-inspect', backend, '--frontend-inspect', frontend, '--registry', 'registry.example/vio', '--git-sha', gitSha, '--output', output);
    assert.notEqual(result.status, 0);
    assert.equal(read(output).passed, false);
    assert.ok(read(output).errors.some((item) => item.includes('恰好包含一个')));
    assert.ok(read(output).errors.some((item) => item.includes('不匹配')));
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('provenance producer binds lockfile/context/SBOM hashes without copying contents', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n7-producers-'));
  try {
    const backendInspect = write(dir, 'backend.inspect.json', JSON.stringify([`registry.example/vio/backend@${digestA}`]));
    const frontendInspect = write(dir, 'frontend.inspect.json', JSON.stringify([`registry.example/vio/frontend@${digestB}`]));
    const refs = path.join(dir, 'image-refs.json');
    assert.equal(run('resolve-repo-digests.mjs', '--backend-inspect', backendInspect, '--frontend-inspect', frontendInspect, '--registry', 'registry.example/vio', '--git-sha', gitSha, '--output', refs).status, 0);
    const lockFile = write(dir, 'lock.txt', 'lockfile evidence\n');
    const lockHash = crypto.createHash('sha256').update(fs.readFileSync(lockFile)).digest('hex');
    const lockEntry = {path: path.relative(root, lockFile).replaceAll('\\', '/'), sha256: lockHash, bytes: fs.statSync(lockFile).size};
    const lockAggregate = crypto.createHash('sha256').update(`${JSON.stringify([lockEntry], null, 2)}\n`).digest('hex');
    const lockManifest = write(dir, 'lock-hashes.json', JSON.stringify({schemaVersion: 'v1.1', files: [lockEntry], aggregateSha256: lockAggregate}));
    const contextBackend = write(dir, 'context-backend.json', JSON.stringify({schemaVersion: 'v1.1', producer: 'hash-build-context', passed: true, contextSha256: 'e'.repeat(64)}));
    const contextFrontend = write(dir, 'context-frontend.json', JSON.stringify({schemaVersion: 'v1.1', producer: 'hash-build-context', passed: true, contextSha256: 'f'.repeat(64)}));
    const backendSbom = write(dir, 'backend-sbom.json', JSON.stringify({bomFormat: 'CycloneDX', specVersion: '1.5', components: []}));
    const frontendSbom = write(dir, 'frontend-sbom.json', JSON.stringify({bomFormat: 'CycloneDX', specVersion: '1.5', components: []}));
    const outDir = path.join(dir, 'release'); fs.mkdirSync(outDir);
    const secret = 'do-not-copy-this-token';
    const result = run('create-provenance.mjs', '--image-refs', refs, '--lockfiles', lockManifest, '--context-hash', `backend=${contextBackend}`, '--context-hash', `frontend=${contextFrontend}`, '--backend-sbom', backendSbom, '--frontend-sbom', frontendSbom, '--git-sha', gitSha, '--build-command', `docker build --token ${secret} .`, '--output-dir', outDir);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const backendPredicate = read(path.join(outDir, 'provenance-backend.json'));
    assert.equal(backendPredicate.passed, true);
    assert.equal(backendPredicate.subject.repoDigest, `registry.example/vio/backend@${digestA}`);
    assert.equal(backendPredicate.materials.lockfiles.files.length, 1);
    assert.equal(JSON.stringify(backendPredicate).includes('lockfile evidence'), false);
    assert.equal(JSON.stringify(backendPredicate).includes(secret), false);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('vulnerability validator passes a clean digest-bound scan and rejects unresolved critical', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n7-producers-'));
  try {
    const refs = write(dir, 'image-refs.json', JSON.stringify({schemaVersion: 'v1.1', images: {backend: {repoDigest: `registry.example/vio/backend@${digestA}`}, frontend: {repoDigest: `registry.example/vio/frontend@${digestB}`}}}));
    const exceptions = write(dir, 'exceptions.json', JSON.stringify({schemaVersion: 'v1.1', producer: 'init-vulnerability-exceptions', scanTime: '2026-09-03T00:00:00Z', images: {backend: {digest: digestA}, frontend: {digest: digestB}}, imageDigests: [digestA, digestB], exceptionCount: 0, exceptions: []}));
    const backendScan = write(dir, 'backend-scan.json', JSON.stringify({ArtifactID: digestA, Results: [{Vulnerabilities: []}]}));
    const frontendScan = write(dir, 'frontend-scan.json', JSON.stringify({ArtifactID: digestB, Results: [{Vulnerabilities: []}]}));
    const report = path.join(dir, 'validation.json');
    let result = run('validate-vulnerability-exceptions.mjs', '--phase', 'pre-sign', '--input', exceptions, '--schema', path.join(root, 'contracts/vulnerability-exceptions-v1.1.schema.json'), '--backend-scan', backendScan, '--frontend-scan', frontendScan, '--image-refs', refs, '--report', report, '--now', '2026-09-03T00:00:00Z');
    assert.equal(result.status, 0, result.stderr || result.stdout);
    assert.equal(read(report).passed, true);
    const critical = write(dir, 'critical-scan.json', JSON.stringify({ArtifactID: digestA, Results: [{Vulnerabilities: [{VulnerabilityID: 'CVE-2026-0001', Severity: 'CRITICAL'}]}]}));
    result = run('validate-vulnerability-exceptions.mjs', '--phase', 'final', '--input', exceptions, '--schema', path.join(root, 'contracts/vulnerability-exceptions-v1.1.schema.json'), '--backend-scan', critical, '--frontend-scan', frontendScan, '--image-refs', refs, '--report', path.join(dir, 'critical-report.json'), '--now', '2026-09-03T00:00:00Z');
    assert.notEqual(result.status, 0);
    assert.equal(read(path.join(dir, 'critical-report.json')).passed, false);
    assert.equal(read(path.join(dir, 'critical-report.json')).unresolvedCount, 1);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('dependency inventory prepare/validate and static scanner remain fail-closed', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n7-producers-'));
  try {
    const baseline = write(dir, 'baseline.json', JSON.stringify({schemaVersion: 'v1.1', producer: 'export-dependency-inventory', owner: 'test', dependencies: [{name: 'redis', version: 'redis:7', imageOrEndpointDigest: null, source: 'fixture', healthProbe: 'ping', networkBoundary: 'private', requiredStage: 'N7', status: 'environment-pending'}]}));
    const refs = write(dir, 'refs.json', JSON.stringify({schemaVersion: 'v1.1', images: {backend: {repoDigest: `registry.example/vio/backend@${digestA}`}, frontend: {repoDigest: `registry.example/vio/frontend@${digestB}`}}}));
    const candidate = path.join(dir, 'candidate.json');
    let result = run('prepare-dependency-inventory.mjs', '--baseline', baseline, '--image-refs', refs, '--output', candidate);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    assert.equal(read(candidate).status, 'environment-pending');
    const validation = path.join(dir, 'validation.json');
    result = run('validate-dependency-inventory.mjs', '--phase', 'structural', '--input', candidate, '--schema', path.join(root, 'contracts/dependency-inventory-v1.1.schema.json'), '--report', validation);
    assert.notEqual(result.status, 0);
    assert.equal(read(validation).passed, false);
    const scan = path.join(dir, 'scan.json');
    result = run('scan-dependency-images.mjs', '--inventory', candidate, '--output-dir', dir, '--report', scan);
    assert.notEqual(result.status, 0);
    assert.equal(read(scan).scanComplete, false);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

function write(dir, name, content) { const file = path.join(dir, name); fs.writeFileSync(file, `${content}\n`, 'utf8'); return file; }
function read(file) { return JSON.parse(fs.readFileSync(file, 'utf8')); }
function run(script, ...args) { return spawnSync(process.execPath, [scripts(script), ...args], {cwd: root, encoding: 'utf8'}); }
