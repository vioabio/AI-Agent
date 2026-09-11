#!/usr/bin/env node

/**
 * v1.1 N8 release gate.
 *
 * The gate accepts only an explicit, BLOCKED preflight manifest and explicit
 * evidence paths.  It never contacts production, Docker, a registry, or a
 * secret store.  On success it atomically creates a *new* GA/DEMO_ONLY
 * manifest; on failure it leaves the input untouched and writes only a
 * redacted report.  Missing evidence is a blocking condition, not a pass.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  collectEvidenceEntries,
  isImageDigest,
  isPlaceholder,
  isSha256,
  normalizeHash,
  normalizeSlashes,
  readJsonFile,
  readJsonLines,
  resolveRepoFile,
  resolveRoot,
  safeText,
  scanSecretLikeText,
  sha256File,
  validateManifestStructure,
  writeAtomicJson
} from './lib/release-manifest.mjs';
import {parseArgs} from './lib/io.mjs';
import {analyzeObservationRows} from './lib/observation-contract.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const warnings = [];
const checks = [];
const noClobber = args['no-clobber'] === true;
const preflightOnly = boolArg(args['preflight-only']);
const knownOptions = new Set([
  'repo-root', 'baseline-manifest', 'manifest', 'release-class', 'environment',
  'require-release-class', 'require-environment', 'readiness', 'observability-dir',
  'business-probes', 'observation', 'observation-validation', 'feedback',
  'feedback-registry', 'feedback-lineage-validation', 'feedback-hashes',
  'rollback', 'rpo-report', 'rto-report', 'previous-manifest', 'previous-resolution',
  'active-release', 'config-snapshot', 'compose-config', 'min-observation-hours',
  'min-observation-requests', 'min-requests-per-window', 'allow-unmeasured-rpo-rto',
  'output-manifest', 'promote-status', 'report', 'preflight-only', 'no-clobber'
]);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

let root = null;
let manifestInfo = null;
let manifest = null;
try { root = resolveRoot(args['repo-root'] ?? '.'); }
catch (error) { errors.push(error.message); }

if (!args.manifest || args.manifest === true) errors.push('--manifest 必须显式提供 preflight manifest');
else if (root) {
  try {
    manifestInfo = resolveRepoFile(root, args.manifest, 'preflight manifest');
    manifest = readJsonFile(manifestInfo.real, 'preflight manifest');
  } catch (error) { errors.push(error.message); }
}

const releaseClass = scalar(args['release-class']) ?? scalar(args['require-release-class']);
const environment = scalar(args.environment) ?? scalar(args['require-environment']);
const promoteStatus = scalar(args['promote-status']) ?? (releaseClass === 'GA' ? 'GA' : releaseClass === 'DEMO_ONLY' ? 'DEMO_ONLY' : null);
if (!['GA', 'DEMO_ONLY'].includes(releaseClass)) errors.push('--release-class 必须是 GA 或 DEMO_ONLY');
if (!['prod', 'staging'].includes(environment)) errors.push('--environment 必须是 prod 或 staging');
if (!['GA', 'DEMO_ONLY'].includes(promoteStatus)) errors.push('--promote-status 必须是 GA 或 DEMO_ONLY');
if (promoteStatus === 'GA' && releaseClass !== 'GA') errors.push('只有 GA releaseClass 可以 promote-status=GA');
if (promoteStatus === 'DEMO_ONLY' && releaseClass !== 'DEMO_ONLY') errors.push('只有 DEMO_ONLY releaseClass 可以 promote-status=DEMO_ONLY');
if (boolArg(args['allow-unmeasured-rpo-rto']) && releaseClass !== 'DEMO_ONLY') errors.push('--allow-unmeasured-rpo-rto 仅允许 DEMO_ONLY');

if (manifest && root) {
  validateBaseline(root);
  const validation = validateManifestStructure(manifest, {
    repoRoot: root,
    manifestPath: manifestInfo.absolute,
    // A final gate still consumes a BLOCKED preflight.  Allow the preflight
    // to carry staging/absent evidence at the structural layer; the explicit
    // final-evidence checks below enforce the prod/GA requirements.
    preflightOnly: true,
    requireReleaseClass: releaseClass,
    requireEnvironment: environment,
    allowStatuses: ['BLOCKED'],
    requireStatus: 'BLOCKED',
    verifyEvidence: true
  });
  errors.push(...validation.errors);
  warnings.push(...validation.warnings);
  checks.push(...validation.checks);
  if (manifest.status !== 'BLOCKED') errors.push('gate 输入必须是 status=BLOCKED 的 preflight，禁止从 GA/DEMO_ONLY 原地升级');
  if (preflightOnly) {
    // Preflight mode checks only the immutable structure and never emits a
    // promoted manifest.  This is useful in CI before staging evidence exists.
    checks.push({name: 'preflight-only', passed: true});
  } else {
    runEvidenceChecks(manifest, root);
  }
  const secretFindings = scanSecretLikeText(JSON.stringify(manifest));
  if (secretFindings.length > 0) secretFindings.forEach((finding) => errors.push(`manifest secret surface: ${finding.reasonCode} (${finding.key})`));
}

if (root && args.report && args['output-manifest']) {
  try {
    if (samePath(resolveOutput(root, args.report, 'gate report').absolute, resolveOutput(root, args['output-manifest'], 'output manifest').absolute)) {
      errors.push('gate report 与 output manifest 不能使用同一路径');
    }
  } catch (error) { errors.push(error.message); }
}

const report = {
  schemaVersion: 'v1.1',
  producer: 'v1.1-release-gate',
  generatedAt: new Date().toISOString(),
  repoRoot: root ? '.' : null,
  inputManifest: manifestInfo && root ? relative(manifestInfo.absolute, root) : safeText(args.manifest),
  releaseClass,
  environment,
  promoteStatus,
  mode: preflightOnly ? 'preflight' : 'final',
  passed: errors.length === 0,
  status: errors.length === 0 ? 'passed' : 'blocked',
  exitCode: errors.length === 0 ? 0 : 1,
  redacted: true,
  checks,
  errors: unique(errors),
  warnings: unique(warnings),
  evidenceHashes: collectExplicitHashes(root),
  outputManifest: null
};

if (report.passed && !preflightOnly && root && manifest && args['output-manifest']) {
  try {
    const outputInfo = resolveOutput(root, args['output-manifest'], 'output manifest');
    if (manifestInfo && samePath(outputInfo.absolute, manifestInfo.absolute)) throw new Error('output manifest 不能覆盖 BLOCKED 输入');
    if (fs.existsSync(outputInfo.absolute) && noClobber) throw new Error('output manifest 已存在（--no-clobber）');
    const promoted = buildPromotedManifest(manifest, promoteStatus, environment, root);
    writeAtomicJson(outputInfo.absolute, promoted, {noClobber});
    report.outputManifest = relative(outputInfo.absolute, root);
  } catch (error) {
    report.errors.push(`output manifest 写入失败: ${safeText(error.message)}`);
    report.passed = false; report.status = 'blocked'; report.exitCode = 1;
  }
} else if (report.passed && !preflightOnly && !args['output-manifest']) {
  report.errors.push('final gate 必须显式提供 --output-manifest，禁止只打印“通过”');
  report.passed = false; report.status = 'blocked'; report.exitCode = 1;
}

// Write the report only after the output manifest decision so the report is a
// complete, append-only record.  A failed report write itself is a gate fail.
const reportPath = args.report;
if (reportPath && root) {
  try {
    const reportInfo = resolveOutput(root, reportPath, 'gate report');
    if (manifestInfo && samePath(reportInfo.absolute, manifestInfo.absolute)) throw new Error('gate report 不能覆盖 manifest 输入');
    writeAtomicJson(reportInfo.absolute, report, {noClobber});
  } catch (error) {
    report.errors.push(`gate report 写入失败: ${safeText(error.message)}`);
    report.passed = false; report.status = 'blocked'; report.exitCode = 1;
  }
}

console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;

function runEvidenceChecks(value, repoRoot) {
  validateRequiredManifestEvidence(value);
  const expectedHours = numberArg(args['min-observation-hours'], releaseClass === 'GA' ? 72 : 24);
  const expectedRequests = numberArg(args['min-observation-requests'], releaseClass === 'GA' ? 500 : 100);
  const expectedPerWindow = numberArg(args['min-requests-per-window'], 20);
  const required = [
    ['readiness', args.readiness], ['business-probes', args['business-probes']],
    ['observation', args.observation], ['observation-validation', args['observation-validation']],
    ['rollback', args.rollback], ['config-snapshot', args['config-snapshot']],
    ['compose-config', args['compose-config']]
  ];
  for (const [label, input] of required) {
    if (input === undefined || input === true || input === false || String(input).trim() === '') {
      errors.push(`缺少 --${label}`);
    }
  }
  if (releaseClass === 'GA') {
    for (const [label, input] of [['feedback', args.feedback], ['feedback-registry', args['feedback-registry']], ['feedback-lineage-validation', args['feedback-lineage-validation']], ['feedback-hashes', args['feedback-hashes']], ['rpo-report', args['rpo-report']], ['rto-report', args['rto-report']], ['previous-manifest', args['previous-manifest']], ['previous-resolution', args['previous-resolution']], ['active-release', args['active-release']]]) {
      if (input === undefined || input === true || input === false || String(input).trim() === '') errors.push(`GA gate 缺少 --${label}`);
    }
  } else {
    for (const [label, input] of [['feedback', args.feedback], ['feedback-registry', args['feedback-registry']], ['feedback-lineage-validation', args['feedback-lineage-validation']], ['feedback-hashes', args['feedback-hashes']]]) {
      if (input === undefined || input === true || input === false || String(input).trim() === '') errors.push(`DEMO_ONLY gate 缺少 --${label}`);
    }
  }

  const readiness = readExplicitJson(repoRoot, args.readiness, 'readiness');
  if (readiness) {
    const ok = readiness.passed === true || ['ready', 'passed', 'UP'].includes(String(readiness.status ?? ''));
    check('readiness', ok, 'readiness 未通过');
    bindRecord(readiness, value, 'readiness');
    const digest = readiness.imageDigest ?? readiness.images?.backend ?? readiness.backendDigest;
    if (digest !== undefined && isPlaceholder(digest)) errors.push('readiness 包含占位镜像 digest');
  }
  const probes = readExplicitJsonLines(repoRoot, args['business-probes'], 'business probes');
  if (probes) {
    check('business-probes-present', probes.length > 0, '业务探针为空');
    let passedCount = 0;
    for (const row of probes) {
      if (!row || typeof row !== 'object') { errors.push('业务探针记录必须是对象'); continue; }
      bindRecord(row, value, 'business-probe');
      if (row.passed === true || ['passed', 'ok', 'success'].includes(String(row.status ?? '').toLowerCase())) passedCount += 1;
      else errors.push('业务探针存在未通过记录');
    }
    check('business-probes-passed', probes.length > 0 && passedCount === probes.length, '业务探针未全部通过');
  }
  const observation = readExplicitJsonLines(repoRoot, args.observation, 'observation');
  if (observation) validateObservation(observation, value, expectedHours, expectedRequests, expectedPerWindow);
  const observationValidation = readExplicitJson(repoRoot, args['observation-validation'], 'observation validation');
  if (observationValidation) {
    check('observation-validation', observationValidation.passed === true || ['passed', 'ready'].includes(String(observationValidation.status ?? '').toLowerCase()), '观察期 validation 未通过');
    bindRecord(observationValidation, value, 'observation-validation');
  }
  const observabilityDir = args['observability-dir'];
  if (!observabilityDir || observabilityDir === true) errors.push('--observability-dir 必须显式提供');
  else validateObservability(repoRoot, observabilityDir);
  const rollback = readExplicitJsonLines(repoRoot, args.rollback, 'rollback drill');
  if (rollback) {
    let passedCount = 0;
    for (const row of rollback) {
      if (!row || typeof row !== 'object') { errors.push('rollback 记录必须是对象'); continue; }
      bindRecord(row, value, 'rollback');
      if (row.passed === true || ['passed', 'ok', 'success', 'recovered'].includes(String(row.status ?? '').toLowerCase())) passedCount += 1;
    }
    check('rollback-drill', rollback.length > 0 && passedCount > 0, '没有成功的回滚/恢复演练记录');
  }
  validateTextEvidence(repoRoot, args['config-snapshot'], 'config snapshot', true);
  validateTextEvidence(repoRoot, args['compose-config'], 'compose config', true);
  validateFeedback(repoRoot, value);
  validateRecovery(repoRoot, value);
  validateReleasePointers(repoRoot, value);
  if (value.evidence) validateManifestEvidenceHashes(value, repoRoot);
}

function validateRequiredManifestEvidence(value) {
  const evidence = value.evidence;
  if (!evidence || typeof evidence !== 'object') return;
  const branchKeys = new Set(['readiness', 'environmentContract', 'activeRelease', 'businessProbes', 'rollback', 'configSnapshot', 'composeConfig', 'observability', 'observabilityRaw', 'observation', 'observationValidation', 'evidenceHashes', 'releasePointer', 'releasePointerEvidence', 'demo']);
  const required = ['evaluationManifest', 'metricDictionary', 'evaluationReport', 'images', 'dependencyInventory', 'vulnerabilityExceptions', 'vulnerabilityExceptionsValidation', 'rpoRto', 'readiness', 'businessProbes', 'rollback', 'configSnapshot', 'composeConfig', 'observability', 'observation', 'observationValidation', 'evidenceHashes', 'feedback', 'feedbackRegistry'];
  const branch = releaseClass === 'GA' ? 'ga' : 'demoOnly';
  for (const key of required) {
    const raw = evidence[key];
    if (raw === undefined || raw === null) { errors.push(`manifest evidence 缺少 ${key}`); continue; }
    const selected = branchKeys.has(key) && raw && typeof raw === 'object' && !Array.isArray(raw) && (raw[branch] !== undefined || raw.ga !== undefined || raw.demoOnly !== undefined)
      ? raw[branch]
      : raw;
    if (selected === undefined || selected === null) { errors.push(`manifest evidence.${key} 缺少 ${branch} 分支`); continue; }
    const entries = collectEvidenceEntries(selected, `evidence.${key}`);
    if (key !== 'rpoRto' && key !== 'images' && entries.length === 0) errors.push(`manifest evidence.${key} 缺少 path 证据`);
    if (key === 'images') {
      for (const imageName of ['backend', 'frontend']) {
        const imageEntries = collectEvidenceEntries(selected?.[imageName] ?? {}, `evidence.images.${imageName}`);
        if (imageEntries.length === 0) errors.push(`manifest evidence.images.${imageName} 缺少供应链证据`);
      }
    }
    if (key === 'rpoRto' && entries.length === 0 && releaseClass === 'GA') errors.push('GA manifest evidence.rpoRto 缺少 machineReport/runbookReport path');
  }
}

function validateBaseline(repoRoot) {
  const input = args['baseline-manifest'];
  if (input === undefined || input === true || input === false || String(input).trim() === '') {
    errors.push('缺少 --baseline-manifest（必须显式绑定 N0 基线）');
    return;
  }
  const info = safeInput(repoRoot, input, 'baseline manifest');
  if (!info) return;
  if (manifestInfo && samePath(info.absolute, manifestInfo.absolute)) errors.push('baseline manifest 不能与 gate 输入相同');
  try {
    const baseline = readJsonFile(info.real, 'baseline manifest');
    if (baseline?.schemaVersion !== 'v1.1') errors.push('baseline manifest schemaVersion 必须为 v1.1');
    if (baseline?.status && !['ready', 'finalized', 'signed'].includes(String(baseline.status).toLowerCase())) errors.push('baseline manifest 尚未签收/ready');
    if (baseline?.status && ['GA', 'DEMO_ONLY'].includes(String(baseline.status))) warnings.push('baseline 已标记为发布状态；请确认这是可回退旧版本而非候选输入');
    if (scanSecretLikeText(JSON.stringify(baseline)).length > 0) errors.push('baseline manifest 含未脱敏 secret');
  } catch (error) { errors.push(error.message); }
}

function validateObservation(rows, value, minHours, minRequests, minPerWindow) {
  const analyzed = analyzeObservationRows(rows, {
    releaseId: value.releaseId,
    environment,
    minPerWindow,
    // GA and DEMO_ONLY both require a real sequence; a single summary row is
    // never sufficient.  Their duration/request thresholds remain distinct
    // and are supplied by the caller.
    minWindows: minHours > 1 ? 2 : 1,
    requireContinuous: true
  });
  errors.push(...analyzed.errors);
  check('observation-request-total', analyzed.totalRequests >= minRequests,
    `观察期总请求不足（要求至少 ${minRequests}）`);
  check('observation-duration', analyzed.durationHours >= minHours && analyzed.coveredDurationHours >= minHours,
    `观察期时长不足（仅按窗口时间戳/窗口覆盖计算，要求至少 ${minHours} 小时）`);
  check('observation-window-sequence', analyzed.continuous,
    '观察窗口必须是至少两段、按时间排序且连续（仅允许不超过 60 秒调度边界抖动）的序列');
  check('observation-independent-source', analyzed.independentSource,
    '每个观察窗口必须声明至少两个独立 telemetry source');
  if (String(value.releaseClass) === 'GA' && String(value.environment) !== 'prod') errors.push('GA observation 必须绑定 prod');
}

function validateObservability(repoRoot, dirArg) {
  const dir = resolveDirectory(repoRoot, dirArg, 'observability');
  if (!dir) return;
  const required = ['targets-alerts-trace.md', 'prometheus-targets.json', 'prometheus-rules.json', 'alertmanager-events.json', 'grafana-health.json', 'fault-injection-timeline.md', 'fault-plan-validation.json'];
  for (const name of required) {
    const file = path.join(dir.absolute, name);
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) errors.push(`observability 缺少 ${name}`);
    else if (scanSecretLikeText(fs.readFileSync(file, 'utf8')).length > 0) errors.push(`observability/${name} 含未脱敏 secret`);
  }
  check('observability-evidence', required.every((name) => fs.existsSync(path.join(dir.absolute, name))), 'observability 原始证据不完整');
}

function validateFeedback(repoRoot, value) {
  const feedback = readExplicitJsonLines(repoRoot, args.feedback, 'feedback');
  const registry = readExplicitJson(repoRoot, args['feedback-registry'], 'feedback registry');
  const lineage = readExplicitJson(repoRoot, args['feedback-lineage-validation'], 'feedback lineage validation');
  const hashes = readExplicitJson(repoRoot, args['feedback-hashes'], 'feedback hashes');
  if (feedback) {
    check('feedback-present', feedback.length > 0, '反馈证据为空');
    for (const row of feedback) {
      if (!row || typeof row !== 'object') { errors.push('feedback 记录必须是对象'); continue; }
      bindRecord(row, value, 'feedback');
      for (const field of ['answerId', 'runId', 'traceId']) if (isPlaceholder(row[field])) errors.push(`feedback.${field} 缺失或占位`);
      for (const field of ['dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId']) if (row[field] !== undefined && isPlaceholder(row[field])) errors.push(`feedback.${field} 缺失或占位`);
    }
  }
  if (registry) {
    const rows = Array.isArray(registry) ? registry : registry.records ?? registry.entries ?? registry.items;
    check('feedback-registry-present', Array.isArray(rows) && rows.length > 0, 'feedback registry 为空');
    if (Array.isArray(rows)) rows.forEach((row) => bindRecord(row, value, 'feedback-registry'));
  }
  if (lineage) {
    check('feedback-lineage', lineage.passed === true && Number(lineage.untraceableCount ?? 0) === 0, '反馈 lineage validation 未通过或含不可追溯记录');
    if (lineage.publishedReleaseId !== undefined && lineage.publishedReleaseId !== value.releaseId) errors.push('feedback lineage publishedReleaseId 不匹配');
  }
  if (hashes) validateHashManifest(repoRoot, hashes, 'feedback hashes');
}

function validateRecovery(repoRoot, value) {
  const allowUnmeasured = boolArg(args['allow-unmeasured-rpo-rto']);
  for (const [label, arg] of [['rpo', args['rpo-report']], ['rto', args['rto-report']]]) {
    if (arg === undefined || arg === true || arg === false) {
      if (releaseClass === 'GA') errors.push(`GA 缺少 ${label} recovery report`);
      continue;
    }
    const report = readExplicitJson(repoRoot, arg, `${label} report`);
    if (!report) continue;
    const actual = Number(report.actualSeconds ?? report[`${label}Seconds`] ?? report.actual?.seconds);
    const target = Number(report.targetSeconds ?? report[`target${label === 'rpo' ? 'Rpo' : 'Rto'}Seconds`] ?? report.target?.seconds);
    if (!Number.isFinite(actual) || !Number.isFinite(target)) {
      if (releaseClass === 'GA' || !allowUnmeasured) errors.push(`${label.toUpperCase()} actual/target 必须是数字`);
      else warnings.push(`${label.toUpperCase()} 未测量（DEMO_ONLY 明确允许）`);
    } else if (actual > target) errors.push(`${label.toUpperCase()} 超出目标`);
    else check(`${label}-target`, true);
  }
}

function validateReleasePointers(repoRoot, value) {
  for (const [label, arg] of [['active-release', args['active-release']], ['previous-manifest', args['previous-manifest']], ['previous-resolution', args['previous-resolution']]]) {
    if (arg === undefined || arg === true || arg === false) continue;
    const doc = readExplicitJson(repoRoot, arg, label);
    if (!doc) continue;
    if (label === 'active-release') {
      if (doc.releaseId !== undefined && doc.releaseId !== value.releaseId) errors.push('active releaseId 与候选不匹配');
      if (doc.environment !== undefined && doc.environment !== environment) errors.push('active release environment 不匹配');
      if (doc.imageDigest !== undefined && isPlaceholder(doc.imageDigest)) errors.push('active release imageDigest 是占位符');
    }
    if (label === 'previous-manifest' && doc.releaseId !== undefined && doc.releaseId === value.releaseId) errors.push('previous manifest 不能与候选 releaseId 相同');
    if (label === 'previous-resolution' && doc.status !== undefined && !['resolved', 'ready', 'passed'].includes(String(doc.status).toLowerCase())) errors.push('previous release resolution 未通过');
  }
}

function validateManifestEvidenceHashes(value, repoRoot) {
  const artifactRoot = path.resolve(repoRoot, value.artifactRoot ?? 'artifacts/v1.1');
  for (const entry of collectEvidenceEntries(value.evidence ?? {})) {
    if (!entry.sha256) continue;
    const file = resolveEvidencePath(repoRoot, artifactRoot, entry.path);
    if (!file || !fs.existsSync(file)) { errors.push(`${entry.location}: evidence 文件不存在`); continue; }
    if (!fs.statSync(file).isFile()) { errors.push(`${entry.location}: evidence path 不是普通文件`); continue; }
    if (normalizeHash(entry.sha256) !== sha256File(file)) errors.push(`${entry.location}: evidence hash 不匹配`);
  }
}

function validateHashManifest(repoRoot, value, label) {
  const rows = Array.isArray(value) ? value : value.files ?? value.entries ?? value.items;
  if (!Array.isArray(rows) || rows.length === 0) { errors.push(`${label} 缺少 files/entries`); return; }
  for (const row of rows) {
    if (!row || typeof row.path !== 'string' || !isSha256(row.sha256)) { errors.push(`${label} 存在无效 path/sha256`); continue; }
    const info = safeInput(repoRoot, row.path, label);
    if (!info) continue;
    if (normalizeHash(row.sha256) !== sha256File(info.real)) errors.push(`${label} hash 不匹配`);
  }
}

function validateTextEvidence(repoRoot, value, label, rejectSecrets) {
  const info = safeInput(repoRoot, value, label);
  if (!info) return;
  const text = fs.readFileSync(info.real, 'utf8');
  if (!text.trim()) errors.push(`${label} 为空`);
  if (rejectSecrets && scanSecretLikeText(text).length > 0) errors.push(`${label} 含未脱敏 secret`);
}

function bindRecord(row, manifest, label) {
  if (!row || typeof row !== 'object') return;
  for (const [recordField, manifestField] of [['releaseId', 'releaseId'], ['environment', 'environment'], ['releaseClass', 'releaseClass']]) {
    if (row[recordField] !== undefined && row[recordField] !== manifest[manifestField]) errors.push(`${label}.${recordField} 与 manifest 不匹配`);
  }
}

function readExplicitJson(repoRoot, value, label) {
  const info = safeInput(repoRoot, value, label);
  if (!info) return null;
  try { return readJsonFile(info.real, label); }
  catch (error) { errors.push(error.message); return null; }
}
function readExplicitJsonLines(repoRoot, value, label) {
  const info = safeInput(repoRoot, value, label);
  if (!info) return null;
  try { return readJsonLines(info.real, label); }
  catch (error) { errors.push(error.message); return null; }
}
function safeInput(repoRoot, value, label) {
  if (value === undefined || value === true || value === false || String(value).trim() === '') {
    errors.push(`${label} 输入缺失`); return null;
  }
  try { return resolveRepoFile(repoRoot, value, label); }
  catch (error) { errors.push(error.message); return null; }
}
function resolveDirectory(repoRoot, value, label) {
  const raw = String(value ?? '').trim();
  if (!raw) { errors.push(`${label} 目录缺失`); return null; }
  const absolute = path.isAbsolute(raw) ? path.normalize(raw) : path.resolve(repoRoot, raw);
  if (!within(repoRoot, absolute)) { errors.push(`${label} 目录越出 repo-root`); return null; }
  if (!fs.existsSync(absolute) || !fs.statSync(absolute).isDirectory()) { errors.push(`${label} 目录不存在`); return null; }
  const real = fs.realpathSync(absolute);
  if (!within(repoRoot, real)) { errors.push(`${label} 目录通过符号链接越界`); return null; }
  return {absolute, real};
}
function resolveEvidencePath(repoRoot, artifactRoot, value) {
  const raw = normalizeSlashes(value);
  if (!raw || path.isAbsolute(raw) || isPlaceholder(raw) || raw.includes('../') || raw.includes('/..')) return null;
  const artifactRel = normalizeSlashes(path.relative(repoRoot, artifactRoot));
  const absolute = raw === artifactRel || raw.startsWith(`${artifactRel}/`) ? path.resolve(repoRoot, raw) : path.resolve(artifactRoot, raw);
  if (!within(repoRoot, absolute) || !within(artifactRoot, absolute)) return null;
  return absolute;
}
function check(name, passed, reason = null) {
  checks.push({name, passed, ...(reason ? {reason: safeText(reason)} : {})});
  if (!passed && reason) errors.push(`${name}: ${safeText(reason)}`);
}
function dateValue(value) {
  if (value === undefined || value === null || isPlaceholder(value)) return null;
  const date = new Date(value); return Number.isNaN(date.getTime()) ? null : date;
}
function buildPromotedManifest(input, status, environment, repoRoot) {
  const output = JSON.parse(JSON.stringify(input));
  output.status = status;
  output.releaseClass = status;
  output.environment = environment;
  output.gate = {
    producer: 'v1.1-release-gate',
    generatedAt: new Date().toISOString(),
    status,
    inputStatus: 'BLOCKED',
    redacted: true
  };
  // Bind machine-measured recovery values into the immutable manifest.  The
  // reports remain separate evidence files; only their numeric result is
  // copied, never raw report content.
  if (repoRoot && output.evidence && typeof output.evidence === 'object') {
    const rpo = readRecoverySummary(repoRoot, args['rpo-report'], 'rpo');
    const rto = readRecoverySummary(repoRoot, args['rto-report'], 'rto');
    if (rpo || rto) {
      output.evidence.rpoRto = {
        ...(output.evidence.rpoRto && typeof output.evidence.rpoRto === 'object' ? output.evidence.rpoRto : {}),
        ...(rpo ? {rpoSeconds: rpo.actual, targetRpoSeconds: rpo.target} : {}),
        ...(rto ? {rtoSeconds: rto.actual, targetRtoSeconds: rto.target} : {})
      };
    }
  }
  return output;
}

function readRecoverySummary(repoRoot, value, label) {
  if (value === undefined || value === true || value === false || String(value).trim() === '') return null;
  try {
    const info = resolveRepoFile(repoRoot, value, `${label} report`);
    const doc = readJsonFile(info.real, `${label} report`);
    const actual = Number(doc.actualSeconds ?? doc[`${label}Seconds`] ?? doc.actual?.seconds);
    const target = Number(doc.targetSeconds ?? doc[`target${label === 'rpo' ? 'Rpo' : 'Rto'}Seconds`] ?? doc.target?.seconds);
    return Number.isFinite(actual) && Number.isFinite(target) ? {actual, target} : null;
  } catch { return null; }
}
function collectExplicitHashes(repoRoot) {
  const result = {};
  for (const [key, value] of Object.entries(args)) {
    if (!key.endsWith('report') && !['readiness', 'business-probes', 'observation', 'observation-validation', 'feedback', 'feedback-registry', 'feedback-lineage-validation', 'feedback-hashes', 'rollback', 'previous-manifest', 'previous-resolution', 'active-release', 'config-snapshot', 'compose-config'].includes(key)) continue;
    if (value === undefined || value === true || value === false) continue;
    try { const info = resolveRepoFile(repoRoot, value, key); result[key] = {path: info.relative, sha256: sha256File(info.real)}; } catch { /* report only safe successful hashes */ }
  }
  return result;
}
function resolveOutput(repoRoot, value, label) {
  const raw = String(value ?? '').trim();
  if (!raw) throw new Error(`${label} 路径为空`);
  const absolute = path.isAbsolute(raw) ? path.normalize(raw) : path.resolve(repoRoot, raw);
  if (!within(repoRoot, absolute) || samePath(repoRoot, absolute)) throw new Error(`${label} 必须位于 repo-root 内`);
  let parent = path.dirname(absolute);
  while (!fs.existsSync(parent)) { const next = path.dirname(parent); if (next === parent) throw new Error(`${label} 父目录无效`); parent = next; }
  if (!within(repoRoot, fs.realpathSync(parent))) throw new Error(`${label} 父目录越界`);
  if (fs.existsSync(absolute)) {
    const real = fs.realpathSync(absolute);
    if (!within(repoRoot, real) || !fs.statSync(real).isFile()) throw new Error(`${label} 已存在但不是仓库内普通文件`);
  }
  return {absolute};
}
function relative(file, root) { return path.relative(root, file).split(path.sep).join('/'); }
function samePath(a, b) { const left = path.resolve(a); const right = path.resolve(b); return process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right; }
function within(root, target) { const rel = path.relative(path.resolve(root), path.resolve(target)); return rel === '' || (rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel)); }
function scalar(value) { return value === undefined || value === true || value === false ? null : String(value).trim() || null; }
function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function numberArg(value, fallback) { if (value === undefined || value === true || value === false || String(value).trim() === '') return fallback; const parsed = Number(value); return Number.isFinite(parsed) ? parsed : fallback; }
function unique(values) { return [...new Set(values.map((value) => String(value)))]; }
