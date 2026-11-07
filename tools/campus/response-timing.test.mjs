import test from 'node:test'
import assert from 'node:assert/strict'
import { checkTimingAttempts } from './response-timing.mjs'

const timing = () => ({ schemaVersion: 'response-timing-v1', scope: 'EXECUTION_ATTEMPT', origin: 'TASK_REGISTERED',
  dispatchWaitMs: 2, firstAnswerReadyMs: 12, observedMs: 20, answerPath: 'DELTA', deliveryMeasured: false })
const tool = () => ({ schemaVersion: 'tool-timing-v1', scope: 'BYPASS', reason: 'CACHED_RESULT' })
const attempt = () => ({ attemptId: 'a1', responseTiming: timing(), calls: [], toolTimingsTruncated: false,
  toolTimings: [{ eventId: 'g1', toolName: 'lookup', timing: tool() }] })
const events = () => [{ type: 'tool.finished', payload: { governanceEventId: 'g1', toolName: 'lookup', timing: tool() } }]

test('timing audit reconciles callback events with persisted attempts without inventing an SDK stream', () => {
  const result = checkTimingAttempts([attempt()], events(), 'COMPLETED')
  assert.equal(result.pass, true)
  assert.equal(result.rows[0].sdkStreamCalls, 0)
  assert.equal(result.browserTiming, 'NOT_MEASURED_BY_NODE_COLLECTOR')
})
test('approval pause may have no public answer; missing, altered and truncated timing evidence fails', () => {
  const a = attempt(); a.responseTiming.firstAnswerReadyMs = null; a.responseTiming.answerPath = null
  assert.equal(checkTimingAttempts([a], events(), 'WAITING_APPROVAL').pass, true)
  assert.equal(checkTimingAttempts([a], events(), 'COMPLETED').pass, false)
  assert.equal(checkTimingAttempts([], [], 'COMPLETED').pass, false)
  const changed = attempt(); changed.toolTimings[0].timing.reason = 'IDEMPOTENT_REPLAY'
  assert.equal(checkTimingAttempts([changed], events(), 'COMPLETED').pass, false)
  const truncated = attempt(); truncated.toolTimingsTruncated = true
  assert.equal(checkTimingAttempts([truncated], events(), 'COMPLETED').pass, false)
})
test('duplicate or invalid event evidence and conflicting attempt history cannot pass', () => {
  const invalid = events(); invalid[0].payload.timing.reason = 'RAW_PLUGIN_TEXT'
  assert.equal(checkTimingAttempts([attempt()], invalid, 'COMPLETED').pass, false)
  const conflict = events(); conflict.push({ type: 'tool.finished', payload: { ...conflict[0].payload, timing: { ...tool(), reason: 'IDEMPOTENT_REPLAY' } } })
  assert.equal(checkTimingAttempts([attempt()], conflict, 'COMPLETED').pass, false)
  assert.equal(checkTimingAttempts([attempt(), attempt()], events(), 'COMPLETED').pass, false)
})
