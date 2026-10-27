// Loopback-only accounts. Credentials and cookies remain in memory; all planned outcomes are retained.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomBytes, createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { localBase } from './evaluation.mjs'
import { checkTaskAttempts } from './task-budget.mjs'
import { checkContextAttempts } from './context-budget.mjs'
import { normalizeModelUsage } from '../../vio-ai-agent-frontend/src/api/model-usage.js'
const [baseArg, outputArg, mode] = process.argv.slice(2)
if (!baseArg || !outputArg || !['calls', 'tokens', 'success'].includes(mode)) throw Error('Usage: node tools/campus/s4-task-scenario.mjs LOCAL_API NEW_OUTPUT calls|tokens|success')
const base = localBase(baseArg), output = resolve(outputArg)
if (existsSync(output)) throw Error('Never overwrite evidence')
mkdirSync(output, { recursive: true })
const report = { schemaVersion: 1, mode, startedAt: new Date().toISOString(), plannedRuns: 1, status: 'NOT_RUN',
  collectorSha256: createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex'), cost: 'UNKNOWN',
  qualityAcceptance: 'NOT_PERFORMED', httpRequestCount: 'UNKNOWN' }
function client() {
  const cookies = new Map()
  return async (path, body, method = body === undefined ? 'GET' : 'POST', expected = 200) => {
    const response = await fetch(base + path, { method, redirect: 'error', signal: AbortSignal.timeout(30000),
      headers: { Origin: 'http://localhost:3000', 'Content-Type': 'application/json', Cookie: [...cookies].map(([n, v]) => `${n}=${v}`).join('; '),
        ...(cookies.get('CSRF-TOKEN') ? { 'X-CSRF-TOKEN': cookies.get('CSRF-TOKEN') } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    for (const line of response.headers.getSetCookie()) { const [n, ...v] = line.split(';')[0].split('='); cookies.set(n, v.join('=')) }
    if (response.status !== expected) throw Error(`HTTP ${response.status}; expected ${expected}`)
    const text = await response.text(), value = text ? JSON.parse(text) : null
    return value && Object.hasOwn(value, 'code') && Object.hasOwn(value, 'data') ? value.data : value
  }
}
async function account(api) {
  if ((await api('/auth/session')).mode !== 'local') throw Error('Requires local accounts')
  const username = 'task_' + randomBytes(6).toString('hex'), password = randomBytes(24).toString('base64url')
  await api('/auth/local/register', { username, password, nickname: '任务额度验证' }, 'POST', 201)
  await api('/auth/local/login', { username, password })
}
const api = client(), foreign = client(), events = [], seen = new Set()
let active, terminal
try {
  await account(api); report.runtime = await api('/chat/runtime')
  const policy = report.runtime.taskBudgetPolicy
  if (!report.runtime.features.taskBudget || !policy || mode === 'calls' && policy.maxModelCalls !== 1
      || mode === 'tokens' && policy.tokenLimit !== 1 || mode === 'success' && (policy.maxModelCalls < 2 || policy.tokenLimit < 32768)) throw Error('Requires the isolated scenario policy')
  const run = await api('/chat/runs', { prompt: '你好，请简短介绍你能做什么。不要调用工具。',
    conversationId: 'task-' + randomBytes(12).toString('hex'), gameReleaseId: 'pokemon-scarlet-violet', battleFormat: 'casual-singles',
    rulesetVersionId: 'casual-v1', dataReleaseId: 'pokeapi-reference-v1', language: 'zh-CN', timeoutSeconds: 120,
    idempotencyKey: randomBytes(16).toString('hex') }, 'POST', 202)
  active = run.runId; report.runId = run.runId
  let cursor = '0', deadline = Date.now() + 140000
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
  active = null; report.status = terminal.type
  const attempts = [...new Map(events.flatMap(e => { const u = e.type === 'usage' ? e.payload : e.payload?.modelUsage; return u ? [[u.attemptId, u]] : [] })).values()]
  report.taskAudit = checkTaskAttempts(attempts); report.contextAudit = checkContextAttempts(attempts)
  const history = await api(`/chat/conversations/${run.conversationId}/messages`)
  const answer = history.find(row => row.runId === run.runId && row.role === 'assistant')
  report.checks = { task: report.taskAudit.pass, context: report.contextAudit.pass }
  if (mode === 'success') {
    report.answer = events.filter(e => e.type === 'answer.delta').map(e => e.payload.text).join('')
    report.checks.completed = terminal.type === 'run.completed'
    report.checks.historyMatches = !!answer && answer.content === report.answer
    report.checks.nativeStream = attempts.some(a => a.calls.some(c => c.mode === 'STREAM' && c.outcome === 'RETURNED'))
    report.checks.storedBudget = !!answer && JSON.stringify(answer.metadata.modelUsageAttempts.map(normalizeModelUsage)) === JSON.stringify(attempts.map(normalizeModelUsage))
  } else {
    const task = attempts.at(-1)?.taskBudget
    report.checks.typedFailure = terminal.type === 'run.failed' && terminal.payload.errorType === 'RUN_TASK_BUDGET_EXCEEDED'
    report.checks.noAssistantSaved = !answer
    report.checks.admission = task?.modelCalls === (mode === 'calls' ? 1 : 0) && task?.blockReason === (mode === 'calls' ? 'CALL_LIMIT' : 'TOKEN_LIMIT')
    report.checks.attemptCount = attempts.reduce((sum, a) => sum + a.modelCallCount, 0) === (mode === 'calls' ? 1 : 0)
  }
  await account(foreign); await foreign(`/chat/runs/${run.runId}`, undefined, 'GET', 404)
  report.checks.foreignOwnerDenied = true
  report.pass = Object.values(report.checks).every(Boolean)
} catch (error) { report.error = error.message; report.pass = false }
finally {
  if (active) { try { await api(`/chat/runs/${active}/cancel`, {}) } catch { report.cleanupFailed = true } }
  for (const client of [api, foreign]) { try { await client('/logout', undefined, 'POST', 204) } catch { report.cleanupFailed = true } }
  report.finishedAt = new Date().toISOString()
  writeFileSync(resolve(output, 'events.json'), JSON.stringify(events, null, 2) + '\n')
  writeFileSync(resolve(output, 'scenario.json'), JSON.stringify(report, null, 2) + '\n')
}
console.log(JSON.stringify({ mode, pass: report.pass, status: report.status, checks: report.checks, error: report.error }))
if (!report.pass) process.exitCode = 1
