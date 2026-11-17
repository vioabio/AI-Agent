import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {verifyTurnEvents} from './upgrade-event-evidence.mjs'

/** Verify first-answer evidence bindings. Verdicts still require content review. */
export function verifyHeldoutReview(baselineBytes,review,cases,readEvents) {
  const baseline=JSON.parse(baselineBytes)
  assert.equal(baseline.mode,'COLLECTED_NOT_REVIEWED')
  assert.equal(baseline.split,'heldout')
  assert.equal(baseline.suiteKind,'FROZEN_HELDOUT_FIRST_ANSWERS')
  assert.equal(review.sourceSha256,digest(baselineBytes))
  assert.equal(review.reviewMethod,'AI_ASSISTED_FIRST_ANSWER_BODY_AND_EVIDENCE')
  assert.equal(review.rubricSha256,baseline.rubricSha256)
  assert.deepEqual(baseline.cleanupErrors,[])
  const expected=cases.filter(row=>row.split==='heldout')
  assert.equal(review.records.length,expected.length)
  assert.equal(new Set(review.records.map(row=>row.caseId)).size,expected.length)
  assert.deepEqual(new Set(baseline.selectedCaseIds),new Set(expected.map(row=>row.id)))
  const counts={PASS:0,FAIL:0,BLOCKED:0},turns=[]
  for(const item of expected) {
    const observed=baseline.records.find(row=>row.caseId===item.id)
    const verdict=review.records.find(row=>row.caseId===item.id)
    assert.ok(observed&&verdict&&Object.hasOwn(counts,verdict.verdict))
    assert.ok(typeof verdict.note==='string'&&verdict.note.length>0)
    assert.equal(verdict.turns.length,observed.turns.length)
    if(verdict.verdict==='PASS')assert.equal(observed.turns.length,item.turns.length)
    for(const [index,turn]of observed.turns.entries()) {
      assert.equal(turn.prompt,item.turns[index])
      assert.equal(turn.eventsFile,`${item.id}-${index}-events.json`)
      assert.equal(verdict.turns[index].eventsSha256,turn.eventsSha256)
      assert.equal(verdict.turns[index].answerSha256,digest(turn.answer))
      assert.ok(verdict.turns[index].note?.length>0)
      const bytes=readEvents(turn.eventsFile)
      assert.equal(digest(bytes),turn.eventsSha256)
      if(turn.status==='COMPLETED')turns.push(verifyTurnEvents(turn,item.id,index,bytes))
      else {assert.notEqual(verdict.verdict,'PASS');turns.push({runId:turn.runId,status:turn.status})}
    }
    counts[verdict.verdict]++
  }
  return {status:'EVIDENCE_BINDINGS_VERIFIED',counts,turns:turns.length,
    reviewMethod:review.reviewMethod,releaseDecision:'NOT_EVALUATED'}
}
