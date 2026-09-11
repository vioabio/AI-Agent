#!/usr/bin/env node

/**
 * Produce the small, auditable hash manifest used by the v1.1 release steps.
 *
 * The manifest deliberately contains metadata only.  Input bytes are streamed
 * into SHA-256 and are never copied into the output (or printed to stdout).
 * Paths in the manifest are always relative to --repo-root and use '/'.
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const ALGORITHM = 'SHA-256';
const MODULE_PATH = path.resolve(fileURLToPath(import.meta.url));

/**
 * Parse the intentionally small CLI.  --input may occur more than once.
 * Unknown options are rejected so a typo cannot silently produce incomplete
 * evidence.
 *
 * @param {string[]} argv
 * @returns {{repoRoot: string, inputs: string[], output: string|null, noClobber: boolean, help: boolean}}
 */
export function parseArgs(argv) {
  const result = {
    repoRoot: '.',
    inputs: [],
    output: null,
    noClobber: false,
    help: false
  };

  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (token === '-h' || token === '--help') {
      result.help = true;
      continue;
    }
    if (token === '--no-clobber') {
      result.noClobber = true;
      continue;
    }
    if (!token.startsWith('--')) {
      throw new Error(`不支持的位置参数: ${token}`);
    }

    const separator = token.indexOf('=');
    const option = separator === -1 ? token.slice(2) : token.slice(2, separator);
    let value = separator === -1 ? undefined : token.slice(separator + 1);
    if (!['repo-root', 'input', 'output'].includes(option)) {
      throw new Error(`未知参数: --${option}`);
    }
    if (value === undefined) {
      value = argv[++index];
      if (value === undefined || value.startsWith('--')) {
        throw new Error(`参数 --${option} 需要一个值`);
      }
    }
    if (value.length === 0) {
      throw new Error(`参数 --${option} 不能为空`);
    }

    if (option === 'repo-root') result.repoRoot = value;
    if (option === 'input') result.inputs.push(value);
    if (option === 'output') result.output = value;
  }

  return result;
}

/**
 * Resolve and validate the repository root.
 *
 * @param {string} rootArg
 * @returns {string}
 */
export function resolveRepoRoot(rootArg) {
  const candidate = path.resolve(process.cwd(), rootArg || '.');
  let root;
  try {
    root = fs.realpathSync(candidate);
  } catch (error) {
    throw new Error(`repo-root 不存在或不可读取: ${candidate} (${error.message})`);
  }
  const stats = fs.statSync(root);
  if (!stats.isDirectory()) {
    throw new Error(`repo-root 不是目录: ${candidate}`);
  }
  return root;
}

/**
 * Return whether target is root itself or a descendant of root.  path.relative
 * keeps this safe for Windows drive letters as well as POSIX paths.
 */
function isWithin(root, target) {
  const relative = path.relative(root, target);
  return relative === ''
    || (relative !== '..'
      && !relative.startsWith(`..${path.sep}`)
      && !path.isAbsolute(relative));
}

function samePath(left, right) {
  const normalizedLeft = path.resolve(left);
  const normalizedRight = path.resolve(right);
  if (process.platform === 'win32') {
    return normalizedLeft.toLowerCase() === normalizedRight.toLowerCase();
  }
  return normalizedLeft === normalizedRight;
}

function pathEntryExists(filePath) {
  try {
    fs.lstatSync(filePath);
    return true;
  } catch (error) {
    if (error?.code === 'ENOENT') return false;
    throw error;
  }
}

function toRepoRelative(root, filePath) {
  return path.relative(root, filePath).split(path.sep).join('/');
}

/**
 * Resolve one input and make sure a symlink cannot escape the repository.
 * The lexical path is retained for the manifest so an in-repository symlink
 * has a stable, user-visible path; its real target must still stay in root.
 */
export function resolveInput(repoRoot, inputArg) {
  const lexicalPath = path.isAbsolute(inputArg)
    ? path.normalize(inputArg)
    : path.resolve(repoRoot, inputArg);

  if (!isWithin(repoRoot, lexicalPath) || samePath(repoRoot, lexicalPath)) {
    throw new Error(`输入必须位于 repo-root 内: ${inputArg}`);
  }

  let realPath;
  try {
    realPath = fs.realpathSync(lexicalPath);
  } catch (error) {
    throw new Error(`输入文件不存在或不可读取: ${inputArg} (${error.message})`);
  }
  if (!isWithin(repoRoot, realPath) || samePath(repoRoot, realPath)) {
    throw new Error(`输入路径通过符号链接越出 repo-root: ${inputArg}`);
  }

  let stats;
  try {
    stats = fs.statSync(realPath);
  } catch (error) {
    throw new Error(`无法读取输入文件: ${inputArg} (${error.message})`);
  }
  if (!stats.isFile()) {
    throw new Error(`输入不是普通文件: ${inputArg}`);
  }

  return {
    lexicalPath,
    realPath,
    relativePath: toRepoRelative(repoRoot, lexicalPath)
  };
}

/**
 * Stream a file through SHA-256.  No input content is retained in the report.
 *
 * @param {string} filePath
 * @returns {Promise<string>}
 */
export async function sha256File(filePath) {
  const hash = crypto.createHash('sha256');
  const stream = fs.createReadStream(filePath);
  try {
    for await (const chunk of stream) {
      hash.update(chunk);
    }
    return hash.digest('hex');
  } finally {
    stream.destroy();
  }
}

