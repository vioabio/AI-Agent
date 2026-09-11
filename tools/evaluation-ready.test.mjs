import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const tool = path.join(root, 'tools', 'mark-evaluation-ready.mjs');

test('evaluation ready producer remains blocked for undersized suites and writes a diagnostic copy', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-eval-ready-'));
  try {
    const manifest = path.join(dir, 'manifest.json');
    const validation = path.join(dir, 'validation.json');
    const dataset = path.join(dir, 'dataset.json');
    const dictionary = path.join(dir, 'dictionary.json');
    const output = path.join(dir, 'manifest.ready.json');
    const report = path.join(dir, 'review.json');
    write(manifest, JSON.stringify({status: 'draft', schemaVersion: 'v1.1', suites: [
      {suiteId: 'fact-v1', minimumCases: 2}
    ]}));
    write(validation, JSON.stringify({passed: true}));
    write(dataset, JSON.stringify({status: 'draft', suites: [{suiteId: 'fact-v1', count: 1}]}));
    write(dictionary, JSON.stringify({schemaVersion: 'v1.1'}));
    const result = spawnSync(process.execPath, [tool, '--manifest', manifest,
      '--validation', validation, '--dataset-manifest', dataset, '--dictionary', dictionary,
      '--reviewer', 'reviewer@example', '--output', output, '--report', report],
    {cwd: root, encoding: 'utf8'});
    assert.notEqual(result.status, 0);
    assert.equal(fs.existsSync(output), false);
    const blocked = JSON.parse(fs.readFileSync(`${output}.blocked.json`, 'utf8'));
    assert.equal(blocked.status, 'blocked');
    assert.ok(blocked.blockedReasons.some((reason) => reason.includes('样本规模')));
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('evaluation ready producer refuses to overwrite an existing review by default', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-eval-ready-'));
  try {
    const manifest = path.join(dir, 'manifest.json');
    const validation = path.join(dir, 'validation.json');
    const dataset = path.join(dir, 'dataset.json');
    const dictionary = path.join(dir, 'dictionary.json');
    const report = path.join(dir, 'review.json');
    write(manifest, JSON.stringify({status: 'draft', schemaVersion: 'v1.1', suites: []}));
    write(validation, JSON.stringify({passed: true}));
    write(dataset, JSON.stringify({status: 'draft', suites: []}));
    write(dictionary, JSON.stringify({schemaVersion: 'v1.1'}));
    write(report, '{"sentinel":true}\n');
    const result = spawnSync(process.execPath, [tool, '--manifest', manifest,
      '--validation', validation, '--dataset-manifest', dataset, '--dictionary', dictionary,
      '--reviewer', 'reviewer@example', '--report', report],
    {cwd: root, encoding: 'utf8'});
    assert.notEqual(result.status, 0);
    assert.equal(fs.readFileSync(report, 'utf8'), '{"sentinel":true}\n');
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

function write(file, content) {
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, content.endsWith('\n') ? content : `${content}\n`, 'utf8');
}
