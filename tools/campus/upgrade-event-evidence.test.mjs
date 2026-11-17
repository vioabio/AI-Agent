import test from 'node:test'
import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {verifyTurnEvents, verifySelectedEvents} from './upgrade-event-evidence.mjs'

function fixture() {
  const events = [
    {eventId:1,runId:'run-example',type:'answer.delta',payload:{text:'已核验'}},
    {eventId:2,runId:'run-example',type:'answer.delta',payload:{text:'事实'}},
    {eventId:3,runId:'run-example',type:'run.completed',payload:{}}
  ]
  const bytes = JSON.stringify(events)
  const turn = {runId:'run-example',status:'COMPLETED',answer:'已核验事实',eventsFile:'U0-FACTS-01-0-events.json',eventsSha256:digest(bytes)}
  return {turn, bytes, events}
}

test('verifies actual answer chunks, Run identity, event order and completion', () => {
  const {turn,bytes} = fixture()
  assert.equal(verifyTurnEvents(turn,'U0-FACTS-01',0,bytes).events,3)
  const report = {selectedCaseIds:['U0-FACTS-01'],records:[{caseId:'U0-FACTS-01',turns:[turn]},{caseId:'U0-FACTS-02'}]}
  assert.deepEqual(verifySelectedEvents(report,() => bytes),{status:'VERIFIED',turns:1,events:3})
})

test('substituted Run events are rejected even with a recomputed file digest', () => {
  const {turn,events} = fixture()
  events[0].runId = 'run-other'
  const bytes = JSON.stringify(events)
  assert.throws(() => verifyTurnEvents({...turn,eventsSha256:digest(bytes)},'U0-FACTS-01',0,bytes),/EVENT_RUN_MISMATCH/)
})

test('missing conflicting and duplicate terminal events cannot pass', () => {
  for (const change of [events => events.pop(),events => events.at(-1).type='run.failed',
    events => events.push({eventId:4,runId:'run-example',type:'run.timed.out'}),
    events => events.push({eventId:4,runId:'run-example',type:'run.completed'})]) {
    const {turn,events} = fixture()
    change(events)
    const bytes = JSON.stringify(events)
    assert.throws(() => verifyTurnEvents({...turn,eventsSha256:digest(bytes)},'U0-FACTS-01',0,bytes),/EVENT_TERMINAL_MISMATCH/)
  }
})

test('reordered or repeated IDs and inconsistent answer chunks are rejected', () => {
  for (const [change,error] of [
    [events => events[1].eventId=1,'EVENT_ORDER_INVALID'],
    [events => events[1].eventId='not-an-id','EVENT_ORDER_INVALID'],
    [events => events[0].payload.text='替换','ANSWER_EVENT_MISMATCH'],
    [events => events[0].payload.text=123,'ANSWER_DELTA_INVALID']
  ]) {
    const {turn,events} = fixture()
    change(events)
    const bytes = JSON.stringify(events)
    assert.throws(() => verifyTurnEvents({...turn,eventsSha256:digest(bytes)},'U0-FACTS-01',0,bytes),new RegExp(error))
  }
})

test('file and turn substitution, changed bytes and non-arrays are rejected', () => {
  const {turn,bytes} = fixture()
  assert.throws(() => verifyTurnEvents(turn,'U0-FACTS-01',1,bytes),/EVENT_FILENAME_MISMATCH/)
  assert.throws(() => verifyTurnEvents(turn,'U0-FACTS-01',0,bytes+' '),/EVENT_HASH_MISMATCH/)
  for (const invalid of ['{}','[]']) {
    assert.throws(() => verifyTurnEvents({...turn,eventsSha256:digest(invalid)},'U0-FACTS-01',0,invalid),/EVENT_ARRAY_REQUIRED/)
  }
})
