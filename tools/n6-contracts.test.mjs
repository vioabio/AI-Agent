import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const metricValidator = path.join(root, 'tools', 'validate-metric-dictionary.mjs');
const metricExporter = path.join(root, 'tools', 'export-metric-queries.mjs');
const otelExporter = path.join(root, 'tools', 'export-otel-contract.mjs');
const otelValidator = path.join(root, 'tools', 'validate-otel-trace-sample.mjs');

test('metric dictionary producer emits a pending, versioned and hashed contract', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n6-contract-'));
  try {
    const output = path.join(dir, 'metric-schema.json');
    const report = path.join(dir, 'metric-validation.json');
    const hash = path.join(dir, 'metric-schema.sha256.json');
    const result = run(metricValidator, '--dictionary', path.join(root, 'evaluation/v1.1/metric-dictionary.json'), '--schema', path.join(root, 'contracts/metric-dictionary-v1.1.schema.json'), '--output', output, '--report', report, '--hash', hash);
    assert.equal(result.status, 0, result.stderr);
    const schema = read(output);
    assert.equal(schema.schemaVersion, 'v1.1');
    assert.equal(schema.status, 'pending-review');
    assert.equal(schema.commit, schema.gitCommit);
    assert.equal(schema.redaction.sensitiveValuesPersisted, false);
    assert.ok(schema.metrics.length >= 1);
    assert.equal(read(hash).sha256.length, 64);
    const before = fs.readFileSync(output, 'utf8');
    const second = run(metricValidator, '--dictionary', path.join(root, 'evaluation/v1.1/metric-dictionary.json'), '--schema', path.join(root, 'contracts/metric-dictionary-v1.1.schema.json'), '--output', output, '--report', report, '--hash', hash);
    assert.notEqual(second.status, 0);
    assert.match(second.stdout, /no-clobber|输出已存在/);
    assert.equal(fs.readFileSync(output, 'utf8'), before);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('metric query and OTel exporters preserve owners, commit and redaction metadata', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n6-contract-'));
  try {
    const metricOutput = path.join(dir, 'metric-queries.json');
    const metricRun = run(metricExporter, '--dictionary', path.join(root, 'evaluation/v1.1/metric-dictionary.json'), '--output', metricOutput, '--report', path.join(dir, 'metric-build.json'));
    assert.equal(metricRun.status, 0, metricRun.stderr);
    const metric = read(metricOutput);
    assert.equal(metric.status, 'pending-review');
    assert.ok(metric.queries.every((item) => item.owner && item.queryOwner));
    assert.equal(read(`${metricOutput}.sha256.json`).sha256.length, 64);

    const otelRun = run(otelExporter, '--output-dir', dir, '--schema-version', 'v1.1');
    assert.equal(otelRun.status, 0, otelRun.stderr);
    const schema = read(path.join(dir, 'otel-schema.json'));
    const queries = read(path.join(dir, 'otel-queries.json'));
    assert.equal(schema.commit, schema.gitCommit);
    assert.ok(schema.spans.length >= 1);
    assert.ok(queries.queries.every((item) => item.owner && item.queryOwner));
    assert.equal(schema.redaction.sensitiveValuesPersisted, false);
    assert.equal(read(path.join(dir, 'otel-contract-hashes.json')).files.length, 2);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('OTel validator accepts correlated redacted sample and rejects sensitive attributes', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-n6-contract-'));
  try {
    run(otelExporter, '--output-dir', dir, '--schema-version', 'v1.1');
    const contract = read(path.join(dir, 'otel-schema.json'));
    const runId = 'run-contract-001';
    const evaluationRunId = 'eval-contract-001';
    const traceId = 'a'.repeat(32);
    const definition = contract.spans[0];
    const attributes = Object.fromEntries(definition.requiredAttributes.map((key) => [key, key === 'trace_id' ? traceId : key === 'run_id' ? runId : key === 'evaluation_run_id' ? evaluationRunId : 'fixture']));
    // The contract requires lineage attributes; use explicit non-secret values.
    Object.assign(attributes, {
      run_id: runId, trace_id: traceId, status: 'completed', contract_version: 'v1.1',
      scenario: 'fact', model_version: 'model-fixture', prompt_version: 'prompt-fixture',
      data_release_id: 'data-fixture', knowledge_release_id: 'knowledge-fixture', index_release_id: 'index-fixture'
    });
    const sample = path.join(dir, 'sample.json');
    fs.writeFileSync(sample, JSON.stringify({schemaVersion: 'v1.1', runId, traceId, evaluationRunId, spans: [{schemaVersion: 'v1.1', traceId, spanId: 'b'.repeat(16), name: definition.name, startTime: '2026-01-01T00:00:00.000Z', endTime: '2026-01-01T00:00:00.100Z', attributes}]}));
    const validReport = path.join(dir, 'valid-report.json');
    const valid = run(otelValidator, '--input', sample, '--schema', path.join(dir, 'otel-schema.json'), '--run-id', runId, '--trace-id', traceId, '--evaluation-run-id', evaluationRunId, '--report', validReport);
    assert.equal(valid.status, 0, valid.stderr + valid.stdout);
    assert.equal(read(validReport).passed, true);

    attributes.api_key = 'should-not-be-persisted';
    const invalidSample = path.join(dir, 'invalid.json');
    fs.writeFileSync(invalidSample, JSON.stringify({schemaVersion: 'v1.1', runId, traceId, evaluationRunId, spans: [{schemaVersion: 'v1.1', traceId, spanId: 'c'.repeat(16), name: definition.name, startTime: '2026-01-01T00:00:00.000Z', endTime: '2026-01-01T00:00:00.100Z', attributes}]}));
    const invalidReport = path.join(dir, 'invalid-report.json');
    const invalid = run(otelValidator, '--input', invalidSample, '--schema', path.join(dir, 'otel-schema.json'), '--run-id', runId, '--trace-id', traceId, '--evaluation-run-id', evaluationRunId, '--report', invalidReport);
    assert.notEqual(invalid.status, 0);
    assert.equal(read(invalidReport).passed, false);
    assert.doesNotMatch(invalid.stdout, /should-not-be-persisted/);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

function run(script, ...args) { return spawnSync(process.execPath, [script, ...args], {cwd: root, encoding: 'utf8'}); }
function read(file) { return JSON.parse(fs.readFileSync(file, 'utf8')); }
