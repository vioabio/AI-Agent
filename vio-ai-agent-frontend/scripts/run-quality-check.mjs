#!/usr/bin/env node
import { boolArg, parseArgs, runQualityCheck } from './quality-runner.mjs'

const [kind, ...rest] = process.argv.slice(2)
if (!['lint', 'typecheck'].includes(kind)) {
  console.error('用法：node scripts/run-quality-check.mjs <lint|typecheck> [--probe-only] [--allow-pending] [--output <repo-relative-path>] [--no-clobber]')
  process.exitCode = 64
} else {
  try {
    const code = runQualityCheck(kind, rest)
    process.exitCode = code
    if (code === 2 && !boolArg(parseArgs(rest)['allow-pending'])) {
      console.error(`${kind}: environment-pending（质量工具/配置或运行环境未就绪；报告已写入）`)
    }
  } catch (error) {
    console.error(`${kind}: quality wrapper blocked: ${error.message}`)
    process.exitCode = 1
  }
}
