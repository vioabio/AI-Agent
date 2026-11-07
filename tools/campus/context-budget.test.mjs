import test from 'node:test'
import assert from 'node:assert/strict'
import { checkContextAttempts } from './context-budget.mjs'
function budget(overrides = {}) { return { schemaVersion: 'context-budget-v1', estimator: 'cl100k-base-proxy-v1', calibration: 'PROXY_UNCALIBRATED', status: 'CHECKED',
  contextTokens: 1024, inputLimit: 896, outputReserve: 128, historyLimit: 256, availableHistoryTurns: 2, omittedHistoryTurns: 1,
  requestsChecked: 1, safetyPercent: 125, lastEstimate: { system: 100, history: 50, toolDefinitions: 20, toolResults: 0, currentMessages: 30, inputTokens: 200 }, ...overrides } }
test('context preflight and provider usage remain separate measurements', () => {
  const checked = checkContextAttempts([{ contextBudget: budget(), modelCallCount: 1, calls: [{ contextBudget: budget(), tokens: { inputTokens: 123 } }] }])
  assert.equal(checked.pass, true); assert.equal(checked.rows[0].calls[0].estimatedInputTokens, 200)
  assert.equal(checked.rows[0].calls[0].providerInputTokens, 123); assert.equal(checked.cost, 'UNKNOWN')
})
test('blocked zero-call attempt is valid; missing, forged or unchecked calls fail', () => {
  assert.equal(checkContextAttempts([{ contextBudget: budget({ status: 'BLOCKED', blockReason: 'INPUT_LIMIT' }), modelCallCount: 0, calls: [] }]).pass, true)
  assert.equal(checkContextAttempts([]).pass, false)
  assert.equal(checkContextAttempts([{ contextBudget: budget(), modelCallCount: 1, calls: [] }]).pass, false)
  assert.equal(checkContextAttempts([{ contextBudget: budget(), modelCallCount: 1, calls: [{ contextBudget: budget({ inputLimit: 1 }) }] }]).pass, false)
})
