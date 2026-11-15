import assert from 'node:assert/strict'
import {verifyBrowserEvent} from './stage6-browser-evidence.mjs'
import {createRunViewModel,reduceRunEvent} from '../../vio-ai-agent-frontend/src/api/run-contract.js'
import {digest,sameJson} from './evaluation.mjs'
import {compareCombinationConfiguration} from './upgrade-combination-verify.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'

export function verifyTerminatedApproval(bytes,reference) {
  assert.ok(['cancelled','timed_out'].includes(reference.status))
  const terminal=reference.status==='cancelled'?'run.cancelled':'run.timed_out'
  const events=verifyBrowserEvent(bytes,{runId:reference.runId,sha256:reference.eventsSha256,terminal})
  const approvals=events.filter(event=>event.type==='approval.required')
  assert.equal(approvals.length,1)
  assert.equal(approvals[0].payload.approvalId,reference.proposalId)
  assert.equal(approvals[0].payload.teamChange.proposalId,reference.proposalId)
  const replay=list=>list.reduce(reduceRunEvent,createRunViewModel({runId:reference.runId}))
  const once=replay(events),repeated=replay([...events,...events])
  assert.equal(once.status,reference.status)
  assert.equal(once.approval,null)
  // Missing wire timestamps use the client's current clock for display.
  assert.deepEqual({...repeated,startedAt:once.startedAt,finishedAt:once.finishedAt},once)
  const last=BigInt(events.at(-1).eventId)
  for(const type of ['approval.required','fact.card','calculation.card','answer.delta','run.completed']) {
    assert.deepEqual(reduceRunEvent(once,{runId:reference.runId,eventId:String(last+1n),type,
      payload:{text:'late',approvalId:reference.proposalId}}),once)
  }
  return {status:'VERIFIED',eventCount:events.length,approvalCleared:true,duplicateReplayStable:true,lateEventsIgnored:true}
}

export function verifyTerminationCombination(enabled,disabled,readEvents) {
  const runs=new Set()
  for(const [mode,report] of [['enabled',enabled],['disabled',disabled]]) {
    assert.equal(report.schemaVersion,'stage6-termination-browser-v1');assert.equal(report.mode,mode)
    assert.equal(report.status,'PASS');assert.deepEqual(report.pageErrors,[]);assert.deepEqual(report.cleanup,['LOGGED_OUT'])
    assert.equal(report.runtime.sha256,digest(report.runtime.configuration))
    assert.equal(report.runtimeAfter.sha256,report.runtime.sha256)
    assert.equal(digest(report.runtimeAfter.configuration),report.runtimeAfter.sha256)
    assert.equal(report.runtimeBuild.sha256,runtimeBuild({implementationSha256:report.runtimeBuild.components}).sha256)
    assert.deepEqual(report.runtimeBuildAfter,report.runtimeBuild)
    assert.equal(report.semanticState.ready,true)
    assert.ok(sameJson(report.teamAfter,report.teamBefore))
    assert.deepEqual(report.records.map(row=>row.status),['cancelled','timed_out'])
    assert.equal(report.attempts.length,2)
    for(const row of report.records) {
      assert.ok(!runs.has(row.runId));runs.add(row.runId)
      const admitted=report.attempts.find(attempt=>attempt.runId===row.runId)
      assert.equal(admitted.dataReleaseId,report.teamBefore.dataReleaseId)
      assert.equal(row.preview.status,'PENDING');assert.equal(row.preview.preview.proposalId,row.proposalId)
      assert.equal(row.lateDecisionStatus,row.status==='timed_out'?410:409)
      assert.equal(row.outcome.status,row.status==='timed_out'?'EXPIRED':'CANCELLED')
      assert.equal(row.approvalButtonsHidden,true);assert.equal(row.reloadApprovalHidden,true)
      assert.ok(sameJson(row.teamAfter,report.teamBefore))
      verifyTerminatedApproval(readEvents(mode,row.eventsFile),row)
    }
  }
  compareCombinationConfiguration(enabled.runtime,disabled.runtime)
  assert.equal(enabled.runtimeBuild.sha256,disabled.runtimeBuild.sha256)
  assert.equal(enabled.producerSha256,disabled.producerSha256);assert.match(enabled.producerSha256,/^[a-f0-9]{64}$/)
  assert.ok(sameJson(enabled.frontendSha256,disabled.frontendSha256))
  assert.ok(sameJson(enabled.guideCatalog,disabled.guideCatalog))
  for(const key of ['enabled','configured','ready','mode','model','dimensions','indexedChunks'])
    assert.equal(enabled.semanticState[key],disabled.semanticState[key])
  return {schemaVersion:'termination-combination-evidence-v1',status:'EVIDENCE_VERIFIED',runs:runs.size,
    scope:'PENDING_APPROVAL_CANCEL_DEADLINE_LATE_DECISION_AND_RELOAD',qualityReview:'REQUIRED',releaseDecision:'NOT_EVALUATED'}
}
