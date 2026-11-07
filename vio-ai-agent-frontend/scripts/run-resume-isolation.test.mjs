import test from 'node:test'
import assert from 'node:assert/strict'
import { webcrypto } from 'node:crypto'
import { clearAllRunResume, clearRunResume, getOrCreateConversationId, getRunResumeEpoch,
  loadRunResume, saveRunResume, syncRunResumeScope } from '../src/composables/runPersistence.js'

class MemoryStorage {
  values = new Map()
  get length() { return this.values.size }
  key(index) { return [...this.values.keys()][index] ?? null }
  getItem(key) { return this.values.get(key) ?? null }
  setItem(key, value) { this.values.set(key, String(value)) }
  removeItem(key) { this.values.delete(key) }
}
function setup(t, crypto = webcrypto) {
  const windowDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'window')
  const cryptoDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'crypto')
  const store = new MemoryStorage()
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { sessionStorage: store } })
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: crypto })
  t.after(() => {
    if (windowDescriptor) Object.defineProperty(globalThis, 'window', windowDescriptor)
    else delete globalThis.window
    if (cryptoDescriptor) Object.defineProperty(globalThis, 'crypto', cryptoDescriptor)
    else delete globalThis.crypto
  })
  return store
}
const alice = { mode: 'oidc', authenticated: true, principalId: 'alice', tenantId: 'tenant-a', roles: ['user'] }
const pointer = { conversationId: 'conv-a', runId: 'run-a', lastEventId: '9007199254740993' }

test('同一账号刷新保留精确游标，只持久化身份摘要', async t => {
  const store = setup(t)
  await syncRunResumeScope(alice)
  saveRunResume(pointer)
  const conversation = getOrCreateConversationId('workbench')
  const epoch = getRunResumeEpoch()
  await syncRunResumeScope({ ...alice })
  assert.equal(getRunResumeEpoch(), epoch)
  assert.deepEqual(loadRunResume('conv-a'), { runId: 'run-a', lastEventId: '9007199254740993' })
  assert.equal(getOrCreateConversationId('workbench'), conversation)
  const owner = store.getItem('vio.agent.resume-owner.v1')
  assert.match(owner, /^[a-f0-9]{64}$/)
  assert.doesNotMatch(JSON.stringify([...store.values]), /alice|tenant-a/)
})

test('账号、租户或角色变化清旧Run和会话ID，保留无关Storage', async t => {
  const store = setup(t)
  store.setItem('unrelated', 'keep')
  for (const next of [{ ...alice, principalId: 'bob' }, { ...alice, tenantId: 'tenant-b' },
    { ...alice, roles: ['admin'] }]) {
    await syncRunResumeScope(alice)
    saveRunResume(pointer)
    getOrCreateConversationId('workbench')
    await syncRunResumeScope(next)
    assert.equal(loadRunResume('conv-a'), null)
    assert.equal(store.getItem('vio.agent.conversation.v1.workbench'), null)
    assert.equal(store.getItem('unrelated'), 'keep')
  }
})

test('退出后的旧Run回调不能回写，也不能删掉新账号指针', async t => {
  setup(t)
  await syncRunResumeScope(alice)
  const oldEpoch = getRunResumeEpoch()
  saveRunResume(pointer, { epoch: oldEpoch })
  clearAllRunResume()
  saveRunResume(pointer, { epoch: oldEpoch })
  assert.equal(loadRunResume('conv-a'), null)
  await syncRunResumeScope({ ...alice, principalId: 'bob' })
  saveRunResume({ ...pointer, runId: 'run-b' })
  clearRunResume('conv-a', null, { epoch: oldEpoch })
  assert.equal(loadRunResume('conv-a').runId, 'run-b')
})

test('匿名OIDC清除所有本应用指针，不清理其它应用数据', async t => {
  const store = setup(t)
  await syncRunResumeScope(alice)
  saveRunResume(pointer)
  getOrCreateConversationId('workbench')
  store.setItem('foreign', 'keep')
  await syncRunResumeScope({ ...alice, authenticated: false })
  assert.deepEqual([...store.values], [['foreign', 'keep']])
})

test('失效异步摘要不能把已退出的账号重新写回Storage', async t => {
  let finish
  const store = setup(t, { subtle: { digest: () => new Promise(resolve => { finish = resolve }) } })
  store.setItem('foreign', 'keep')
  let current = true
  const pending = syncRunResumeScope(alice, { isCurrent: () => current })
  current = false
  clearAllRunResume()
  finish(new Uint8Array(32).buffer)
  await pending
  assert.equal(store.getItem('vio.agent.resume-owner.v1'), null)
  assert.deepEqual([...store.values], [['foreign', 'keep']])
})

test('无WebCrypto或摘要失败时不保留跨刷新账号指针', async t => {
  const store = setup(t, {})
  saveRunResume(pointer)
  getOrCreateConversationId('workbench')
  await syncRunResumeScope(alice)
  assert.equal(loadRunResume('conv-a'), null)
  assert.equal(store.length, 0)
})
