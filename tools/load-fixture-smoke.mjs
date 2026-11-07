#!/usr/bin/env node

/**
 * Run the v1.1 fixture against an isolated PostgreSQL Compose project.
 * Without --execute this is a safe preflight and remains environment-pending.
 */
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {currentCommit, generatedAt, parseArgs, readJson, repoRoot, sha256File, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const project = args['compose-project'] ?? process.env.VIO_DEV_COMPOSE_PROJECT;
const manifestPath = args['fixture-manifest'] ?? 'artifacts/v1.1/baseline/fixture-manifest.json';
const output = args.output ?? 'artifacts/v1.1/staging/fixture-pg-smoke.json';
const sqlPath = args['sql-file'] ?? 'data/v1.1-fixture/fixture.sql';
const baseUrl = args['base-url'] ?? process.env.VIO_BASE_URL ?? null;
const errors = [];
const steps = [];
const startedAt = Date.now();

if (!project || !/^[a-z0-9][a-z0-9_-]{1,62}$/i.test(project)) errors.push('必须提供合法 --compose-project/VIO_DEV_COMPOSE_PROJECT');
const manifest = loadJson(manifestPath, 'fixture manifest');
const sqlFile = path.resolve(process.cwd(), sqlPath);
if (!fs.existsSync(sqlFile) || !fs.statSync(sqlFile).isFile()) errors.push(`fixture SQL 不存在: ${sqlPath}`);
if (manifest?.status === 'blocked') errors.push('fixture manifest 已 blocked');
if (manifest?.productionEligible === true) errors.push('fixture manifest 不得把隔离 fixture 标为 productionEligible');

const dockerAvailable = commandAvailable('docker');
if (!dockerAvailable) errors.push('Docker CLI 不可用');
if (args.execute !== true) steps.push({name: 'execute', status: 'environment-pending', exitCode: null, detail: '未指定 --execute；未触碰 Docker/数据库'});

if (errors.length === 0 && args.execute === true) {
  run(['compose', '-p', project, 'up', '-d', 'postgres', '--wait'], 'postgres-start');
  const sql = fs.readFileSync(sqlFile, 'utf8');
  runWithInput(['compose', '-p', project, 'exec', '-T', 'postgres', 'psql', '-U', 'vio', '-d', 'vio_agent', '-v', 'ON_ERROR_STOP=1'], sql, 'fixture-import');
  runWithInput(['compose', '-p', project, 'exec', '-T', 'postgres', 'psql', '-U', 'vio', '-d', 'vio_agent', '-At', '-c',
    "SELECT 'fact_count=' || count(*) FROM form_fact_version; SELECT 'team_table=' || count(*) FROM saved_team;"], '', 'fact-team-query');
  if (baseUrl) runHttpProbe(baseUrl);
  if (args.cleanup === true) run(['compose', '-p', project, 'down', '-v'], 'cleanup');
}

const passed = errors.length === 0 && args.execute === true && steps.every((step) => step.exitCode === 0);
const status = passed ? 'passed' : (errors.length > 0 ? 'environment-pending' : 'environment-pending');
const report = {
  schemaVersion: 'v1.1', producer: 'load-fixture-smoke', generatedAt: generatedAt(), gitCommit: currentCommit(),
  composeProject: project ?? null, fixtureManifest: manifestPath, fixtureManifestSha256: hashIfExists(manifestPath),
  fixtureSql: sqlPath, fixtureSqlSha256: hashIfExists(sqlPath), executeRequested: args.execute === true,
  status, passed, durationMs: Date.now() - startedAt, steps, errors,
  policy: '只允许在显式 project 名和 --execute 下操作隔离 Compose；不输出数据库密码或连接串。'
};
writeJson(output, report, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify({output, status, passed, steps, errors}, null, 2));
if (!passed && args['allow-pending'] !== true) process.exitCode = 1;

function run(command, name) {
  const result = spawnSync(command[0], command.slice(1), {cwd: repoRoot, encoding: 'utf8', timeout: 180000, windowsHide: true});
  steps.push({name, status: result.status === 0 ? 'passed' : 'failed', exitCode: result.status ?? 1, output: redact(result.stdout || result.stderr || '').slice(0, 500)});
  if (result.error) errors.push(`${name}: ${result.error.message}`);
  if (result.status !== 0) errors.push(`${name}: exit ${result.status ?? 1}`);
}
function runWithInput(command, input, name) {
  const result = spawnSync(command[0], command.slice(1), {cwd: repoRoot, input, encoding: 'utf8', timeout: 180000, windowsHide: true});
  steps.push({name, status: result.status === 0 ? 'passed' : 'failed', exitCode: result.status ?? 1, output: redact(result.stdout || result.stderr || '').slice(0, 500)});
  if (result.error) errors.push(`${name}: ${result.error.message}`);
  if (result.status !== 0) errors.push(`${name}: exit ${result.status ?? 1}`);
}
function runHttpProbe(url) {
  let parsed;
  try { parsed = new URL(url); } catch { errors.push('base-url 无效'); return; }
  const result = spawnSync('curl.exe', ['--fail', '--silent', '--show-error', '--max-time', '10', `${parsed.origin}/api/health`], {encoding: 'utf8', timeout: 15000, windowsHide: true});
  steps.push({name: 'http-health', status: result.status === 0 ? 'passed' : 'failed', exitCode: result.status ?? 1, output: redact(result.stdout || result.stderr || '').slice(0, 300)});
  if (result.status !== 0) errors.push(`http-health: exit ${result.status ?? 1}`);
}
function commandAvailable(command) {
  const probe = spawnSync(process.platform === 'win32' ? 'where.exe' : 'which', [command], {encoding: 'utf8', windowsHide: true});
  return probe.status === 0;
}
function loadJson(file, label) {
  try { return readJson(file, label); } catch (error) { errors.push(error.message); return null; }
}
function hashIfExists(file) {
  try { const absolute = path.resolve(process.cwd(), file); return fs.existsSync(absolute) ? sha256File(absolute) : null; } catch { return null; }
}
function redact(value) {
  return String(value).replace(/(password|secret|token|api[_-]?key|authorization)\s*[:=]\s*[^\s,;]+/gi, '$1=[redacted]')
    .replace(/(postgres(?:ql)?:\/\/)[^\s]+/gi, '$1[redacted]');
}
