import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const contractPath = path.join(root, 'contracts', 'openapi-v1.1.json');
const contract = JSON.parse(fs.readFileSync(contractPath, 'utf8'));
const session = contract.paths['/auth/session']?.get;
const logout = contract.paths['/logout']?.post;
const codegen = path.join(root, 'vio-ai-agent-frontend', 'node_modules', 'openapi-typescript-codegen', 'bin', 'index.js');

test('session bootstrap is anonymous, uses the /api server and returns uncached direct JSON', () => {
  assert.ok(session);
  assert.equal(session.operationId, 'getAuthSession');
  assert.deepEqual(contract.servers.map(server => server.url), ['/api']);
  assert.deepEqual(session.security, []);
  assert.deepEqual(session.parameters, []);
  assert.equal(session.requestBody, undefined);
  assert.deepEqual(Object.keys(session.responses), ['200']);
  assert.equal(session.responses['200'].content['application/json'].schema.$ref,
    '#/components/schemas/SessionStatus');
  assert.deepEqual(session.responses['200'].headers['Cache-Control'].schema.enum, ['no-store']);
  assert.equal(session.responses['200'].headers['Cache-Control'].required, true);
  assert.match(session.description, /no API key is required/);
});

test('session response preserves nullable identity and deadline fields in every authentication mode', () => {
  const status = contract.components.schemas.SessionStatus;
  const fields = ['mode', 'authenticated', 'accessDenied', 'principalId', 'tenantId', 'roles',
    'serverTime', 'absoluteExpiresAt', 'loginUrl', 'logoutUrl', 'csrf'];
  assert.deepEqual(status.required, fields);
  assert.deepEqual(Object.keys(status.properties), fields);
  assert.deepEqual(status.properties.mode.enum, ['oidc', 'local', 'api_key', 'disabled']);
  for (const field of ['authenticated', 'accessDenied']) {
    assert.equal(status.properties[field].type, 'boolean');
  }
  for (const field of ['principalId', 'tenantId', 'absoluteExpiresAt', 'loginUrl', 'logoutUrl']) {
    assert.equal(status.properties[field].nullable, true, `${field} must allow an explicit null`);
  }
  assert.equal(status.properties.roles.type, 'array');
  assert.equal(status.properties.roles.items.type, 'string');
  assert.equal(status.properties.serverTime.format, 'date-time');
  assert.equal(status.properties.absoluteExpiresAt.format, 'date-time');
  assert.equal(status.properties.csrf.$ref, '#/components/schemas/CsrfStatus');
  assert.equal(status.properties.data, undefined, 'The bootstrap response has no Result envelope');
  assert.match(status.description, /no personal login identity/);
});

test('CSRF metadata declares the browser cookie/header names without publishing a credential value', () => {
  const csrf = contract.components.schemas.CsrfStatus;
  assert.deepEqual([...csrf.required].sort(), ['available', 'cookieName', 'enabled', 'headerName']);
  assert.deepEqual(Object.keys(csrf.properties).sort(), ['available', 'cookieName', 'enabled', 'headerName']);
  assert.equal(csrf.properties.enabled.type, 'boolean');
  assert.equal(csrf.properties.available.type, 'boolean');
  assert.deepEqual(csrf.properties.cookieName.enum, ['CSRF-TOKEN']);
  assert.deepEqual(csrf.properties.headerName.enum, ['X-CSRF-TOKEN']);
  assert.match(csrf.description, /cookie at \//);
});

test('logout requires CSRF, respects conditional browser Origin and has an empty 204 response', () => {
  assert.ok(logout);
  assert.equal(logout.operationId, 'logoutOidcSession');
  assert.equal(logout.requestBody, undefined);
  assert.equal(contract.paths['/logout'].get, undefined);
  assert.deepEqual(logout.security, [{}, {oidcSession: []}]);
  const headers = new Map(logout.parameters.map(parameter => [parameter.name, parameter]));
  assert.equal(headers.get('X-CSRF-TOKEN').required, true);
  assert.equal(headers.get('Origin').required, false);
  assert.match(headers.get('Origin').description, /Referer as fallback/);
  assert.equal(headers.has('X-API-Key'), false);
  assert.deepEqual(Object.keys(logout.responses).sort(), ['204', '403', '404']);
  assert.equal(logout.responses['204'].content, undefined);
  assert.match(logout.description, /only in OIDC mode/);
});

test('generated Axios authentication client returns SessionStatus directly and sends logout without a body',
  {skip: !fs.existsSync(codegen) ? 'Frontend codegen dependencies are not installed; rerun after npm ci' : false},
  () => {
    const directory = fs.mkdtempSync(path.join(root, '.tmp-auth-client-'));
    try {
      const generated = path.join(directory, 'generated');
      const result = spawnSync(process.execPath, [codegen, '--input', contractPath, '--output', generated,
        '--client', 'axios', '--useOptions', '--useUnionTypes'], {cwd: root, encoding: 'utf8'});
      assert.equal(result.status, 0, result.stderr || result.stdout);
      const service = fs.readFileSync(path.join(generated, 'services', 'AuthenticationService.ts'), 'utf8');
      const model = fs.readFileSync(path.join(generated, 'models', 'SessionStatus.ts'), 'utf8');
      const bootstrap = service.slice(service.indexOf('public static getAuthSession('),
        service.indexOf('public static logoutOidcSession('));
      const exit = service.slice(service.indexOf('public static logoutOidcSession('));
      assert.match(bootstrap, /CancelablePromise<SessionStatus>/);
      assert.match(bootstrap, /method: 'GET'/);
      assert.match(bootstrap, /url: '\/auth\/session'/);
      assert.doesNotMatch(bootstrap, /body:|X-API-Key|requestBody/);
      assert.match(model, /principalId: string \| null/);
      assert.match(model, /absoluteExpiresAt: string \| null/);
      assert.match(exit, /CancelablePromise<void>/);
      assert.match(exit, /method: 'POST'/);
      assert.match(exit, /url: '\/logout'/);
      assert.match(exit, /'X-CSRF-TOKEN': xCsrfToken/);
      assert.doesNotMatch(exit, /body:|requestBody/);
    } finally {
      fs.rmSync(directory, {recursive: true, force: true});
    }
  });
