#!/usr/bin/env node

/**
 * Structural/execute gate for the v1.1 expand/migrate/contract matrix.
 *
 * The repository cannot infer a running PostgreSQL or an old application
 * image.  Consequently the default mode validates the explicit matrix and
 * emits `environment-pending`; `--execute` is deliberately unsupported until
 * a reviewed adapter is supplied.  This prevents a static SQL check from
 * being reported as a rollback proof.
 */
import fs from 'node:fs';
import path from 'node:path';
import {parseArgs, resolvePath, requireFile, sha256File, writeJson, currentCommit, generatedAt, repoRoot, repoRelative} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const matrixArg = scalar(args.matrix);
const outputArg = scalar(args.output ?? args.report);
const execute = args.execute === true;
const allowPending = args['allow-pending'] === true;
const errors = [];
const requiredIds = new Set(['v1.0-app-v1.0-schema', 'v1.0-app-v1.1-expand-schema', 'v1.1-app-v1.1-schema', 'v1.1-app-previous-pointers']);
let matrix = null;
let matrixPath = null;

if (!matrixArg) errors.push('--matrix 必须显式提供');
if (!outputArg) errors.push('--output/--report 必须显式提供');
if (matrixArg) {
  try {
    matrixPath = requireFile(matrixArg, 'migration matrix');
    repoRelative(matrixPath);
    matrix = JSON.parse(fs.readFileSync(matrixPath, 'utf8'));
  } catch (error) { errors.push(`matrix 读取失败: ${safe(error.message)}`); }
}

const cases = Array.isArray(matrix?.cases) ? matrix.cases : [];
if (!matrix || matrix.schemaVersion !== 'v1.1') errors.push('matrix schemaVersion 必须为 v1.1');
if (cases.length === 0) errors.push('matrix.cases 不能为空');
const seen = new Set();
for (const [index, item] of cases.entries()) {
  if (!item || typeof item !== 'object' || Array.isArray(item)) { errors.push(`cases[${index}] 必须是对象`); continue; }
  const id = String(item.id ?? '').trim();
  if (!id || seen.has(id)) errors.push(`cases[${index}] id 缺失或重复`);
  seen.add(id);
  for (const field of ['app', 'schema', 'migration', 'readiness', 'rollback']) {
    if (!item[field] || typeof item[field] !== 'string') errors.push(`cases[${index}] 缺少 ${field}`);
  }
  if (item.destructive === true) errors.push(`cases[${index}] 禁止 destructive=true；迁移矩阵必须可回滚`);
}
for (const id of requiredIds) if (!seen.has(id)) errors.push(`缺少必需矩阵项: ${id}`);
if (execute) errors.push('--execute 尚未绑定经审核的隔离环境适配器，保持 environment-pending');

const checks = [
  {name: 'matrix-shape', passed: errors.length === 0 || !matrix ? false : true},
  {name: 'required-combinations', passed: [...requiredIds].every((id) => seen.has(id))},
  {name: 'execution-adapter', passed: false, reason: '未执行真实隔离数据库/应用组合'}
];
const report = {
  schemaVersion: 'v1.1', producer: 'check-migration-compatibility', generatedAt: generatedAt(), gitCommit: currentCommit(),
  matrix: matrixPath ? safeRelative(matrixPath) : null, matrixSha256: matrixPath ? sha256File(matrixPath) : null,
  executeRequested: execute, caseCount: cases.length,
  cases: cases.map((item) => ({
    id: item?.id ?? null, app: item?.app ?? null, schema: item?.schema ?? null, migration: item?.migration ?? null,
    readiness: item?.readiness ?? null, rollback: item?.rollback ?? null,
    status: errors.length === 0 && !execute ? 'environment-pending' : 'blocked', exitCode: null
  })),
  checks, status: errors.length === 0 && !execute ? 'environment-pending' : 'blocked',
  passed: false, errors: [...new Set(errors)], redacted: true,
  policy: '静态矩阵/SQL 校验不能替代真实隔离数据库、应用 readiness 与 rollback 演练'
};
if (outputArg) {
  try { const resolvedOutput = resolvePath(outputArg); repoRelative(resolvedOutput); writeJson(resolvedOutput, report, {noClobber: args['no-clobber'] === true}); }
  catch (error) { report.errors.push(`report 写入失败: ${safe(error.message)}`); report.status = 'blocked'; }
}
report.exitCode = report.status === 'environment-pending' && allowPending ? 0 : 1;
console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0) process.exitCode = 1;

function scalar(value) { if (Array.isArray(value)) return value.length === 1 ? String(value[0]).trim() : null; if (value === undefined || value === null || value === true || value === false) return null; return String(value).trim(); }
function safe(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 240); }
function safeRelative(file) { const rel = path.relative(repoRoot, path.resolve(file)); return rel && !rel.startsWith('..') && !path.isAbsolute(rel) ? rel.split(path.sep).join('/') : null; }
