import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

import {
  currentCommit,
  generatedAt,
  repoPathInfo,
  repoRoot,
  writeAtomic,
  writeJson
} from '../../tools/lib/io.mjs'

export const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const require = createRequire(import.meta.url)
const DEFAULT_OUTPUTS = {
  lint: 'artifacts/v1.1/test/frontend-lint-report.json',
  typecheck: 'artifacts/v1.1/test/frontend-typecheck-report.json'
}

/** Parse only the small, explicit CLI surface used by quality wrappers. */
export function parseArgs(argv) {
  const result = { _: [] }
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index]
    if (token === '--') {
      result._.push(...argv.slice(index + 1))
      break
    }
    if (!token.startsWith('--')) {
      result._.push(token)
      continue
    }
    const body = token.slice(2)
    const equals = body.indexOf('=')
    if (equals >= 0) {
      result[body.slice(0, equals)] = body.slice(equals + 1)
      continue
    }
    const next = argv[index + 1]
    if (next === undefined || next.startsWith('--')) {
      result[body] = true
    } else {
      result[body] = next
      index += 1
    }
  }
  return result
}

export function boolArg(value) {
  return value === true || ['1', 'true', 'yes', 'y', 'on'].includes(String(value ?? '').toLowerCase())
}

const BOOLEAN_ARGUMENTS = new Set(['1', '0', 'true', 'false', 'yes', 'no', 'y', 'n', 'on', 'off'])

/**
 * Validate the wrapper-owned CLI surface before parseArgs can silently turn a
 * typo into an ignored option.  The E2E wrapper may opt into a literal `--`
 * passthrough for Playwright flags; quality tools intentionally expose no
 * passthrough because their reports must be reproducible.
 */
export function validateCliArgs(argv, schema, { allowPassthrough = false } = {}) {
  const tokens = Array.isArray(argv) ? argv.map(String) : []
  const errors = []
  let separatorIndex = -1

  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index]
    if (token === '--') {
      separatorIndex = index
      if (!allowPassthrough && index < tokens.length - 1) {
        errors.push('不支持 `--` 后的位置参数或工具参数')
      }
      break
    }
    if (!token.startsWith('--')) {
      errors.push(`不支持的位置参数: ${token}`)
      continue
    }

    const body = token.slice(2)
    const equals = body.indexOf('=')
    const key = equals >= 0 ? body.slice(0, equals) : body
    const argument = schema && Object.hasOwn(schema, key) ? schema[key] : null
    if (!argument) {
      errors.push(`未知参数: --${key}`)
      continue
    }

    if (equals >= 0) {
      const value = body.slice(equals + 1)
      if (argument.type === 'value' && !value.trim()) errors.push(`参数 --${key} 必须提供值`)
      if (argument.type === 'boolean' && !BOOLEAN_ARGUMENTS.has(value.toLowerCase())) {
        errors.push(`参数 --${key} 必须是布尔值`)
      }
      continue
    }

    if (argument.type === 'value') {
      const next = tokens[index + 1]
      if (next === undefined || next === '--' || next.startsWith('--')) {
        errors.push(`参数 --${key} 必须提供值`)
      } else {
        index += 1
      }
    } else {
      // Flags normally stand alone, but accepting an explicit boolean value
      // keeps `--allow-pending false` deterministic instead of treating
      // `false` as an accidental positional argument.
      const next = tokens[index + 1]
      if (next && !next.startsWith('--') && BOOLEAN_ARGUMENTS.has(next.toLowerCase())) index += 1
    }
  }

  if (errors.length > 0) throw new Error(`CLI 参数校验失败：${errors.join('；')}`)
  return separatorIndex >= 0 ? tokens.slice(separatorIndex + 1) : []
}

function packageRootFrom(entry) {
  let cursor = path.dirname(entry)
  while (cursor !== path.dirname(cursor)) {
    const metadata = path.join(cursor, 'package.json')
    if (fs.existsSync(metadata)) {
      try {
        const parsed = JSON.parse(fs.readFileSync(metadata, 'utf8'))
        if (parsed.name) return { root: cursor, metadata: parsed }
      } catch {
        return null
      }
    }
    cursor = path.dirname(cursor)
  }
  return null
}

