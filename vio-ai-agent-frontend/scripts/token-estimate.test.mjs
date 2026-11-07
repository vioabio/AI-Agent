import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeContextBudget, normalizeModelUsage } from '../src/api/model-usage.js'
import { normalizeTokenComparison } from '../src/api/token-estimate.js'
import { readFileSync } from 'node:fs'
const context=()=>({schemaVersion:'context-budget-v1',estimator:'cl100k-base-proxy-v1',calibration:'PROXY_UNCALIBRATED',status:'CHECKED',
  contextTokens:1024,inputLimit:896,outputReserve:128,historyLimit:100,availableHistoryTurns:0,omittedHistoryTurns:0,requestsChecked:1,safetyPercent:125,
  lastEstimate:{system:0,history:0,toolDefinitions:0,toolResults:0,currentMessages:18,inputTokens:18},
  estimateSchemaVersion:'context-estimate-pair-v1',baseEstimate:{system:0,history:0,toolDefinitions:0,toolResults:0,currentMessages:17,inputTokens:17}})
const pair=()=>({schemaVersion:'token-estimate-comparison-v1',status:'PAIR_RECORDED',baseInputTokens:17,budgetInputTokens:18,
  providerInputTokens:20,baseErrorTokens:-3,budgetErrorTokens:-2,budgetCovered:false})
test('input comparison is independently checked against both estimate and provider totals',()=>{
  const budget=normalizeContextBudget(context()),tokens={inputTokens:20,outputTokens:900,totalTokens:920}
  assert.deepEqual(normalizeTokenComparison(pair(),budget,tokens),pair())
  assert.equal(normalizeTokenComparison({...pair(),budgetCovered:true},budget,tokens).reason,'INVALID_COMPARISON')
  assert.equal(normalizeTokenComparison({...pair(),providerInputTokens:21},budget,tokens).reason,'INVALID_COMPARISON')
  assert.equal(normalizeTokenComparison(pair(),budget,null).reason,'INVALID_COMPARISON')
})
test('legacy history is not reverse estimated; malformed optional base counts cannot certify a sample',()=>{
  const old=context();delete old.baseEstimate;delete old.estimateSchemaVersion
  assert.equal(normalizeContextBudget(old).baseEstimate,null)
  assert.equal(normalizeTokenComparison(undefined,normalizeContextBudget(old),{inputTokens:20}),null)
  const invalid=context();invalid.baseEstimate.currentMessages=19
  assert.equal(normalizeContextBudget(invalid).baseEstimate,null)
  assert.equal(normalizeTokenComparison(pair(),normalizeContextBudget(invalid),{inputTokens:20}).reason,'INVALID_COMPARISON')
})
test('per-call context and comparison survive repeated history normalization',()=>{
  const fixture=JSON.parse(readFileSync(new URL('../e2e/fixtures/model-cost.json',import.meta.url)))
  const call=fixture.calls[0];call.contextBudget=context()
  call.tokenEstimateComparison={...pair(),providerInputTokens:call.tokens.inputTokens,baseErrorTokens:17-call.tokens.inputTokens,
    budgetErrorTokens:18-call.tokens.inputTokens,budgetCovered:18>=call.tokens.inputTokens}
  const normalized=normalizeModelUsage(fixture)
  assert.equal(normalized.calls[0].contextBudget.baseEstimate.inputTokens,17)
  assert.equal(normalized.calls[0].tokenEstimateComparison.status,'PAIR_RECORDED')
  assert.deepEqual(normalizeModelUsage(normalized),normalized)
})
