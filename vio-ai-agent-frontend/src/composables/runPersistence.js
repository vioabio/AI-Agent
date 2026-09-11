/**
 * 页面级 Run 恢复元数据。
 *
 * 只保存不敏感的 conversationId/runId/lastEventId，答案、Prompt 和任何
 * API Key 都不进入 Web Storage。sessionStorage 天生按标签页隔离，刷新后
 * 可以继续同一 Run，关闭标签页后元数据自动回收。
 */

import { normalizeCursorId } from '../api/run-contract.js'

const RUNS_STORAGE_KEY = 'vio.agent.run-resume.v1'
const CONVERSATION_STORAGE_PREFIX = 'vio.agent.conversation.v1.'
const MAX_ENTRIES = 20

function storage() {
  if (typeof window === 'undefined') return null
  try {
    return window.sessionStorage
  } catch {
    // 隐私模式/浏览器策略可能禁止 Storage；此时退化为纯内存运行。
    return null
  }
}

function readRuns() {
  const store = storage()
  if (!store) return Object.create(null)
  try {
    const parsed = JSON.parse(store.getItem(RUNS_STORAGE_KEY) || '{}')
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return Object.create(null)
    // 丢弃未知/畸形条目并使用无原型对象，避免用户可控 conversationId
    // 触发 __proto__/constructor 属性污染。
    const safe = Object.create(null)
    for (const [key, value] of Object.entries(parsed)) {
      const rawCursor = value?.lastEventId
      const cursorAbsent = rawCursor === null || rawCursor === undefined || rawCursor === ''
      const cursor = cursorAbsent ? null : normalizeCursorId(rawCursor, { allowZero: true })
      if (validText(key) && value && typeof value === 'object'
          && validText(value.runId) && (cursorAbsent || cursor !== null)) {
        safe[key] = {
          runId: value.runId,
          lastEventId: cursor,
          savedAt: Number(value.savedAt || 0)
        }
      }
    }
    return safe
  } catch {
    return Object.create(null)
  }
}

function writeRuns(runs) {
  const store = storage()
  if (!store) return
  try {
    store.setItem(RUNS_STORAGE_KEY, JSON.stringify(runs))
  } catch {
    // 配额满或 Storage 被禁用时不阻塞主链；服务端事件仍可正常消费。
  }
}

function validText(value, max = 256) {
  return typeof value === 'string' && value.length > 0 && value.length <= max
}

function validStorageId(value) {
  return validText(value) && /^[a-zA-Z0-9._:-]+$/.test(value)
}

export function saveRunResume({ conversationId, runId, lastEventId }) {
  if (!validStorageId(conversationId) || !validStorageId(runId)) return
  const runs = readRuns()
  // Persist the same canonical decimal representation used by the reducer;
  // malformed values must never make a refresh restart from an attacker-
  // controlled cursor or poison the next request.
  const cursor = normalizeCursorId(lastEventId, { allowZero: true })
  runs[conversationId] = {
    runId,
    lastEventId: cursor,
    savedAt: Date.now()
  }
  const keys = Object.keys(runs)
  if (keys.length > MAX_ENTRIES) {
    keys.sort((a, b) => Number(runs[a]?.savedAt || 0) - Number(runs[b]?.savedAt || 0))
    keys.slice(0, keys.length - MAX_ENTRIES).forEach(key => delete runs[key])
  }
  writeRuns(runs)
}

export function loadRunResume(conversationId) {
  if (!validStorageId(conversationId)) return null
  const value = readRuns()[conversationId]
  if (!value || !validStorageId(value.runId)) return null
  return {
    runId: value.runId,
    lastEventId: normalizeCursorId(value.lastEventId, { allowZero: true })
  }
}

export function clearRunResume(conversationId, runId = null) {
  if (!validStorageId(conversationId)) return
  const runs = readRuns()
  if (runId && runs[conversationId]?.runId !== runId) return
  delete runs[conversationId]
  writeRuns(runs)
}

/** 为一个标签页生成稳定的会话 ID；不包含用户输入或凭据。 */
export function getOrCreateConversationId(scope = 'default') {
  const normalizedScope = String(scope).replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 32) || 'default'
  const key = `${CONVERSATION_STORAGE_PREFIX}${normalizedScope}`
  const store = storage()
  if (store) {
    try {
      const existing = store.getItem(key)
      if (validText(existing, 128)) return existing
      const created = `${normalizedScope}_${randomId()}`
      store.setItem(key, created)
      return created
    } catch {
      // fall through to an in-memory ID
    }
  }
  return `${normalizedScope}_${randomId()}`
}

function randomId() {
  try {
    if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID().slice(0, 12)
  } catch {
    // older browsers may not expose crypto.randomUUID
  }
  return Math.random().toString(36).slice(2, 14)
}
