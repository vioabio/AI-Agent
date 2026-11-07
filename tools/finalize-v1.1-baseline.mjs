#!/usr/bin/env node

/** Seal a BLOCKED v1.1 baseline only after every N0 input is present and valid. */
import fs from 'node:fs';
import path from 'node:path';
import {currentCommit, generatedAt, parseArgs, parseJsonBlock, readJson, repoRelative, resolvePath, sha256File, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const output = args.output ?? 'artifacts/v1.1/baseline/manifest.json';
const hashOutput = args['hash-report'] ?? 'artifacts/v1.1/baseline/baseline-file-hashes.json';
const errors = [];
const warnings = [];
const inputNames = {
  draft: args.draft,
  fixture: args.fixture,
  fixtureAudit: args['fixture-audit'],
  evaluationManifest: args['evaluation-manifest'],
  datasetManifest: args['dataset-manifest'],
  metricDictionary: args['metric-dictionary'],
  evaluationReview: args['evaluation-review'],
  environmentContract: args['environment-contract'],
  environmentContractHash: args['environment-contract-hash'],
  environmentValidation: args['environment-validation'],
  secretStore: args['secret-store'],
  secretStoreHash: args['secret-store-hash'],
  supportMatrix: args['support-matrix'],
  switches: args.switches,
  dependencyInventory: args['dependency-inventory']
};
const resolved = {};
for (const [name, value] of Object.entries(inputNames)) {
  if (!value) { errors.push(`缺少 ${name} 参数`); continue; }
  try {
    const absolute = resolvePath(value);
    if (!absolute || !fs.existsSync(absolute) || !fs.statSync(absolute).isFile()) throw new Error('不存在或不是文件');
    repoRelative(absolute);
    resolved[name] = absolute;
  } catch (error) { errors.push(`${name}: ${error.message}`); }
}
const draft = readSafe(resolved.draft, 'baseline draft');
if (draft?.schemaVersion !== 'v1.1') errors.push('draft schemaVersion 必须为 v1.1');
if (draft && draft.releaseStatus !== 'BLOCKED') errors.push('finalizer 只接受 releaseStatus=BLOCKED 的 draft');
if (resolved.output === resolved.draft) errors.push('final manifest 不得覆盖 draft');
for (const target of [output, hashOutput]) {
  if (target && !isInsideRepo(resolvePath(target))) errors.push(`输出路径越界: ${target}`);
  if (args['no-clobber'] === true && fs.existsSync(resolvePath(target))) errors.push(`输出已存在（--no-clobber）: ${target}`);
}

const fixture = readSafe(resolved.fixture, 'fixture manifest');
const fixtureAudit = readSafe(resolved.fixtureAudit, 'fixture source audit');
const evaluation = readSafe(resolved.evaluationManifest, 'evaluation manifest');
const dataset = readSafe(resolved.datasetManifest, 'evaluation dataset manifest');
const dictionary = readSafe(resolved.metricDictionary, 'metric dictionary');
const evaluationReview = readSafe(resolved.evaluationReview, 'evaluation ready review');
const environment = readSafe(resolved.environmentContract, 'environment contract');
const environmentValidation = readSafe(resolved.environmentValidation, 'environment validation');
const secretStore = readSafe(resolved.secretStore, 'secret store inventory');
const support = readSupport(resolved.supportMatrix);
const switches = readSafe(resolved.switches, 'switch snapshot');
const dependencies = readSafe(resolved.dependencyInventory, 'dependency inventory');

if (fixture?.schemaVersion !== 'v1.1' || fixture?.status === 'blocked') errors.push('fixture manifest 无效或已 blocked');
if (fixtureAudit?.schemaVersion !== 'v1.1') errors.push('fixture source audit 无效');
if (evaluation?.schemaVersion !== 'v1.1' || evaluation?.status !== 'ready') errors.push('evaluation manifest 必须是 v1.1 ready');
if (dataset?.schemaVersion !== 'v1.1') errors.push('dataset manifest schemaVersion 必须为 v1.1');
if (dictionary?.schemaVersion !== 'v1.1') errors.push('metric dictionary schemaVersion 必须为 v1.1');
if (evaluationReview?.passed !== true || evaluationReview?.status !== 'ready') errors.push('evaluation ready review 未通过');
if (environment?.schemaVersion !== 'v1.1') errors.push('environment contract 无效');
if (environmentValidation?.passed !== true) errors.push('environment contract 最终校验未通过');
if (secretStore?.schemaVersion !== 'v1.1') errors.push('secret store inventory 无效');
if (support?.schemaVersion !== 'v1.1' || !support?.game_release_id || !support?.battle_format_id || !support?.ruleset_version_id) errors.push('support matrix canonical 字段不完整');
if (switches?.schemaVersion !== 'v1.1') errors.push('switch snapshot 无效');
if (dependencies?.schemaVersion !== 'v1.1') errors.push('dependency inventory 无效');
if (dependencies?.status !== 'ready') warnings.push('依赖仍未完成现场 smoke；封存基线保持 BLOCKED');
if (fixture?.source?.reviewStatus !== 'approved') warnings.push('fixture 来源尚未批准；不得进入 active release');
if (secretStore?.status !== 'ready-for-review') warnings.push('Secret store 权限/历史扫描仍 pending');

const fileEntries = [];
for (const [name, file] of Object.entries(resolved)) {
  if (!file) continue;
  fileEntries.push({name, path: artifactRelative(file), sha256: hash(file), algorithm: 'SHA-256'});
}
fileEntries.sort((a, b) => a.name.localeCompare(b.name));
if (fileEntries.some((item) => !item.sha256 || !item.path)) errors.push('存在无法复算的 baseline 输入 hash');
const finalStatus = errors.length === 0 ? 'BLOCKED' : 'BLOCKED';
const hashReport = {schemaVersion: 'v1.1', producer: 'finalize-v1.1-baseline', generatedAt: generatedAt(), gitCommit: currentCommit(), status: errors.length === 0 ? 'sealed' : 'blocked', files: fileEntries, excluded: ['manifest.json', 'baseline-file-hashes.json'], errors, warnings};
const finalManifest = draft ? {
  ...draft,
  generatedAt: generatedAt(), finalizedAt: generatedAt(), gitCommit: currentCommit(), releaseStatus: finalStatus,
  baselineFileHashes: {path: artifactRelative(hashOutput), sha256: null, algorithm: 'SHA-256', status: 'sealed'},
  baselineInputs: {
    fixture: ref('fixture'), fixtureAudit: ref('fixtureAudit'), evaluation: ref('evaluationManifest'), dataset: ref('datasetManifest'), metricDictionary: ref('metricDictionary'), evaluationReview: ref('evaluationReview'), environmentContract: ref('environmentContract'), environmentValidation: ref('environmentValidation'), secretStore: ref('secretStore'), supportMatrix: ref('supportMatrix'), switches: ref('switches'), dependencyInventory: ref('dependencyInventory')
  },
  finalizer: {producer: 'finalize-v1.1-baseline', status: errors.length === 0 ? 'sealed' : 'blocked', errors, warnings}
} : null;

if (errors.length === 0) {
  writeJson(hashOutput, hashReport, {noClobber: args['no-clobber'] === true});
  finalManifest.baselineFileHashes.sha256 = sha256File(resolvePath(hashOutput));
  writeJson(output, finalManifest, {noClobber: args['no-clobber'] === true});
}
const report = {schemaVersion: 'v1.1', producer: 'finalize-v1.1-baseline', generatedAt: generatedAt(), output, hashReport: hashOutput, passed: errors.length === 0, status: finalStatus, errors, warnings};
console.log(JSON.stringify(report, null, 2));
if (errors.length > 0) process.exitCode = 1;

function readSafe(file, label) { if (!file) return null; try { return readJson(file, label); } catch (error) { errors.push(`${label}: ${error.message}`); return null; } }
function readSupport(file) { if (!file) return null; try { return parseJsonBlock(fs.readFileSync(file, 'utf8')); } catch (error) { errors.push(`support matrix: ${error.message}`); return null; } }
function hash(file) { try { return sha256File(file); } catch { return null; } }
function artifactRelative(file) { try { const value = repoRelative(file); return value.startsWith('artifacts/v1.1/') ? value.slice('artifacts/v1.1/'.length) : value; } catch { return null; } }
function isInsideRepo(file) { try { repoRelative(file); return true; } catch { return false; } }
function ref(name) { const file = resolved[name]; return {path: artifactRelative(file), sha256: hash(file), algorithm: 'SHA-256', status: file ? 'captured' : 'pending'}; }
