import assert from 'node:assert/strict'
import {digest,sameJson} from './evaluation.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {compareCombinationConfiguration} from './upgrade-combination-verify.mjs'

export function verifyBrowserEvent(bytes,{runId,sha256,terminal}) {
  assert.equal(digest(bytes),sha256)
  const events=JSON.parse(bytes);assert.ok(Array.isArray(events)&&events.length>0)
  let previous=0n
  for(const event of events) {
    assert.equal(event.runId,runId)
    assert.match(String(event.eventId??event.id),/^[1-9][0-9]{0,19}$/)
    const next=BigInt(event.eventId??event.id);assert.ok(next>previous);previous=next
  }
  const ends=events.filter(e=>['run.completed','run.failed','run.cancelled','run.timed.out','run.timed_out'].includes(e.type))
  assert.equal(ends.length,1);assert.equal(ends[0].type,terminal)
  assert.equal(events.at(-1),ends[0],'Event after terminal')
  return events
}

export function verifyBrowserCombination(enabled,disabled,readEvents) {
  const runs=new Set(),schemas={cards:'provider-cards-browser-v1',hitl:'stage6-hitl-browser-v1',governance:'stage6-conflict-account-browser-v1'}
  const summaries=[]
  for(const [mode,group]of [['enabled',enabled],['disabled',disabled]]) {
    const check=(kind,reference)=>{
      assert.ok(!runs.has(reference.runId),'Repeated browser Run');runs.add(reference.runId)
      return verifyBrowserEvent(readEvents(mode,kind,reference.file),reference)
    }
    for(const [kind,schema]of Object.entries(schemas)) {
      const report=group[kind];assert.equal(report.schemaVersion,schema)
      assert.equal(report.status,kind==='cards'?'PASS':'COLLECTED_PENDING_CONTENT_REVIEW')
      assert.deepEqual(report.pageErrors,[]);assert.ok(Array.isArray(report.cleanup)&&report.cleanup.includes('LOGGED_OUT'))
      assert.ok(!report.cleanup.some(value=>/FAILED|UNVERIFIED/.test(value)))
      assert.equal(report.runtime.sha256,digest(report.runtime.configuration))
      assert.equal(report.runtimeAfter.sha256,report.runtime.sha256)
      assert.equal(digest(report.runtimeAfter.configuration),report.runtimeAfter.sha256)
      assert.equal(report.runtimeBuild.sha256,runtimeBuild({implementationSha256:report.runtimeBuild.components}).sha256)
      assert.deepEqual(report.runtimeBuildAfter,report.runtimeBuild)
      assert.equal(report.runtimeBuild.sha256,group.cards.runtimeBuild.sha256)
      assert.equal(report.runtime.sha256,group.cards.runtime.sha256)
      assert.ok(sameJson(report.guideCatalog,group.cards.guideCatalog))
      assert.equal(report.semanticState.ready,true)
    }
    assert.equal(group.cards.mode,mode);assert.equal(group.cards.records.length,4);assert.deepEqual(group.cards.teamMutationRequests,[])
    for(const row of group.cards.records) {
      assert.equal(row.checks,'PASS');assert.equal(row.narrowViewportChecked,true)
      const events=check('cards',{runId:row.turn.runId,file:row.turn.eventsFile,sha256:row.turn.eventsSha256,terminal:'run.completed'})
      assert.equal(events.filter(e=>e.type==='answer.delta').map(e=>e.payload.text).join(''),row.turn.answer)
    }
    assert.deepEqual(group.hitl.records.map(row=>row.approved),[false,true])
    for(const row of group.hitl.records) {
      assert.equal(row.outcome.status,row.approved?'APPLIED':'DENIED')
      assert.equal(row.decision.approved,row.approved)
      assert.equal(row.decision.approvalId,row.preview.preview.proposalId)
      const expected=structuredClone(group.hitl.teamBefore)
      if(row.approved)expected.members[0].build.level=52
      assert.ok(sameJson(row.after,expected),'Unexpected team mutation')
      check('hitl',{runId:row.runId,file:row.eventsFile,sha256:row.eventsSha256,terminal:row.approved?'run.completed':'run.failed'})
    }
    assert.ok(sameJson(group.hitl.restoredTeam,group.hitl.teamBefore))
    assert.equal(group.governance.scope,'governance')
    assert.deepEqual(group.governance.records.map(row=>row.caseId),['S6-06','S6-04'])
    const [conflict,account]=group.governance.records
    assert.equal(conflict.applied.status,'APPLIED');assert.equal(conflict.conflict.status,'CONFLICT')
    const expected=structuredClone(group.governance.teamBefore);expected.members[0].build.level=52
    assert.ok(sameJson(conflict.teamAfter,expected));assert.ok(sameJson(group.governance.restoredTeam,group.governance.teamBefore))
    for(const row of [conflict.first,conflict.stale])check('governance',{runId:row.runId,file:row.eventsFile,sha256:row.eventsSha256,terminal:'run.completed'})
    assert.equal(account.browserSwitch,true);assert.equal(account.privateContentHidden,true);assert.equal(account.ownTeamsOnly,true)
    assert.notEqual(account.nextConversationId,account.oldConversationId)
    assert.equal(account.accountEventFiles.length,2)
    for(const row of account.accountEventFiles)check('governance',{...row,terminal:'run.completed'})
    summaries.push({mode,cards:4,approvalDecisions:2,conflictAndAccountFlows:2})
  }
  compareCombinationConfiguration(enabled.cards.runtime,disabled.cards.runtime)
  for(const kind of ['cards','hitl']) {
    assert.match(enabled[kind].producerSha256,/^[a-f0-9]{64}$/)
    assert.equal(enabled[kind].producerSha256,disabled[kind].producerSha256)
  }
  assert.ok(sameJson(enabled.cards.frontendComponentsSha256,disabled.cards.frontendComponentsSha256))
  assert.ok(sameJson(enabled.governance.sourceSha256,disabled.governance.sourceSha256))
  assert.equal(enabled.cards.runtimeBuild.sha256,disabled.cards.runtimeBuild.sha256)
  assert.ok(sameJson(enabled.cards.guideCatalog,disabled.cards.guideCatalog))
  for(const key of ['enabled','configured','ready','mode','model','dimensions','indexedChunks'])
    assert.equal(enabled.cards.semanticState[key],disabled.cards.semanticState[key])
  return {schemaVersion:'browser-combination-evidence-v1',status:'EVIDENCE_VERIFIED',runs:runs.size,groups:summaries,
    qualityReview:'REQUIRED',releaseDecision:'NOT_EVALUATED'}
}
