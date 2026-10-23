import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeModelUsage, summarizeModelUsage } from '../src/api/model-usage.js'
import { createRunViewModel, reduceRunEvent } from '../src/api/run-contract.js'

function usage(overrides = {}) {
  return { schemaVersion: 'model-usage-v1', attemptId: 'a1111111-1111-4111-a111-111111111111', scope: 'EXECUTION_ATTEMPT',
    coverage: 'INSTRUMENTED', modelCallCount: 1, callsWithUsage: 1, usageStatus: 'COMPLETE',
    totals: { inputTokens: 11, outputTokens: 3, totalTokens: 14 }, modelDurationMs: 300,
    recordsTruncated: false, cost: { status: 'UNKNOWN' }, calls: [{ callNo: 1, mode: 'STREAM', outcome: 'RETURNED',
      configuredModelId: 'fixture-model', responseModelId: null, durationMs: 300, firstTextDeltaMs: 100,
      usageStatus: 'PROVIDER_REPORTED', tokens: { inputTokens: 11, outputTokens: 3, totalTokens: 14 } }], ...overrides }
}
test('usage survives repeated view normalization and uses only whitelisted metadata', () => {
  const raw = usage({ secret: 'private', cost: { status: 'ESTIMATED', amount: 100 } })
  raw.calls[0].providerBody = 'private'
  const value = normalizeModelUsage(raw)
  assert.deepEqual(normalizeModelUsage(value), value)
  assert.equal(summarizeModelUsage([value]).totals.totalTokens, 14)
  assert.equal(value.cost.status, 'UNKNOWN')
  assert.doesNotMatch(JSON.stringify(value), /private|amount/)
})
test('missing, failed or malformed usage remains unknown; explicit complete zero stays zero', () => {
  const missing = usage({ totals: null, callsWithUsage: 0, usageStatus: 'UNKNOWN' })
  missing.calls[0].tokens = null
  assert.equal(summarizeModelUsage([missing]).totals, null)
  const failed = usage(); failed.calls[0].outcome = 'FAILED'
  assert.equal(summarizeModelUsage([failed]).totals, null)
  const inconsistent = usage(); inconsistent.totals.totalTokens = 15
  assert.equal(summarizeModelUsage([inconsistent]).totals, null)
  const zero = usage({ calls: [], modelCallCount: 0, callsWithUsage: 0,
    totals: { inputTokens: 0, outputTokens: 0, totalTokens: 0 }, modelDurationMs: 0 })
  assert.equal(summarizeModelUsage([zero]).totals.totalTokens, 0)
  assert.equal(summarizeModelUsage([usage({ coverage: 'NOT_INSTRUMENTED' })]).modelCalls, null)
  for (const raw of [null, {}, { executor: 'legacy' }, usage({ calls: [null] }), usage({ attemptId: '<script>' })]) {
    assert.equal(normalizeModelUsage(raw), null)
  }
})
test('partial or truncated attempts cannot claim complete totals', () => {
  const partial = usage({ modelCallCount: 2, usageStatus: 'PARTIAL' })
  assert.equal(summarizeModelUsage([partial]).totals, null)
  assert.equal(summarizeModelUsage([partial]).callsWithUsage, 1)
  assert.equal(summarizeModelUsage([usage({ recordsTruncated: true })]).totals, null)
})
test('usage and terminal replay deduplicate the same attempt and late events stay closed', () => {
  let model = createRunViewModel()
  model = reduceRunEvent(model, { id: '1', type: 'usage', data: usage() })
  model = reduceRunEvent(model, { id: '2', type: 'run.completed', data: { modelUsage: usage() } })
  assert.equal(model.modelUsageAttempts.length, 1)
  assert.equal(summarizeModelUsage(model.modelUsageAttempts).modelCalls, 1)
  const before = JSON.stringify(model.modelUsageAttempts)
  model = reduceRunEvent(model, { id: '3', type: 'usage', data: usage({ modelCallCount: 20 }) })
  assert.equal(JSON.stringify(model.modelUsageAttempts), before)
})
test('approval work and confirmation zero aggregate once across events and history seed', () => {
  const zero = usage({ attemptId: 'b2222222-2222-4222-a222-222222222222', calls: [], modelCallCount: 0, callsWithUsage: 0,
    totals: { inputTokens: 0, outputTokens: 0, totalTokens: 0 }, modelDurationMs: 0 })
  const model = createRunViewModel({ modelUsageAttempts: [usage(), usage(), zero] })
  const summary = summarizeModelUsage(model.modelUsageAttempts)
  assert.equal(model.modelUsageAttempts.length, 2)
  assert.equal(summary.modelCalls, 1)
  assert.equal(summary.totals.totalTokens, 14)
})
