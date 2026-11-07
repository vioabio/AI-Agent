import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {createRequire} from 'node:module';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const contractPath = path.join(root, 'contracts', 'openapi-v1.1.json');
const contract = JSON.parse(fs.readFileSync(contractPath, 'utf8'));
const upload = contract.paths['/knowledge/evaluation-reports']?.post;
const read = contract.paths['/knowledge/evaluation-reports/{evaluationRunId}']?.get;
const codegen = path.join(root, 'vio-ai-agent-frontend', 'node_modules', 'openapi-typescript-codegen', 'bin', 'index.js');

test('checked contract local references resolve before client generation', () => {
  function inspect(value) {
    if (!value || typeof value !== 'object') return;
    if (typeof value.$ref === 'string' && value.$ref.startsWith('#/')) {
      let target = contract;
      for (const token of value.$ref.substring(2).split('/')) {
        target = target?.[token.replaceAll('~1', '/').replaceAll('~0', '~')];
      }
      assert.notEqual(target, undefined, `Unresolved local reference: ${value.$ref}`);
    }
    for (const child of Object.values(value)) inspect(child);
  }
  inspect(contract);
});

test('report import accepts original binary JSON with a required digest and source, not a boolean projection', () => {
  assert.ok(upload);
  assert.equal(upload.operationId, 'importEvaluationReportArtifact');
  assert.equal(upload.requestBody.required, true);
  assert.equal(upload.requestBody.content['application/json'].schema.$ref,
    '#/components/schemas/EvaluationReportArtifact');
  const artifact = contract.components.schemas.EvaluationReportArtifact;
  assert.equal(artifact.type, 'string');
  assert.equal(artifact.format, 'binary');
  assert.equal(artifact['x-max-bytes'], 1048576);
  assert.equal(artifact['x-preserve-original-bytes'], true);
  const headers = new Map(upload.parameters.map(parameter => [parameter.name, parameter]));
  assert.equal(headers.get('X-Evaluation-Report-Hash').required, true);
  assert.equal(headers.get('X-Evaluation-Report-Hash').schema.pattern, '^sha256:[a-f0-9]{64}$');
  assert.equal(headers.get('X-Evaluation-Source').required, true);
  assert.equal(headers.get('X-Evaluation-Source').schema.maxLength, 512);
  assert.ok(headers.has('X-CSRF-TOKEN'));
  assert.equal(headers.has('X-Role'), false);
  assert.equal(headers.has('X-User-Id'), false);
  assert.match(upload.description, /without JSON reserialization/);
  assert.deepEqual(upload.security, [{bearerAuth: []}, {oidcSession: []}, {apiKeyAuth: []}]);
});

test('report read/import return a typed Result projection and declare controller failure statuses', () => {
  assert.ok(read);
  assert.equal(read.operationId, 'getEvaluationReport');
  assert.equal(read.parameters[0].name, 'evaluationRunId');
  assert.equal(read.parameters[0].required, true);
  assert.equal(read.requestBody, undefined);
  assert.deepEqual(Object.keys(upload.responses).sort(), ['200', '400', '401', '403', '409', '413', '503']);
  assert.deepEqual(Object.keys(read.responses).sort(), ['200', '401', '403', '404', '503']);
  for (const operation of [upload, read]) {
    const envelope = operation.responses['200'].content['application/json'].schema;
    assert.deepEqual(envelope.required, ['code', 'message', 'data', 'timestamp']);
    assert.equal(envelope.properties.data.$ref, '#/components/schemas/EvaluationReport');
  }
  const projection = contract.components.schemas.EvaluationReport;
  assert.equal(projection.readOnly, true);
  assert.deepEqual(projection.required, ['evaluationRunId', 'reportHash', 'suiteVersion', 'modelVersion',
    'dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId', 'sampleSize', 'passed', 'immutable',
    'generatedAt', 'metrics', 'thresholds']);
  assert.equal(projection.properties.metrics.additionalProperties.type, 'number');
  assert.equal(projection.properties.thresholds.additionalProperties.type, 'string');
  assert.match(projection.description, /passed=false/);
});

test('stream-based controller explicitly documents original bytes rather than a deserialized request DTO', () => {
  const controller = fs.readFileSync(path.join(root, 'src/main/java/com/vio/vioaiagent/controller/EvaluationReportController.java'), 'utf8');
  assert.match(controller, /@Operation\(operationId = "importEvaluationReportArtifact"/);
  assert.match(controller, /schema = @Schema\(type = "string", format = "binary"\)/);
  assert.match(controller, /request\.getInputStream\(\)\.readNBytes\(MAX_ARTIFACT_BYTES \+ 1\)/);
  assert.match(controller, /@Operation\(operationId = "getEvaluationReport"/);
  assert.doesNotMatch(controller, /@RequestBody\s+EvaluationReport/);
});

test('generated Axios import uses Blob and preserves every original byte',
  {skip: !fs.existsSync(codegen) ? 'Frontend codegen dependencies are not installed; rerun after npm ci' : false},
  async () => {
    const directory = fs.mkdtempSync(path.join(root, '.tmp-report-client-'));
    try {
      const generated = path.join(directory, 'generated');
      const result = spawnSync(process.execPath, [codegen, '--input', contractPath, '--output', generated,
        '--client', 'axios', '--useOptions', '--useUnionTypes'], {cwd: root, encoding: 'utf8'});
      assert.equal(result.status, 0, result.stderr || result.stdout);
      const model = fs.readFileSync(path.join(generated, 'models', 'EvaluationReportArtifact.ts'), 'utf8');
      assert.match(model, /EvaluationReportArtifact\s*=\s*Blob/);
      const services = fs.readdirSync(path.join(generated, 'services')).map(name =>
        fs.readFileSync(path.join(generated, 'services', name), 'utf8'));
      const service = services.find(source => source.includes('importEvaluationReportArtifact('));
      assert.ok(service, 'Generated import service is missing');
      assert.match(service, /requestBody: EvaluationReportArtifact/);
      assert.match(service, /body: requestBody/);
      assert.match(service, /mediaType: 'application\/json'/);
      const transport = fs.readFileSync(path.join(generated, 'core', 'request.ts'), 'utf8');
      const bodyStart = transport.indexOf('export const getRequestBody');
      const bodyEnd = transport.indexOf('export const sendRequest', bodyStart);
      const bodyFunction = transport.slice(bodyStart, bodyEnd);
      assert.match(bodyFunction, /return options\.body/);
      assert.doesNotMatch(bodyFunction, /JSON\.stringify/);

      const frontendRequire = createRequire(path.join(root, 'vio-ai-agent-frontend', 'package.json'));
      const axios = frontendRequire('axios');
      const original = Buffer.from('{\n  "note": "synthetic contract fixture 宝可梦", "value": 1.00\n}\n', 'utf8');
      const artifact = new Blob([original], {type: 'application/json'});
      const digest = crypto.createHash('sha256').update(original).digest('hex');
      let delivered = false;
      await axios.post('https://fixture.invalid/report', artifact, {
        headers: {'Content-Type': 'application/json', 'X-Evaluation-Report-Hash': `sha256:${digest}`},
        adapter: async config => {
          // Local adapter only: no network request, credentials or real evidence.
          delivered = true;
          assert.equal(config.data, artifact);
          const bytes = Buffer.from(await config.data.arrayBuffer());
          assert.deepEqual(bytes, original);
          assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), digest);
          return {status: 200, statusText: 'OK', data: {}, headers: {}, config};
        }
      });
      assert.equal(delivered, true);
    } finally {
      fs.rmSync(directory, {recursive: true, force: true});
    }
  });
