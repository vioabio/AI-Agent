import test from 'node:test'
import assert from 'node:assert/strict'
import { defaultConstraints, normalizeConstraints } from '../src/api/conversation-constraints.js'
import { fetchConversationConstraints, saveConversationConstraints } from '../src/api/index.js'
test('explicit preferences whitelist fields and reject invalid revisions, enums and oversized notes', () => {
  const initial = defaultConstraints()
  assert.deepEqual(normalizeConstraints({ ...initial, inferred: 'model text', userId: 'foreign' }), initial)
  for (const patch of [{ revision: -1 }, { revision: 1.5 }, { avoidLegendary: 'true' }, { notes: 'x'.repeat(801) },
    { notes: '\u0000' }, { responseStyle: 'ADMIN' }, { trainingBudget: 'FREE_MODEL_CALLS' }]) assert.equal(normalizeConstraints({ ...initial, ...patch }), null)
  assert.equal(normalizeConstraints({ ...initial, notes: '  新手\n简洁  ' }).notes, '新手\n简洁')
})
test('restoring defaults keeps the current revision for compare-and-set', () => {
  const current = { ...defaultConstraints(), revision: 4, notes: 'previous' }
  const cleared = { ...defaultConstraints(), revision: current.revision }
  assert.equal(normalizeConstraints(cleared).revision, 4)
  assert.equal(normalizeConstraints(cleared).notes, '')
})
test('preference requests use same-origin Cookie and CSRF without placing notes in a URL', async () => {
  const oldFetch = globalThis.fetch, oldDocument = globalThis.document, calls = []
  globalThis.document = { cookie: 'CSRF-TOKEN=fixture' }
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return new Response(JSON.stringify(defaultConstraints()), { status: 200 }) }
  try {
    await fetchConversationConstraints('game-c')
    await saveConversationConstraints('game-c', { ...defaultConstraints(), notes: 'private preference' })
    assert.equal(calls[0].options.credentials, 'include')
    assert.equal(calls[1].options.method, 'PUT')
    assert.equal(calls[1].options.headers['X-CSRF-TOKEN'], 'fixture')
    assert.equal(calls[1].url, '/api/chat/conversations/game-c/constraints')
    assert.equal(JSON.parse(calls[1].options.body).notes, 'private preference')
  } finally { globalThis.fetch = oldFetch; if (oldDocument === undefined) delete globalThis.document; else globalThis.document = oldDocument }
})
