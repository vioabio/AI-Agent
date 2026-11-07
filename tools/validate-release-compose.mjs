#!/usr/bin/env node

/**
 * Validate the static release Compose contract.
 *
 * The base Compose file may intentionally contain build sections and local
 * tags.  When an effective (already redacted) `docker compose config` output
 * is supplied, policy is evaluated against that final projection; otherwise
 * a conservative service-level overlay is calculated and any remaining build
 * or mutable image is rejected.  No Docker command is executed here.
 */
import fs from 'node:fs';
import {
  collectFileArgs,
  imageSummary,
  inputFile,
  isDigestImage,
  isSafeProjectName,
  outputFile,
  parseCompose,
  readInput,
  sourceInfo
} from './lib/supply-chain.mjs';
import {currentCommit, generatedAt, parseArgs, placeholder, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const outputPath = args.report ?? args.output ?? 'artifacts/v1.1/staging/release-compose-validation.json';
const requireDigests = boolArg(args['require-digests']) || boolArg(args['require-digest']);
const forbidBuild = boolArg(args['forbid-build']);
const errors = [];
const warnings = [];
const inputFiles = [];
const sourceImages = [];
const sourceBuilds = [];
const effectiveSecretFindings = [];

const knownOptions = new Set([
  'file', 'effective-config', 'project-name', 'report', 'output', 'require-digests',
  'require-digest', 'forbid-build', 'no-clobber'
]);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const projectName = String(args['project-name'] ?? '').trim();
if (!projectName || placeholder(projectName)) errors.push('--project-name 必须显式提供且不能是占位符');
else if (!isSafeProjectName(projectName)) errors.push('--project-name 格式无效（仅允许字母、数字、_、-）');

const fileArgs = collectFileArgs(args, {minimum: 2});
errors.push(...fileArgs.errors);
const seen = new Set();
const parsedSources = [];
for (const value of fileArgs.values) {
  let info;
  try { info = inputFile(value, 'Compose 输入文件'); }
  catch (error) {
    errors.push(`Compose 输入文件 ${safePath(value)}: ${error.message}`);
    continue;
  }
  if (seen.has(info.relative)) {
    errors.push(`Compose 输入文件重复: ${info.relative}`);
    continue;
  }
  seen.add(info.relative);
  inputFiles.push(info);
  const text = readInput(info);
  if (/^\s*FROM\b/im.test(text)) errors.push(`${info.relative}: 不是 Compose 文件（检测到 Dockerfile FROM）`);
  const parsed = parseCompose(text, info.relative);
  errors.push(...parsed.errors);
  sourceImages.push(...parsed.images);
  sourceBuilds.push(...parsed.builds);
  parsedSources.push({info, parsed});
}

let effectiveInfo = null;
let effectiveImages = [];
let effectiveBuilds = [];
if (args['effective-config'] !== undefined) {
  try {
    effectiveInfo = inputFile(args['effective-config'], 'effective config');
    if (seen.has(effectiveInfo.relative)) errors.push('effective config 不能与 Compose 输入文件重复');
    const effectiveText = readInput(effectiveInfo);
    const parsed = parseCompose(effectiveText, effectiveInfo.relative);
    errors.push(...parsed.errors);
    effectiveImages = parsed.images;
    effectiveBuilds = parsed.builds;
    effectiveSecretFindings.push(...findSecretValues(effectiveText, effectiveInfo.relative));
    if (effectiveSecretFindings.length > 0) {
      errors.push(`effective config 含 ${effectiveSecretFindings.length} 个未脱敏 Secret 字段`);
    }
  } catch (error) {
    errors.push(`effective config ${safePath(args['effective-config'])}: ${error.message}`);
  }
} else {
  const merged = mergeCompose(parsedSources);
  effectiveImages = merged.images;
  effectiveBuilds = merged.builds;
  warnings.push('未提供 --effective-config；仅执行保守的静态 overlay 合并，不能替代 docker compose config 输出');
}

if (effectiveImages.length === 0) errors.push('最终 Compose 未发现 image 字段');
for (const image of effectiveImages) {
  if (!image.valid) continue;
  if (requireDigests && !isDigestImage(image.image)) {
    errors.push(`${image.sourceFile}:${image.line}: release image 必须使用 @sha256 digest`);
  }
  if (!image.immutable) warnings.push(`${image.sourceFile}:${image.line}: mutable image ${image.image}`);
}
if (forbidBuild && effectiveBuilds.length > 0) {
  for (const build of effectiveBuilds) {
    errors.push(`${build.sourceFile}:${build.line}: release Compose 禁止 build 字段${build.service ? `（service=${build.service}）` : ''}`);
  }
}
if (!requireDigests && effectiveImages.some((item) => item.valid && !item.immutable)) {
  warnings.push('未启用 --require-digests；mutable image 只能保持候选/pending，不能作为发布凭据');
}
if (forbidBuild && !args['effective-config'] && sourceBuilds.length > 0 && effectiveBuilds.length > 0) {
  warnings.push('源 Compose 含 build；未提供 effective config 时按最终合并结果拒绝残留 build');
}

let status = 'passed';
if (errors.length > 0) status = 'blocked';
else if (effectiveImages.some((item) => item.valid && !item.immutable) || effectiveBuilds.length > 0) status = 'environment-pending';
const report = {
  schemaVersion: 'v1.1',
  producer: 'validate-release-compose',
  generatedAt: generatedAt(),
  gitCommit: currentCommit(),
  parser: 'compose-static-overlay-v1',
  projectName: projectName || null,
  requireDigests,
  forbidBuild,
  status,
  passed: status === 'passed',
  exitCode: status === 'passed' ? 0 : 1,
  redacted: true,
  inputFiles: inputFiles.map(sourceInfo),
  effectiveConfig: effectiveInfo ? sourceInfo(effectiveInfo) : null,
  sourceImages: sourceImages.map(imageSummary),
  sourceBuilds: sourceBuilds.map(safeBuild),
  images: effectiveImages.map(imageSummary),
  builds: effectiveBuilds.map(safeBuild),
  secretFindings: effectiveSecretFindings,
  errors,
  warnings,
  policy: '仅 digest 形状、build 禁止和脱敏静态配置通过；不证明 Docker 启动、registry 存在、签名、健康检查或 staging smoke'
};

try {
  const out = outputFile(outputPath, '报告');
  if (inputFiles.some((item) => samePath(item.absolute, out.absolute)) || (effectiveInfo && samePath(effectiveInfo.absolute, out.absolute))) {
    throw new Error('报告不能覆盖输入或 effective config');
  }
  writeJson(out.absolute, report, {noClobber: boolArg(args['no-clobber'])});
} catch (error) {
  report.errors.push(`报告写入失败: ${error.message}`);
  report.status = 'blocked'; report.passed = false; report.exitCode = 1;
}

console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0) process.exitCode = 1;

