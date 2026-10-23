import test from 'node:test'
import assert from 'node:assert/strict'
import { auditModelUsage } from './model-usage.mjs'
function fixture() {
  const usage = { schemaVersion: 'model-usage-v1', attemptId: 'a1111111-1111-4111-a111-111111111111', scope: 'EXECUTION_ATTEMPT',
    coverage: 'INSTRUMENTED', modelCallCount: 1, callsWithUsage: 1, usageStatus: 'COMPLETE', recordsTruncated: false,
    totals: { inputTokens: 11, outputTokens: 3, totalTokens: 14 }, modelDurationMs: 100, cost: { status: 'UNKNOWN' },
    calls: [{ callNo: 1, mode: 'STREAM', outcome: 'RETURNED', durationMs: 100, firstTextDeltaMs: 50,
      usageStatus: 'PROVIDER_REPORTED', tokens: { inputTokens: 11, outputTokens: 3, totalTokens: 14 } }] }
  const events = [{ type: 'usage', payload: usage }, { type: 'run.completed', payload: { modelUsage: usage } }]
  const report = { selectedCaseIds: ['dev-a'], repetitions: 1, records: [{ caseId: 'dev-a', repetition: 1, status: 'COMPLETED',
    modelUsageAttempts: [usage], historyModelUsageAttempts: [usage] }] }
  return { usage, events, report }
}
test('S4 audit deduplicates terminal snapshots and compares stored history', () => {
  const f = fixture(), result = auditModelUsage(f.report, () => f.events)
  assert.equal(result.snapshotChecksPassed, 1); assert.equal(result.completeRunTokenSubtotal, 14)
  assert.equal(result.rows[0].modelCalls, 1); assert.equal(result.rows[0].streamCalls, 1)
  assert.equal(result.cost, 'UNKNOWN')
})
test('S4 audit keeps failed and missing collection in the denominator', () => {
  const f = fixture(); f.report.selectedCaseIds.push('missing')
  const result = auditModelUsage(f.report, () => f.events)
  assert.equal(result.denominator, 2); assert.equal(result.completeUsageRuns, 1)
  assert.equal(result.unknownOrIncompleteUsageRuns, 1)
  assert.ok(result.rows[1].failures.includes('MISSING_USAGE'))
})
test('S4 audit does not turn unknown usage into zero or a complete run', () => {
  const f = fixture(); f.usage.usageStatus = 'UNKNOWN'; f.usage.callsWithUsage = 0; f.usage.totals = null
  f.usage.calls[0].usageStatus = 'UNKNOWN'; f.usage.calls[0].tokens = null
  const result = auditModelUsage(f.report, () => f.events)
  assert.equal(result.snapshotChecksPassed, 1); assert.equal(result.completeUsageRuns, 0)
  assert.equal(result.rows[0].tokens, null)
})
test('S4 audit rejects changed frozen snapshots, forged totals and missing history', () => {
  for (const kind of ['late', 'totals', 'history']) {
    const f = fixture()
    if (kind === 'late') { f.events[1].payload.modelUsage = structuredClone(f.usage); f.events[1].payload.modelUsage.modelDurationMs++ }
    if (kind === 'totals') f.usage.totals.totalTokens++
    if (kind === 'history') delete f.report.records[0].historyModelUsageAttempts
    assert.equal(auditModelUsage(f.report, () => f.events).snapshotChecksPassed, 0)
  }
})
