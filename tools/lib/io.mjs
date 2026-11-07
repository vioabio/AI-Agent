import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const toolsDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const repoRoot = path.resolve(toolsDirectory, '..');

/** Parse the small, dependency-free CLI contract used by v1.1 producers. */
export function parseArgs(argv) {
  const result = {_positional: []};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith('--')) {
      result._positional.push(token);
      continue;
    }
    const body = token.slice(2);
    if (body === 'no-clobber') {
      result[body] = true;
      continue;
    }
    if (body.startsWith('no-') && !body.includes('=')) {
      result[body.slice(3)] = false;
      continue;
    }
    const separator = body.indexOf('=');
    const key = separator >= 0 ? body.slice(0, separator) : body;
    const value = separator >= 0 ? body.slice(separator + 1) : argv[index + 1];
    if (separator < 0 && (value === undefined || value.startsWith('--'))) {
      result[key] = true;
      continue;
    }
    if (separator < 0) index += 1;
    if (result[key] === undefined) result[key] = value;
    else result[key] = Array.isArray(result[key]) ? [...result[key], value] : [result[key], value];
  }
  return result;
}

export function listArg(value) {
  if (value === undefined || value === null || value === '') return [];
  return (Array.isArray(value) ? value : [value])
    .flatMap((item) => String(item).split(','))
    .map((item) => item.trim())
    .filter(Boolean);
}

export function resolvePath(value, base = repoRoot) {
  if (!value) return undefined;
  return path.isAbsolute(value) ? path.normalize(value) : path.resolve(base, value);
}

export function requireFile(value, label = value) {
  const resolved = resolvePath(value);
  if (!resolved || !fs.existsSync(resolved) || !fs.statSync(resolved).isFile()) {
    throw new Error(`${label} 不存在或不是文件: ${value}`);
  }
  return resolved;
}

export function readText(value, label = value) {
  return fs.readFileSync(requireFile(value, label), 'utf8');
}

export function readJson(value, label = value) {
  const resolved = requireFile(value, label);
  try {
    return JSON.parse(fs.readFileSync(resolved, 'utf8'));
  } catch (error) {
    throw new Error(`${label} JSON 解析失败: ${error.message}`);
  }
}

export function repoRelative(value, root = repoRoot) {
  const absolute = path.resolve(root, value);
  const relative = path.relative(root, absolute);
  if (!relative || relative.startsWith(`..${path.sep}`) || relative === '..' || path.isAbsolute(relative)) {
    throw new Error(`路径必须位于仓库内: ${value}`);
  }
  return relative.split(path.sep).join('/');
}

export function sha256File(value) {
  return crypto.createHash('sha256').update(fs.readFileSync(requireFile(value))).digest('hex');
}

export function sha256Text(value) {
  return crypto.createHash('sha256').update(value, 'utf8').digest('hex');
}

/** Write through a sibling temporary file so readers never observe partial JSON. */
export function writeAtomic(value, content, {noClobber = false} = {}) {
  const target = resolvePath(value);
  if (noClobber && fs.existsSync(target)) throw new Error(`输出已存在（--no-clobber）: ${value}`);
  fs.mkdirSync(path.dirname(target), {recursive: true});
  const temporary = path.join(
    path.dirname(target),
    `.${path.basename(target)}.${process.pid}.${crypto.randomBytes(6).toString('hex')}.tmp`
  );
  fs.writeFileSync(temporary, content, {encoding: 'utf8', flag: 'wx'});
  try {
    fs.renameSync(temporary, target);
  } catch (error) {
    try { fs.rmSync(temporary, {force: true}); } catch { /* best effort cleanup */ }
    throw error;
  }
  return target;
}

export function writeJson(value, payload, options = {}) {
  return writeAtomic(value, `${JSON.stringify(payload, null, 2)}\n`, options);
}

