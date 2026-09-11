#!/usr/bin/env node

/** Export a checked, hash-only N3 evidence index for the N6a evaluator. */
import {
  generatedAt,
  parseArgs,
  readJson,
  repoRelative,
  resolvePath,
  sha256File,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const manifestPath = args.manifest;
const output = args.output;
const runId = String(args['run-id'] ?? '').trim();
const errors = [];
if (!manifestPath) errors.push('缺少 --manifest');
if (!output) errors.push('缺少 --output');
if (!/^[A-Za-z0-9._-]{3,128}$/.test(runId)) errors.push('--run-id 缺失或格式无效');
let manifest = null;
try { if (manifestPath) manifest = readJson(manifestPath, 'N3 evidence manifest'); }
catch (error) { errors.push(error.message); }
if (manifest) {
  if (manifest.schemaVersion !== 'v1.1' || manifest.status !== 'ready') errors.push('manifest 必须是 v1.1 ready');
  if (manifest.runId !== runId) errors.push('manifest runId 与参数不一致');
  if (!Array.isArray(manifest.files) || manifest.files.length === 0) errors.push('manifest.files 不能为空');
  for (const [index, item] of (manifest.files ?? []).entries()) {
    if (!item?.path || !/^[a-f0-9]{64}$/i.test(String(item.sha256 ?? ''))) errors.push(`files[${index + 1}] 缺 path/sha256`);
    if (item?.runId !== runId) errors.push(`files[${index + 1}] runId 不一致`);
  }
}
const result = {
  schemaVersion: 'v1.1', producer: 'export-n3-evidence-index', generatedAt: generatedAt(),
  runId: runId || null, sourceManifest: manifestPath ? String(manifestPath).replaceAll('\\', '/') : null,
  manifestSha256: manifestPath ? safeHash(manifestPath) : null,
  root: manifest?.root ?? null,
  files: (manifest?.files ?? []).map((item) => ({path: item.path, sha256: item.sha256, runId: item.runId, schemaVersion: item.schemaVersion, producer: item.producer})),
  passed: errors.length === 0, status: errors.length === 0 ? 'ready' : 'blocked', errors, redacted: true
};
try { if (output) writeJson(output, result, {noClobber: args['no-clobber'] === true}); }
catch (error) { result.passed = false; result.status = 'blocked'; result.errors.push(`输出失败: ${error.message}`); process.exitCode = 1; }
if (!result.passed) process.exitCode = 1;
console.log(JSON.stringify(result, null, 2));

function safeHash(value) {
  try { return sha256File(resolvePath(value)); } catch { return null; }
}
