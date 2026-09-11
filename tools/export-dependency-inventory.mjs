#!/usr/bin/env node

/**
 * Produce the redacted N0 dependency inventory from committed build inputs.
 *
 * This producer performs no registry/Docker/network calls.  An image tag is
 * intentionally retained as a candidate version, while the missing immutable
 * digest keeps the item environment-pending.  Runtime probes and supply-chain
 * evidence are added by the later N7 producers.
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
import {parseCompose, parseImageReference} from './lib/supply-chain.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const noClobber = args['no-clobber'] === true;
let destinationBlocked = false;
const allowedOptions = new Set(['output', 'hash-output', 'report', 'owner', 'compose', 'pom', 'allow-pending', 'no-clobber']);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!allowedOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const outputArg = args.output ?? 'artifacts/v1.1/baseline/dependency-inventory.json';
const hashArg = args['hash-output'];
const reportArg = args.report;
const outputInfo = destination(outputArg, 'dependency inventory 输出');
const hashInfo = hashArg === undefined ? null : destination(hashArg, 'dependency inventory hash 输出');
const reportInfo = reportArg === undefined ? null : destination(reportArg, 'dependency inventory 报告');
for (const [label, info] of [['inventory', outputInfo], ['hash', hashInfo], ['report', reportInfo]]) {
  if (!info && (label === 'inventory' || (label === 'hash' && hashArg !== undefined) || (label === 'report' && reportArg !== undefined))) destinationBlocked = true;
  if (info && noClobber && fs.existsSync(info.absolute)) { errors.push(`${label} 已存在（--no-clobber）: ${info.relative}`); destinationBlocked = true; }
}
for (const [leftLabel, left, rightLabel, right] of [
  ['output', outputInfo, 'hash-output', hashInfo],
  ['output', outputInfo, 'report', reportInfo],
  ['hash-output', hashInfo, 'report', reportInfo]
]) {
  if (left && right && samePath(left.absolute, right.absolute)) { errors.push(`${leftLabel} 与 ${rightLabel} 不能是同一路径`); destinationBlocked = true; }
}

const owner = scalar(args.owner ?? process.env.VIO_BASELINE_OWNER);
if (!owner || !/^[a-z0-9][a-z0-9._-]{2,63}$/i.test(owner)) errors.push('owner 必须通过 --owner/VIO_BASELINE_OWNER 提供稳定标识');

const composeArg = args.compose ?? 'docker-compose.yml';
const pomArg = args.pom ?? 'pom.xml';
const composeInfo = input(composeArg, 'Compose 输入');
const pomInfo = input(pomArg, 'pom 输入');
const compose = composeInfo ? fs.readFileSync(composeInfo.real, 'utf8') : '';
const pom = pomInfo ? fs.readFileSync(pomInfo.real, 'utf8') : '';
if (!composeInfo) errors.push(`Compose 输入缺失或无效: ${String(composeArg)}`);
if (!pomInfo) errors.push(`pom 输入缺失或无效: ${String(pomArg)}`);

const composeResult = composeInfo ? parseCompose(compose, composeInfo.relative) : {images: [], builds: [], errors: []};
for (const error of composeResult.errors ?? []) warnings.push(`Compose 解析提示: ${error}`);
const imagesByService = new Map();
for (const image of composeResult.images ?? []) {
  if (image.service && !imagesByService.has(image.service)) imagesByService.set(image.service, image);
}

const dependencies = [];
const addImage = (name, service, requiredStage, healthProbe, networkBoundary) => {
  const parsed = imagesByService.get(service);
  const raw = parsed?.raw ?? null;
  const valid = parsed?.valid === true;
  const digestRef = valid && parsed?.digest
    ? (/[^\s@]+@sha256:[a-f0-9]{64}$/i.test(raw ?? '') ? raw : `${parsed.image}@sha256:${parsed.digest}`)
    : null;
  const version = raw;
  const itemErrors = [];
  if (!parsed) itemErrors.push('Compose 中未发现 image');
  else if (!valid) itemErrors.push(parsed.reason ?? '镜像引用无效');
  if (parsed?.tag && /^latest$/i.test(parsed.tag)) itemErrors.push('禁止 latest 浮动 tag');
  if (itemErrors.length > 0) errors.push(`${name}: ${itemErrors.join('; ')}`);
  dependencies.push({
    // Keep the mutable tag in `version` for human review, but leave the
    // digest field null until a later registry producer verifies an immutable
    // RepoDigest.  Writing a tag into a digest field would be a hard schema
    // error rather than the intended environment-pending state.
    name, version, imageOrEndpointDigest: digestRef, source: composeInfo?.relative ?? 'docker-compose.yml',
    healthProbe, networkBoundary, requiredStage,
    status: itemErrors.length > 0 ? 'blocked' : (digestRef ? 'candidate' : 'environment-pending'),
    ...(digestRef ? {repoDigest: digestRef} : {})
  });
};

addImage('postgresql-pgvector', 'postgres', 'N0', 'pg_isready', 'private vio network / loopback published');
addImage('prometheus', 'prometheus', 'N7', '/-/ready', 'private monitoring network / loopback published');
addImage('grafana', 'grafana', 'N7', '/api/health', 'private monitoring network / loopback published');
addImage('alertmanager', 'alertmanager', 'N7', '/-/ready', 'private monitoring network');

// These components are intentionally explicit even when not present in the
// current Compose file; their missing runtime setup remains not-configured.
dependencies.push(
  {name: 'redis', version: null, imageOrEndpointDigest: null, source: 'v1.1 N0 dependency decision', healthProbe: 'redis PING', networkBoundary: 'private staging network', requiredStage: 'N0', status: 'not-configured'},
  {name: 'oidc-provider', version: null, imageOrEndpointDigest: null, source: 'v1.1 N0 dependency decision', healthProbe: 'OIDC discovery/JWKS', networkBoundary: 'private staging network', requiredStage: 'N3', status: 'not-configured'},
  {name: 'dashscope-chat-model', version: extractVersion(pom, 'dashscope-sdk-java'), imageOrEndpointDigest: null, source: pomInfo?.relative ?? 'pom.xml / Secret', healthProbe: 'model capability probe', networkBoundary: 'egress allowlist', requiredStage: 'N0', status: 'environment-pending'},
  {name: 'dashscope-embedding', version: process.env.VIO_RAG_EMBEDDING_MODEL && !looksSecret(process.env.VIO_RAG_EMBEDDING_MODEL) ? process.env.VIO_RAG_EMBEDDING_MODEL : 'dashscope-text-embedding-v3', imageOrEndpointDigest: null, source: 'application-prod.yml / Secret', healthProbe: 'embedding capability probe', networkBoundary: 'egress allowlist', requiredStage: 'N1', status: 'environment-pending'},
  {name: 'search-api', version: null, imageOrEndpointDigest: null, source: 'application-prod.yml / Secret', healthProbe: 'search capability probe', networkBoundary: 'domain allowlist', requiredStage: 'N0', status: 'environment-pending'},
  {name: 'spring-ai-mcp-provider', version: extractVersion(pom, 'spring-ai-starter-mcp-client') ?? 'Spring AI BOM 1.0.0', imageOrEndpointDigest: null, source: pomInfo?.relative ?? 'pom.xml', healthProbe: 'MCP initialize/tools-list smoke', networkBoundary: 'sandbox/private network', requiredStage: 'N3', status: 'environment-pending'},
  {name: 'backup-store', version: null, imageOrEndpointDigest: null, source: 'v1.1 N7 recovery decision', healthProbe: 'restore probe', networkBoundary: 'restricted storage', requiredStage: 'N7', status: 'not-configured'},
  {name: 'otel-collector', version: null, imageOrEndpointDigest: null, source: 'v1.1 N6 decision', healthProbe: 'OTLP health', networkBoundary: 'private monitoring network', requiredStage: 'N6a', status: 'not-configured'}
);

const structuralBlocked = errors.length > 0;
const pendingCount = dependencies.filter((item) => item.status !== 'ready').length;
const payload = {
  schemaVersion: 'v1.1', producer: 'export-dependency-inventory', generatedAt: generatedAt(), gitCommit: currentCommit(), owner: owner ?? 'unassigned',
  status: structuralBlocked ? 'blocked' : 'environment-pending', passed: false, producerCompleted: !structuralBlocked, exitCode: structuralBlocked ? 1 : 1,
  redacted: true,
  dependencies,
  sourceFiles: [composeInfo, pomInfo].filter(Boolean).map((info) => ({path: info.relative, sha256: sha256File(info.real), bytes: fs.statSync(info.real).size})),
  policy: '浮动 tag、缺少 immutable digest、缺现场探针或未配置依赖不得标记 ready；本 producer 不访问网络/registry。',
  errors, warnings
};

if (outputInfo && !destinationBlocked) {
  try {
    writeJson(outputInfo.absolute, payload, {noClobber});
    if (hashInfo) writeJson(hashInfo.absolute, {
      schemaVersion: 'v1.1', producer: 'export-dependency-inventory', generatedAt: payload.generatedAt, gitCommit: payload.gitCommit, owner: payload.owner,
      files: [{path: outputInfo.relative, sha256: sha256File(outputInfo.absolute), algorithm: 'SHA-256'}]
    }, {noClobber});
  } catch (error) {
    errors.push(`producer 输出失败: ${safeError(error)}`);
    payload.status = 'blocked'; payload.producerCompleted = false; payload.exitCode = 1; payload.errors = [...errors];
  }
}

const pendingAllowed = args['allow-pending'] === true;
const report = {
  schemaVersion: 'v1.1', producer: 'export-dependency-inventory', generatedAt: payload.generatedAt, gitCommit: payload.gitCommit, owner: payload.owner,
  output: outputInfo?.relative ?? '[invalid-path]', hashOutput: hashInfo?.relative ?? null, redacted: true,
  dependencyCount: dependencies.length, pendingCount, status: errors.length > 0 ? 'blocked' : 'environment-pending',
  passed: errors.length === 0 && pendingAllowed, exitCode: errors.length === 0 && pendingAllowed ? 0 : 1,
  errors, warnings
};
if (reportInfo && !(noClobber && fs.existsSync(reportInfo.absolute))) {
  try { writeJson(reportInfo.absolute, report, {noClobber}); } catch (error) { errors.push(`报告写入失败: ${safeError(error)}`); }
}
console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0 || errors.length > 0) process.exitCode = 1;

function input(value, label) {
  try { return repoPathInfo(value, {label, mustExist: true, kind: 'file'}); }
  catch { return null; }
}
function destination(value, label) {
  try { return repoOutputInfo(value, label); }
  catch (error) { errors.push(`${label} 路径无效: ${safeError(error)}`); destinationBlocked = true; return null; }
}
function scalar(value) {
  if (value === undefined || value === null || value === true || value === false) return null;
  const text = String(value).trim(); return text || null;
}
function extractVersion(text, artifactId) {
  const match = String(text ?? '').match(new RegExp(`<artifactId>\\s*${artifactId.replace(/[.*+?^${}()|[\\]\\]/g, '\\\\$&')}\\s*</artifactId>[\\s\\S]{0,300}?<version>\\s*([^<\\s]+)\\s*</version>`, 'i'));
  return match?.[1] ?? null;
}
function looksSecret(value) { return /(?:key|token|secret|password|replace|your[-_]|<)/i.test(String(value ?? '')); }
function samePath(left, right) {
  const a = path.resolve(left); const b = path.resolve(right);
  return process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b;
}
function safeError(error) { return String(error?.message ?? error).replace(/[\r\n]/g, ' ').slice(0, 500); }
