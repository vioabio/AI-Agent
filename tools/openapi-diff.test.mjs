import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const diffTool = path.join(root, 'tools', 'openapi-diff.mjs');

test('旧 schema 中的可选属性变为 required 时拒绝候选契约', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-openapi-diff-'));
  try {
    const baseline = path.join(dir, 'baseline.json');
    const candidate = path.join(dir, 'candidate.json');
    const report = path.join(dir, 'report.json');
    fs.writeFileSync(baseline, `${JSON.stringify(contract(['id']))}\n`, 'utf8');
    fs.writeFileSync(candidate, `${JSON.stringify(contract(['id', 'displayName']))}\n`, 'utf8');

    const result = runDiff(baseline, candidate, report);

    assert.notEqual(result.status, 0, result.stderr || result.stdout);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(parsed.passed, false);
    assert.ok(parsed.breaking.includes('schema User: 可选属性变为 required: displayName'));
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('保持 required 集合不变时通过契约检查', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-openapi-diff-'));
  try {
    const baseline = path.join(dir, 'baseline.json');
    const candidate = path.join(dir, 'candidate.json');
    const report = path.join(dir, 'report.json');
    const document = contract(['id']);
    fs.writeFileSync(baseline, `${JSON.stringify(document)}\n`, 'utf8');
    fs.writeFileSync(candidate, `${JSON.stringify(document)}\n`, 'utf8');

    const result = runDiff(baseline, candidate, report);

    assert.equal(result.status, 0, result.stderr || result.stdout);
    assert.equal(JSON.parse(fs.readFileSync(report, 'utf8')).passed, true);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('支持手册中的 --base 别名，并默认阻断 breaking change', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-openapi-diff-'));
  try {
    const baseline = path.join(dir, 'baseline.json');
    const candidate = path.join(dir, 'candidate.json');
    const report = path.join(dir, 'report.json');
    fs.writeFileSync(baseline, `${JSON.stringify(contract(['id']))}\n`, 'utf8');
    fs.writeFileSync(candidate, `${JSON.stringify(contract(['id', 'displayName']))}\n`, 'utf8');

    const result = spawnSync(process.execPath, [
      diffTool, '--base', baseline, '--candidate', candidate,
      '--fail-on-breaking', '--report', report
    ], {cwd: root, encoding: 'utf8'});

    assert.notEqual(result.status, 0, result.stderr || result.stdout);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(parsed.baseline.path, baseline.replaceAll('\\', '/'));
    assert.equal(parsed.failOnBreaking, true);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('仓库内 v1.1 候选契约作为默认候选且保持 v1.0 兼容', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-openapi-diff-'));
  try {
    const report = path.join(dir, 'report.json');
    const result = spawnSync(process.execPath, [
      diffTool, '--base', 'contracts/openapi-v1.0.json', '--report', report
    ], {cwd: root, encoding: 'utf8'});

    assert.equal(result.status, 0, result.stderr || result.stdout);
    const parsed = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(parsed.candidate.path, 'contracts/openapi-v1.1.json');
    assert.equal(parsed.passed, true);
    assert.deepEqual(parsed.breaking, []);
    assert.ok(parsed.additions.includes('新增路径: /chat/runs/{runId}/events/stream'));
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('v1.1 生成器是确定性的，并输出结构化领域卡片 schema', () => {
  const dir = fs.mkdtempSync(path.join(root, '.tmp-openapi-generate-'));
  try {
    const output = path.join(dir, 'openapi-v1.1.json');
    const result = spawnSync(process.execPath, [
      path.join(root, 'tools', 'generate-openapi-v1.1.mjs'), '--output', output
    ], {cwd: root, encoding: 'utf8'});
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const generated = JSON.parse(fs.readFileSync(output, 'utf8'));
    const checkedIn = JSON.parse(fs.readFileSync(path.join(root, 'contracts', 'openapi-v1.1.json'), 'utf8'));
    assert.deepEqual(generated, checkedIn);
    for (const schema of ['FactCard', 'CalculationCard', 'TeamMatrixCard', 'RetrievalCitationCard', 'ToolTimeline', 'ApprovalCard', 'FeedbackCard', 'ErrorCard']) {
      assert.ok(generated.components.schemas[schema], `缺少 ${schema}`);
    }
    for (const endpoint of [
      '/pokemon/calculations/type-effectiveness',
      '/pokemon/calculations/speed',
      '/pokemon/calculations/damage',
      '/pokemon/calculations/legality'
    ]) {
      assert.ok(generated.paths[endpoint]?.post, `缺少 ${endpoint} POST`);
    }
    assert.deepEqual(generated.components.schemas.TypeEffectivenessCalculationRequest.required,
      ['dataReleaseId', 'attackType', 'defendTypes']);
    assert.deepEqual(generated.components.schemas.DamageCalculationResult.required,
      ['dataReleaseId', 'calculationVersion', 'minDamage', 'maxDamage', 'modifier']);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('v1.1 会话删除契约与 ResourceController 的 HTTP 200 Result<Void> 一致', () => {
  const document = JSON.parse(fs.readFileSync(path.join(root, 'contracts', 'openapi-v1.1.json'), 'utf8'));
  const controller = fs.readFileSync(path.join(root, 'src', 'main', 'java', 'com', 'vio', 'vioaiagent',
    'controller', 'ResourceController.java'), 'utf8');
  const mappingStart = controller.indexOf('@DeleteMapping("/conversations/{conversationId}")');
  const mappingEnd = controller.indexOf('@Operation(', mappingStart + 1);
  assert.ok(mappingStart >= 0, 'ResourceController 缺少会话删除映射');
  const deleteMethod = controller.slice(mappingStart, mappingEnd < 0 ? undefined : mappingEnd);
  assert.match(deleteMethod, /ResponseEntity<Result<Void>> deleteConversation/);
  assert.match(deleteMethod, /return ResponseEntity\.ok\(Result\.success\(\)\);/);
  const operation = document.paths?.['/conversations/{conversationId}']?.delete;
  assert.ok(operation, '缺少 DELETE /conversations/{conversationId}');
  assert.equal(operation.responses?.['204'], undefined, '实际控制器不返回 204');
  assert.equal(operation.responses?.['200']?.content?.['application/json']?.schema?.$ref,
    '#/components/schemas/ResultEnvelope');
  assert.deepEqual(document.components.schemas.ResultEnvelope.required, ['code', 'message', 'timestamp']);
  assert.equal(document.components.schemas.ResultEnvelope.required.includes('data'), false,
    'Result<Void> 在 NON_NULL 序列化下会省略 data');
});

function runDiff(baseline, candidate, report) {
  return spawnSync(process.execPath, [
    diffTool,
    '--baseline', baseline,
    '--candidate', candidate,
    '--report', report
  ], {cwd: root, encoding: 'utf8'});
}

function contract(required) {
  return {
    openapi: '3.0.3',
    info: {title: 'OpenAPI diff test', version: '1.0.0'},
    paths: {},
    components: {
      schemas: {
        User: {
          type: 'object',
          required,
          properties: {
            id: {type: 'string'},
            displayName: {type: 'string'}
          }
        }
      }
    }
  };
}
