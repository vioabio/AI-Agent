import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeToolTiming, normalizeResponseTiming, normalizeClientTiming, formatToolTiming } from '../src/api/response-timing.js'
import { normalizeModelUsage } from '../src/api/model-usage.js'

const row = { accepted: true, callbackStarted: true, callbackCompleted: true, queueWaitMs: 12, authorizationMs: 2, executionMs: 20, observedMs: 35 }
const tool = { schemaVersion: 'tool-timing-v1', scope: 'CALLBACK_ATTEMPTS', complete: true, attempts: [row],
  queueWaitMs: 12, authorizationMs: 2, executionMs: 20, observedMs: 35 }
test('retry durations are independently validated and cache reuse is never zero callback execution', () => {
  const raw = { ...tool, attempts: [row, row], queueWaitMs: 24, authorizationMs: 4, executionMs: 40, observedMs: 70 }
  assert.equal(normalizeToolTiming(raw).executionMs, 40)
  assert.match(formatToolTiming(raw), /排队 24 ms.*执行 40 ms/)
  const bypass = normalizeToolTiming({ schemaVersion: 'tool-timing-v1', scope: 'BYPASS', reason: 'CACHED_RESULT', executionMs: 0 })
  assert.equal(bypass.executionMs, undefined)
  assert.equal(formatToolTiming(bypass), '本次未执行回调')
  assert.equal(normalizeToolTiming({ ...raw, queueWaitMs: 12 }), null)
})
test('partial observations and missing callback durations remain explicit and sanitized', () => {
  const partial = { ...tool, complete: false, attempts: [{ ...row, callbackCompleted: false }] }
  assert.match(formatToolTiming(partial), /未确认结束/)
  const pending = { ...tool, complete: false, attempts: [{ ...row, callbackStarted: false, callbackCompleted: false,
    queueWaitMs: 35, authorizationMs: null, executionMs: null }], queueWaitMs: 35, authorizationMs: null, executionMs: null }
  assert.equal(normalizeToolTiming(pending).executionMs, null)
  for (const raw of [null, {}, { ...tool, executionMs: -1 }, { ...tool, attempts: [{ ...row, observedMs: 1 }] }]) assert.equal(normalizeToolTiming(raw), null)
})
test('server readiness stays distinct from SDK text and browser receipt, including buffered answers', () => {
  const response = { schemaVersion: 'response-timing-v1', scope: 'EXECUTION_ATTEMPT', origin: 'TASK_REGISTERED', dispatchWaitMs: 20,
    firstAnswerReadyMs: 50, answerPath: 'BUFFERED', observedMs: 60, deliveryMeasured: false, secret: 'private' }
  assert.equal(normalizeResponseTiming(response).answerPath, 'BUFFERED')
  assert.doesNotMatch(JSON.stringify(normalizeResponseTiming(response)), /private/)
  assert.equal(normalizeResponseTiming({ ...response, firstAnswerReadyMs: 10 }), null)
  assert.equal(normalizeResponseTiming({ ...response, deliveryMeasured: true }), null)
  assert.equal(normalizeClientTiming({ schemaVersion: 'client-response-timing-v1', source: 'BROWSER_OBSERVED', firstAnswerReceivedMs: 100 }).firstAnswerReceivedMs, 100)
  assert.equal(normalizeClientTiming({ schemaVersion: 'client-response-timing-v1', source: 'SERVER', firstAnswerReceivedMs: 100 }), null)
})
test('timing history remains stable when usage is repeatedly normalized and secrets are discarded', () => {
  const raw = { schemaVersion: 'model-usage-v1', scope: 'EXECUTION_ATTEMPT', attemptId: 'a1111111-1111-4111-a111-111111111111',
    coverage: 'INSTRUMENTED', modelCallCount: 0, callsWithUsage: 0, calls: [], usageStatus: 'COMPLETE', modelDurationMs: 0,
    totals: { inputTokens: 0, outputTokens: 0, totalTokens: 0 }, toolTimings: [{ eventId: 'event', toolName: 'lookup', timing: { ...tool, secret: 'private' } }] }
  const value = normalizeModelUsage(raw)
  assert.deepEqual(normalizeModelUsage(value), value)
  assert.equal(value.toolTimings[0].timing.queueWaitMs, 12)
  assert.doesNotMatch(JSON.stringify(value), /private/)
})
