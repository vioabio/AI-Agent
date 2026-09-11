#!/usr/bin/env node
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

import {
  boolArg,
  currentCommit,
  frontendRoot,
  generatedAt,
  loadToolContract,
  packageBin,
  parseArgs,
  findPackage,
  redactOutput,
  repoRoot,
  assertNoClobber,
  validateCliArgs
} from './quality-runner.mjs'
import { redactEndpoint, repoPathInfo, writeAtomic, writeJson } from '../../tools/lib/io.mjs'

const DEFAULT_JSON = 'artifacts/v1.1/test/n5-frontend-e2e-report.json'
const DEFAULT_HTML = 'artifacts/v1.1/test/n5-frontend-e2e-report.html'
const require = createRequire(import.meta.url)
const BLOCKED_PLAYWRIGHT_FLAGS = [
  // Playwright accepts both `-c=...` and the compact `-c...` spelling.
  // Reject both forms so a caller cannot swap in a different config.
  /^-c(?:=|$|\S)/i,
  /^--config(?:=|$)/i,
  /^--reporter(?:=|$)/i,
  /^--output(?:=|$)/i,
  /^--pass-with-no-tests(?:=|$)/i,
  /^--list(?:=|$)/i,
  /^--ui(?:=|$)/i,
  /^--debug(?:=|$)/i
]

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

export function parseTarget(args) {
  const baseUrl = String(args['base-url'] || process.env.N5_E2E_BASE_URL || 'http://127.0.0.1:3000').trim()
  let parsed
  try {
    parsed = new URL(baseUrl)
  } catch {
    return { status: 'blocked', reason: 'invalid-base-url', baseUrl: null, target: null }
  }
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password) {
    return { status: 'blocked', reason: 'base-url-must-be-http-without-credentials', baseUrl: redactEndpoint(baseUrl), target: null }
  }
  if ([...parsed.searchParams.keys()].some(key => /key|token|secret|password|authorization/i.test(key))) {
    return { status: 'blocked', reason: 'base-url-contains-sensitive-query-key', baseUrl: redactEndpoint(baseUrl), target: null }
  }
  // Hash fragments are not sent to the server, but they remain in browser
  // history and traces. Reject credential-like fragments before launching
  // Playwright so an operator cannot accidentally put a token in the target.
  let decodedHash = parsed.hash
  try { decodedHash = decodeURIComponent(decodedHash) } catch { /* keep raw hash for the conservative check */ }
  if (/(?:api[_-]?key|token|secret|password|authorization)\s*=/i.test(decodedHash)) {
    return { status: 'blocked', reason: 'base-url-contains-sensitive-fragment', baseUrl: redactEndpoint(baseUrl), target: null }
  }
  const explicit = String(args.target || process.env.N5_E2E_TARGET || '').trim().toLowerCase()
  const hostname = parsed.hostname.replace(/^\[|\]$/g, '').toLowerCase()
  const local = ['localhost', '127.0.0.1', '::1'].includes(hostname)
  const target = explicit || (local ? 'local' : 'unknown')
  if (!['local', 'staging'].includes(target)) {
    return { status: 'environment-pending', reason: 'target-must-be-local-or-explicit-staging', baseUrl: redactEndpoint(baseUrl), target }
  }
  if (!local && target !== 'staging') {
    return { status: 'environment-pending', reason: 'non-local-target-not-allowlisted', baseUrl: redactEndpoint(baseUrl), target }
  }
  if (target === 'local' && parsed.protocol !== 'http:') {
    return { status: 'blocked', reason: 'local-target-must-use-http', baseUrl: redactEndpoint(baseUrl), target }
  }
  if (target === 'staging' && !boolArg(args['allow-staging']) && process.env.N5_E2E_ALLOW_STAGING !== 'true') {
    return { status: 'environment-pending', reason: 'staging-requires-explicit-allowance', baseUrl: redactEndpoint(baseUrl), target }
  }
  if (target === 'staging' && /prod(?:uction)?|live/i.test(parsed.hostname)) {
    return { status: 'blocked', reason: 'production-like-host-rejected', baseUrl: redactEndpoint(baseUrl), target }
  }
  return {
    status: 'ready',
    // Keep the exact validated URL only in memory for Playwright. Reports use
    // the redacted form so query/hash data never becomes durable evidence.
    executionBaseUrl: parsed.toString(),
    baseUrl: redactEndpoint(baseUrl),
    target
  }
}

function outputDescriptor(value, fallback, label) {
  const selected = String(value || fallback)
  return repoPathInfo(selected, { label, kind: 'file' }).relative
}

function sameOutputPath(left, right) {
  const normalize = value => path.resolve(repoRoot, value)
    .replaceAll('\\', '/')
    .toLowerCase()
  return normalize(left) === normalize(right)
}

