import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeModelUsage, summarizeModelUsage } from '../src/api/model-usage.js'
import { formatReferenceMoney, normalizeBillingUsage } from '../src/api/model-cost.js'

export function pricedAttempt() {
  const price = { version: 'fixture-v1', cardId: 'fixture', scope: 'offline-test', provider: 'deepseek', requestedModelId: 'fixture-model',
    sourceType: 'TEST_FIXTURE', sourceUrl: 'https://example.invalid/prices', sha256: 'a'.repeat(64), modelBasis: 'REQUESTED_MODEL_NOT_ATTESTED',
    unitTokens: 1000000, maxInputTokens: 1000000, inputAccounting: 'SPLIT_CACHE', selectedAt: '2026-10-24T00:00:00Z',
    validFrom: '2020-01-01T00:00:00Z', validUntil: '2100-01-01T00:00:00Z', inputPerMillion: '1', cachedInputPerMillion: '0.1', outputPerMillion: '3' }
  const callCost = { schemaVersion: 'model-cost-v1', kind: 'REFERENCE_ESTIMATE_NOT_INVOICE', status: 'ESTIMATED', currency: 'USD',
    amount: '0.00094', price, components: { input: '0.0006', cachedInput: '0.00004', output: '0.0003' } }
  return { schemaVersion: 'model-usage-v1', attemptId: 'a1111111-1111-4111-a111-111111111111', scope: 'EXECUTION_ATTEMPT', coverage: 'INSTRUMENTED',
    modelCallCount: 1, callsWithUsage: 1, usageStatus: 'COMPLETE', recordsTruncated: false, modelDurationMs: 10,
    totals: { inputTokens: 1000, outputTokens: 100, totalTokens: 1100 },
    cost: { schemaVersion: 'model-cost-v1', kind: 'REFERENCE_ESTIMATE_NOT_INVOICE', status: 'ESTIMATED', currency: 'USD',
      amount: '0.00094', knownSubtotal: '0.00094', pricedCalls: 1, totalCalls: 1, reason: null },
    calls: [{ callNo: 1, mode: 'CALL', outcome: 'RETURNED', configuredModelId: 'other-configured', requestedModelId: 'fixture-model',
      durationMs: 10, firstTextDeltaMs: null, usageStatus: 'PROVIDER_REPORTED', tokens: { inputTokens: 1000, outputTokens: 100, totalTokens: 1100 },
      billingUsage: { schemaVersion: 'billing-usage-v1', status: 'REPORTED', cachedInputTokens: 400, uncachedInputTokens: 600,
        reasoningOutputTokens: 30, reasoningIncludedInOutput: true }, cost: callCost }] }
}
test('decimal reference costs retain frozen source evidence and reasoning is not added twice', () => {
  const raw = pricedAttempt(); raw.calls[0].cost.price.secret = 'private quote'
  const normalized = normalizeModelUsage(raw)
  assert.equal(normalized.cost.amount, '0.00094')
  assert.equal(normalized.totals.totalTokens, 1100)
  assert.equal(normalized.calls[0].billingUsage.reasoningOutputTokens, 30)
  assert.deepEqual(normalizeModelUsage(normalized), normalized)
  assert.doesNotMatch(JSON.stringify(normalized), /private/)
  assert.equal(summarizeModelUsage([raw, raw]).cost.amount, '0.00094')
})
test('forged totals, amounts, models and partitions become unknown without invalidating native usage', () => {
  for (const mutate of [r => { r.cost.amount = '9' }, r => { r.calls[0].cost.amount = '9' },
    r => { r.calls[0].cost.price.requestedModelId = 'other' }, r => { r.calls[0].billingUsage.reasoningOutputTokens = 101 },
    r => { r.calls[0].billingUsage.uncachedInputTokens = 599 }, r => { r.calls[0].cost.price.sourceUrl = 'https://user:secret@example.invalid/' }]) {
    const raw = pricedAttempt(); mutate(raw)
    const normalized = normalizeModelUsage(raw)
    assert.equal(normalized.cost.status, 'UNKNOWN')
    assert.equal(normalized.totals.totalTokens, 1100)
  }
})
test('failed or missing call costs remain in the denominator and only known subtotal is shown', () => {
  const raw = pricedAttempt()
  raw.modelCallCount = 2; raw.usageStatus = 'PARTIAL'; raw.totals = null
  raw.calls.push({ callNo: 2, mode: 'CALL', outcome: 'FAILED', configuredModelId: 'fixture-model', durationMs: 10,
    usageStatus: 'UNKNOWN', tokens: null, cost: { schemaVersion: 'model-cost-v1', kind: 'REFERENCE_ESTIMATE_NOT_INVOICE', status: 'UNKNOWN',
      reason: 'MODEL_CALL_INCOMPLETE', currency: null, amount: null } })
  raw.cost = { ...raw.cost, status: 'PARTIAL', amount: null, totalCalls: 2, reason: 'INCOMPLETE_CALL_COSTS' }
  const normalized = normalizeModelUsage(raw)
  assert.equal(normalized.cost.status, 'PARTIAL')
  assert.equal(normalized.cost.amount, null)
  assert.equal(normalized.cost.knownSubtotal, '0.00094')
  assert.equal(normalized.cost.totalCalls, 2)
  assert.deepEqual(normalizeModelUsage(normalized), normalized)
})
test('approval zero work is not charged twice and currencies are never implicitly exchanged', () => {
  const first = pricedAttempt(), zero = { ...first, attemptId: 'b2222222-2222-4222-a222-222222222222',
    calls: [], modelCallCount: 0, callsWithUsage: 0, totals: { inputTokens: 0, outputTokens: 0, totalTokens: 0 },
    cost: { schemaVersion: 'model-cost-v1', kind: 'REFERENCE_ESTIMATE_NOT_INVOICE', status: 'UNKNOWN', reason: 'NO_MODEL_CALLS', amount: null, currency: null } }
  assert.equal(summarizeModelUsage([first, zero]).cost.amount, '0.00094')
  const second = pricedAttempt(); second.attemptId = zero.attemptId
  second.cost.currency = 'CNY'; second.calls[0].cost.currency = 'CNY'
  assert.equal(summarizeModelUsage([first, second]).cost.reason, 'MIXED_CURRENCIES')
  assert.equal(summarizeModelUsage([first, second]).cost.amount, null)
})
test('tiny decimal values and rounding do not pass through floating point', () => {
  const raw = pricedAttempt(), call = raw.calls[0]
  raw.totals = call.tokens = { inputTokens: 1, outputTokens: 1, totalTokens: 2 }
  call.billingUsage.cachedInputTokens = 0; call.billingUsage.uncachedInputTokens = 1; call.billingUsage.reasoningOutputTokens = 0
  Object.assign(call.cost.price, { inputPerMillion: '0.00000001', cachedInputPerMillion: '0', outputPerMillion: '0.00000002' })
  call.cost.components = { input: '0.00000000000001', cachedInput: '0', output: '0.00000000000002' }
  raw.cost.amount = raw.cost.knownSubtotal = call.cost.amount = '0.00000000000003'
  assert.equal(normalizeModelUsage(raw).cost.amount, '0.00000000000003')
  assert.equal(formatReferenceMoney('USD', raw.cost.amount), '< USD 0.000001')
  assert.equal(formatReferenceMoney('USD', '1.0000005'), 'USD 1.000001')
  assert.equal(formatReferenceMoney('USD', '9000000000000000.000001'), 'USD 9000000000000000.000001')
})
test('missing cache is not zero and only explicit flat input contracts can ignore cache partitions', () => {
  const raw = pricedAttempt(), call = raw.calls[0]
  Object.assign(call.billingUsage, { status: 'UNKNOWN', cachedInputTokens: null, uncachedInputTokens: null, reasoningOutputTokens: null })
  assert.equal(normalizeModelUsage(raw).cost.status, 'UNKNOWN')
  Object.assign(call.cost.price, { inputAccounting: 'ALL_INPUT', cachedInputPerMillion: null })
  call.cost.components = { input: '0.001', cachedInput: '0', output: '0.0003' }
  raw.cost.amount = raw.cost.knownSubtotal = call.cost.amount = '0.0013'
  assert.equal(normalizeModelUsage(raw).cost.amount, '0.0013')
  assert.equal(normalizeBillingUsage({ ...call.billingUsage, cachedInputTokens: 0 }, call.tokens), null)
})
