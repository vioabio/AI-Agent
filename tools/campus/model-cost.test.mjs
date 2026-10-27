import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { checkCostAttempts } from './model-cost.mjs'
const fixture = () => JSON.parse(readFileSync(new URL('../../vio-ai-agent-frontend/e2e/fixtures/model-cost.json', import.meta.url)))
test('frozen reference costs can be independently recomputed without a current price catalog', () => {
  const result = checkCostAttempts([fixture()])
  assert.equal(result.pass, true)
  assert.equal(result.combined.amount, '0.00094')
})
test('tampered amounts, missing attempts and fake zero pricing are rejected', () => {
  assert.equal(checkCostAttempts([]).pass, false)
  const tampered = fixture(); tampered.calls[0].cost.amount = '0'
  assert.equal(checkCostAttempts([tampered]).pass, false)
  assert.equal(checkCostAttempts([tampered]).combined, null)
  const missing = fixture(); delete missing.cost
  assert.equal(checkCostAttempts([missing]).pass, false)
})
