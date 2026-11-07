import test from 'node:test'
import assert from 'node:assert/strict'
import {
  clearRunResume,
  getOrCreateConversationId,
  loadRunResume,
  saveRunResume
} from '../src/composables/runPersistence.js'

class MemoryStorage {
  #values = new Map()
  getItem(key) { return this.#values.has(key) ? this.#values.get(key) : null }
  setItem(key, value) { this.#values.set(key, String(value)) }
  removeItem(key) { this.#values.delete(key) }
}

test('Run 恢复元数据只保存可重连游标并支持清理', () => {
  const previousWindow = globalThis.window
  globalThis.window = { sessionStorage: new MemoryStorage() }
  try {
    const conversationId = getOrCreateConversationId('game')
    assert.equal(conversationId, getOrCreateConversationId('game'))
    saveRunResume({ conversationId, runId: 'run-1', lastEventId: 12 })
    assert.deepEqual(loadRunResume(conversationId), { runId: 'run-1', lastEventId: '12' })
    clearRunResume(conversationId, 'different-run')
    assert.deepEqual(loadRunResume(conversationId), { runId: 'run-1', lastEventId: '12' })
    clearRunResume(conversationId, 'run-1')
    assert.equal(loadRunResume(conversationId), null)
  } finally {
    if (previousWindow === undefined) delete globalThis.window
    else globalThis.window = previousWindow
  }
})

test('恢复存储拒绝非法游标并规范化 int64/零游标', () => {
  const previousWindow = globalThis.window
  const store = new MemoryStorage()
  globalThis.window = { sessionStorage: store }
  try {
    const conversationId = 'safe-conversation'
    saveRunResume({ conversationId, runId: 'run-safe', lastEventId: '0007' })
    assert.deepEqual(loadRunResume(conversationId), { runId: 'run-safe', lastEventId: '7' })

    saveRunResume({ conversationId, runId: 'run-safe', lastEventId: 0 })
    assert.deepEqual(loadRunResume(conversationId), { runId: 'run-safe', lastEventId: '0' })

    // A malformed value from a manually edited sessionStorage entry is
    // discarded instead of being replayed as an arbitrary query cursor.
    store.setItem('vio.agent.run-resume.v1', JSON.stringify({
      bad: { runId: 'run-bad', lastEventId: 'not-a-number', savedAt: 1 },
      good: { runId: 'run-good', lastEventId: '9223372036854775807', savedAt: 2 }
    }))
    assert.equal(loadRunResume('bad'), null)
    assert.deepEqual(loadRunResume('good'), {
      runId: 'run-good', lastEventId: '9223372036854775807'
    })
  } finally {
    if (previousWindow === undefined) delete globalThis.window
    else globalThis.window = previousWindow
  }
})
