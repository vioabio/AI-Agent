#!/usr/bin/env node

/**
 * Produce per-dependency scan placeholders without executing a scanner.
 *
 * The command is intentionally useful on a developer machine: it checks that
 * inventory entries are exact image digests and emits machine-readable
 * `environment-pending` records explaining that Trivy/vendor evidence is
 * still required.  It never treats an empty finding list as a clean scan.
 */
import fs from 'node:fs';
import path from 'node:path';
import {inputFile, outputFile, parseImageReference, sourceInfo} from './lib/supply-chain.mjs';
import {currentCommit, generatedAt, parseArgs, readJson, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const knownOptions = new Set(['inventory', 'output-dir', 'report', 'scanner', 'allow-pending', 'no-clobber']);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);
const inventoryInfo = loadInfo(args.inventory, 'dependency inventory', errors);
const outputDirRaw = scalar(args['output-dir']) ?? 'artifacts/v1.1/release';
let reportInfo = null;
try { reportInfo = outputFile(args.report ?? path.join(outputDirRaw, 'dependency-image-scan.json'), 'dependency scan report'); }
catch (error) { errors.push(`报告路径无效: ${safeText(error.message)}`); }
const inventory = readDocument(inventoryInfo, errors);
if (!inventory || typeof inventory !== 'object' || Array.isArray(inventory)) errors.push('dependency inventory 必须是 JSON 对象');
if (inventory && inventory.schemaVersion !== 'v1.1') errors.push('dependency inventory schemaVersion 必须为 v1.1');
const dependencies = Array.isArray(inventory?.dependencies) ? inventory.dependencies : [];
if (dependencies.length === 0) errors.push('dependency inventory dependencies 不能为空');
const outputItems = [];
const seenNames = new Set();
for (const item of dependencies) {
  const name = scalar(item?.name);
  if (!name || seenNames.has(name)) { errors.push(`依赖名称缺失或重复: ${safeText(name)}`); continue; }
  seenNames.add(name);
  const digest = scalar(item?.repoDigest ?? item?.imageOrEndpointDigest);
  const parsed = parseImageReference(digest ?? '');
  const isImage = Boolean(digest && (digest.includes('@') || item?.repoDigest));
  const itemErrors = [];
  if (isImage && (!parsed.valid || !parsed.immutable || !parsed.digest)) itemErrors.push('镜像必须使用精确 @sha256 digest');
  const fileName = `dependency-${safeFileName(name)}-scan.json`;
  let itemInfo = null;
  try { itemInfo = outputFile(path.join(outputDirRaw, fileName), `${name} scan output`); }
  catch (error) { itemErrors.push(`输出路径无效: ${safeText(error.message)}`); }
  if (itemInfo && inventoryInfo && samePath(itemInfo.absolute, inventoryInfo.absolute)) itemErrors.push('scan 输出不能覆盖 inventory');
  const status = itemErrors.length > 0 ? 'blocked' : 'environment-pending';
  const itemPayload = {
    schemaVersion: 'v1.1', producer: 'scan-dependency-images', generatedAt: generatedAt(), gitCommit: currentCommit(),
    name, status, passed: false, exitCode: 1, redacted: true,
    image: isImage && parsed.valid ? {repoDigest: digest, digest: parsed.digest.toLowerCase()} : {repoDigest: digest ?? null, digest: null},
    scanner: scalar(args.scanner) ?? 'trivy-or-vendor-adapter', scannerExecuted: false, scanComplete: false,
    findings: [], errors: itemErrors,
    policy: '此记录仅声明扫描尚未执行；findings=[] 不代表无漏洞。请在受控 staging 运行 Trivy/供应商扫描后替换为带 digest 的原始报告。'
  };
  if (itemInfo) {
    try { writeJson(itemInfo.absolute, itemPayload, {noClobber: boolArg(args['no-clobber'])}); }
    catch (error) { errors.push(`${name} scan 输出失败: ${safeText(error.message)}`); }
  }
  outputItems.push({name, status, path: itemInfo ? safePath(itemInfo.relative) : null, imageDigest: parsed?.digest ?? null, scannerExecuted: false, errors: itemErrors});
  if (status === 'environment-pending') warnings.push(`${name}: scanner 未执行，保持 environment-pending`);
}
const status = errors.length > 0 ? 'blocked' : 'environment-pending';
const report = {
  schemaVersion: 'v1.1', producer: 'scan-dependency-images', generatedAt: generatedAt(), gitCommit: currentCommit(),
  status, passed: false, producerCompleted: errors.length === 0, exitCode: errors.length === 0 && boolArg(args['allow-pending']) ? 0 : 1,
  redacted: true, inventory: inventoryInfo ? sourceInfo(inventoryInfo) : null, scanner: scalar(args.scanner) ?? 'trivy-or-vendor-adapter',
  scannerExecuted: false, scanComplete: false, items: outputItems, errors, warnings,
  policy: '不调用 Docker/registry/Trivy；缺少真实依赖扫描时 gate 必须保持 pending/blocked。'
};
if (reportInfo) {
  try { writeJson(reportInfo.absolute, report, {noClobber: boolArg(args['no-clobber'])}); }
  catch (error) { report.errors.push(`报告写入失败: ${safeText(error.message)}`); report.exitCode = 1; }
}
console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0) process.exitCode = 1;

function readDocument(info, targetErrors) { if (!info) return null; try { return readJson(info.real, 'dependency inventory'); } catch (error) { targetErrors.push(`dependency inventory JSON 无法解析`); return null; } }
function loadInfo(value, label, targetErrors) { const text = scalar(value); if (!text) { targetErrors.push(`${label} 必须显式提供`); return null; } try { return inputFile(text, label); } catch (error) { targetErrors.push(`${label}: ${safeText(error.message)}`); return null; } }
function safeFileName(value) { const text = String(value ?? '').replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, ''); return text || 'dependency'; }
function scalar(value) { return value === undefined || value === true || value === false ? null : String(value).trim() || null; }
function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function safePath(value) { return String(value ?? '').replaceAll('\\', '/').replace(/[\r\n]/g, ''); }
function safeText(value) { return String(value ?? '').replace(/[\r\n]/g, ' ').slice(0, 500); }
function samePath(a, b) { return path.resolve(a).toLowerCase() === path.resolve(b).toLowerCase(); }
