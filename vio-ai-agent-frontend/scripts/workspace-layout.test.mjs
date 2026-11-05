import test from 'node:test'
import assert from 'node:assert/strict'
import { groupConversations, readLayoutPreference, saveLayoutPreference } from '../src/api/workspace-layout.js'

test('历史按本地日历分组，昨天使用日历运算，不依赖24小时长度', () => {
  const today = new Date(2026, 10, 4, 12)
  const groups = groupConversations([
    { id: 'old', updatedAt: new Date(2026, 9, 20).toISOString() },
    { id: 'today', updatedAt: new Date(2026, 10, 4, 1).toISOString() },
    { id: 'yesterday', createdAt: new Date(2026, 10, 3, 23).toISOString() },
    { id: 'unknown' }
  ], today)
  assert.deepEqual(groups.map(g => g.label), ['今天', '昨天', '更早'])
  assert.deepEqual(groups.map(g => g.items.map(i => i.id)), [['today'], ['yesterday'], ['old', 'unknown']])
})
test('布局偏好在存储不可用或类型损坏时使用默认值', () => {
  const before = globalThis.localStorage
  try {
    globalThis.localStorage = { getItem: () => { throw Error('blocked') }, setItem: () => { throw Error('blocked') } }
    assert.equal(readLayoutPreference('developer-mode', false), false)
    assert.doesNotThrow(() => saveLayoutPreference('developer-mode', true))
    globalThis.localStorage = { getItem: () => '"true"' }
    assert.equal(readLayoutPreference('developer-mode', false), false)
    globalThis.localStorage = { getItem: () => 'true' }
    assert.equal(readLayoutPreference('developer-mode', false), true)
  } finally { if (before === undefined) delete globalThis.localStorage; else globalThis.localStorage = before }
})
