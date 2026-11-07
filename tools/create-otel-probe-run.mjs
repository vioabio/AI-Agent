#!/usr/bin/env node

/**
 * Create one explicitly versioned probe Run for collecting an OTel sample.
 *
 * This producer performs one HTTP request only when a caller supplies a base
 * URL.  It never prints response bodies or credentials.  Network failures and
 * incomplete Run responses are represented as blocked evidence, not as a
 * synthetic trace/run id.
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {generatedAt, parseArgs, readJson, repoRelative, resolvePath, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const baseArg = scalar(args['base-url']);
const evaluationRunId = scalar(args['evaluation-run-id']);
const outputArg = scalar(args.output ?? args.report);
const endpoint = scalar(args.endpoint) ?? '/chat/runs';
const environment = scalar(args.environment) ?? 'staging';
const timeoutMs = boundedNumber(args['timeout-ms'], 15000, 1000, 120000);
const errors = [];
let baseUrl = null;
let requestPayload = null;

if (!baseArg) errors.push('缺少 --base-url');
if (!evaluationRunId || !/^[A-Za-z0-9._-]{3,128}$/.test(evaluationRunId)) errors.push('evaluation-run-id 缺失或格式无效');
if (!outputArg) errors.push('缺少 --output');
if (!['local', 'staging', 'prod'].includes(environment)) errors.push('environment 只允许 local/staging/prod');
if (baseArg) {
  try {
    const parsed = new URL(baseArg);
    if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error('只允许 http/https');
    if (environment === 'prod' && /^(localhost|127\.0\.0\.1|\[?::1\]?)$/i.test(parsed.hostname)) throw new Error('prod 禁止 localhost');
    if (environment === 'prod' && /staging|test/i.test(parsed.hostname)) throw new Error('prod 禁止 staging/test 地址');
    baseUrl = parsed;
  } catch (error) { errors.push(`base-url 无效: ${safe(error.message)}`); }
}
if (outputArg) {
  try { repoRelative(resolvePath(outputArg)); } catch (error) { errors.push(`output 无效: ${safe(error.message)}`); }
}

if (args['request-file']) {
  try {
    requestPayload = readJson(args['request-file'], 'probe request');
    if (!requestPayload || typeof requestPayload !== 'object' || Array.isArray(requestPayload)) throw new Error('必须是 JSON 对象');
  } catch (error) { errors.push(`request-file 读取失败: ${safe(error.message)}`); }
}
if (!requestPayload) {
  requestPayload = {
    prompt: 'v1.1 OTel probe: return a concise versioned fact answer.',
    gameReleaseId: scalar(args['game-release-id']) ?? 'pokemon-scarlet-violet',
    battleFormat: scalar(args['battle-format']) ?? 'casual-singles',
    rulesetVersionId: scalar(args['ruleset-version-id']) ?? 'sv-v1',
    language: scalar(args.language) ?? 'zh-CN',
    dataReleaseId: scalar(args['data-release-id']) ?? 'data-v1',
    knowledgeReleaseId: scalar(args['knowledge-release-id']) ?? 'knowledge-v1',
    indexReleaseId: scalar(args['index-release-id']) ?? 'index-v1',
    contractVersion: 'v1.1'
  };
}

const traceId = randomHex(32);
const idempotencyKey = `otel-${evaluationRunId}-${randomHex(12)}`;
const result = {
  schemaVersion: 'v1.1',
  producer: 'create-otel-probe-run',
  generatedAt: generatedAt(),
  evaluationRunId: evaluationRunId || null,
  environment,
  endpoint: baseUrl ? `${baseUrl.origin}${normalizeApiPath(baseUrl.pathname, endpoint)}` : null,
  runId: null,
  traceId,
  status: 'environment-pending',
  passed: false,
  httpStatus: null,
  latencyMs: null,
  errors: [...new Set(errors)],
  redacted: true
};

if (result.errors.length === 0) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const started = Date.now();
  try {
    const apiPath = normalizeApiPath(baseUrl.pathname, endpoint);
    const url = new URL(apiPath, baseUrl.origin);
    const token = process.env.VIO_API_KEY;
    const headers = {'content-type': 'application/json', accept: 'application/json', 'x-trace-id': traceId,
      'x-evaluation-run-id': evaluationRunId, 'idempotency-key': idempotencyKey};
    if (token && token.trim()) headers['x-api-key'] = token.trim();
    const response = await fetch(url, {method: 'POST', headers, body: JSON.stringify(requestPayload), signal: controller.signal});
    result.httpStatus = response.status;
    result.latencyMs = Date.now() - started;
    let body = null;
    try { body = await response.json(); } catch { /* body intentionally not surfaced */ }
    const runId = body && typeof body.runId === 'string' ? body.runId.trim() : '';
    const responseTrace = body && typeof body.traceId === 'string' ? body.traceId.trim() : '';
    if (!response.ok) result.errors.push(`HTTP ${response.status}：Run 创建失败（响应内容已脱敏）`);
    else if (!runId) result.errors.push('响应缺少 runId，拒绝生成伪造证据');
    else if (responseTrace && responseTrace !== traceId) result.errors.push('响应 traceId 与请求不一致');
    else {
      result.runId = runId;
      if (responseTrace) result.traceId = responseTrace;
      result.status = 'ready';
      result.passed = true;
    }
  } catch (error) {
    result.latencyMs = Date.now() - started;
    result.errors.push(error?.name === 'AbortError' ? 'Run 创建超时' : 'Run 创建失败（网络/响应已脱敏）');
  } finally {
    clearTimeout(timer);
  }
}
if (result.errors.length > 0) {
  result.status = result.status === 'environment-pending' ? 'environment-pending' : 'blocked';
  result.passed = false;
}
if (outputArg) {
  try { writeJson(outputArg, result, {noClobber: args['no-clobber'] === true}); }
  catch (error) { result.errors.push(`输出失败: ${safe(error.message)}`); result.status = 'blocked'; result.passed = false; }
}
console.log(JSON.stringify(result, null, 2));
if (!result.passed) process.exitCode = 1;

function scalar(value) {
  if (Array.isArray(value)) return value.length === 1 ? String(value[0]).trim() : null;
  if (value === undefined || value === null || value === true || value === false) return null;
  return String(value).trim();
}
function boundedNumber(value, fallback, min, max) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.min(max, Math.max(min, Math.floor(parsed))) : fallback;
}
function randomHex(length) { return crypto.randomBytes(Math.ceil(length / 2)).toString('hex').slice(0, length); }
function normalizeApiPath(basePath, endpointPath) {
  const base = String(basePath || '/').replace(/\/+$/, '');
  const endpointValue = String(endpointPath || '/chat/runs').startsWith('/') ? String(endpointPath || '/chat/runs') : `/${endpointPath}`;
  if (base === '/api' || base.endsWith('/api')) return `${base}${endpointValue}`;
  return `${base}/api${endpointValue}`.replace(/\/\/+/g, '/');
}
function safe(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 240); }