function mergeCompose(sources) {
  // Compose override semantics for the fields relevant to this gate:
  // later files replace an image; `build: null` removes a previous build.
  const byService = new Map();
  let anonymous = 0;
  for (const {parsed} of sources) {
    for (const image of parsed.images) {
      const key = image.service ?? `__anonymous_image_${anonymous++}`;
      byService.set(key, {...byService.get(key), image});
    }
    for (const build of parsed.builds) {
      const key = build.service ?? `__anonymous_build_${anonymous++}`;
      const prior = byService.get(key) ?? {};
      if (build.remove || /^null$/i.test(String(build.value ?? ''))) byService.set(key, {...prior, build: null});
      else byService.set(key, {...prior, build});
    }
  }
  return {
    images: [...byService.values()].map((entry) => entry.image).filter(Boolean),
    builds: [...byService.values()].map((entry) => entry.build).filter(Boolean)
  };
}

function safeBuild(build) {
  return {
    sourceFile: build.sourceFile,
    line: build.line,
    service: build.service ?? null,
    hasValue: Boolean(build.value && !/^null$/i.test(String(build.value))),
    mapping: Boolean(build.mapping),
    remove: Boolean(build.remove || /^null$/i.test(String(build.value ?? '')))
  };
}

function findSecretValues(text, sourceFile) {
  const findings = [];
  const lines = String(text).split(/\r?\n/);
  const keyPattern = /(password|passwd|api[_-]?key|access[_-]?key|secret|token|private[_-]?key|client[_-]?secret)/i;
  // A resolved effective config must not contain an interpolation token.  An
  // explicit redaction marker is accepted; `${SECRET}` is still unresolved
  // configuration and therefore a release blocker.
  const redacted = /^(?:\[?redacted\]?|\*{3,}|<redacted>|null|""|'')$/i;
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    const match = line.match(/^\s*[-A-Za-z0-9_.]+\s*:\s*(.*?)\s*$/);
    if (!match || !keyPattern.test(match[0].split(':', 1)[0])) continue;
    const value = unquote(match[1]);
    if (!value || redacted.test(value)) continue;
    findings.push({sourceFile, line: index + 1, key: match[0].split(':', 1)[0].trim(), reasonCode: 'UNREDACTED_SECRET_VALUE'});
  }
  for (let index = 0; index < lines.length; index += 1) {
    const listMatch = lines[index].match(/^\s*-\s*([A-Za-z0-9_.-]+)=(.*?)\s*$/);
    if (!listMatch || !keyPattern.test(listMatch[1])) continue;
    const value = unquote(listMatch[2]);
    if (!value || redacted.test(value)) {
      if (value && /^\$\{/.test(value)) findings.push({sourceFile, line: index + 1, key: listMatch[1], reasonCode: 'UNRESOLVED_SECRET_INTERPOLATION'});
      continue;
    }
    findings.push({sourceFile, line: index + 1, key: listMatch[1], reasonCode: 'UNREDACTED_SECRET_VALUE'});
  }
  // Catch common token prefixes without retaining the matched value.
  for (let index = 0; index < lines.length; index += 1) {
    if (/(?:sk-[A-Za-z0-9]{12,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,})/.test(lines[index])) {
      findings.push({sourceFile, line: index + 1, key: '[value]', reasonCode: 'TOKEN_SHAPED_VALUE'});
    }
  }
  return findings;
}

function unquote(value) {
  const text = String(value ?? '').trim();
  if (text.length >= 2 && ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith("'") && text.endsWith("'")))) return text.slice(1, -1).trim();
  return text;
}

function boolArg(value) { return value === true || String(value ?? '').toLowerCase() === 'true'; }
function safePath(value) { return String(value ?? '').replaceAll('\\', '/').replace(/[\r\n]/g, ''); }
function samePath(left, right) { return String(left).toLowerCase() === String(right).toLowerCase(); }
