import fs from 'node:fs';
import path from 'node:path';
import {
  listArg,
  placeholder,
  repoRelative,
  repoRoot,
  resolvePath,
  sha256File
} from './io.mjs';

/**
 * Common, dependency-free parsing helpers used by the N7 static gates.
 *
 * These helpers deliberately do not attempt to be a complete Dockerfile or
 * Compose parser.  They extract the security-sensitive image/build fields and
 * fail closed when a value is dynamic or ambiguous.  Runtime Docker/registry
 * validation is a separate, environment-bound step.
 */

export function collectFileArgs(args, {minimum = 2} = {}) {
  const raw = args.file;
  const values = listArg(raw);
  const errors = [];
  const rawValues = Array.isArray(raw) ? raw : (raw === undefined ? [] : [raw]);
  if (values.length === 0) errors.push('--file 至少需要一个文件');
  // Count CLI occurrences, not comma-expanded listArg values.  A comma is
  // intentionally rejected below and must not satisfy the minimum-file gate.
  if (rawValues.length > 0 && rawValues.length < minimum) {
    errors.push(`--file 至少需要 ${minimum} 个独立参数；禁止只校验部分供应链输入`);
  }
  if (raw !== undefined && !Array.isArray(raw) && typeof raw === 'boolean') {
    errors.push('--file 缺少路径值');
  }
  if (rawValues.some((value) => String(value).includes(','))) errors.push('--file 必须重复传入，禁止逗号拼接路径');
  return {values, errors};
}

/** Resolve a file and ensure both symlink and parent stay inside the repo. */
export function inputFile(value, label = value) {
  const text = String(value ?? '');
  if (!text || text.includes(',')) throw new Error(`${label} 路径无效`);
  const absolute = resolvePath(text);
  const relative = repoRelative(absolute);
  if (!relative || relative === '.') throw new Error(`${label} 不能指向仓库根目录`);
  if (!fs.existsSync(absolute) || !fs.statSync(absolute).isFile()) {
    throw new Error(`${label} 不存在或不是普通文件`);
  }
  const real = fs.realpathSync(absolute);
  if (!within(repoRoot, real) || !fs.statSync(real).isFile()) {
    throw new Error(`${label} 越出仓库边界`);
  }
  return {absolute, real, relative: relative.replaceAll(path.sep, '/'), sha256: sha256File(real)};
}

/** Resolve an output path while preventing writes outside the repository. */
export function outputFile(value, label = value) {
  const text = String(value ?? '');
  if (!text) throw new Error(`${label} 不能为空`);
  const absolute = resolvePath(text);
  const relative = repoRelative(absolute);
  if (!relative || relative === '.') throw new Error(`${label} 不能覆盖仓库根目录`);
  let parent = path.dirname(absolute);
  while (!fs.existsSync(parent)) {
    const next = path.dirname(parent);
    if (next === parent) throw new Error(`${label} 父目录无效`);
    parent = next;
  }
  const realParent = fs.realpathSync(parent);
  if (!within(repoRoot, realParent)) throw new Error(`${label} 父目录越出仓库边界`);
  if (fs.existsSync(absolute)) {
    const real = fs.realpathSync(absolute);
    if (!within(repoRoot, real) || !fs.statSync(real).isFile()) throw new Error(`${label} 不是仓库内普通文件`);
  }
  return {absolute, relative: relative.replaceAll(path.sep, '/')};
}

export function readInput(info) {
  return fs.readFileSync(info.real, 'utf8');
}

export function sourceInfo(info) {
  return {path: info.relative, sha256: info.sha256, bytes: fs.statSync(info.real).size};
}

/** Strip comments outside quotes; enough for image/build scalar extraction. */
export function withoutYamlComment(line) {
  let quote = null;
  for (let index = 0; index < line.length; index += 1) {
    const ch = line[index];
    if ((ch === '"' || ch === "'") && line[index - 1] !== '\\') {
      quote = quote === ch ? null : (quote ?? ch);
    }
    if (ch === '#' && !quote && (index === 0 || /\s/.test(line[index - 1]))) return line.slice(0, index);
  }
  return line;
}

export function unquote(value) {
  const text = String(value ?? '').trim();
  if (text.length >= 2 && ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith("'") && text.endsWith("'")))) {
    return text.slice(1, -1).trim();
  }
  return text;
}