function assertDistinctOutputs(paths) {
  const normalized = paths.map(value => path.resolve(repoRoot, value).replaceAll('\\', '/').toLowerCase())
  if (new Set(normalized).size !== normalized.length) {
    throw new Error('e2e JSON、HTML 和日志 output 不能复用同一文件')
  }
}

function validatePlaywrightPassthrough(args) {
  for (const token of args) {
    if (BLOCKED_PLAYWRIGHT_FLAGS.some(pattern => pattern.test(String(token)))) {
      throw new Error(`禁止透传 Playwright 完整性参数: ${token}`)
    }
  }
}

function writeHtml(relative, report, noClobber) {
  const rows = Object.entries(report.summary || {}).map(([key, value]) => `<tr><th>${escapeHtml(key)}</th><td>${escapeHtml(value)}</td></tr>`).join('')
  const body = `<!doctype html><meta charset="utf-8"><title>N5 Frontend E2E</title><style>body{font:14px system-ui;margin:2rem;background:#111;color:#eee}table{border-collapse:collapse}th,td{border:1px solid #555;padding:.4rem .7rem;text-align:left}.pending{color:#ffd666}.passed{color:#95de64}.failed,.blocked{color:#ff7875}</style><h1>N5 前端 E2E 报告</h1><p class="${escapeHtml(report.status)}">状态：<strong>${escapeHtml(report.status)}</strong></p><table>${rows}</table><p>${escapeHtml(report.policy || '')}</p>`
  writeAtomic(relative, body, { noClobber })
}

function writePending(jsonPath, htmlPath, report, noClobber) {
  const parsed = path.parse(jsonPath)
  const logPath = path.join(parsed.dir, `${parsed.name}.log`).split(path.sep).join('/')
  assertNoClobber([jsonPath, htmlPath, logPath], noClobber)
  writeJson(jsonPath, report, { noClobber })
  writeHtml(htmlPath, report, noClobber)
  writeAtomic(logPath, `${redactOutput(report.log || report.reason || JSON.stringify(report.summary || {}))}\n`, { noClobber })
}

function announcePending(report) {
  if (report.status === 'environment-pending') {
    console.error(`test:e2e: environment-pending (${redactOutput(report.reason || 'quality environment is not ready')})`)
  }
}

function parsePlaywrightJson(file) {
  if (!fs.existsSync(file)) return null
  try { return JSON.parse(fs.readFileSync(file, 'utf8')) } catch { return null }
}

/**
 * Playwright packages and browser binaries have separate lifecycles. Detect
 * the configured Chromium executable before starting Vite/Playwright so a
 * freshly installed package cannot leave the quality command waiting through
 * every test timeout. This check is local-only and never downloads a browser.
 */
function browserExecutableAvailable() {
  try {
    const { chromium } = require('playwright')
    const executable = chromium?.executablePath?.()
    return typeof executable === 'string' && executable.length > 0 && fs.existsSync(executable)
  } catch {
    return false
  }
}

