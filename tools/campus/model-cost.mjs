import { normalizeModelUsage, summarizeModelUsage } from '../../vio-ai-agent-frontend/src/api/model-usage.js'
import { existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { resolve, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { digest } from './evaluation.mjs'

export function checkCostAttempts(attempts) {
  if (!Array.isArray(attempts) || !attempts.length) return { pass: false, reason: 'MISSING_ATTEMPT', rows: [] }
  const normalized = attempts.map(normalizeModelUsage)
  const rows = attempts.map((raw, i) => {
    const value = normalized[i]
    const pass = !!value && raw.cost?.schemaVersion === 'model-cost-v1' && raw.cost.status === value.cost.status
      && raw.cost.amount === value.cost.amount && raw.cost.reason === value.cost.reason
      && raw.calls.every((call, index) => call.cost?.status === value.calls[index]?.cost.status
        && call.cost.amount === value.calls[index]?.cost.amount)
    return { pass, modelCalls: value?.modelCallCount ?? null, cost: value?.cost ?? null }
  })
  const pass = rows.every(row => row.pass)
  return { pass, rows, combined: pass ? summarizeModelUsage(normalized)?.cost ?? null : null,
    scope: 'Frozen decimal reference-cost consistency; not supplier invoice, discounts, tool charges or HTTP retries.' }
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [inputArg, outputArg, ...extra] = process.argv.slice(2)
  if (!inputArg || !outputArg || extra.length) throw Error('Usage: node tools/campus/model-cost.mjs OBSERVATIONS_JSON NEW_OUTPUT')
  const input = resolve(inputArg), output = resolve(outputArg), bytes = readFileSync(input), report = JSON.parse(bytes)
  if (existsSync(output)) throw Error('Never overwrite evidence')
  if (!report.runtime?.features?.costObservation || !report.records?.length) throw Error('Missing cost observation or planned runs')
  const rows = report.records.map(record => {
    if (!record.eventsFile || basename(record.eventsFile) !== record.eventsFile) throw Error('Invalid event path')
    const raw = readFileSync(resolve(dirname(input), record.eventsFile))
    if (digest(raw) !== record.eventsSha256) throw Error('Event identity mismatch')
    const events = JSON.parse(raw)
    const attempts = [...new Map(events.flatMap(e => { const u = e.type === 'usage' ? e.payload : e.payload?.modelUsage; return u ? [[u.attemptId, u]] : [] })).values()]
    if (JSON.stringify(attempts) !== JSON.stringify(record.modelUsageAttempts)) throw Error('Attempt identity mismatch')
    return { caseId: record.caseId, repetition: record.repetition, ...checkCostAttempts(attempts) }
  })
  const checked = { planned: rows.length, passed: rows.filter(row => row.pass).length, rows, observationsSha256: digest(bytes), pricingPolicy: report.runtime.pricingPolicy }
  mkdirSync(output, { recursive: true }); writeFileSync(resolve(output, 'cost-audit.json'), JSON.stringify(checked, null, 2) + '\n')
  console.log(`Reference cost checks: ${checked.passed}/${checked.planned}`)
  if (checked.passed !== checked.planned) process.exitCode = 1
}
