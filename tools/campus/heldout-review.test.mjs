import test from 'node:test'
import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {verifyHeldoutReview} from './heldout-review.mjs'

function fixture() {
  const events=JSON.stringify([{runId:'r',eventId:'1',type:'answer.delta',payload:{text:'回答'}},
    {runId:'r',eventId:'2',type:'run.completed',payload:{}}])
  const turn={runId:'r',prompt:'题目',status:'COMPLETED',answer:'回答',eventsFile:'U0-FACTS-05-0-events.json',eventsSha256:digest(events)}
  const bytes=JSON.stringify({mode:'COLLECTED_NOT_REVIEWED',split:'heldout',suiteKind:'FROZEN_HELDOUT_FIRST_ANSWERS',rubricSha256:'fixed',cleanupErrors:[],
    selectedCaseIds:['U0-FACTS-05'],records:[{caseId:'U0-FACTS-05',turns:[turn]}]})
  return {events,bytes,cases:[{id:'U0-FACTS-05',split:'heldout',turns:['题目']}],
    review:{sourceSha256:digest(bytes),rubricSha256:'fixed',reviewMethod:'AI_ASSISTED_FIRST_ANSWER_BODY_AND_EVIDENCE',
      records:[{caseId:'U0-FACTS-05',verdict:'PASS',note:'正文已核对',turns:[{answerSha256:digest(turn.answer),eventsSha256:turn.eventsSha256,note:'事实及事件一致'}]}]}}
}
test('first-answer review binds the fixed rubric, answer and original event bytes',()=>{
  const f=fixture();assert.equal(verifyHeldoutReview(f.bytes,f.review,f.cases,()=>f.events).turns,1)
})
test('changing the answer, rubric or verdict coverage cannot yield verified review',()=>{
  const f=fixture(),changed=structuredClone(f.review)
  changed.records[0].turns[0].answerSha256='other'
  assert.throws(()=>verifyHeldoutReview(f.bytes,changed,f.cases,()=>f.events))
  assert.throws(()=>verifyHeldoutReview(f.bytes,{...f.review,rubricSha256:'changed'},f.cases,()=>f.events))
  assert.throws(()=>verifyHeldoutReview(f.bytes,{...f.review,records:[]},f.cases,()=>f.events))
  const changedQuestion=JSON.parse(f.bytes);changedQuestion.records[0].turns[0].prompt='替换题目'
  const changedBytes=JSON.stringify(changedQuestion)
  assert.throws(()=>verifyHeldoutReview(changedBytes,{...f.review,sourceSha256:digest(changedBytes)},f.cases,()=>f.events))
})
