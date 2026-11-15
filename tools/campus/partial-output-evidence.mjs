import assert from 'node:assert/strict'
import {verifyBrowserEvent} from './stage6-browser-evidence.mjs'
import {createRunViewModel,reduceRunEvent} from '../../vio-ai-agent-frontend/src/api/run-contract.js'
import {digest,sameJson} from './evaluation.mjs'
import {compareCombinationConfiguration} from './upgrade-combination-verify.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'

export function verifyPartialTermination(bytes,row) {
  assert.ok(['cancelled','timed_out'].includes(row.status))
  const events=verifyBrowserEvent(bytes,{runId:row.runId,sha256:row.eventsSha256,
    terminal:row.status==='cancelled'?'run.cancelled':'run.timed_out'})
  assert.ok(BigInt(row.cursor)<BigInt(events.at(-1).eventId),'CURSOR_MUST_PRECEDE_TERMINATION')
  const before=events.filter(event=>BigInt(event.eventId)<=BigInt(row.cursor))
  assert.equal(before.filter(event=>event.type==='answer.delta').map(event=>event.payload.text).join(''),'部分测试回答')
  const replay=list=>list.reduce(reduceRunEvent,createRunViewModel({runId:row.runId}))
  const full=replay(events),again=replay([...before,...events,...events])
  assert.equal(full.status,row.status);assert.equal(full.answer,'部分测试回答');assert.equal(again.answer,full.answer)
  assert.equal(full.approval,null)
  assert.equal(row.providerAfter.decisions-row.providerBefore.decisions,1)
  assert.equal(row.providerAfter.streams-row.providerBefore.streams,1)
  assert.equal(row.providerAfter.tailAttempts-row.providerBefore.tailAttempts,1)
  return {status:'VERIFIED',partialBeforeTerminal:true,lateProviderAttempt:true,duplicateReplayStable:true}
}

export function verifyPartialCombination(enabled,disabled,readEvents) {
  const runs=new Set()
  for(const [mode,report] of [['enabled',enabled],['disabled',disabled]]) {
    assert.equal(report.schemaVersion,'partial-output-browser-v1');assert.equal(report.mode,mode)
    assert.equal(report.status,'PASS');assert.deepEqual(report.pageErrors,[])
    assert.equal(report.runtime.sha256,digest(report.runtime.configuration))
    assert.equal(report.runtimeBuild.sha256,runtimeBuild({implementationSha256:report.runtimeBuild.components}).sha256)
    assert.deepEqual(report.runtimeAfter,report.runtime);assert.deepEqual(report.runtimeBuildAfter,report.runtimeBuild)
    assert.deepEqual(report.records.map(row=>row.status),['cancelled','timed_out'])
    for(const row of report.records){
      assert.ok(!runs.has(row.runId));runs.add(row.runId)
      assert.equal(row.uiPartialOnce,true);assert.equal(row.uiNotCompleted,true);assert.equal(row.runCreations,1)
      verifyPartialTermination(readEvents(mode,row.eventsFile),row)
    }
  }
  compareCombinationConfiguration(enabled.runtime,disabled.runtime)
  assert.equal(enabled.runtimeBuild.sha256,disabled.runtimeBuild.sha256)
  assert.equal(enabled.producerSha256,disabled.producerSha256)
  assert.match(enabled.producerSha256,/^[a-f0-9]{64}$/)
  assert.ok(sameJson(enabled.frontendSha256,disabled.frontendSha256))
  return {schemaVersion:'partial-output-combination-v1',status:'EVIDENCE_VERIFIED',runs:runs.size,
    scope:'CONTROLLED_PROVIDER_PARTIAL_TERMINATION',qualityBenefit:'NOT_EVALUATED',releaseDecision:'NOT_EVALUATED'}
}
