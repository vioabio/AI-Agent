#!/usr/bin/env node

/**
 * Dependency-free OpenAPI breaking-change check for the v1.1 contract.
 * It intentionally compares two local, reviewed JSON snapshots; fetching a
 * running server is a separate step so an unavailable server cannot make a
 * release appear green.
 */
import fs from 'node:fs';
import {generatedAt, parseArgs, readJson, repoRelative, resolvePath, sha256File, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
// `--base` is the spelling used by the v1.1 handbook; retain `--baseline`
// for existing CI callers.  The explicit alias is important because silently
// falling back to the repository baseline would compare the wrong contract.
const baselinePath = args.baseline ?? args.base ?? 'contracts/openapi-v1.0.json';
// The checked-in v1.1 snapshot is the local candidate of record.  A runtime
// SpringDoc export may still be supplied explicitly with --candidate; keeping
// a deterministic repository default lets CI and offline reviewers run the
// gate without starting the application or reading local secrets.
const candidatePath = args.candidate ?? 'contracts/openapi-v1.1.json';
const reportPath = args.report ?? 'artifacts/v1.1/test/openapi-diff.json';
const breaking = [];
const additions = [];
const warnings = [];
let baseline = null;
let candidate = null;

try { baseline = readJson(baselinePath, 'OpenAPI baseline'); } catch (error) { breaking.push(error.message); }
try { candidate = readJson(candidatePath, 'OpenAPI candidate'); } catch (error) { breaking.push(error.message); }
if (baseline) validateDocument(baseline, 'baseline');
if (candidate) validateDocument(candidate, 'candidate');
if (baseline && candidate) compareDocuments(baseline, candidate);

const allowBreaking = args['allow-breaking'] === true || String(args['allow-breaking'] ?? '').toLowerCase() === 'true';
// Breaking changes are rejected by default (the historical behavior).  A
// caller may explicitly opt out with --no-fail-on-breaking for a diagnostic
// comparison, but the report remains visibly non-blocking.
const failOnBreaking = args['fail-on-breaking'] !== false;
const passed = breaking.length === 0 || allowBreaking || !failOnBreaking;
if (allowBreaking && breaking.length > 0) warnings.push('breaking change 已由显式 --allow-breaking 放行，必须在变更评审中登记');
if (!failOnBreaking && breaking.length > 0) warnings.push('breaking change 仅作诊断，--no-fail-on-breaking 已显式放行');
const report = {
  schemaVersion: 'v1.1',
  producer: 'openapi-diff',
  generatedAt: generatedAt(),
  baseline: sourceInfo(baselinePath),
  candidate: sourceInfo(candidatePath),
  passed,
  status: passed ? 'passed' : 'blocked',
  allowBreaking,
  failOnBreaking,
  breaking,
  additions,
  warnings,
  redacted: true
};
try {
  repoRelative(resolvePath(reportPath));
  writeJson(reportPath, report, {noClobber: args['no-clobber'] === true});
} catch (error) {
  report.passed = false;
  report.status = 'blocked';
  report.breaking.push(`报告写入失败: ${error.message}`);
  process.exitCode = 1;
}
console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;

function validateDocument(document, label) {
  if (!document || typeof document !== 'object' || Array.isArray(document)) {
    breaking.push(`${label}: OpenAPI 文档必须是对象`);
    return;
  }
  if (!/^3\.\d+\.\d+$/.test(String(document.openapi ?? ''))) breaking.push(`${label}: 仅支持 OpenAPI 3.x`);
  if (!document.info || typeof document.info !== 'object') breaking.push(`${label}: 缺少 info`);
  if (!document.paths || typeof document.paths !== 'object') breaking.push(`${label}: 缺少 paths`);
}

function compareDocuments(oldDoc, newDoc) {
  const oldPaths = oldDoc.paths ?? {};
  const newPaths = newDoc.paths ?? {};
  for (const [pathName, oldItem] of Object.entries(oldPaths)) {
    if (!(pathName in newPaths)) {
      breaking.push(`删除路径: ${pathName}`);
      continue;
    }
    for (const [method, oldOperation] of operations(oldItem)) {
      const newOperation = newPaths[pathName]?.[method];
      if (!newOperation) {
        breaking.push(`删除操作: ${method.toUpperCase()} ${pathName}`);
        continue;
      }
      compareOperation(pathName, method, oldOperation, newOperation);
    }
  }
  for (const [pathName, item] of Object.entries(newPaths)) {
    if (!(pathName in oldPaths)) {
      additions.push(`新增路径: ${pathName}`);
      continue;
    }
    for (const method of Object.keys(item ?? {})) {
      if (isMethod(method) && !oldPaths[pathName]?.[method]) additions.push(`新增操作: ${method.toUpperCase()} ${pathName}`);
    }
  }
  compareSchemas(oldDoc.components?.schemas ?? {}, newDoc.components?.schemas ?? {});
}

function compareOperation(pathName, method, oldOp, newOp) {
  const prefix = `${method.toUpperCase()} ${pathName}`;
  const oldParams = new Map((oldOp.parameters ?? []).map((item) => [`${item.in}:${item.name}`, item]));
  const newParams = new Map((newOp.parameters ?? []).map((item) => [`${item.in}:${item.name}`, item]));
  for (const [key, oldParam] of oldParams) {
    const next = newParams.get(key);
    if (!next) {
      if (oldParam.required) breaking.push(`${prefix}: 删除必需参数 ${key}`);
      else additions.push(`${prefix}: 删除可选参数 ${key}`);
      continue;
    }
    if (!oldParam.required && next.required) breaking.push(`${prefix}: 可选参数变为必需 ${key}`);
    if (typeFingerprint(oldParam.schema) !== typeFingerprint(next.schema)) breaking.push(`${prefix}: 参数类型改变 ${key}`);
  }
  if (oldOp.requestBody?.required && !newOp.requestBody) breaking.push(`${prefix}: 删除必需 requestBody`);
  if (oldOp.requestBody?.required && newOp.requestBody?.required !== true) breaking.push(`${prefix}: requestBody 不再必需`);
  const oldResponses = oldOp.responses ?? {};
  const newResponses = newOp.responses ?? {};
  for (const [code, oldResponse] of Object.entries(oldResponses)) {
    if (!(code in newResponses)) {
      if (code.startsWith('2') || code === 'default') breaking.push(`${prefix}: 删除响应 ${code}`);
      continue;
    }
    const oldSchema = responseSchema(oldResponse);
    const newSchema = responseSchema(newResponses[code]);
    if (oldSchema && newSchema) compareSchemaShape(`${prefix} response ${code}`, oldSchema, newSchema);
  }
}

function compareSchemas(oldSchemas, newSchemas) {
  for (const [name, oldSchema] of Object.entries(oldSchemas)) {
    const next = newSchemas[name];
    if (!next) {
      breaking.push(`删除 schema: ${name}`);
      continue;
    }
    compareSchemaShape(`schema ${name}`, oldSchema, next);
  }
}

function compareSchemaShape(label, oldSchema, newSchema) {
  if (typeFingerprint(oldSchema) !== typeFingerprint(newSchema)
      && oldSchema.type && newSchema.type && oldSchema.type !== newSchema.type) {
    breaking.push(`${label}: type 从 ${oldSchema.type} 变为 ${newSchema.type}`);
  }
  const oldRequired = new Set(oldSchema.required ?? []);
  const newRequired = new Set(newSchema.required ?? []);
  for (const name of oldRequired) if (!newRequired.has(name)) breaking.push(`${label}: 属性不再 required: ${name}`);
  for (const name of newRequired) {
    if (oldRequired.has(name)) continue;
    if (Object.prototype.hasOwnProperty.call(oldSchema.properties ?? {}, name)) {
      breaking.push(`${label}: 可选属性变为 required: ${name}`);
    } else {
      breaking.push(`${label}: 新增 required 属性: ${name}`);
    }
  }
  for (const [name, oldProperty] of Object.entries(oldSchema.properties ?? {})) {
    const next = newSchema.properties?.[name];
    if (!next) {
      if (oldRequired.has(name)) breaking.push(`${label}: 删除 required 属性: ${name}`);
      continue;
    }
    if (typeFingerprint(oldProperty) !== typeFingerprint(next)) breaking.push(`${label}: 属性类型改变: ${name}`);
    if (Array.isArray(oldProperty.enum) && Array.isArray(next.enum)) {
      for (const value of oldProperty.enum) if (!next.enum.includes(value)) breaking.push(`${label}: 删除 enum 值 ${name}=${value}`);
    }
  }
}

function responseSchema(response) {
  return response?.content?.['application/json']?.schema ?? response?.content?.['text/event-stream']?.schema ?? response?.schema ?? null;
}

function operations(pathItem) {
  return Object.entries(pathItem ?? {}).filter(([method]) => isMethod(method));
}

function isMethod(value) { return ['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'].includes(value); }

function typeFingerprint(schema) {
  if (!schema || typeof schema !== 'object') return '';
  if (schema.$ref) return `ref:${schema.$ref}`;
  return JSON.stringify({type: schema.type, format: schema.format, ref: schema.$ref, items: schema.items?.type ?? schema.items?.$ref ?? null});
}

function sourceInfo(value) {
  const source = String(value).replaceAll('\\', '/');
  let hash = null;
  try { hash = sha256File(resolvePath(value)); } catch { /* included as null in blocked report */ }
  return {path: source, sha256: hash};
}