export function safeGit(args, cwd = repoRoot) {
  try {
    return execFileSync('git', args, {cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore']}).trim();
  } catch {
    return 'unknown';
  }
}

export function currentCommit() {
  return safeGit(['rev-parse', 'HEAD']);
}

export function isGitDirty() {
  return safeGit(['status', '--porcelain']) !== '';
}

export function generatedAt() {
  return new Date().toISOString();
}

export function placeholder(value) {
  if (value === undefined || value === null || String(value).trim() === '') return true;
  const text = String(value).trim();
  return /^(your[-_]|replace[-_]|change[-_]?me|<[^>]+>|pending|tbd|set[-_]?at[-_]|local[-_]|not[-_]?published|.*[-_]pending$)/i.test(text)
    || /(?:^|[.@/])example\.invalid(?:$|[/:])/i.test(text)
    || /^(?:null|undefined|none|n\/a)$/i.test(text);
}

export function redactEndpoint(value) {
  if (!value) return null;
  try {
    const parsed = new URL(value);
    parsed.username = '';
    parsed.password = '';
    parsed.search = '';
    parsed.hash = '';
    return parsed.toString();
  } catch {
    return String(value).replace(/\/\/[^/@]+@/g, '//[redacted]@').replace(/[?&](?:token|key|secret|password)=[^&]*/gi, '');
  }
}

export function writeReport(output, report, options = {}) {
  if (!output) return undefined;
  return writeJson(output, report, options);
}

/**
 * Resolve a repository-scoped input or output path without following a
 * symlink outside the repository.  Producers use this helper before reading
 * or writing any artifact so a malformed CLI path cannot escape the project.
 */
export function repoPathInfo(value, {label = 'path', mustExist = false, kind = 'file'} = {}) {
  const text = String(value ?? '').trim();
  if (!text || text.includes('\0') || /[\r\n]/.test(text)) throw new Error(`${label} 路径为空或包含控制字符`);
  const absolute = resolvePath(text);
  const relative = repoRelative(absolute);
  if (!relative || relative === '.') throw new Error(`${label} 不能指向仓库根目录`);
  const exists = fs.existsSync(absolute);
  if (!exists) {
    if (mustExist) throw new Error(`${label} 不存在: ${relative}`);
    let parent = path.dirname(absolute);
    while (!fs.existsSync(parent)) {
      const next = path.dirname(parent);
      if (next === parent) throw new Error(`${label} 父目录无效`);
      parent = next;
    }
    const realParent = fs.realpathSync(parent);
    if (!isWithinPath(repoRoot, realParent)) throw new Error(`${label} 父目录越出仓库边界`);
    return {absolute, relative, real: null, exists: false};
  }
  const statLink = fs.lstatSync(absolute);
  if (statLink.isSymbolicLink()) throw new Error(`${label} 不得是符号链接`);
  const real = fs.realpathSync(absolute);
  if (!isWithinPath(repoRoot, real)) throw new Error(`${label} 越出仓库边界`);
  const stat = fs.statSync(real);
  if (kind === 'file' && !stat.isFile()) throw new Error(`${label} 不是普通文件`);
  if (kind === 'directory' && !stat.isDirectory()) throw new Error(`${label} 不是目录`);
  return {absolute, relative, real, exists: true};
}

/** Repository-scoped destination descriptor used by no-clobber producers. */
export function repoOutputInfo(value, label = 'output') {
  return repoPathInfo(value, {label, mustExist: false, kind: 'file'});
}

export function isWithinPath(root, target) {
  const relative = path.relative(path.resolve(root), path.resolve(target));
  return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}

export function parseJsonBlock(markdown) {
  const match = markdown.match(/```json\s*([\s\S]*?)```/i);
  if (!match) throw new Error('未找到 JSON fenced block');
  return JSON.parse(match[1]);
}

export function platformSummary() {
  return {platform: process.platform, arch: process.arch, node: process.version, hostname: os.hostname()};
}
