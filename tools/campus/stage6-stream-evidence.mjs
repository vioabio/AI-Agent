import assert from 'node:assert/strict'
import {createRunViewModel, reduceRunEvent} from '../../vio-ai-agent-frontend/src/api/run-contract.js'

export function verifyFailureReconnect(runId, before, events, tail, cursor) {
  let prior = 0n
  for (const event of events) {
    assert.equal(event.runId, runId)
    assert.ok(BigInt(event.eventId) > prior)
    prior = BigInt(event.eventId)
  }
  assert.deepEqual(events.slice(0, before.length), before)
  assert.deepEqual(tail, events.filter(event => BigInt(event.eventId) > BigInt(cursor)))
  assert.equal(events.filter(event => event.type === 'run.failed').length, 1)
  assert.equal(events.at(-1)?.type, 'run.failed', 'OUTPUT_AFTER_FAILURE')
  assert.equal(events.some(event => ['run.completed','answer.completed','run.cancelled'].includes(event.type)), false)
  assert.ok(tail.some(event => event.type === 'answer.delta'), 'DISCONNECTED_ANSWER_TAIL_REQUIRED')
  const replay = list => list.reduce((view, event) => reduceRunEvent(view, event), createRunViewModel({runId,status:'RUNNING'}))
  const full = replay(events), repeated = replay([...before,...tail,...events,...tail])
  assert.equal(full.status, 'failed'); assert.equal(repeated.status, 'failed')
  assert.equal(full.answer, '部分测试回答'); assert.equal(repeated.answer, full.answer)
  return {status:'VERIFIED',cursor,eventCount:events.length,tailEvents:tail.length,
    duplicateReplayStable:true,partialAnswer:full.answer,errorType:events.find(event=>event.type==='run.failed').payload.errorType}
}
