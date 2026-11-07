import { normalizeTaskBudget } from '../../vio-ai-agent-frontend/src/api/model-usage.js'
import { existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { resolve, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { digest } from './evaluation.mjs'
import { checkContextAttempts } from './context-budget.mjs'

/** Each root snapshot is cumulative across attempts of one Run; never add budget snapshots together. */
export function checkTaskAttempts(attempts) {
  if (!Array.isArray(attempts) || !attempts.length) return { pass: false, reason: 'MISSING_ATTEMPT', rows: [] }
  let calls = 0, knownNative = 0, prior, id
  const rows = attempts.map(attempt => {
    const budget = normalizeTaskBudget(attempt.taskBudget)
    calls += attempt.modelCallCount
    knownNative += (attempt.calls || []).reduce((sum, call) => sum + (call.outcome === 'RETURNED' ? call.tokens?.totalTokens || 0 : 0), 0)
    let pass = !!budget && Number.isSafeInteger(calls) && calls >= 0 && (!id || id === budget.budgetId)
    if (budget) {
      id = budget.budgetId
      if (budget.stateKnown) {
        pass &&= budget.modelCalls === calls && budget.chargedTokens >= knownNative
          && (!prior || !prior.stateKnown || budget.chargedTokens >= prior.chargedTokens && budget.reservedTokens >= prior.reservedTokens)
      } else pass &&= budget.status === 'BLOCKED' && budget.blockReason === 'STATE_UNAVAILABLE'
      prior = budget
    }
    return { pass, attemptCalls: attempt.modelCallCount, taskBudget: budget }
  })
  return { pass: rows.every(row => row.pass), rows, cost: 'UNKNOWN',
    scope: 'Run admission integrity. Reserved or charged tokens are not provider usage or a billing cap.' }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [inputArg, outputArg, ...extra] = process.argv.slice(2)
  if (!inputArg || !outputArg || extra.length) throw Error('Usage: node tools/campus/task-budget.mjs OBSERVATIONS_JSON NEW_OUTPUT')
  const input = resolve(inputArg), output = resolve(outputArg), raw = readFileSync(input)
  if (existsSync(output)) throw Error('Never overwrite evidence')
  const report = JSON.parse(raw)
  if (!report.runtime?.features?.taskBudget || !Array.isArray(report.records) || !report.records.length) throw Error('Missing budget runtime identity or planned cases')
  const rows = report.records.map(record => {
    if (!record.eventsFile || basename(record.eventsFile) !== record.eventsFile) throw Error('Invalid captured events path')
    const bytes = readFileSync(resolve(dirname(input), record.eventsFile))
    if (digest(bytes) !== record.eventsSha256) throw Error('Events identity mismatch')
    const events = JSON.parse(bytes)
    const attempts = [...new Map(events.flatMap(e => { const u = e.type === 'usage' ? e.payload : e.payload?.modelUsage; return u ? [[u.attemptId, u]] : [] })).values()]
    if (JSON.stringify(attempts) !== JSON.stringify(record.modelUsageAttempts)) throw Error('Captured attempt mismatch')
    const task = checkTaskAttempts(attempts), context = checkContextAttempts(attempts)
    return { caseId: record.caseId, repetition: record.repetition, pass: task.pass && context.pass, task, context }
  })
  const checked = { planned: rows.length, passed: rows.filter(row => row.pass).length, rows,
    observationsSha256: digest(raw), cost: 'UNKNOWN', scope: 'Captured development attempts; not independent answer quality or billing acceptance' }
  mkdirSync(output, { recursive: true }); writeFileSync(resolve(output, 'budget-audit.json'), JSON.stringify(checked, null, 2) + '\n')
  console.log(`Run budget checks: ${checked.passed}/${checked.planned}`)
  if (checked.passed !== checked.planned) process.exitCode = 1
}
