import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const script = path.join(repoRoot, 'tools', 'hash-files.mjs');

function makeTempRoot() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'vio-hash-files-'));
}

function runHash(args) {
  return spawnSync(process.execPath, [script, ...args], {
    cwd: repoRoot,
    encoding: 'utf8'
  });
}

test('生成 repo-relative 多输入清单且不泄漏输入内容', () => {
  const root = makeTempRoot();
  const nested = path.join(root, '知识', 'fixture.txt');
  const secretText = 'DO_NOT_SERIALIZE_THIS_VALUE';
  fs.mkdirSync(path.dirname(nested), {recursive: true});
  fs.writeFileSync(nested, secretText, 'utf8');
  const second = path.join(root, 'plain.txt');
  fs.writeFileSync(second, 'plain', 'utf8');
  const output = path.join(root, 'hashes.json');

  try {
    const result = runHash([
      '--repo-root', root,
      '--input', '知识/fixture.txt',
      '--input', 'plain.txt',
      '--output', output,
      '--no-clobber'
    ]);
    assert.equal(result.status, 0, result.stderr);
    const report = JSON.parse(fs.readFileSync(output, 'utf8'));
    assert.equal(report.length, 2);
    assert.deepEqual(Object.keys(report[0]), [
      'path', 'sha256', 'algorithm', 'generatedAt', 'commit'
    ]);
    assert.equal(report[0].path, '知识/fixture.txt');
    assert.equal(report[0].algorithm, 'SHA-256');
    assert.match(report[0].generatedAt, /^\d{4}-\d{2}-\d{2}T/);
    assert.equal(
      report[0].sha256,
      crypto.createHash('sha256').update(secretText).digest('hex')
    );
    assert.equal(report[1].path, 'plain.txt');
    assert.equal(JSON.stringify(report).includes(secretText), false);
    assert.equal(result.stdout.includes(secretText), false);
  } finally {
    fs.rmSync(root, {recursive: true, force: true});
  }
});

test('--no-clobber 拒绝覆盖并阻止越界输入', () => {
  const root = makeTempRoot();
  const outsideRoot = makeTempRoot();
  const input = path.join(root, 'input.txt');
  const output = path.join(root, 'hashes.json');
  fs.writeFileSync(input, 'first', 'utf8');
  try {
    const first = runHash([
      '--repo-root', root,
      '--input', 'input.txt',
      '--output', output,
      '--no-clobber'
    ]);
    assert.equal(first.status, 0, first.stderr);
    const original = fs.readFileSync(output, 'utf8');

    const second = runHash([
      '--repo-root', root,
      '--input', 'input.txt',
      '--output', output,
      '--no-clobber'
    ]);
    assert.notEqual(second.status, 0);
    assert.match(second.stderr, /no-clobber/);
    assert.equal(fs.readFileSync(output, 'utf8'), original);

    const outside = path.join(outsideRoot, 'outside.txt');
    fs.writeFileSync(outside, 'outside', 'utf8');
    try {
      const traversal = runHash([
        '--repo-root', root,
        '--input', '../outside.txt',
        '--output', path.join(root, 'traversal.json')
      ]);
      assert.notEqual(traversal.status, 0);
      assert.match(traversal.stderr, /repo-root/);
    } finally {
      fs.rmSync(outsideRoot, {recursive: true, force: true});
    }
  } finally {
    fs.rmSync(root, {recursive: true, force: true});
    fs.rmSync(outsideRoot, {recursive: true, force: true});
  }
});
