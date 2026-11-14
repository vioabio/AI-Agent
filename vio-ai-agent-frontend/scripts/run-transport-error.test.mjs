import test from 'node:test'
import assert from 'node:assert/strict'
import { createTransportErrorTracker } from '../src/api/run-transport-error.js'

test('successful recovery clears only the recorded network message', () => {
  const errors = createTransportErrorTracker(), model = { status: 'reconnecting', error: null }
  errors.record(model, 'Failed to fetch')
  model.status = 'running'; errors.recover(model)
  assert.equal(model.error, null)
})
test('completed run does not keep an obsolete network error', () => {
  const errors = createTransportErrorTracker(), model = { status: 'running' }
  errors.record(model, 'Failed to fetch'); model.status = 'completed'; errors.recover(model)
  assert.equal(model.error, null)
})
test('real failure and timeout are preserved even when text matches the network error', () => {
  for (const status of ['failed', 'timed_out']) {
    const errors = createTransportErrorTracker(), model = { status: 'running' }
    errors.record(model, 'connection failed'); model.status = status; errors.recover(model)
    assert.equal(model.error, 'connection failed')
  }
})
test('recovery cannot clear another error or another run', () => {
  const errors = createTransportErrorTracker(), a = { status: 'running' }, b = { status: 'running', error: 'permission denied' }
  errors.record(a, 'offline'); a.error = 'invalid event cursor'; errors.recover(a); errors.recover(b)
  assert.equal(a.error, 'invalid event cursor'); assert.equal(b.error, 'permission denied')
})
