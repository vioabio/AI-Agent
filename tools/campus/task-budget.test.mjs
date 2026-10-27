import test from 'node:test'
import assert from 'node:assert/strict'
import { checkTaskAttempts } from './task-budget.mjs'

const budget = { schemaVersion: 'task-budget-v1', scope: 'RUN', budgetId: 'b2222222-2222-4222-a222-222222222222',
  accounting: 'MAX_RESERVATION_OR_PROVIDER_V1', maxModelCalls: 2, tokenLimit: 1000, stateKnown: true,
  modelCalls: 1, reservedTokens: 300, chargedTokens: 300, remainingModelCalls: 1, remainingTokens: 700,
  status: 'ACTIVE', closed: false, blockReason: null }
const first = { modelCallCount: 1, calls: [{ outcome: 'RETURNED', tokens: { totalTokens: 50 } }], taskBudget: budget }
test('approval attempts retain one cumulative budget and known native usage remains separate', () => {
  const confirmation = { modelCallCount: 0, calls: [], taskBudget: { ...budget, closed: true } }
  const checked = checkTaskAttempts([first, confirmation])
  assert.equal(checked.pass, true)
  assert.equal(checked.rows.at(-1).taskBudget.chargedTokens, 300)
  assert.equal(checked.cost, 'UNKNOWN')
})
test('missing, reset, mismatched or malformed credit cannot pass admission audit', () => {
  assert.equal(checkTaskAttempts([]).pass, false)
  assert.equal(checkTaskAttempts([{ modelCallCount: 0 }]).pass, false)
  assert.equal(checkTaskAttempts([first, first]).pass, false)
  assert.equal(checkTaskAttempts([first, { modelCallCount: 0, taskBudget: { ...budget, budgetId: 'c3333333-3333-4333-a333-333333333333' } }]).pass, false)
  assert.equal(checkTaskAttempts([{ ...first, taskBudget: { ...budget, chargedTokens: 10 } }]).pass, false)
})