/**
 * Read the commit associated with the repository.  A temporary/non-git root
 * is still useful for local checks, so it gets an explicit "unknown" value;
 * release finalizers can reject that value when a real commit is mandatory.
 */
export function readCommit(repoRoot) {
  try {
    const commit = execFileSync(
      'git',
      ['-C', repoRoot, 'rev-parse', '--verify', 'HEAD'],
      {encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore']}
    ).trim();
    return commit || 'unknown';
  } catch {
    return 'unknown';
  }
}

/**
 * Build an array (including one item for one input) so callers can safely
 * consume the same shape regardless of input count.  Input order is retained
 * exactly as supplied on the command line.
 */
export async function buildHashReport({repoRoot, inputs, generatedAt = new Date().toISOString()}) {
  if (!Array.isArray(inputs) || inputs.length === 0) {
    throw new Error('至少需要一个 --input');
  }

  const commit = readCommit(repoRoot);
  const seen = new Set();
  const resolvedInputs = inputs.map((inputArg) => {
    const resolved = resolveInput(repoRoot, inputArg);
    const key = process.platform === 'win32'
      ? resolved.relativePath.toLowerCase()
      : resolved.relativePath;
    if (seen.has(key)) {
      throw new Error(`重复的输入路径: ${resolved.relativePath}`);
    }
    seen.add(key);
    return resolved;
  });

  return Promise.all(resolvedInputs.map(async (input) => ({
    path: input.relativePath,
    sha256: await sha256File(input.realPath),
    algorithm: ALGORITHM,
    generatedAt,
    commit
  })));
}

/**
 * Atomically write a UTF-8 file.  The temporary file lives beside the target,
 * is fsync'ed before rename, and is removed on every failure.  no-clobber is
 * checked both before and immediately before the rename.
 */
export function writeAtomic(targetPath, content, {noClobber = false} = {}) {
  const absoluteTarget = path.resolve(targetPath);
  const parent = path.dirname(absoluteTarget);

  if (noClobber && pathEntryExists(absoluteTarget)) {
    throw new Error(`输出文件已存在（--no-clobber）: ${absoluteTarget}`);
  }
  fs.mkdirSync(parent, {recursive: true});

  const temporary = path.join(
    parent,
    `.${path.basename(absoluteTarget)}.${process.pid}.${crypto.randomUUID()}.tmp`
  );
  let descriptor;
  try {
    descriptor = fs.openSync(temporary, 'wx', 0o644);
    fs.writeFileSync(descriptor, content, {encoding: 'utf8'});
    fs.fsyncSync(descriptor);
    fs.closeSync(descriptor);
    descriptor = undefined;

    if (noClobber && pathEntryExists(absoluteTarget)) {
      throw new Error(`输出文件在写入期间出现（--no-clobber）: ${absoluteTarget}`);
    }
    fs.renameSync(temporary, absoluteTarget);

    // Directory fsync is supported on POSIX.  Windows does not expose a
    // directory descriptor, while rename itself is atomic on the same volume.
    if (process.platform !== 'win32') {
      try {
        const directoryDescriptor = fs.openSync(parent, 'r');
        try {
          fs.fsyncSync(directoryDescriptor);
        } finally {
          fs.closeSync(directoryDescriptor);
        }
      } catch {
        // The file has already been atomically renamed; directory fsync is a
        // durability enhancement, not a reason to report a false failure.
      }
    }
  } finally {
    if (descriptor !== undefined) {
      try {
        fs.closeSync(descriptor);
      } catch {
        // Preserve the original failure.
      }
    }
    try {
      if (pathEntryExists(temporary)) fs.unlinkSync(temporary);
    } catch {
      // Preserve the original failure; a later cleanup can remove the temp.
    }
  }
}

function usage() {
  return [
    '用法: node tools/hash-files.mjs --repo-root <dir> --input <file> [--input <file> ...] --output <file> [--no-clobber]',
    '',
    '输出是 JSON 数组，每项仅包含 path、sha256、algorithm、generatedAt、commit。',
    'path 始终是相对于 repo-root 的 POSIX 路径；输入内容不会写入输出。',
    '使用 --output - 可将 JSON 写到 stdout（此模式不执行文件写入）。'
  ].join('\n');
}

export async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  if (options.help) {
    process.stdout.write(`${usage()}\n`);
    return;
  }
  if (options.inputs.length === 0) {
    throw new Error('至少需要一个 --input（使用 --help 查看用法）');
  }
  if (options.output === null) {
    throw new Error('必须提供 --output（使用 --output - 输出到 stdout）');
  }

  const repoRoot = resolveRepoRoot(options.repoRoot);
  const report = await buildHashReport({repoRoot, inputs: options.inputs});
  const serialized = `${JSON.stringify(report, null, 2)}\n`;
  if (options.output === '-') {
    process.stdout.write(serialized);
    return;
  }

  const outputPath = path.isAbsolute(options.output)
    ? options.output
    : path.resolve(repoRoot, options.output);
  if (report.some((entry) => samePath(outputPath, path.resolve(repoRoot, entry.path)))) {
    throw new Error('输出文件不能同时作为输入文件，否则会形成自引用');
  }
  writeAtomic(outputPath, serialized, {noClobber: options.noClobber});
  const outputRelative = isWithin(repoRoot, outputPath)
    ? toRepoRelative(repoRoot, outputPath)
    : outputPath;
  process.stdout.write(`hash manifest written: ${outputRelative}\n`);
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === MODULE_PATH) {
  main().catch((error) => {
    process.stderr.write(`hash-files: ${error.message}\n`);
    process.exitCode = 1;
  });
}
