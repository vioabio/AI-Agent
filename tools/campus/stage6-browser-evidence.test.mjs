import test from 'node:test'
import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {verifyBrowserEvent} from './stage6-browser-evidence.mjs'

test('approved, denied and conflict browser events bind identity, ordering and the actual terminal',()=>{
  for(const terminal of ['run.completed','run.failed']) {
    const bytes=JSON.stringify([{runId:'run-a',eventId:'1',type:'run.started'},{runId:'run-a',eventId:'2',type:terminal}])
    assert.equal(verifyBrowserEvent(bytes,{runId:'run-a',sha256:digest(bytes),terminal}).length,2)
  }
})
test('changed, foreign, duplicated or wrong-terminal browser events are refused',()=>{
  const ref={runId:'run-a',terminal:'run.completed'}
  for(const rows of [[{runId:'run-b',eventId:'1',type:'run.completed'}],
    [{runId:'run-a',eventId:'1',type:'run.started'},{runId:'run-a',eventId:'1',type:'run.completed'}],
    [{runId:'run-a',eventId:'1',type:'run.failed'}],
    [{runId:'run-a',eventId:'1',type:'run.completed'},{runId:'run-a',eventId:'2',type:'answer.delta'}],[]]) {
    const bytes=JSON.stringify(rows);assert.throws(()=>verifyBrowserEvent(bytes,{...ref,sha256:digest(bytes)}))
  }
  assert.throws(()=>verifyBrowserEvent('[]',{...ref,sha256:'changed'}))
})
