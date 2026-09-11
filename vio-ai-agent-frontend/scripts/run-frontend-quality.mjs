#!/usr/bin/env node

/**
 * Run every frontend quality check and collect the result without short
 * circuiting on an environment-pending dependency.  A pending or failed
 * check still blocks the aggregate command unless --allow-pending is used
 * explicitly for a local probe.
 */
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

import {
  boolArg,
  currentCommit,
  findPackage,
  frontendRoot,
  generatedAt,
  packageBin,
  parseArgs,
  redactOutput,
  assertNoClobber,
  validateCliArgs
} from './quality-runner.mjs'
import { repoPathInfo, writeAtomic, writeJson } from '../../tools/lib/io.mjs'

export const DEFAULT_OUTPUT = 'artifacts/v1.1/test/frontend-quality-report.json'
const MISSING_RUNTIME = /cannot find module|module not found|executable.*(?:not found|doesn't exist)|browser.*not installed|playwright install|is not recognized|vite.*not recognized|command not found|\bENOENT\b/i
const PENDING_MARKER = /environment-pending|package-not-installed|runner-bin-not-found|runtime-dependency-missing/i
const CHECKS = Object.freeze([
  { name: 'lint', command: () => [process.execPath, path.join(frontendRoot, 'scripts', 'run-quality-check.mjs'), 'lint'] },
  { name: 'typecheck', command: () => [process.execPath, path.join(frontendRoot, 'scripts', 'run-quality-check.mjs'), 'typecheck'] },
  { name: 'security:contract', command: () => [process.execPath, path.join(frontendRoot, 'scripts', 'security-contract-check.mjs')] },
  { name: 'test:contract', command: contractTestCommand },
  { name: 'test:e2e', command: () => [process.execPath, path.join(frontendRoot, 'scripts', 'run-e2e.mjs')] },
  { name: 'build', command: buildCommand }
])

function contractTestCommand() {
  const testFiles = fs.readdirSync(path.join(frontendRoot, 'scripts'))
    .filter(name => name.endsWith('.test.mjs'))
    .sort()
    .map(name => path.join(frontendRoot, 'scripts', name))
  return [process.execPath, '--test', ...testFiles]
}

function buildCommand() {
  const installed = packageBin(findPackage('vite'))
  const executable = installed || path.join(frontendRoot, 'node_modules', 'vite', 'bin', 'vite.js')
  return [process.execPath, executable, 'build']
}

export function classifyCheckResult({ exitCode, output = '' }) {
  const numericExitCode = typeof exitCode === 'number' ? exitCode : 1
  const text = String(output ?? '')
  if (numericExitCode === 0) return 'passed'
  // Exit code 2 is not itself proof of a missing environment: type checkers
  // and CLIs may use it for real failures.  Require an explicit pending or
  // runtime-missing marker before allowing --allow-pending to soften a result.
  if (PENDING_MARKER.test(text) || MISSING_RUNTIME.test(text)) return 'environment-pending'
  return 'failed'
}

function logPath(output) {
  const parsed = path.parse(output)
  return path.join(parsed.dir, `${parsed.name}.log`).split(path.sep).join('/')
}

function normalizeSpawnResult(result) {
  const output = `${result?.stdout || ''}${result?.stderr || ''}${result?.error?.message || ''}`
  return {
    exitCode: typeof result?.status === 'number' ? result.status : 1,
    output,
    signal: result?.signal || null
  }
}

/**
 * Execute the fixed check list.  `spawn` is injectable so contract tests can
 * prove that a pending check does not prevent later checks from running.
 */
export function runFrontendQuality({
  spawn = spawnSync,
  output = DEFAULT_OUTPUT,
  allowPending = false,
  noClobber = false
} = {}) {
  // Preflight the aggregate destinations before running child checks.  This
  // keeps --no-clobber from allowing child checks to do work before the final
  // report write fails.
  assertNoClobber([output, logPath(output)], noClobber)
  const checks = []
  for (const check of CHECKS) {
    const command = check.command()
    const started = Date.now()
    let child
    try {
      child = spawn(command[0], command.slice(1), {
        cwd: frontendRoot,
        env: { ...process.env, CI: process.env.CI || '1' },
        encoding: 'utf8',
        maxBuffer: 8 * 1024 * 1024,
        windowsHide: true
      })
    } catch (error) {
      child = { status: null, stdout: '', stderr: error?.message || String(error) }
    }
    const normalized = normalizeSpawnResult(child)
    const status = classifyCheckResult(normalized)
    checks.push({
      name: check.name,
      command,
      status,
      passed: status === 'passed',
      exitCode: normalized.exitCode,
      signal: normalized.signal,
      durationMs: Date.now() - started,
      output: redactOutput(normalized.output)
    })
  }

  const hasFailure = checks.some(check => check.status === 'failed')
  const hasPending = checks.some(check => check.status === 'environment-pending')
  const status = hasFailure ? 'failed' : hasPending ? 'environment-pending' : 'passed'
  const exitCode = status === 'passed' ? 0 : status === 'environment-pending' ? 2 : 1
  const effectiveExitCode = allowPending && status === 'environment-pending' ? 0 : exitCode
  const report = {
    schemaVersion: 'v1.1',
    producer: 'frontend-quality',
    generatedAt: generatedAt(),
    gitCommit: currentCommit(),
    output,
    allowPending,
    commandExecuted: true,
    status,
    passed: status === 'passed',
    exitCode,
    effectiveExitCode,
    checks,
    policy: '前端质量检查逐项执行；environment-pending/failed 不能伪装成 passed，只有显式 --allow-pending 才可将待验收环境用于本地探针。'
  }
  writeJson(output, report, { noClobber })
  writeAtomic(logPath(output), `${checks.map(check => `[${check.status}] ${check.name}\n${check.output}`).join('\n')}\n`, { noClobber })
  return { code: effectiveExitCode, report }
}

function main(argv = process.argv.slice(2)) {
  validateCliArgs(argv, {
    output: { type: 'value' },
    'allow-pending': { type: 'boolean' },
    'no-clobber': { type: 'boolean' }
  })
  const args = parseArgs(argv)
  const outputInput = String(args.output || DEFAULT_OUTPUT)
  const output = repoPathInfo(outputInput, { label: 'frontend quality output', kind: 'file' }).relative
  const result = runFrontendQuality({
    output,
    allowPending: boolArg(args['allow-pending']),
    noClobber: boolArg(args['no-clobber'])
  })
  console.log(JSON.stringify({
    output,
    status: result.report.status,
    passed: result.report.passed,
    effectiveExitCode: result.code,
    checks: result.report.checks.map(check => ({ name: check.name, status: check.status, exitCode: check.exitCode }))
  }, null, 2))
  return result.code
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  try {
    process.exitCode = main()
  } catch (error) {
    console.error(`quality:frontend: quality wrapper blocked: ${error.message}`)
    process.exitCode = 1
  }
}
