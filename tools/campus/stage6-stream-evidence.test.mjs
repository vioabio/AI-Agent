import test from 'node:test'
import assert from 'node:assert/strict'
import {verifyFailureReconnect} from './stage6-stream-evidence.mjs'

const events = [
  {runId:'fault',eventId:'1',type:'answer.delta',payload:{text:'部分测试'}},
  {runId:'fault',eventId:'2',type:'answer.delta',payload:{text:'回答'}},
  {runId:'fault',eventId:'3',type:'run.failed',payload:{errorType:'MODEL_STREAM_FAILED'}}
]
test('missed answer tail and duplicate cursor replay preserve the failed partial answer once', () => {
  assert.equal(verifyFailureReconnect('fault',events.slice(0,1),events,events.slice(1),'1').duplicateReplayStable,true)
})
test('missing cursor events, different ownership and false completion are rejected', () => {
  assert.throws(()=>verifyFailureReconnect('fault',events.slice(0,1),events,events.slice(2),'1'))
  assert.throws(()=>verifyFailureReconnect('another-run',events.slice(0,1),events,events.slice(1),'1'))
  const completed=[...events.slice(0,2),{...events[2],type:'run.completed'}]
  assert.throws(()=>verifyFailureReconnect('fault',completed.slice(0,1),completed,completed.slice(1),'1'))
})
test('late output after failure cannot be hidden by the frontend terminal guard', () => {
  const late=[...events,{runId:'fault',eventId:'4',type:'answer.delta',payload:{text:'late'}}]
  assert.throws(()=>verifyFailureReconnect('fault',late.slice(0,1),late,late.slice(1),'1'), /OUTPUT_AFTER_FAILURE/)
})
