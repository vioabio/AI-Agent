// Isolated local accounts only. Credentials and cookies live in memory and are never written.
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomBytes } from 'node:crypto'
import { localBase } from './evaluation.mjs'
import { normalizeModelUsage } from '../../vio-ai-agent-frontend/src/api/model-usage.js'
import { checkContextAttempts } from './context-budget.mjs'
const [baseArg, outputArg, mode = 'conversation'] = process.argv.slice(2)
if (!baseArg || !outputArg || !['conversation', 'blocked'].includes(mode)) throw Error('Usage: node tools/campus/s4-context-scenario.mjs LOCAL_API NEW_OUTPUT [conversation|blocked]')
const base = localBase(baseArg), output = resolve(outputArg)
if (existsSync(output)) throw Error('Never overwrite evidence')
mkdirSync(output, { recursive: true })
const report = { schemaVersion: 1, mode, startedAt: new Date().toISOString(), plannedRuns: mode === 'conversation' ? 3 : 1,
  records: Array.from({ length: mode === 'conversation' ? 3 : 1 }, (_, i) => ({ round: i + 1, status: 'NOT_RUN' })), cost: 'UNKNOWN' }
function client() {
  const cookies = new Map()
  return async (path, body, method = body === undefined ? 'GET' : 'POST', expected = 200) => {
    const response = await fetch(base + path, { method, redirect: 'error', signal: AbortSignal.timeout(30000),
      headers: { Origin: 'http://localhost:3000', 'Content-Type': 'application/json', Cookie: [...cookies].map(([n, v]) => `${n}=${v}`).join('; '),
        ...(cookies.get('CSRF-TOKEN') ? { 'X-CSRF-TOKEN': cookies.get('CSRF-TOKEN') } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    for (const line of response.headers.getSetCookie()) { const [n, ...v] = line.split(';')[0].split('='); cookies.set(n, v.join('=')) }
    if (response.status !== expected) throw Error(`HTTP ${response.status}; expected ${expected}`)
    const text = await response.text()
    const value = text ? JSON.parse(text) : null
    return value && Object.hasOwn(value, 'code') && Object.hasOwn(value, 'data') ? value.data : value
  }
}
async function account(api) {
  if ((await api('/auth/session')).mode !== 'local') throw Error('Requires local account mode')
  const username = 'ctx_' + randomBytes(6).toString('hex'), password = randomBytes(24).toString('base64url')
  await api('/auth/local/register', { username, password, nickname: '上下文开发验证' }, 'POST', 201)
  await api('/auth/local/login', { username, password })
}
const api = client(), foreign = client(), opened = new Set(), conversation = 'ctx-' + randomBytes(12).toString('hex')
const save = () => writeFileSync(resolve(output, 'scenario.json'), JSON.stringify(report, null, 2) + '\n')
try {
  await account(api); report.runtime = await api('/chat/runtime')
  if (!report.runtime.features.contextBudget || mode === 'conversation' && report.runtime.contextBudgetPolicy.historyTurns !== 1
      || mode === 'blocked' && report.runtime.contextBudgetPolicy.contextTokens !== 512) throw Error('Use isolated scenario policy: historyTurns=1 or blocked contextTokens=512')
  const prompts = mode === 'blocked' ? ['你好'] : [
    '我的明确限制：不使用传说宝可梦，只讨论朱紫休闲单打。请简短确认。不要调用工具。',
    '再次明确限制：不使用传说宝可梦，只讨论休闲单打。请用一句话确认，不要调用工具。',
    '请用一句话复述你目前可见的对战限制，不要调用工具。'
  ]
  for (const [index, prompt] of prompts.entries()) {
    const record = report.records[index], events = [], seen = new Set()
    try {
      const run = await api('/chat/runs', { prompt, conversationId: conversation, gameReleaseId: 'pokemon-scarlet-violet', battleFormat: 'casual-singles',
        rulesetVersionId: 'casual-v1', dataReleaseId: 'pokeapi-reference-v1', language: 'zh-CN', timeoutSeconds: 120,
        idempotencyKey: randomBytes(16).toString('hex') }, 'POST', 202)
      opened.add(run.runId); record.runId = run.runId
      let cursor = '0', terminal, deadline = Date.now() + 140000
      while (!terminal && Date.now() < deadline) {
        const page = await api(`/chat/runs/${run.runId}/events?afterEventId=${cursor}`)
        for (const event of page.events || []) {
          const id = String(event.eventId); if (seen.has(id)) continue
          seen.add(id); events.push(event); cursor = id
          if (['run.completed', 'run.failed', 'run.cancelled', 'run.timed_out'].includes(event.type)) terminal = event
        }
        if (!terminal) await new Promise(done => setTimeout(done, 350))
      }
      if (!terminal) throw Error('Missing terminal event')
      opened.delete(run.runId); record.status = terminal.type
      const attempts = [...new Map(events.flatMap(e => { const u = e.type === 'usage' ? e.payload : e.payload?.modelUsage; return u ? [[u.attemptId, u]] : [] })).values()]
      record.context = checkContextAttempts(attempts)
      const history = await api(`/chat/conversations/${conversation}/messages`), answer = history.find(m => m.runId === run.runId && m.role === 'assistant')
      record.checks = { context: record.context.pass }
      if (mode === 'blocked') {
        record.checks.blocked = terminal.type === 'run.failed' && terminal.payload.errorType === 'RUN_CONTEXT_BUDGET_EXCEEDED'
        record.checks.noProviderCall = attempts.length === 1 && attempts[0].modelCallCount === 0 && attempts[0].contextBudget.status === 'BLOCKED'
        record.checks.noAssistantSaved = !answer
      } else {
        const text = events.filter(e => e.type === 'answer.delta').map(e => e.payload.text).join('')
        record.answer = text
        record.checks.completed = terminal.type === 'run.completed'
        record.checks.history = !!answer && answer.content === text && JSON.stringify(answer.metadata.modelUsageAttempts.map(normalizeModelUsage)) === JSON.stringify(attempts.map(normalizeModelUsage))
        record.checks.historyBoundary = attempts[0]?.contextBudget.availableHistoryTurns === index && attempts[0]?.contextBudget.omittedHistoryTurns === Math.max(0, index - 1)
        record.checks.restrictionMentioned = index < 2 || /(?:不使用|不用|排除|禁止|不含|不选用|不采用|避免使用).{0,8}(?:传说|神兽)/.test(text) && /休闲|单打/.test(text)
      }
      record.pass = Object.values(record.checks).every(Boolean)
      writeFileSync(resolve(output, `round-${index + 1}-events.json`), JSON.stringify(events, null, 2) + '\n')
    } catch (error) { record.status = 'COLLECTION_ERROR'; record.error = error.message; record.pass = false }
    save()
  }
  await account(foreign); await foreign(`/chat/conversations/${conversation}/messages`, undefined, 'GET', 404); report.foreignOwnerDenied = true
} catch (error) { report.setupError = error.message; process.exitCode = 1 }
finally {
  for (const id of opened) { try { await api(`/chat/runs/${id}/cancel`, {}) } catch { report.cleanupFailed = true } }
  for (const client of [api, foreign]) { try { await client('/logout', undefined, 'POST', 204) } catch { report.cleanupFailed = true } }
  report.finishedAt = new Date().toISOString(); report.passed = report.records.filter(r => r.pass).length; save()
}
if (report.passed !== report.plannedRuns || !report.foreignOwnerDenied) process.exitCode = 1
console.log(JSON.stringify({ mode, plannedRuns: report.plannedRuns, passed: report.passed, foreignOwnerDenied: report.foreignOwnerDenied, setupError: report.setupError }))
