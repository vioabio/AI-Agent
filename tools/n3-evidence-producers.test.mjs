import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import test from 'node:test';

const root = path.resolve(import.meta.dirname, '..');
const node = process.execPath;

test('N3 file list, validation and manifest are explicit and immutable', () => {
  // Producers resolve paths against the repository by design; create a short
  // in-repo fixture instead of allowing a temp path to bypass that boundary.
  const fixture = path.join(root, '.tmp-n3-producer-test', 'n3-test-1', 'n3-raw');
  fs.mkdirSync(fixture, {recursive: true});
  fs.writeFileSync(path.join(fixture, 'n3-security-check.jsonl'),
    '{"schemaVersion":"v1.1","runId":"n3-test-1","producer":"test","passed":true}\n');
  try {
    const inputDir = '.tmp-n3-producer-test/n3-test-1/n3-raw';
    execFileSync(node, ['tools/create-n3-evidence-file-list.mjs', '--input-dir', inputDir,
      '--path-root', '.tmp-n3-producer-test', '--run-id', 'n3-test-1',
      '--allow-regex', '^[A-Za-z0-9._/-]+\\.(jsonl|json)$', '--output', '.tmp-n3-list.txt',
      '--report', '.tmp-n3-list.json', '--no-clobber'], {cwd: root, stdio: 'pipe'});
    execFileSync(node, ['tools/validate-n3-evidence-file-list.mjs', '--input', '.tmp-n3-list.txt',
      '--root', '.tmp-n3-producer-test', '--run-id', 'n3-test-1', '--report', '.tmp-n3-list-validation.json',
      '--no-clobber'], {cwd: root, stdio: 'pipe'});
    execFileSync(node, ['tools/create-n3-evidence-manifest.mjs', '--run-id', 'n3-test-1',
      '--files', '.tmp-n3-list.txt', '--root', '.tmp-n3-producer-test', '--output', '.tmp-n3-manifest.json',
      '--report', '.tmp-n3-manifest-report.json', '--no-clobber'], {cwd: root, stdio: 'pipe'});
    execFileSync(node, ['tools/export-n3-evidence-index.mjs', '--manifest', '.tmp-n3-manifest.json',
      '--run-id', 'n3-test-1', '--output', '.tmp-n3-index.json', '--no-clobber'], {cwd: root, stdio: 'pipe'});
    execFileSync(node, ['tools/aggregate-security-report.mjs', '--inputs-file', '.tmp-n3-index.json',
      '--output', '.tmp-n3-security-summary.json', '--no-clobber'], {cwd: root, stdio: 'pipe'});
    const output = JSON.parse(fs.readFileSync(path.join(root, '.tmp-n3-manifest.json'), 'utf8'));
    assert.equal(output.status, 'ready');
    assert.equal(output.fileCount, 1);
    assert.equal(output.files[0].runId, 'n3-test-1');
    assert.match(output.files[0].sha256, /^[a-f0-9]{64}$/);
    const summary = JSON.parse(fs.readFileSync(path.join(root, '.tmp-n3-security-summary.json'), 'utf8'));
    assert.equal(summary.status, 'passed');
    assert.throws(() => execFileSync(node, ['tools/create-n3-evidence-manifest.mjs', '--run-id', 'n3-test-1',
      '--files', '.tmp-n3-list.txt', '--root', '.tmp-n3-producer-test', '--output', '.tmp-n3-manifest.json',
      '--report', '.tmp-n3-manifest-report-2.json', '--no-clobber'], {cwd: root, stdio: 'pipe'}));
  } finally {
    for (const file of ['.tmp-n3-list.txt', '.tmp-n3-list.json', '.tmp-n3-list-validation.json',
      '.tmp-n3-manifest.json', '.tmp-n3-manifest-report.json', '.tmp-n3-manifest-report-2.json',
      '.tmp-n3-index.json', '.tmp-n3-security-summary.json']) {
      try { fs.rmSync(path.join(root, file), {force: true}); } catch { /* best effort */ }
    }
    try { fs.rmSync(path.join(root, '.tmp-n3-producer-test'), {recursive: true, force: true}); } catch { /* best effort */ }
  }
});