export function parseImageReference(value) {
  const raw = unquote(value);
  const result = {
    raw,
    image: raw || null,
    registry: null,
    repository: null,
    tag: null,
    digest: null,
    immutable: false,
    valid: true,
    reason: null
  };
  if (!raw || placeholder(raw) || raw.includes('${') || /\s/.test(raw)) {
    result.valid = false;
    result.reason = '镜像引用为空、含动态插值或占位符';
    return result;
  }
  if (raw === 'scratch') {
    result.registry = null;
    result.repository = 'scratch';
    result.immutable = true;
    return result;
  }
  let name = raw;
  const digestMatch = name.match(/^(.+)@(sha256:([a-f0-9]{64}))$/i);
  if (digestMatch) {
    name = digestMatch[1];
    result.digest = digestMatch[2].toLowerCase();
    result.immutable = true;
  } else if (name.includes('@')) {
    result.valid = false;
    result.reason = '仅支持 sha256 digest';
    return result;
  }
  if (!/^[a-z0-9][a-z0-9._/-]*(?::[a-z0-9_][a-z0-9_.-]*)?$/i.test(name)) {
    result.valid = false;
    result.reason = '镜像名称格式无效';
    return result;
  }
  const slash = name.indexOf('/');
  const first = slash < 0 ? name : name.slice(0, slash);
  const hasRegistry = slash > 0 && (first.includes('.') || first.includes(':') || first === 'localhost');
  result.registry = hasRegistry ? first.toLowerCase() : 'docker.io';
  let repository = hasRegistry ? name.slice(slash + 1) : name;
  const lastSlash = repository.lastIndexOf('/');
  const colon = repository.lastIndexOf(':');
  if (colon > lastSlash) {
    result.tag = repository.slice(colon + 1);
    repository = repository.slice(0, colon);
  }
  if (!result.tag && !result.digest) result.tag = 'latest';
  result.repository = !hasRegistry && !repository.includes('/') ? `library/${repository}` : repository;
  return result;
}

export function parseDockerfile(text, sourceFile) {
  const images = [];
  const errors = [];
  const lines = String(text).split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    const original = lines[index];
    const line = original.trim();
    if (!line || line.startsWith('#')) continue;
    const match = line.match(/^FROM(?:\s+--platform=\S+)?\s+([^\s]+)(?:\s+AS\s+[^\s]+)?\s*$/i);
    if (!match) {
      if (/^FROM\b/i.test(line)) errors.push(`${sourceFile}:${index + 1}: FROM 语法无法静态解析`);
      continue;
    }
    const parsed = parseImageReference(match[1]);
    images.push({...parsed, sourceFile, line: index + 1, kind: 'dockerfile-from'});
    if (!parsed.valid) errors.push(`${sourceFile}:${index + 1}: ${parsed.reason}`);
  }
  if (images.length === 0) errors.push(`${sourceFile}: 未发现 FROM 基础镜像`);
  return {images, builds: [], errors};
}

export function parseCompose(text, sourceFile) {
  const images = [];
  const builds = [];
  const errors = [];
  const lines = String(text).split(/\r?\n/);
  let currentService = null;
  for (let index = 0; index < lines.length; index += 1) {
    const original = lines[index];
    const line = withoutYamlComment(original);
    if (!line.trim()) continue;
    const serviceMatch = line.match(/^\s{2}([A-Za-z0-9][A-Za-z0-9_.-]*):\s*$/);
    if (serviceMatch) currentService = serviceMatch[1];
    const imageMatch = line.match(/^\s*image\s*:\s*(.*?)\s*$/i);
    if (imageMatch) {
      const parsed = parseImageReference(imageMatch[1]);
      images.push({...parsed, sourceFile, line: index + 1, kind: 'compose-image', service: currentService});
      if (!parsed.valid) errors.push(`${sourceFile}:${index + 1}: ${parsed.reason}`);
    }
    const buildMatch = line.match(/^\s*build\s*:\s*(.*?)\s*$/i);
    if (buildMatch) {
      const value = unquote(buildMatch[1]);
      builds.push({sourceFile, line: index + 1, service: currentService, value: value || null, mapping: !value});
    }
  }
  if (images.length === 0 && builds.length === 0) errors.push(`${sourceFile}: 未发现 image/build 字段`);
  return {images, builds, errors};
}

export function looksLikeDockerfile(info, text) {
  const base = path.basename(info.relative).toLowerCase();
  if (base === 'dockerfile' || base.startsWith('dockerfile.')) return true;
  return /^\s*FROM\b/im.test(text) && !/^\s*services\s*:/im.test(text);
}

export function imageSummary(item) {
  return {
    image: item.image,
    registry: item.registry,
    repository: item.repository,
    tag: item.tag,
    digest: item.digest,
    immutable: item.immutable,
    valid: item.valid,
    kind: item.kind,
    service: item.service ?? null,
    sourceFile: item.sourceFile,
    line: item.line
  };
}

export function isDigestImage(value) {
  return /^.+@sha256:[a-f0-9]{64}$/i.test(String(value ?? '').trim());
}

export function isSafeProjectName(value) {
  return /^[a-z0-9][a-z0-9_-]{1,62}$/i.test(String(value ?? '').trim()) && !placeholder(value);
}

function within(root, target) {
  const relative = path.relative(path.resolve(root), path.resolve(target));
  return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}
