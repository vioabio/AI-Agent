import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const baseGate = path.join(root, 'tools', 'validate-docker-base-images.mjs');
const composeGate = path.join(root, 'tools', 'validate-release-compose.mjs');
const exceptionProducer = path.join(root, 'tools', 'init-vulnerability-exceptions.mjs');
const digestA = 'a'.repeat(64);
const digestB = 'b'.repeat(64);

test('base image gate rejects mutable Dockerfile/Compose tags and reports hashes', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n7-supply-'));
  try {
    const dockerfile = write(dir, 'Dockerfile', `FROM maven:3.9.9-eclipse-temurin-21 AS build\nFROM eclipse-temurin:21-jre-jammy\n`);
    const compose = write(dir, 'compose.yml', 'services:\n  db:\n    image: pgvector/pgvector:pg16\n');
    const report = path.join(dir, 'base.json');
    const result = run(baseGate, '--file', dockerfile, '--file', compose, '--require-digest', '--report', report);
    assert.notEqual(result.status, 0, result.stdout);
    const payload = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(payload.passed, false);
    assert.equal(payload.status, 'blocked');
    assert.equal(payload.inputFiles.length, 2);
    assert.ok(payload.inputFiles.every((item) => /^[a-f0-9]{64}$/.test(item.sha256)));
    assert.ok(payload.mutableImages.length >= 3);
    assert.equal(payload.redacted, true);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('base image gate passes two immutable references', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n7-supply-'));
  try {
    const dockerfile = write(dir, 'Dockerfile', `FROM registry.example/vio/build@sha256:${digestA}\nFROM registry.example/vio/runtime@sha256:${digestB}\n`);
    const compose = write(dir, 'compose.yml', `services:\n  app:\n    image: registry.example/vio/app@sha256:${digestA}\n`);
    const report = path.join(dir, 'base.json');
    const result = run(baseGate, '--file', dockerfile, '--file', compose, '--require-digest', '--report', report);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    assert.equal(JSON.parse(fs.readFileSync(report, 'utf8')).status, 'passed');
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('base image gate rejects comma-concatenated or incomplete file arguments', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n7-supply-'));
  try {
    const dockerfile = write(dir, 'Dockerfile', `FROM registry.example/vio/build@sha256:${digestA}\n`);
    const report = path.join(dir, 'base.json');
    const result = run(baseGate, '--file', `${dockerfile},${dockerfile}`, '--require-digest', '--report', report);
    assert.notEqual(result.status, 0);
    const payload = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.ok(payload.errors.some((item) => item.includes('逗号')));
    assert.ok(payload.errors.some((item) => item.includes('至少需要 2')));
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('release compose gate evaluates redacted effective config and forbids build/tag', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n7-supply-'));
  try {
    const base = write(dir, 'docker-compose.yml', 'services:\n  app:\n    build: .\n    image: local/app:dev\n');
    const overlay = write(dir, 'docker-compose.release.yml', `services:\n  app:\n    build: null\n    image: registry.example/vio/app@sha256:${digestA}\n`);
    const effective = write(dir, 'effective.txt', `services:\n  app:\n    image: registry.example/vio/app@sha256:${digestA}\n`);
    const report = path.join(dir, 'compose.json');
    const result = run(composeGate, '--file', base, '--file', overlay, '--effective-config', effective, '--project-name', 'vio-release', '--require-digests', '--forbid-build', '--report', report);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const payload = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(payload.status, 'passed');
    assert.equal(payload.builds.length, 0);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('release compose gate rejects unredacted secret without echoing its value', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n7-supply-'));
  const secret = 'do-not-leak-this-secret-123';
  try {
    const base = write(dir, 'docker-compose.yml', `services:\n  app:\n    image: registry.example/vio/app@sha256:${digestA}\n`);
    const overlay = write(dir, 'docker-compose.release.yml', `services:\n  app:\n    image: registry.example/vio/app@sha256:${digestB}\n`);
    const effective = write(dir, 'effective.txt', `services:\n  app:\n    image: registry.example/vio/app@sha256:${digestB}\n    API_KEY: ${secret}\n`);
    const report = path.join(dir, 'compose.json');
    const result = run(composeGate, '--file', base, '--file', overlay, '--effective-config', effective, '--project-name', 'vio-release', '--require-digests', '--forbid-build', '--report', report);
    assert.notEqual(result.status, 0);
    const output = `${result.stdout}\n${result.stderr}\n${fs.readFileSync(report, 'utf8')}`;
    assert.equal(output.includes(secret), false);
    assert.equal(JSON.parse(fs.readFileSync(report, 'utf8')).status, 'blocked');
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('exception producer emits pending empty array bound to immutable digests', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n7-supply-'));
  try {
    const refs = write(dir, 'image-refs.json', JSON.stringify({schemaVersion: 'v1.1', backend: {repoDigest: `registry.example/vio/backend@sha256:${digestA}`}, frontend: {repoDigest: `registry.example/vio/frontend@sha256:${digestB}`}}));
    const output = path.join(dir, 'vulnerability-exceptions.json');
    const result = run(exceptionProducer, '--image-refs', refs, '--scan-time', '2026-09-03T00:00:00.000Z', '--output', output);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const payload = JSON.parse(fs.readFileSync(output, 'utf8'));
    assert.equal(payload.status, 'pending');
    assert.equal(payload.passed, false);
    assert.equal(payload.scanComplete, false);
    assert.deepEqual(payload.exceptions, []);
    assert.equal(payload.images.backend.sha256, digestA);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('exception producer fails closed when image refs are missing', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n7-supply-'));
  try {
    const output = path.join(dir, 'vulnerability-exceptions.json');
    const result = run(exceptionProducer, '--image-refs', path.join(dir, 'missing.json'), '--scan-time', '2026-09-03T00:00:00.000Z', '--output', output);
    assert.notEqual(result.status, 0);
    const payload = JSON.parse(fs.readFileSync(output, 'utf8'));
    assert.equal(payload.status, 'blocked');
    assert.equal(payload.passed, false);
    assert.deepEqual(payload.exceptions, []);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

function write(dir, name, content) {
  const file = path.join(dir, name);
  fs.writeFileSync(file, `${content}\n`, 'utf8');
  return file;
}

function run(script, ...args) {
  return spawnSync(process.execPath, [script, ...args], {cwd: root, encoding: 'utf8'});
}