function run(argv = process.argv.slice(2)) {
  const playwrightArgs = validateCliArgs(argv, {
    'base-url': { type: 'value' },
    target: { type: 'value' },
    'allow-staging': { type: 'boolean' },
    'allow-pending': { type: 'boolean' },
    'probe-only': { type: 'boolean' },
    output: { type: 'value' },
    html: { type: 'value' },
    'no-clobber': { type: 'boolean' }
  }, { allowPassthrough: true })
  validatePlaywrightPassthrough(playwrightArgs)
  const args = parseArgs(argv)
  const contract = loadToolContract()
  const spec = contract.tools?.e2e
  if (!spec) throw new Error('quality-tools.json 未登记 e2e 工具')
  const jsonPath = outputDescriptor(args.output, DEFAULT_JSON, 'e2e JSON output')
  const htmlPath = outputDescriptor(args.html, DEFAULT_HTML, 'e2e HTML output')
  const logPath = path.join(path.parse(jsonPath).dir, `${path.parse(jsonPath).name}.log`).split(path.sep).join('/')
  if (sameOutputPath(jsonPath, htmlPath)) throw new Error('e2e JSON output 与 HTML output 不能是同一文件')
  assertDistinctOutputs([jsonPath, htmlPath, logPath])
  const noClobber = boolArg(args['no-clobber'])
  // Reserve all final destinations before any browser/server process starts.
  assertNoClobber([jsonPath, htmlPath, logPath], noClobber)
  const target = parseTarget(args)
  const report = {
    schemaVersion: 'v1.1',
    producer: 'frontend-e2e-quality',
    generatedAt: generatedAt(),
    gitCommit: currentCommit(),
    target: target.target,
    baseUrl: target.baseUrl,
    output: jsonPath,
    htmlOutput: htmlPath,
    requiredPackages: spec.packages || [],
    requiredConfigs: spec.configs || [],
    commandExecuted: false,
    passed: false,
    policy: 'E2E 只允许 local 或显式获准 staging；缺 Playwright、浏览器或目标环境时保持 environment-pending，不自动安装、不访问生产。'
  }
  const missing = (spec.packages || []).filter(name => !findPackage(name)).map(name => ({ name, reason: 'package-not-installed' }))
  const missingConfigs = (spec.configs || []).filter(file => !fs.existsSync(path.join(frontendRoot, file))).map(file => ({ file, reason: 'config-not-found' }))
  if (spec.requiredBrowser && missing.length === 0 && !browserExecutableAvailable()) {
    missing.push({ name: 'chromium', reason: 'browser-executable-not-installed' })
  }
  if (missing.length || missingConfigs.length || target.status !== 'ready') {
    report.status = target.status === 'blocked' ? 'blocked' : 'environment-pending'
    report.reason = target.reason
      || missing.find(item => item.reason === 'browser-executable-not-installed')?.reason
      || 'quality-dependency-or-config-missing'
    report.missing = [...missing, ...missingConfigs]
    report.exitCode = report.status === 'blocked' ? 1 : 2
    report.summary = { target: report.target || 'unknown', missing: report.missing.length, command: 'not executed' }
    writePending(jsonPath, htmlPath, report, noClobber)
    announcePending(report)
    return boolArg(args['allow-pending']) && report.status === 'environment-pending' ? 0 : report.exitCode
  }
  if (boolArg(args['probe-only'])) {
    report.status = 'ready-to-run'
    report.exitCode = 0
    report.summary = { target: report.target, missing: 0, command: 'probe only' }
    writePending(jsonPath, htmlPath, report, noClobber)
    return 0
  }
  const runner = packageBin(findPackage(spec.runner))
  if (!runner) {
    report.status = 'environment-pending'
    report.reason = 'playwright-runner-bin-not-found'
    report.exitCode = 2
    report.summary = { target: report.target, missing: 1, command: 'not executed' }
    writePending(jsonPath, htmlPath, report, noClobber)
    announcePending(report)
    return boolArg(args['allow-pending']) ? 0 : 2
  }
  fs.mkdirSync(path.join(repoRoot, 'tmp'), { recursive: true })
  const temporaryRoot = fs.mkdtempSync(path.join(repoRoot, 'tmp', 'n5-e2e-'))
  const rawJson = path.join(temporaryRoot, 'playwright.json')
  const commandArgs = ['test', '--config=playwright.config.js', ...playwrightArgs]
  const started = Date.now()
  const result = spawnSync(process.execPath, [runner, ...commandArgs], {
    cwd: frontendRoot,
    env: {
      ...process.env,
      CI: process.env.CI || '1',
      N5_E2E_BASE_URL: target.executionBaseUrl,
      N5_E2E_TARGET: target.target,
      N5_E2E_JSON_REPORT: rawJson
    },
    encoding: 'utf8',
    maxBuffer: 8 * 1024 * 1024,
    windowsHide: true
  })
  const output = `${result.stdout || ''}${result.stderr || ''}`
  const exitCode = typeof result.status === 'number' ? result.status : 1
  const missingBrowser = /executable.*doesn't exist|browser.*not.*installed|playwright install|command not found|is not recognized|cannot find module|module not found/i.test(output)
  const raw = parsePlaywrightJson(rawJson)
  try { fs.rmSync(temporaryRoot, { recursive: true, force: true }) } catch { /* best effort cleanup */ }
  report.status = missingBrowser ? 'environment-pending' : (exitCode === 0 ? 'passed' : 'failed')
  report.passed = report.status === 'passed'
  report.commandExecuted = true
  report.exitCode = exitCode
  report.durationMs = Date.now() - started
  // Playwright selection flags are operator input; redact them before they
  // become durable evidence (for example `--grep token=...`).
  report.command = [process.execPath, runner, ...commandArgs].map(value => redactOutput(String(value)))
  report.summary = raw?.stats ? {
    target: report.target,
    expected: raw.stats.expected ?? null,
    unexpected: raw.stats.unexpected ?? null,
    flaky: raw.stats.flaky ?? null,
    skipped: raw.stats.skipped ?? null,
    durationMs: raw.stats.duration ?? null
  } : { target: report.target, tests: null, command: 'playwright test' }
  report.rawResult = raw ? {
    suites: Array.isArray(raw.suites) ? raw.suites.length : null,
    errors: Array.isArray(raw.errors) ? raw.errors.length : null
  } : null
  report.log = redactOutput(output)
  writePending(jsonPath, htmlPath, report, noClobber)
  announcePending(report)
  return report.status === 'passed' ? 0 : report.status === 'environment-pending' ? 2 : 1
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  try {
    process.exitCode = run()
  } catch (error) {
    console.error(`test:e2e: quality wrapper blocked: ${error.message}`)
    process.exitCode = 1
  }
}
