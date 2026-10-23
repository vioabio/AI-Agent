import { normalizeModelUsage, summarizeModelUsage } from '../../vio-ai-agent-frontend/src/api/model-usage.js'
import { digest } from './evaluation.mjs'

// All planned records stay in the denominator, including failed collection and missing usage.
export function auditModelUsage(report, eventsFor) {
  if (!Array.isArray(report.selectedCaseIds) || !Number.isInteger(report.repetitions) || report.repetitions < 1) throw Error('Missing planned denominator')
  const rows = []
  for (const caseId of report.selectedCaseIds) for (let repetition = 1; repetition <= report.repetitions; repetition++) {
    const records = (report.records || []).filter(r => r.caseId === caseId && r.repetition === repetition)
    const record = records[0], failures = []
    if (records.length !== 1) failures.push('PLANNED_RECORD_MISSING_OR_DUPLICATED')
    let events = []
    try { events = record ? eventsFor(record) : [] } catch { failures.push('EVENT_READ_FAILED') }
    const frozen = new Map()
    for (const event of events) {
      const raw = event.type === 'usage' ? event.payload : event.payload?.modelUsage
      if (!raw) continue
      const normalized = normalizeModelUsage(raw)
      if (!normalized || normalized.usageStatus !== raw.usageStatus || normalized.callsWithUsage !== raw.callsWithUsage) {
        failures.push('INVALID_USAGE'); continue
      }
      const previous = frozen.get(normalized.attemptId)
      if (previous && digest(previous) !== digest(normalized)) failures.push('FROZEN_SNAPSHOT_CHANGED')
      frozen.set(normalized.attemptId, normalized)
    }
    const attempts = [...frozen.values()], summary = summarizeModelUsage(attempts)
    const normalizeAll = values => Array.isArray(values) ? values.map(normalizeModelUsage) : null
    if (!summary) failures.push('MISSING_USAGE')
    if (digest(normalizeAll(record?.modelUsageAttempts)) !== digest(attempts)) failures.push('COLLECTOR_SNAPSHOT_MISMATCH')
    if (record?.status === 'COMPLETED' && digest(normalizeAll(record.historyModelUsageAttempts)) !== digest(attempts)) failures.push('HISTORY_SNAPSHOT_MISMATCH')
    rows.push({ caseId, repetition, status: record?.status || 'MISSING', failures,
      modelCalls: summary?.modelCalls ?? null, callsWithUsage: summary?.callsWithUsage ?? 0,
      complete: summary?.complete ?? false, tokens: summary?.totals ?? null, modelDurationMs: summary?.modelDurationMs ?? null,
      streamCalls: attempts.flatMap(a => a.calls).filter(call => call.mode === 'STREAM').length,
      sdkFirstTextSamples: attempts.flatMap(a => a.calls).filter(call => call.firstTextDeltaMs !== null).length })
  }
  const complete = rows.filter(row => row.complete && !row.failures.length)
  return { schemaVersion: 1, scope: 'PLANNED_DEVELOPMENT_RUNS', denominator: rows.length,
    snapshotChecksPassed: rows.filter(row => !row.failures.length).length, completeUsageRuns: complete.length,
    unknownOrIncompleteUsageRuns: rows.length - complete.length,
    completeRunTokenSubtotal: complete.reduce((sum, row) => sum + row.tokens.totalTokens, 0),
    tokenSubtotalScope: 'ONLY_VALIDATED_COMPLETE_RUNS_NOT_ALL_RUNS', httpRequestCount: 'UNKNOWN', cost: 'UNKNOWN', rows }
}
