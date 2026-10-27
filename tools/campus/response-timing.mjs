import { existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { basename, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { normalizeToolTiming, normalizeResponseTiming } from '../../vio-ai-agent-frontend/src/api/response-timing.js'
import { digest } from './evaluation.mjs'

// Timings measure different boundaries. Never combine SDK text, prepared answer and browser reception as TTFT.
export function checkTimingAttempts(attempts, events, status) {
  if (!Array.isArray(attempts) || !attempts.length || !Array.isArray(events)) return { pass: false, reason: 'MISSING_OBSERVATIONS' }
  const captured = new Map(), stored = new Map()
  let validEvents = true, validStored = true
  for (const event of events) {
    if (event.type !== 'tool.finished' || !event.payload?.timing) continue
    const p = event.payload, timing = normalizeToolTiming(p.timing)
    if (!timing || !p.governanceEventId || !p.toolName) { validEvents = false; continue }
    const row = { eventId: p.governanceEventId, toolName: p.toolName, timing }
    if (captured.has(row.eventId) && JSON.stringify(captured.get(row.eventId)) !== JSON.stringify(row)) validEvents = false
    captured.set(row.eventId, row)
  }
  const rows = attempts.map(attempt => {
    const response = normalizeResponseTiming(attempt.responseTiming)
    const valid = !!response && Array.isArray(attempt.toolTimings) && typeof attempt.toolTimingsTruncated === 'boolean'
    for (const raw of attempt.toolTimings || []) {
      const timing = normalizeToolTiming(raw.timing)
      if (!timing || !raw.eventId || !raw.toolName) { validStored = false; continue }
      const row = { eventId: raw.eventId, toolName: raw.toolName, timing }
      if (stored.has(row.eventId)) validStored = false
      stored.set(row.eventId, row)
    }
    return { pass: valid, attemptId: attempt.attemptId, responseTiming: response,
      timedTools: attempt.toolTimings?.length ?? null, truncated: attempt.toolTimingsTruncated ?? null,
      sdkStreamCalls: (attempt.calls || []).filter(call => call.mode === 'STREAM').length,
      sdkFirstTextSamples: (attempt.calls || []).filter(call => call.firstTextDeltaMs != null).length }
  })
  const eventsMatchAttemptSnapshots = captured.size === stored.size && [...captured].every(([id, row]) => JSON.stringify(row) === JSON.stringify(stored.get(id)))
  // A completed answer needs one ready attempt; approval pauses and failures need not have public text.
  const ready = status !== 'COMPLETED' || rows.some(row => row.responseTiming?.firstAnswerReadyMs != null)
  return { pass: rows.every(row => row.pass && !row.truncated) && validEvents && validStored && eventsMatchAttemptSnapshots && ready,
    rows, eventsMatchAttemptSnapshots, capturedTimedTools: captured.size, browserTiming: 'NOT_MEASURED_BY_NODE_COLLECTOR' }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [inputArg, outputArg, ...extra] = process.argv.slice(2)
  if (!inputArg || !outputArg || extra.length) throw Error('Usage: node tools/campus/response-timing.mjs OBSERVATIONS_JSON NEW_OUTPUT')
  const input = resolve(inputArg), output = resolve(outputArg), bytes = readFileSync(input), report = JSON.parse(bytes)
  if (existsSync(output)) throw Error('Never overwrite evidence')
  if (!report.runtime?.features?.timingObservation || !report.records?.length) throw Error('Missing timing runtime identity or planned runs')
  const rows = report.records.map(record => {
    if (!record.eventsFile || basename(record.eventsFile) !== record.eventsFile) throw Error('Invalid event path')
    const raw = readFileSync(resolve(dirname(input), record.eventsFile))
    if (digest(raw) !== record.eventsSha256) throw Error('Event identity mismatch')
    const events = JSON.parse(raw)
    const attempts = [...new Map(events.flatMap(e => { const u = e.type === 'usage' ? e.payload : e.payload?.modelUsage; return u ? [[u.attemptId, u]] : [] })).values()]
    if (JSON.stringify(attempts) !== JSON.stringify(record.modelUsageAttempts)) throw Error('Attempt identity mismatch')
    return { caseId: record.caseId, repetition: record.repetition, status: record.status, ...checkTimingAttempts(attempts, events, record.status) }
  })
  const checked = { planned: rows.length, passed: rows.filter(row => row.pass).length, rows, observationsSha256: digest(bytes),
    scope: 'Monotonic server readiness and physical callback observations. Not delivery, browser paint, SDK TTFT, latency improvement or answer quality.' }
  mkdirSync(output, { recursive: true }); writeFileSync(resolve(output, 'timing-audit.json'), JSON.stringify(checked, null, 2) + '\n')
  console.log(`Timing checks: ${checked.passed}/${checked.planned}`)
  if (checked.passed !== checked.planned) process.exitCode = 1
}