/** Resolve an installed local package without invoking npm/npx or the network. */
export function findPackage(name) {
  try {
    const entry = require.resolve(name, { paths: [frontendRoot] })
    const resolved = packageRootFrom(entry)
    if (!resolved || resolved.metadata.name !== name) return null
    return { name, ...resolved }
  } catch {
    return null
  }
}

export function packageBin(pkg) {
  if (!pkg) return null
  const declared = pkg.metadata.bin
  const relative = typeof declared === 'string' ? declared : declared?.[pkg.name]
  if (!relative) return null
  const candidate = path.resolve(pkg.root, relative)
  if (!candidate.startsWith(`${pkg.root}${path.sep}`) || !fs.existsSync(candidate)) return null
  return candidate
}

export function loadToolContract() {
  const contractPath = path.join(frontendRoot, 'quality-tools.json')
  try {
    return JSON.parse(fs.readFileSync(contractPath, 'utf8'))
  } catch (error) {
    throw new Error(`quality-tools.json 无法读取或解析：${error.message}`)
  }
}

export function redactOutput(value) {
  const ansiSgr = new RegExp(`${String.fromCharCode(27)}\\[[0-9;]*m`, 'g')
  let text = String(value ?? '').replace(ansiSgr, '')
  text = text.replace(/(\b(?:api[_-]?key|token|secret|password|authorization)\b\s*[:=]\s*)([^\s,;]+)/gi, '$1[REDACTED]')
  text = text.replace(/(Bearer\s+)[A-Za-z0-9._~+/=-]+/gi, '$1[REDACTED]')
  text = text.replace(/([?&](?:api[_-]?key|token|secret|password)=)[^&\s]+/gi, '$1[REDACTED]')
  return text.length > 12000 ? `${text.slice(0, 12000)}\n[TRUNCATED]` : text
}

function relativeOutput(value, fallback) {
  const candidate = String(value || fallback)
  const descriptor = repoPathInfo(candidate, { label: 'quality output', kind: 'file' })
  return descriptor.relative
}

function logOutput(reportRelative) {
  const parsed = path.parse(reportRelative)
  return path.join(parsed.dir, `${parsed.name}.log`).split(path.sep).join('/')
}

export function assertNoClobber(paths, noClobber) {
  if (!noClobber) return
  for (const value of paths) {
    const absolute = path.resolve(repoRoot, value)
    if (fs.existsSync(absolute)) throw new Error(`输出已存在（--no-clobber）: ${value}`)
  }
}

function missingDependencies(toolSpec) {
  let manifest
  try {
    manifest = JSON.parse(fs.readFileSync(path.join(frontendRoot, 'package.json'), 'utf8'))
  } catch (error) {
    return [{ name: 'package.json', reason: 'package-manifest-invalid', detail: error.message }]
  }
  const declared = new Set([
    ...Object.keys(manifest.dependencies || {}),
    ...Object.keys(manifest.devDependencies || {}),
    ...Object.keys(manifest.optionalDependencies || {}),
    ...Object.keys(manifest.peerDependencies || {})
  ])
  return (toolSpec.packages || []).flatMap(name => {
    if (!declared.has(name)) return [{ name, reason: 'package-not-declared' }]
    return findPackage(name) ? [] : [{ name, reason: 'package-not-installed' }]
  })
}

function missingConfigs(toolSpec) {
  return (toolSpec.configs || []).map(file => {
    const descriptor = repoPathInfo(path.join(frontendRoot, file), { label: `quality config ${file}`, kind: 'file' })
    return descriptor.exists ? null : { file, reason: 'config-not-found' }
  }).filter(Boolean)
}

function writeArtifacts(reportRelative, report, logText, noClobber) {
  assertNoClobber([reportRelative, logOutput(reportRelative)], noClobber)
  writeJson(reportRelative, report, { noClobber })
  if (logText !== undefined) writeAtomic(logOutput(reportRelative), `${redactOutput(logText)}\n`, { noClobber })
}

