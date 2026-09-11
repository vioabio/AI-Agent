#!/usr/bin/env node

/**
 * Produce the redacted v1.1 feature-switch snapshot.
 *
 * This is an offline producer: it reads only committed example/application
 * files, never the local secret configuration, and records no secret value.
 * Missing inputs produce an explicit blocked artifact and a non-zero exit;
 * they are never silently replaced with a healthy/default claim.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  repoOutputInfo,
  repoPathInfo,
  sha256File,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
let destinationBlocked = false;
const noClobber = args['no-clobber'] === true;
const allowedOptions = new Set(['output', 'hash-output', 'report', 'owner', 'no-clobber']);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!allowedOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const outputArg = args.output ?? 'artifacts/v1.1/baseline/switches.json';
const hashArg = args['hash-output'];
const reportArg = args.report;
const outputInfo = destination(outputArg, 'switch snapshot 输出');
const hashInfo = hashArg === undefined ? null : destination(hashArg, 'switch snapshot hash 输出');
const reportInfo = reportArg === undefined ? null : destination(reportArg, 'switch snapshot 报告');
for (const [label, info] of [['switch snapshot', outputInfo], ['hash', hashInfo], ['report', reportInfo]]) {
  if (info && noClobber && fs.existsSync(info.absolute)) {
    errors.push(`${label} 已存在（--no-clobber）: ${info.relative}`);
    destinationBlocked = true;
  }
}
for (const [leftLabel, left, rightLabel, right] of [
  ['output', outputInfo, 'hash-output', hashInfo],
  ['output', outputInfo, 'report', reportInfo],
  ['hash-output', hashInfo, 'report', reportInfo]
]) {
  if (left && right && samePath(left.absolute, right.absolute)) {
    errors.push(`${leftLabel} 与 ${rightLabel} 不能是同一路径`);
    destinationBlocked = true;
  }
}

const owner = scalar(args.owner ?? process.env.VIO_BASELINE_OWNER);
if (!owner || !/^[a-z0-9][a-z0-9._-]{2,63}$/i.test(owner)) errors.push('owner 必须通过 --owner/VIO_BASELINE_OWNER 提供稳定标识');

// Deliberately exclude application-local.yml and mcp-servers.json: those are
// local secret stores and must never be opened by a baseline producer.
const sourceFiles = [
  'src/main/resources/application.yml',
  'src/main/resources/application-prod.yml',
  'src/main/resources/application-local.example.yml',
  '.env.example'
];
const sourceInfo = [];
const missing = [];
for (const relative of sourceFiles) {
  try {
    const info = repoPathInfo(relative, {label: `source ${relative}`, mustExist: true, kind: 'file'});
    sourceInfo.push({path: info.relative, sha256: sha256File(info.real), bytes: fs.statSync(info.real).size});
  } catch {
    missing.push(relative);
  }
}
if (missing.length > 0) errors.push(...missing.map((value) => `缺少 source 文件: ${value}`));

const switches = [
  ['VIO_AGENT_V1_ENABLED', false, false, false, true, 'N0 staging smoke 与版本化 grounding 通过', '关闭现代主链并返回安全拒答'],
  ['VIO_RAG_PGVECTOR_ENABLED', false, false, false, true, 'N1 真实 PGVector 迁移、维度和检索 smoke', '停止 RAG 流量并保持发布阻断'],
  ['VIO_RUN_DURABLE_RESUME', false, false, false, false, 'N2 checkpoint、lease 和幂等通过', '安全失败，不假装继续执行'],
  ['VIO_EVENT_BROKER_ENABLED', false, false, false, false, 'N2 fan-out、背压和游标压测通过', '使用事件页/有限轮询'],
  ['VIO_REMOTE_MCP_ENABLED', false, false, false, false, 'N3 认证、受众、超时和审计通过', '关闭远程 MCP'],
  ['VIO_MCP_ENABLED', false, false, false, false, '唯一生产 MCP 路径和断开 fail-closed 通过', '仅保留本地只读工具'],
  ['VIO_HIGH_RISK_TOOLS_ENABLED', false, false, false, false, '持久 HITL 和红队测试通过', '拒绝所有高风险操作'],
  ['VIO_MEMORY_ENABLED', false, false, false, false, '用户级授权、TTL、删除和恢复通过', '仅使用窗口记忆'],
  ['VIO_FEEDBACK_ENABLED', true, true, true, true, 'N6b 持久反馈和血缘通过', '关闭纠错发布入口，不删除历史反馈'],
  ['VIO_LEGACY_AI_ENABLED', true, false, false, false, '现代 Run 主链和回退证据通过', '返回 410/安全拒答'],
  ['VIO_LEGACY_MANUS_ENABLED', true, false, false, false, '现代 Run 主链和回退证据通过', '返回 410/安全拒答'],
  ['VIO_REAL_EVAL_GATE', false, false, false, false, '真实 Golden Set 和不可变报告通过', 'release gate 保持阻断'],
  ['VIO_CANARY_ENABLED', false, false, false, false, 'readiness、监控和自动回滚通过', '不切流，使用上一 digest']
].map(([name, localDefault, prodDefault, localTarget, prodTarget, prerequisite, rollback]) => ({
  name,
  kind: 'feature-flag',
  currentDefault: {
    local: resolveDefault(name, 'local', localDefault),
    prod: resolveDefault(name, 'prod', prodDefault)
  },
  targetDefault: {local: localTarget, prod: prodTarget},
  removeDate: null,
  source: sourceFiles.filter((file) => sourceInfo.some((item) => item.path === file)),
  owner: owner ?? 'unassigned',
  enablePrerequisite: prerequisite,
  rollback
}));

const blocked = errors.length > 0;
const payload = {
  schemaVersion: 'v1.1',
  producer: 'export-switch-snapshot',
  generatedAt: generatedAt(),
  gitCommit: currentCommit(),
  owner: owner ?? 'unassigned',
  status: blocked ? 'blocked' : 'ready-for-review',
  passed: !blocked,
  producerCompleted: !blocked,
  exitCode: blocked ? 1 : 0,
  redacted: true,
  switches,
  secretVariables: ['VIO_API_KEY', 'SPRING_DATASOURCE_PASSWORD', 'AI_DASHSCOPE_API_KEY', 'SEARCH_API_KEY', 'DB_PASSWORD'],
  secretPolicy: '仅记录 Secret 变量名和存在性，不记录值、摘要或内容 hash',
  sourceFiles: sourceInfo,
  missing,
  errors
};

// Preflight all destinations before the first write. A malformed path or a
// no-clobber conflict therefore cannot leave a half-written producer set.
if (outputInfo && !destinationBlocked) {
  try {
    writeJson(outputInfo.absolute, payload, {noClobber});
    if (hashInfo && fs.existsSync(outputInfo.absolute)) {
      writeJson(hashInfo.absolute, {
        schemaVersion: 'v1.1', producer: 'export-switch-snapshot', generatedAt: payload.generatedAt,
        gitCommit: payload.gitCommit, owner: payload.owner,
        files: [{path: outputInfo.relative, sha256: sha256File(outputInfo.absolute), algorithm: 'SHA-256'}]
      }, {noClobber});
    }
  } catch (error) {
    errors.push(`producer 输出失败: ${safeError(error)}`);
    payload.status = 'blocked'; payload.passed = false; payload.producerCompleted = false; payload.exitCode = 1; payload.errors = [...errors];
  }
}

const report = {
  schemaVersion: 'v1.1', producer: 'export-switch-snapshot', generatedAt: payload.generatedAt,
  gitCommit: payload.gitCommit, owner: payload.owner,
  output: outputInfo?.relative ?? '[invalid-path]', hashOutput: hashInfo?.relative ?? null,
  passed: errors.length === 0, status: errors.length === 0 ? 'ready-for-review' : 'blocked',
  exitCode: errors.length === 0 ? 0 : 1, redacted: true, switchCount: switches.length,
  sourceFiles: sourceInfo.map((item) => item.path), missing, errors
};
if (reportInfo && !(noClobber && fs.existsSync(reportInfo.absolute))) {
  try { writeJson(reportInfo.absolute, report, {noClobber}); } catch (error) { errors.push(`报告写入失败: ${safeError(error)}`); }
}
console.log(JSON.stringify(report, null, 2));
if (errors.length > 0) process.exitCode = 1;

function destination(value, label) {
  try { return repoOutputInfo(value, label); }
  catch (error) { errors.push(`${label} 路径无效: ${safeError(error)}`); destinationBlocked = true; return null; }
}

function resolveDefault(name, profile, fallback) {
  const file = profile === 'prod' ? 'src/main/resources/application-prod.yml' : 'src/main/resources/application.yml';
  let text = '';
  try {
    const info = repoPathInfo(file, {label: file, mustExist: true, kind: 'file'});
    text = fs.readFileSync(info.real, 'utf8');
  } catch { return fallback; }
  // Supports literal booleans and Spring `${NAME:true}` placeholders. A
  // required placeholder without a default remains represented by fallback;
  // the source file hash and pending external contract carry the uncertainty.
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = text.match(new RegExp(`${escaped}\\s*:\\s*\\$\\{${escaped}(?::(true|false))?\\}|${escaped}\\s*:\\s*(true|false)`, 'i'));
  const value = match?.[1] ?? match?.[2];
  return value === undefined ? fallback : value.toLowerCase() === 'true';
}

function scalar(value) {
  if (value === undefined || value === null || value === true || value === false) return null;
  const text = String(value).trim();
  return text || null;
}

function samePath(left, right) {
  const a = path.resolve(left); const b = path.resolve(right);
  return process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b;
}

function safeError(error) { return String(error?.message ?? error).replace(/[\r\n]/g, ' ').slice(0, 500); }
