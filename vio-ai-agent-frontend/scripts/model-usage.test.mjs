import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeModelUsage, summarizeModelUsage, normalizeContextBudget, normalizeTaskBudget } from '../src/api/model-usage.js'
import { createRunViewModel, reduceRunEvent } from '../src/api/run-contract.js'

function usage(overrides = {}) {
  return { schemaVersion: 'model-usage-v1', attemptId: 'a1111111-1111-4111-a111-111111111111', scope: 'EXECUTION_ATTEMPT',
    coverage: 'INSTRUMENTED', modelCallCount: 1, callsWithUsage: 1, usageStatus: 'COMPLETE',
    totals: { inputTokens: 11, outputTokens: 3, totalTokens: 14 }, modelDurationMs: 300,
    recordsTruncated: false, cost: { status: 'UNKNOWN' }, calls: [{ callNo: 1, mode: 'STREAM', outcome: 'RETURNED',
      configuredModelId: 'fixture-model', responseModelId: null, durationMs: 300, firstTextDeltaMs: 100,
      usageStatus: 'PROVIDER_REPORTED', tokens: { inputTokens: 11, outputTokens: 3, totalTokens: 14 } }], ...overrides }
}
function task(overrides = {}) {
  return { schemaVersion: 'task-budget-v1', scope: 'RUN', budgetId: 'b2222222-2222-4222-a222-222222222222',
    accounting: 'MAX_RESERVATION_OR_PROVIDER_V1', maxModelCalls: 16, tokenLimit: 10000, stateKnown: true,
    modelCalls: 1, reservedTokens: 200, chargedTokens: 300, remainingModelCalls: 15, remainingTokens: 9700,
    status: 'ACTIVE', closed: false, blockReason: null, ...overrides }
}
test('Run credit is sanitized and cumulative attempt snapshots are selected, never added', () => {
  const first = usage({ taskBudget: task({ secret: 'private reservation' }) })
  const second = usage({ attemptId: 'c3333333-3333-4333-a333-333333333333', taskBudget: task({
    modelCalls: 2, reservedTokens: 400, chargedTokens: 500, remainingModelCalls: 14, remainingTokens: 9500 }) })
  const summary = summarizeModelUsage([first, first, second])
  assert.equal(summary.modelCalls, 2)
  assert.equal(summary.taskBudget.chargedTokens, 500)
  assert.equal(summary.totals.totalTokens, 28)
  assert.deepEqual(normalizeTaskBudget(summary.taskBudget), summary.taskBudget)
  assert.deepEqual(normalizeModelUsage(normalizeModelUsage(first)), normalizeModelUsage(first))
  assert.doesNotMatch(JSON.stringify(summary), /private/)
  assert.equal(summarizeModelUsage([second, first]).taskBudget, null)
  assert.equal(summarizeModelUsage([first, { ...second, taskBudget: task({ budgetId: 'c3333333-3333-4333-a333-333333333333' }) }]).taskBudget, null)
})
test('unknown recovered credit is explicitly null while native overrun remains separate from actual usage', () => {
  const unknown = task({ stateKnown: false, status: 'BLOCKED', blockReason: 'STATE_UNAVAILABLE',
    modelCalls: null, reservedTokens: null, chargedTokens: null, remainingModelCalls: null, remainingTokens: null })
  assert.equal(normalizeTaskBudget(unknown).chargedTokens, null)
  const overrun = task({ status: 'BLOCKED', blockReason: 'TOKEN_LIMIT', chargedTokens: 11000, remainingTokens: 0 })
  assert.equal(normalizeTaskBudget(overrun).chargedTokens, 11000)
  for (const bad of [task({ remainingTokens: 10000 }), task({ blockReason: 'private body' }), task({ tokenLimit: NaN }),
    { ...unknown, chargedTokens: 0 }, task({ chargedTokens: 11000, remainingTokens: 0 })]) assert.equal(normalizeTaskBudget(bad), null)
})
test('usage survives repeated view normalization and uses only whitelisted metadata', () => {
  const raw = usage({ secret: 'private', cost: { status: 'ESTIMATED', amount: 100 } })
  raw.calls[0].providerBody = 'private'
  const value = normalizeModelUsage(raw)
  assert.deepEqual(normalizeModelUsage(value), value)
  assert.equal(summarizeModelUsage([value]).totals.totalTokens, 14)
  assert.equal(value.cost.status, 'UNKNOWN')
  assert.doesNotMatch(JSON.stringify(value), /private|"amount":100/)
  assert.equal(value.cost.amount, null)
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

test('context estimate is separately labeled, sanitized, and stable across history and event replay', () => {
  const budget = { schemaVersion: 'context-budget-v1', estimator: 'cl100k-base-proxy-v1', calibration: 'PROXY_UNCALIBRATED', status: 'CHECKED',
    contextTokens: 1024, inputLimit: 896, outputReserve: 128, historyLimit: 256, availableHistoryTurns: 3, omittedHistoryTurns: 2,
    requestsChecked: 2, safetyPercent: 125, lastEstimate: { system: 100, history: 50, toolDefinitions: 100, toolResults: 20, currentMessages: 80, inputTokens: 350 },
    prompt: 'private request', blockReason: 'private body' }
  const normalized = normalizeContextBudget(budget)
  assert.deepEqual(normalizeContextBudget(normalized), normalized)
  assert.doesNotMatch(JSON.stringify(normalized), /private/)
  const view = normalizeModelUsage(usage({ contextBudget: budget }))
  assert.deepEqual(normalizeModelUsage(view), view)
  assert.equal(view.totals.totalTokens, 14)
  assert.equal(view.contextBudget.lastEstimate.inputTokens, 350)
  assert.equal(normalizeContextBudget({ ...budget, inputLimit: 1 }), null)
  assert.equal(normalizeContextBudget({ ...budget, status: 'CHECKED', lastEstimate: {} }), null)
})
