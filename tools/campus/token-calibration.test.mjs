import test from 'node:test'
import assert from 'node:assert/strict'
import { calibrationReport, auditCapture } from './token-calibration.mjs'
const context=()=>({schemaVersion:'context-budget-v1',estimator:'cl100k-base-proxy-v1',calibration:'PROXY_UNCALIBRATED',status:'CHECKED',
  contextTokens:1024,inputLimit:896,outputReserve:128,historyLimit:100,availableHistoryTurns:0,omittedHistoryTurns:0,requestsChecked:1,safetyPercent:125,
  lastEstimate:{system:0,history:0,toolDefinitions:0,toolResults:0,currentMessages:18,inputTokens:18},
  estimateSchemaVersion:'context-estimate-pair-v1',baseEstimate:{system:0,history:0,toolDefinitions:0,toolResults:0,currentMessages:17,inputTokens:17}})
const attempt=(native=20)=>({schemaVersion:'model-usage-v1',scope:'EXECUTION_ATTEMPT',attemptId:'a1111111-1111-4111-a111-111111111111',coverage:'INSTRUMENTED',
  modelCallCount:1,callsWithUsage:1,usageStatus:'COMPLETE',totals:{inputTokens:native,outputTokens:3,totalTokens:native+3},calls:[{callNo:1,mode:'CALL',outcome:'RETURNED',
    configuredModelId:'fixture',requestedModelId:'fixture',responseModelId:null,durationMs:1,usageStatus:'PROVIDER_REPORTED',tokens:{inputTokens:native,outputTokens:3,totalTokens:native+3,source:'FIXTURE'},
    contextBudget:context(),tokenEstimateComparison:{schemaVersion:'token-estimate-comparison-v1',status:'PAIR_RECORDED',baseInputTokens:17,budgetInputTokens:18,
      providerInputTokens:native,baseErrorTokens:17-native,budgetErrorTokens:18-native,budgetCovered:18>=native}}]})
test('input-only signed errors, absolute errors and coverage keep observed split and model identity',()=>{
  const a=attempt(),b=attempt(10);b.attemptId='b1111111-1111-4111-a111-111111111111';b.calls[0].mode='STREAM'
  const report=calibrationReport([{caseId:'dev',split:'development',configuredProvider:'fixture',attempts:[a]},
    {caseId:'validation',split:'validation',configuredProvider:'fixture',attempts:[b]}])
  assert.equal(report.overall.pairedCalls,2);assert.equal(report.overall.budgetCoverageRate,.5)
  assert.equal(report.bySplit.development.baseRelativeError.mean,-.15);assert.equal(report.bySplit.validation.baseRelativeError.mean,.7)
  assert.equal(report.groups.length,2);assert.equal(report.policyChange,'NONE_OBSERVATIONAL_ONLY')
})
test('missing, failed and corrupted observations stay excluded; duplicates cannot count twice',()=>{
  const bad=attempt();bad.calls[0].tokenEstimateComparison.budgetErrorTokens=0
  const report=calibrationReport([{caseId:'missing',split:'validation',attempts:[]},{caseId:'tampered',split:'validation',attempts:[bad]}])
  assert.equal(report.plannedRuns,2);assert.equal(report.overall.pairedCalls,0);assert.equal(report.overall.excluded,2)
  assert.equal(report.overall.budgetCoverageRate,null)
  assert.throws(()=>calibrationReport([{caseId:'a',split:'development',attempts:[attempt(),attempt()]}]),/Duplicate/)
})
test('capture audit verifies frozen attempts, planned records and persisted history',()=>{
  const a=attempt(),report={schemaVersion:'token-calibration-capture-v1',plan:{cases:[{id:'a',split:'validation'}]},runtime:{configuredProvider:'fixture'},
    records:[{caseId:'a',status:'COMPLETED',attempts:[a],historyAttempts:[a]}]}
  assert.equal(auditCapture(report,()=>[{type:'usage',payload:a}]).overall.pairedCalls,1)
  assert.equal(auditCapture(report,()=>[{type:'usage',payload:{...a,gameReleaseId:'sv',language:'zh-CN'}},
    {type:'run.completed',payload:{modelUsage:a}}]).overall.pairedCalls,1)
  assert.throws(()=>auditCapture({...report,records:[]},()=>[]),/fixed plan/)
  const invalid=structuredClone(report);invalid.records[0].historyAttempts=[]
  assert.throws(()=>auditCapture(invalid,()=>[{type:'usage',payload:a}]),/History/)
  const changed=structuredClone(a);changed.calls[0].durationMs=2
  assert.throws(()=>auditCapture(report,()=>[{type:'usage',payload:a},{type:'run.completed',payload:{modelUsage:changed}}]),/Frozen/)
})
