#!/usr/bin/env node

/** Probe each dependency in the frozen inventory; no reachability is inferred from Compose syntax. */
import fs from 'node:fs';
import {spawnSync} from 'node:child_process';
import {currentCommit, generatedAt, parseArgs, readJson, repoRoot, resolvePath, redactEndpoint, writeAtomic, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const inventoryPath = args.inventory ?? 'artifacts/v1.1/baseline/dependency-inventory.json';
const contractPath = args['environment-contract'] ?? 'artifacts/v1.1/baseline/environment-contract.json';
const output = args.output ?? 'artifacts/v1.1/staging/dependency-smoke.json';
const textOutput = args['text-output'];
const errors = [];
let inventory = null;
try { inventory = readJson(inventoryPath, 'dependency inventory'); } catch (error) { errors.push(error.message); }
if (inventory && inventory.schemaVersion !== 'v1.1') errors.push('dependency inventory schemaVersion 必须为 v1.1');
if (contractPath && !fs.existsSync(resolvePath(contractPath))) errors.push('environment contract 不存在');
const execute = args.execute === true;
const results = [];
const dependencies = Array.isArray(inventory?.dependencies) ? inventory.dependencies : [];
if (dependencies.length === 0) errors.push('dependency inventory 必须至少包含一项依赖');
const names = dependencies.map((item) => item?.name).filter(Boolean);
if (new Set(names).size !== names.length) errors.push('dependency inventory 存在重复 name');
for (const required of ['postgresql-pgvector', 'dashscope-chat-model', 'dashscope-embedding', 'search-api']) {
  if (!names.includes(required)) errors.push(`缺少 P0 依赖: ${required}`);
}
for (const item of dependencies) {
  const result = {name: item.name, status: 'environment-pending', latencyMs: null, version: item.version ?? null, digest: item.imageOrEndpointDigest ?? null, exitCode: null, healthProbe: item.healthProbe ?? null, networkBoundary: item.networkBoundary ?? null};
  const started = Date.now();
  if (!item.version || !item.healthProbe || !item.owner && !inventory?.owner) {
    result.status = 'blocked-metadata'; result.exitCode = 2;
  } else if (!execute) {
    result.status = item.status === 'not-configured' ? 'not-configured' : 'environment-pending';
  } else if (isHttpProbe(item.healthProbe, item.name)) {
    await probeHttp(result, item.healthProbe);
  } else if (isDockerImage(item.version)) {
    probeDocker(result, item.version);
  } else {
    result.status = 'environment-pending';
  }
  result.latencyMs = Date.now() - started;
  results.push(result);
}
const p0 = results.filter((item) => ['N0', 'N1'].includes(dependencies.find((dep) => dep.name === item.name)?.requiredStage));
const failedP0 = p0.filter((item) => item.status !== 'passed');
const status = errors.length > 0 ? 'blocked' : (failedP0.length === 0 && execute ? 'passed' : 'environment-pending');
const report = {schemaVersion: 'v1.1', producer: 'dependency-smoke', generatedAt: generatedAt(), gitCommit: currentCommit(), inventory: inventoryPath, environmentContract: contractPath, executeRequested: execute, status, passed: status === 'passed', dependencies: results, errors, policy: 'P0 依赖必须有现场 health/version/digest 探针；未授权或未执行保持 environment-pending。'};
writeJson(output, report, {noClobber: args['no-clobber'] === true});
if (textOutput) writeAtomic(textOutput, results.map((item) => `${item.name}\t${item.status}\t${item.latencyMs ?? '-'}\t${item.version ?? '-'}\t${item.digest ?? '-'}\t${item.exitCode ?? '-'}`).join('\n') + '\n', {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify({output, status, passed: report.passed, p0Failed: failedP0.map((item) => item.name), errors}, null, 2));
if (!report.passed && args['allow-pending'] !== true) process.exitCode = 1;

async function probeHttp(result, probe) {
  const envName = endpointEnv(result.name);
  const endpoint = envName ? process.env[envName] : null;
  if (!endpoint) return;
  try {
    const base = new URL(endpoint);
    const url = new URL(probe.startsWith('/') ? probe : `/${probe}`, base);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 10000);
    const response = await fetch(url, {signal: controller.signal});
    clearTimeout(timer);
    result.status = response.ok ? 'passed' : 'failed'; result.exitCode = response.ok ? 0 : 1; result.version = result.version ?? response.headers.get('server');
    result.endpoint = redactEndpoint(base.toString());
  } catch { result.status = 'failed'; result.exitCode = 1; }
}
function probeDocker(result, image) {
  const probe = spawnSync('docker', ['image', 'inspect', image, '--format', '{{.Id}}'], {cwd: repoRoot, encoding: 'utf8', timeout: 30000, windowsHide: true});
  result.exitCode = probe.status ?? 1;
  if (probe.status === 0 && /^sha256:[a-f0-9]{64}$/i.test((probe.stdout ?? '').trim())) { result.digest = probe.stdout.trim(); result.status = 'passed'; }
  else result.status = 'failed';
}
function isDockerImage(version) { return /^(?:[a-z0-9._-]+\/)+[a-z0-9._-]+(?::[a-z0-9._-]+)?$/i.test(String(version)); }
function isHttpProbe(probe, name) { return String(probe).startsWith('/') || /^https?:/i.test(String(probe)); }
function endpointEnv(name) { return {'prometheus': 'VIO_PROMETHEUS_URL', 'grafana': 'VIO_GRAFANA_URL', 'alertmanager': 'VIO_ALERTMANAGER_URL', 'oidc-provider': 'VIO_OIDC_URL'}[name]; }
