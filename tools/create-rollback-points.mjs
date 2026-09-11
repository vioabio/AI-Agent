#!/usr/bin/env node

/** Create explicit, non-secret rollback pointers for a candidate baseline. */
import fs from 'node:fs';
import {currentCommit, generatedAt, parseArgs, readJson, repoRelative, resolvePath, sha256File, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const baselinePath = args.baseline ?? 'artifacts/v1.1/baseline/manifest.draft.json';
const output = args.output ?? 'artifacts/v1.1/baseline/rollback-points.json';
const reportOutput = args.report;
const errors = [];
const warnings = [];
let baseline = null;
try { baseline = readJson(baselinePath, 'baseline draft'); } catch (error) { errors.push(error.message); }
if (baseline && baseline.schemaVersion !== 'v1.1') errors.push('baseline draft schemaVersion 必须为 v1.1');
if (baseline && baseline.releaseStatus !== 'BLOCKED') errors.push('rollback producer 只接受 releaseStatus=BLOCKED 的候选基线');

const previous = optionalJson(args['previous-manifest']);
const imageRefs = optionalJson(args['image-refs']);
const points = {
  schemaVersion: 'v1.1', producer: 'create-rollback-points', generatedAt: generatedAt(), gitCommit: currentCommit(),
  baseline: {path: safeRelative(baselinePath), sha256: hashIfExists(baselinePath), releaseStatus: baseline?.releaseStatus ?? 'unknown'},
  status: 'pending',
  image: {
    backend: pointer(imageRefs?.backend ?? baseline?.images?.backend, 'backend image digest'),
    frontend: pointer(imageRefs?.frontend ?? baseline?.images?.frontend, 'frontend image digest')
  },
  migration: {forward: 'Flyway migration version recorded in release manifest', rollback: 'restore previous immutable database backup / compatible down plan', status: 'pending'},
  data: pointer(baseline?.supportMatrix?.data_release_id ?? baseline?.dataReleaseId, 'data_release_id'),
  knowledge: pointer(baseline?.supportMatrix?.knowledge_release_id, 'knowledge_release_id'),
  index: pointer(baseline?.supportMatrix?.index_release_id, 'index_release_id'),
  config: {source: 'environment-contract + sealed deployment config', status: 'pending', valuesIncluded: false},
  previousManifest: previous ? {path: safeRelative(args['previous-manifest']), sha256: hashIfExists(args['previous-manifest']), releaseId: previous.releaseId ?? null, status: previous.status ?? null} : {status: 'pending', reason: '未提供已签名 GA previous manifest'},
  policy: {secretsIncluded: false, immutablePointersRequired: true, previousMustBeSignedGaProd: true}
};
if (!previous) warnings.push('没有已签名 GA/prod previous manifest，回滚不能执行');
if (!imageRefs) warnings.push('没有不可变镜像 digest，镜像回滚点保持 pending');
if (points.data.status === 'pending') warnings.push('data release pointer 尚未冻结');
if (points.knowledge.status === 'pending' || points.index.status === 'pending') warnings.push('knowledge/index release 尚未冻结');
if (errors.length === 0 && warnings.length === 0) points.status = 'ready-for-review';
const report = {schemaVersion: 'v1.1', producer: 'create-rollback-points', generatedAt: points.generatedAt, output, passed: errors.length === 0 && warnings.length === 0, status: points.status, errors, warnings};
writeJson(output, points, {noClobber: args['no-clobber'] === true});
if (reportOutput) writeJson(reportOutput, report, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify(report, null, 2));
if (errors.length > 0 || (warnings.length > 0 && args['allow-pending'] !== true)) process.exitCode = 1;

function pointer(value, label) {
  if (value && typeof value === 'object') {
    const digest = value.digest ?? value.sha256 ?? value.imageDigest;
    if (digest && /^sha256:[a-f0-9]{64}$/i.test(String(digest))) return {label, digest: String(digest), status: 'captured'};
  }
  if (typeof value === 'string' && value.trim()) return {label, releaseId: value.trim(), status: 'candidate-only'};
  return {label, status: 'pending'};
}
function optionalJson(file) { if (!file) return null; try { return readJson(file, file); } catch (error) { errors.push(error.message); return null; } }
function hashIfExists(file) { try { const p = resolvePath(file); return fs.existsSync(p) ? sha256File(p) : null; } catch { return null; } }
function safeRelative(file) { if (!file) return null; try { return repoRelative(resolvePath(file)); } catch { return null; } }
