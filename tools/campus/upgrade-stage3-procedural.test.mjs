import test from 'node:test'
import assert from 'node:assert/strict'
import {verifyFaultEvents} from './upgrade-stage3-fault-collect.mjs'
import {verifyCancelledReplay} from './upgrade-stage3-cancel-browser.mjs'

const runId='run-fault-fixture'
const event=(eventId,type,payload={})=>({eventId,runId,type,payload})
test('engine fault needs actual failed calculation and failure terminal',()=>{
 const events=[event(1,'tool.started',{toolName:'calculateBattleDamage'}),event(2,'tool.finished',{toolName:'calculateBattleDamage',status:'FAILED'}),event(3,'run.failed',{errorType:'DAMAGE_CALCULATION_FAILED'})]
 assert.equal(verifyFaultEvents('EngineUnavailable',runId,events).status,'VERIFIED')
 assert.throws(()=>verifyFaultEvents('EngineUnavailable',runId,[...events,event(4,'run.completed')]))
 events[1].payload.status='SUCCEEDED'
 assert.throws(()=>verifyFaultEvents('EngineUnavailable',runId,events))
})
test('low budget accepts failure-envelope usage and rejects reset counters',()=>{
 const budget={budgetId:'one',maxModelCalls:1,tokenLimit:32768,stateKnown:true,modelCalls:1,status:'BLOCKED',blockReason:'CALL_LIMIT'}
 const events=[event(1,'run.failed',{errorType:'RUN_TASK_BUDGET_EXCEEDED',modelUsage:{taskBudget:budget}})]
 assert.equal(verifyFaultEvents('LowBudget',runId,events).status,'VERIFIED')
 assert.throws(()=>verifyFaultEvents('LowBudget',runId,[event(1,'usage',{taskBudget:{...budget,budgetId:'other'}}),event(2,'run.failed',{errorType:'RUN_TASK_BUDGET_EXCEEDED',modelUsage:{taskBudget:budget}})]))
 budget.modelCalls=2;assert.throws(()=>verifyFaultEvents('LowBudget',runId,events))
})
test('cancelled partial text is stable under repeated pages and cursor tail',()=>{
 const events=[event(1,'run.started'),event(2,'answer.delta',{text:'部分回答'}),event(3,'run.cancelled')]
 const result=verifyCancelledReplay(runId,events,[events[2]],'2')
 assert.equal(result.answerLength,4);assert.equal(result.duplicateReplayStable,true)
 assert.throws(()=>verifyCancelledReplay(runId,[...events,event(4,'answer.delta',{text:'不应出现'})],[], '4'))
 for(const type of ['fact.card','calculation.card','team.matrix','approval.required'])
  assert.throws(()=>verifyCancelledReplay(runId,[...events,event(4,type)],[], '4'))
 assert.throws(()=>verifyCancelledReplay(runId,events,[], '2'))
})
test('shared two-call budget requires both planning roles and cannot reset after handoff',()=>{
 const policy={maxModelCalls:2,tokenLimit:32768}
 const budget={...policy,budgetId:'shared',modelCalls:2,stateKnown:true,status:'BLOCKED',blockReason:'CALL_LIMIT'}
 const events=[event(1,'route.selected',{delegationEvent:'agent.delegation.started',agentRole:'TEAM_PLANNING'}),event(2,'route.selected',{delegationEvent:'agent.delegation.started',agentRole:'DAMAGE_CALCULATION'}),event(3,'run.failed',{errorType:'RUN_TASK_BUDGET_EXCEEDED',modelUsage:{taskBudget:budget}})]
 assert.equal(verifyFaultEvents('LowBudget',runId,events,policy).status,'VERIFIED')
 budget.modelCalls=1;assert.throws(()=>verifyFaultEvents('LowBudget',runId,events,policy))
 budget.modelCalls=2;events[1].payload.agentRole='TEAM_PLANNING';assert.throws(()=>verifyFaultEvents('LowBudget',runId,events,policy))
})
