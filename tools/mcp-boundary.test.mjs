import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';

const root = path.resolve(import.meta.dirname, '..');
const script = path.join(root, 'tools', 'validate-mcp-production-boundary.mjs');

test('MCP production boundary static gate is explicit and fail-closed', () => {
  const report = path.join(root, '.tmp-mcp-boundary-report.json');
  try {
    const result = spawnSync(process.execPath, [script, '--repo-root', root, '--report', report, '--allow-pending'], {encoding: 'utf8'});
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    const payload = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(payload.passed, false);
    assert.equal(payload.status, 'environment-pending');
    assert.equal(payload.execution, 'static-only-no-transport-no-secret-read');
    assert.equal(payload.redacted, true);
  } finally { try { fs.rmSync(report, {force: true}); } catch {} }
});
