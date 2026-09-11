#!/usr/bin/env node

/**
 * Static N7 Dockerfile/Compose image gate.
 *
 * This producer only checks the image references that are present in reviewed
 * files.  It never contacts a registry and never runs Docker.  A digest-shaped
 * reference is therefore a necessary static condition, not proof that the
 * digest exists in a registry or that the image was signed.
 */
import {
  collectFileArgs,
  imageSummary,
  inputFile,
  looksLikeDockerfile,
  outputFile,
  parseCompose,
  parseDockerfile,
  readInput,
  sourceInfo
} from './lib/supply-chain.mjs';
import {currentCommit, generatedAt, parseArgs, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const outputPath = args.report ?? args.output ?? 'artifacts/v1.1/release/base-image-validation.json';
const requireDigest = boolArg(args['require-digest']) || boolArg(args['require-digests']);
const allowPending = boolArg(args['allow-pending']);
const errors = [];
const warnings = [];
const inputFiles = [];
const images = [];
const builds = [];

const knownOptions = new Set([
  'file', 'report', 'output', 'require-digest', 'require-digests', 'allow-pending', 'no-clobber'
]);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!knownOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const fileArgs = collectFileArgs(args, {minimum: 2});
errors.push(...fileArgs.errors);
const seen = new Set();
for (const value of fileArgs.values) {
  let info;
  try {
    info = inputFile(value, '输入文件');
  } catch (error) {
    errors.push(`输入文件 ${safePath(value)}: ${error.message}`);
    continue;
  }
  if (seen.has(info.relative)) {
    errors.push(`输入文件重复: ${info.relative}`);
    continue;
  }
  seen.add(info.relative);
  inputFiles.push(info);
  const text = readInput(info);
  const parsed = looksLikeDockerfile(info, text)
    ? parseDockerfile(text, info.relative)
    : parseCompose(text, info.relative);
  errors.push(...parsed.errors);
  images.push(...parsed.images);
  builds.push(...parsed.builds);
}

if (images.length === 0) errors.push('未发现任何 Dockerfile FROM 或 Compose image 引用');
for (const image of images) {
  if (!image.valid) continue;
  if (!image.immutable) {
    const location = `${image.sourceFile}:${image.line}`;
    if (requireDigest) errors.push(`${location}: 镜像必须使用 @sha256 digest（当前为浮动 tag ${image.image}）`);
    else warnings.push(`${location}: 镜像尚未锁定 digest: ${image.image}`);
  }
}
if (builds.length > 0) warnings.push(`发现 ${builds.length} 个 Compose build 字段；本工具只校验镜像锁定，发布 Compose 的 build 禁止由 validate-release-compose 执行`);

let status;
if (errors.length > 0) status = 'blocked';
else if (images.some((item) => !item.immutable)) status = 'environment-pending';
else status = 'passed';
const report = {
  schemaVersion: 'v1.1',
  producer: 'validate-docker-base-images',
  generatedAt: generatedAt(),
  gitCommit: currentCommit(),
  parser: 'dockerfile-compose-image-static-v1',
  requireDigest,
  status,
  passed: status === 'passed',
  exitCode: status === 'passed' || (status === 'environment-pending' && allowPending) ? 0 : 1,
  redacted: true,
  inputFiles: inputFiles.map(sourceInfo),
  images: images.map(imageSummary),
  mutableImages: images.filter((item) => item.valid && !item.immutable).map(imageSummary),
  builds: builds.map(({sourceFile, line, service, value, mapping}) => ({sourceFile, line, service: service ?? null, hasValue: Boolean(value), mapping})),
  errors,
  warnings,
  policy: '静态 digest 形状检查不等于 registry 存在性、SBOM、签名或验签；缺失/浮动引用保持 pending 或 blocked'
};

try {
  const out = outputFile(outputPath, '报告');
  if (inputFiles.some((item) => samePath(item.absolute, out.absolute))) errors.push('报告不能覆盖输入文件');
  if (errors.length > 0 && status === 'passed') {
    report.status = 'blocked'; report.passed = false; report.exitCode = 1;
  }
  writeJson(out.absolute, report, {noClobber: boolArg(args['no-clobber'])});
} catch (error) {
  report.errors.push(`报告写入失败: ${error.message}`);
  report.status = 'blocked'; report.passed = false; report.exitCode = 1;
}

console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0) process.exitCode = 1;

function boolArg(value) {
  return value === true || String(value ?? '').toLowerCase() === 'true';
}

function safePath(value) {
  return String(value ?? '').replaceAll('\\', '/').replace(/[\r\n]/g, '');
}

function samePath(left, right) {
  const a = String(left).toLowerCase();
  const b = String(right).toLowerCase();
  return a === b;
}
