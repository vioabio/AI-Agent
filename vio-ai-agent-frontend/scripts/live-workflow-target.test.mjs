import test from 'node:test'
import assert from 'node:assert/strict'
import { liveWorkflowTargets } from './live-workflow-target.mjs'
const valid = { N5_E2E_BASE_URL: 'http://127.0.0.1:3191', VIO_WORKFLOW_LIVE_API: 'http://127.0.0.1:18150/api' }
test('live workflow requires two explicit dedicated loopback targets', () => {
  assert.equal(liveWorkflowTargets(valid).apiOrigin, 'http://127.0.0.1:18150')
  assert.throws(() => liveWorkflowTargets({}), /Explicit/)
  assert.throws(() => liveWorkflowTargets({ ...valid, VIO_WORKFLOW_LIVE_API: 'http://127.0.0.1:3191/api' }), /different/)
})
test('daily ports and remote, credentialed or ambiguous targets are rejected', () => {
  for (const value of ['http://127.0.0.1:8123/api', 'http://127.0.0.1:3000/api', 'https://127.0.0.1:18150/api',
    'http://example.com:18150/api', 'http://user:secret@localhost:18150/api', 'http://localhost:18150/api?next=x',
    'http://localhost:18150/api#fragment', 'http://localhost/api', 'http://localhost:0/api', 'http://localhost:18150/api/']) {
    assert.throws(() => liveWorkflowTargets({ ...valid, VIO_WORKFLOW_LIVE_API: value }))
  }
})
