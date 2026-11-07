#!/usr/bin/env node

/**
 * Validate the reviewed shape of the v1.1 fault-injection plan.
 *
 * This tool is a local contract/evidence validator. It never starts a fault,
 * calls an application, talks to a monitoring system, or changes production.
 * A pending plan is intentionally not a pass unless --allow-pending is
 * explicitly supplied for a pre-review structural check.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  placeholder,
  readJson,
  repoRelative,
  resolvePath,
  sha256File,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const inputArg = scalar(args.input);
const schemaArg = scalar(args.schema) || 'config/fault-injection-v1.1.schema.json';
const outputArg = scalar(args.report || args.output) || 'artifacts/v1.1/observability/fault-plan-validation.json';
const allowPending = args['allow-pending'] === true;
const noClobber = args['no-clobber'] === true;
const known = new Set(['input', 'schema', 'report', 'output', 'allow-pending', 'no-clobber']);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!known.has(key)) errors.push('未知参数: --' + key);
}
if (args._positional.length > 0) errors.push('不支持的位置参数: ' + args._positional.join(' '));
if (!inputArg) errors.push('--input 必须显式提供故障计划');

let inputPath = null;
let schemaPath = null;
let plan = null;
let schema = null;
try {
  if (inputArg) {
    inputPath = resolveRepoFile(inputArg, 'fault plan');
    plan = readJson(inputPath, 'fault plan');
  }
} catch (error) {
  errors.push(safe(error.message));
}
try {
  schemaPath = resolveRepoFile(schemaArg, 'fault plan schema');
  schema = readJson(schemaPath, 'fault plan schema');
  if (!String(schema?.$id || '').includes('fault-injection-v1.1')) errors.push('schema 不是 fault-injection-v1.1 契约');
} catch (error) {
  errors.push(safe(error.message));
}

let outputPath = null;
try {
  outputPath = resolveOutput(outputArg, 'fault plan validation report');
  if (inputPath && samePath(outputPath, inputPath)) errors.push('report 不能覆盖 input');
  if (schemaPath && samePath(outputPath, schemaPath)) errors.push('report 不能覆盖 schema');
} catch (error) {
  errors.push(safe(error.message));
}

const checks = [];
if (plan && typeof plan === 'object' && !Array.isArray(plan)) {
  validatePlan(plan);
} else if (plan !== null) {
  errors.push('fault plan 必须是 JSON 对象');
}

const structuralPassed = errors.length === 0;
const status = structuralPassed
  ? (String(plan?.status) === 'approved' ? 'passed' : 'environment-pending')
  : 'blocked';
const passed = status === 'passed';
if (structuralPassed && String(plan?.status) !== 'approved') {
  warnings.push('plan status 不是 approved；未经过 reviewer 批准，不能进入故障注入或 GA 门禁');
}
const report = {
  schemaVersion: 'v1.1',
  producer: 'validate-fault-plan',
  generatedAt: generatedAt(),
  gitCommit: currentCommit(),
  input: inputPath ? repoRelative(inputPath) : null,
  inputSha256: inputPath && fs.existsSync(inputPath) ? sha256File(inputPath) : null,
  schema: schemaPath ? repoRelative(schemaPath) : null,
  schemaSha256: schemaPath && fs.existsSync(schemaPath) ? sha256File(schemaPath) : null,
  planId: plan?.planId || null,
  planStatus: plan?.status || null,
  scenarioCount: Array.isArray(plan?.scenarios) ? plan.scenarios.length : 0,
  scenarioIds: Array.isArray(plan?.scenarios) ? plan.scenarios.map((item) => item?.scenarioId || null) : [],
  allowPending,
  passed,
  status,
  exitCode: passed || (status === 'environment-pending' && allowPending) ? 0 : 1,
  checks,
  errors: unique(errors),
  warnings: unique(warnings),
  redacted: true
};
if (outputPath) {
  try {
    writeJson(outputPath, report, {noClobber});
  } catch (error) {
    report.errors.push('报告写入失败: ' + safe(error.message));
    report.passed = false;
    report.status = 'blocked';
    report.exitCode = 1;
  }
}
console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0) process.exitCode = 1;

function validatePlan(value) {
  check('schemaVersion', value.schemaVersion === 'v1.1', 'schemaVersion 必须为 v1.1');
  check('planId', validId(value.planId), 'planId 缺失或无效');
  check('owner', typeof value.owner === 'string' && value.owner.trim().length > 0, 'owner 缺失');
  check('status', ['pending', 'approved', 'blocked'].includes(value.status), 'status 必须是 pending/approved/blocked');
  if (value.status === 'approved') {
    check('reviewer', typeof value.reviewer === 'string' && !placeholder(value.reviewer), 'approved plan 必须有 reviewer');
    const approvedAt = value.approvedAt ? new Date(String(value.approvedAt)) : null;
    check('approvedAt', approvedAt && !Number.isNaN(approvedAt.getTime()), 'approved plan 必须有有效 approvedAt');
  }
  const scenarios = Array.isArray(value.scenarios) ? value.scenarios : [];
  check('scenarios', scenarios.length >= 4, '必须覆盖四类故障场景');
  const requiredTypes = new Set(['model-timeout-5xx', 'database-unavailable', 'mcp-disconnect', 'candidate-index-query-failure']);
  const seenIds = new Set();
  const seenTypes = new Set();
  scenarios.forEach((scenario, index) => {
    const prefix = 'scenarios[' + index + ']';
    if (!validId(scenario?.scenarioId) || seenIds.has(String(scenario?.scenarioId))) errors.push(prefix + '.scenarioId 缺失或重复');
    seenIds.add(String(scenario?.scenarioId));
    if (!requiredTypes.has(String(scenario?.faultType)) || seenTypes.has(String(scenario?.faultType))) errors.push(prefix + '.faultType 缺失、未知或重复');
    seenTypes.add(String(scenario?.faultType));
    if (typeof scenario?.target !== 'string' || !scenario.target.trim() || placeholder(scenario.target)) errors.push(prefix + '.target 缺失或为占位符');
    if (!Array.isArray(scenario?.expectedSignals) || scenario.expectedSignals.length === 0 || scenario.expectedSignals.some((item) => typeof item !== 'string' || !item.trim())) errors.push(prefix + '.expectedSignals 必须是非空字符串数组');
    if (typeof scenario?.recoveryAction !== 'string' || !scenario.recoveryAction.trim() || placeholder(scenario.recoveryAction)) errors.push(prefix + '.recoveryAction 缺失或为占位符');
    const duration = Number(scenario?.maxDurationSeconds);
    if (!Number.isInteger(duration) || duration < 1 || duration > 86400) errors.push(prefix + '.maxDurationSeconds 必须是 1..86400 的整数');
    if (scenario?.requiresApproval !== true) errors.push(prefix + '.requiresApproval 必须为 true');
  });
  for (const required of requiredTypes) if (!seenTypes.has(required)) errors.push('缺少故障类型: ' + required);
  if (value.redaction !== undefined) {
    check('redaction.persistResponseBodies', value.redaction?.persistResponseBodies === false, 'redaction.persistResponseBodies 必须为 false');
  } else {
    errors.push('缺少 redaction 配置');
  }
  checks.push({name: 'four-fault-types', passed: requiredTypes.size === seenTypes.size && scenarios.length >= 4});
  checks.push({name: 'manual-approval-required', passed: scenarios.length > 0 && scenarios.every((item) => item?.requiresApproval === true)});
}

function check(name, passedValue, message) {
  checks.push({name, passed: Boolean(passedValue)});
  if (!passedValue) errors.push(message);
}
function validId(value) {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$/.test(value) && !placeholder(value);
}
function scalar(value) {
  if (Array.isArray(value)) return value.length === 1 ? String(value[0]).trim() : null;
  if (value === undefined || value === null || value === true || value === false) return null;
  return String(value).trim() || null;
}
function safe(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 240); }
function unique(values) { return [...new Set(values.filter(Boolean))]; }
function resolveRepoFile(value, label) {
  const absolute = resolvePath(value);
  repoRelative(absolute);
  if (!fs.existsSync(absolute) || !fs.statSync(absolute).isFile()) throw new Error(label + ' 不存在或不是文件');
  if (fs.lstatSync(absolute).isSymbolicLink()) throw new Error(label + ' 不允许是符号链接');
  return absolute;
}
function resolveOutput(value, label) {
  const absolute = resolvePath(value);
  repoRelative(absolute);
  if (fs.existsSync(absolute) && !fs.statSync(absolute).isFile()) throw new Error(label + ' 不能覆盖目录');
  return absolute;
}
function samePath(left, right) {
  const a = path.resolve(left);
  const b = path.resolve(right);
  return process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b;
}