function baseReport(kind, output, toolSpec) {
  return {
    schemaVersion: 'v1.1',
    producer: `frontend-${kind}-quality`,
    generatedAt: generatedAt(),
    gitCommit: currentCommit(),
    tool: kind,
    output,
    requiredPackages: toolSpec.packages || [],
    requiredConfigs: toolSpec.configs || [],
    policy: '缺少真实质量工具或目标配置时保持 environment-pending；不自动安装依赖，不把静态脚本检查伪装成通过。'
  }
}

function runInstalledTool(kind, toolSpec, args) {
  const entryPackage = findPackage(toolSpec.runner)
  const executable = packageBin(entryPackage)
  if (!executable) return { status: 'environment-pending', reason: 'runner-bin-not-found', exitCode: null }

  const commandArgs = kind === 'lint'
    ? ['--max-warnings=0', '.', ...args]
    : ['--noEmit', ...args]
  const started = Date.now()
  const result = spawnSync(process.execPath, [executable, ...commandArgs], {
    cwd: frontendRoot,
    env: { ...process.env, CI: process.env.CI || '1' },
    encoding: 'utf8',
    maxBuffer: 2 * 1024 * 1024,
    windowsHide: true
  })
  const exitCode = typeof result.status === 'number' ? result.status : 1
  const output = `${result.stdout || ''}${result.stderr || ''}`
  const browserLikeMissing = /cannot find module|module not found|executable.*not found/i.test(output)
  return {
    status: browserLikeMissing ? 'environment-pending' : (exitCode === 0 ? 'passed' : 'failed'),
    reason: browserLikeMissing ? 'runtime-dependency-missing' : undefined,
    exitCode,
    durationMs: Date.now() - started,
    command: [process.execPath, executable, ...commandArgs].map(String),
    output
  }
}

export function runQualityCheck(kind, argv = process.argv.slice(2)) {
  validateCliArgs(argv, {
    output: { type: 'value' },
    'no-clobber': { type: 'boolean' },
    'allow-pending': { type: 'boolean' },
    'probe-only': { type: 'boolean' }
  })
  const args = parseArgs(argv)
  const contract = loadToolContract()
  const toolSpec = contract.tools?.[kind]
  if (!toolSpec) throw new Error(`未登记质量工具：${kind}`)
  const output = relativeOutput(args.output, DEFAULT_OUTPUTS[kind])
  const noClobber = boolArg(args['no-clobber'])
  // Fail before probing or invoking a real tool so --no-clobber cannot leave
  // partial/side-effect artifacts behind when the destination is occupied.
  assertNoClobber([output, logOutput(output)], noClobber)
  const report = baseReport(kind, output, toolSpec)
  const missing = [...missingDependencies(toolSpec), ...missingConfigs(toolSpec)]
  if (missing.length) {
    report.status = 'environment-pending'
    report.passed = false
    report.exitCode = 2
    report.effectiveExitCode = boolArg(args['allow-pending']) ? 0 : 2
    report.missing = missing
    report.commandExecuted = false
    writeArtifacts(output, report, `environment-pending: ${JSON.stringify(missing)}`, noClobber)
    return report.effectiveExitCode
  }
  if (boolArg(args['probe-only'])) {
    report.status = 'ready-to-run'
    report.passed = false
    report.exitCode = 0
    report.commandExecuted = false
    report.probe = 'all declared packages and configs are present'
    writeArtifacts(output, report, 'ready-to-run: dependency/config probe only', noClobber)
    return 0
  }
  const execution = runInstalledTool(kind, toolSpec, args._)
  Object.assign(report, execution)
  report.passed = execution.status === 'passed'
  report.commandExecuted = execution.status !== 'environment-pending'
  const effectiveExitCode = execution.status === 'passed'
    ? 0
    : execution.status === 'environment-pending' && boolArg(args['allow-pending']) ? 0 : execution.status === 'environment-pending' ? 2 : 1
  report.effectiveExitCode = effectiveExitCode
  writeArtifacts(output, report, execution.output || execution.reason || '', noClobber)
  return effectiveExitCode
}

export function safeHash(value) {
  return crypto.createHash('sha256').update(String(value), 'utf8').digest('hex')
}

export function hostSummary() {
  return { node: process.version, platform: process.platform, arch: process.arch, cwd: frontendRoot, tempDir: os.tmpdir() }
}

export { DEFAULT_OUTPUTS, currentCommit, generatedAt, repoRoot }
