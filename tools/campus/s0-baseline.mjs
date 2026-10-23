// Real HTTP baseline. Never reads application-local.yml or stores credentials/cookies.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomBytes, createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const option = (key, fallback) => process.argv.includes(key) ? process.argv[process.argv.indexOf(key) + 1] : fallback
const base = new URL(option('--base-url', 'http://127.0.0.1:18123/api'))
if (base.protocol !== 'http:' || !['localhost', '127.0.0.1', '[::1]'].includes(base.hostname)
    || base.username || base.password || base.search || base.hash || base.pathname !== '/api') throw new Error('Use a loopback http://host:port/api URL')
const output = resolve(option('--output', resolve(root, 'artifacts/campus/s0')))
if (existsSync(resolve(output, 'report.json'))) throw new Error('Evidence already exists; choose a new --output directory')
const withModel = process.argv.includes('--with-model')
const caseFile = process.argv.includes('--s01') ? 'document/秋招升级/S01/cases.json' : 'document/秋招升级/S0/cases.json'
const cases = JSON.parse(readFileSync(resolve(root, caseFile)))
const caseId = option('--case', null)
if (caseId && !cases.cases.some(demo => demo.id === caseId)) throw new Error('Unknown fixed S0 case')
const report = { schemaVersion: 1, caseFile, baselineId: cases.baselineId || 'campus-s0-2026-10-23', startedAt: new Date().toISOString(), evidenceMode: 'real-http',
  modelRequested: withModel, modelId: option('--model-id', 'unverified-runtime-model'),
  runtimeSource: option('--runtime-source', 'unverified-existing-runtime'),
  sourceCommit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
  sourceWorkingTreeDirty: !!execFileSync('git', ['status', '--porcelain'], { cwd: root, encoding: 'utf8' }).trim(),
  node: process.version, checks: [], cases: [], notes: ['This is a baseline, not an accuracy/quality score.', 'A dedicated demo account is created; it does not claim or read legacy data.'] }
report.promptFiles = Object.fromEntries(['VioManus', 'ToolCallAgent'].map(name => [name,
  createHash('sha256').update(readFileSync(resolve(root, `src/main/java/com/vio/vioaiagent/agent/${name}.java`))).digest('hex')]))
mkdirSync(output, { recursive: true })
const cookies = new Map()
let csrf
function save() { writeFileSync(resolve(output, 'report.json'), JSON.stringify(report, null, 2) + '\n') }
function unwrap(value) { return value && Object.hasOwn(value, 'code') && Object.hasOwn(value, 'data') ? value.data : value }
async function request(path, body, method = body === undefined ? 'GET' : 'POST', expectedStatus = null) {
  const begin = Date.now()
  const response = await fetch(`${base}${path}`, { method, redirect: 'error', signal: AbortSignal.timeout(30000), headers: {
    'Content-Type': 'application/json', Origin: 'http://localhost:3000',
    Cookie: [...cookies].map(([key, value]) => `${key}=${value}`).join('; '),
    ...(csrf ? { 'X-CSRF-TOKEN': csrf } : {})
  }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  for (const line of response.headers.getSetCookie()) {
    const [name, ...parts] = line.split(';')[0].split('=')
    cookies.set(name, parts.join('='))
  }
  const value = response.status === 204 ? null : await response.json()
  // Only record method/path/status/time. Never request bodies, auth headers or registration responses.
  report.checks.push({ method, path, status: response.status, elapsedMs: Date.now() - begin })
  if (expectedStatus ? response.status !== expectedStatus : !response.ok) throw new Error(`${method} ${path}: HTTP ${response.status}`)
  return expectedStatus ? value : unwrap(value)
}
async function session() { const value = await request('/auth/session'); csrf = cookies.get('CSRF-TOKEN'); return value }
async function modelCase(demo) {
  const record = { id: demo.id, input: demo.prompt, expectedTools: demo.expectedTools, expected: demo.expected,
    mode: 'real-model-via-runtime', actualTools: [], startedAt: new Date().toISOString() }
  const start = Date.now()
  report.cases.push(record)
  try {
    const run = await request('/chat/runs', { ...cases.context, conversationId: `s0-${randomBytes(8).toString('hex')}`,
      prompt: demo.prompt, timeoutSeconds: 120, idempotencyKey: `s0-${randomBytes(12).toString('hex')}` })
    record.runId = run.runId; record.traceId = run.traceId
    record.conversationId = run.conversationId
    let cursor = '0'; let finished = false; let answer = ''
    const events = []
    while (!finished && Date.now() - start < 140000) {
      const page = await request(`/chat/runs/${run.runId}/events?afterEventId=${cursor}`)
      for (const event of page.events || []) {
        events.push(event)
        cursor = String(event.eventId ?? event.id ?? cursor)
        const type = String(event.eventType || event.type || '').toLowerCase().replaceAll('_', '.')
        const data = event.data || event.payload || {}
        if (type === 'answer.delta') answer += data.text || data.delta || (typeof data === 'string' ? data : '')
        if (type === 'answer.completed' || type === 'run.completed') answer = data.answer || answer
      }
      finished = page.terminal === true
      if (!finished) await new Promise(resolve => setTimeout(resolve, 500))
    }
    const view = await request(`/chat/runs/${run.runId}`)
    record.status = view.status
    record.actualTools = events.filter(event => /tool[._]started/i.test(event.eventType || event.type || ''))
      .map(event => (event.data || event.payload || {}).toolName)
    record.toolOutcomes = events.filter(event => /tool[._]finished/i.test(event.eventType || event.type || ''))
      .map(event => { const data = event.data || event.payload || {}; return {
        tool: data.toolName, callId: data.governanceEventId, status: data.status, durationMs: data.durationMs,
        error: data.error || data.reason || null
      } })
    record.answer = answer
    if (view.status === 'COMPLETED') {
      const history = await request(`/chat/conversations/${run.conversationId}/messages`)
      record.historyReadBackMatches = history.some(message => message.role === 'assistant' && message.content === answer)
    }
    record.eventsFile = `${demo.id}-events.json`
    writeFileSync(resolve(output, record.eventsFile), JSON.stringify(events, null, 2) + '\n')
    record.requiredToolsObserved = demo.expectedTools.every(name => record.actualTools.includes(name))
    record.requiredToolsSucceeded = demo.expectedTools.every(name => record.toolOutcomes.some(result =>
      result.tool === name && ['SUCCEEDED', 'EXECUTED'].includes(result.status)))
    record.structuralPass = view.status === 'COMPLETED' && !!answer && record.requiredToolsObserved && record.requiredToolsSucceeded && record.historyReadBackMatches
    record.manualQualityReview = 'pending: verify actual answer and source support; do not infer from completed status'
    console.log(`${demo.id}: ${record.status}; tools=${record.actualTools.join(',')}`)
  } catch (error) { record.status = 'ERROR'; record.error = error instanceof Error ? error.message : 'unknown error' }
  record.elapsedMs = Date.now() - start
  save()
}
try {
  const before = await session()
  if (before.mode !== 'local') throw new Error('Expected local account mode; do not run against shared/production services')
  const username = `s0_${randomBytes(6).toString('hex')}`
  const password = randomBytes(24).toString('base64url')
  await request('/auth/local/register', { username, password, nickname: 'S0 基线演示' })
  await request('/auth/local/login', { username, password })
  if (!(await session()).authenticated) throw new Error('Login was not persisted')
  try {
    report.runtime = await request('/chat/runtime');
    report.modelId = report.runtime.configuredModelId;
    report.modelIdentityLevel = 'runtime-configured; provider returned model is not attested';
  } catch {
    report.runtime = null;
    report.modelIdentityLevel = 'unverified-existing-runtime';
  }
  report.account = { createdDedicatedDemoAccount: true, legacyDataClaimed: false }
  report.referenceStatus = await request('/pokemon/reference/status')
  report.reference = await request('/pokemon/reference/pokemon/squirtle?dataReleaseId=pokeapi-reference-v1')
  await request('/pokemon/reference/pokemon/charmander?dataReleaseId=pokeapi-reference-v1')
  const team = await request('/teams', cases.team)
  const readBack = await request(`/teams/${team.id}`)
  report.teamReadBackMatches = JSON.stringify(readBack.members.map(m => m.build)) === JSON.stringify(team.members.map(m => m.build))
  if (!report.teamReadBackMatches) throw new Error('Team read-back mismatch')
  report.team = readBack
  const damage = { ...cases.damage, attacker: { species: 'squirtle', build: cases.team.members[0].build },
    defender: { species: 'charmander', build: cases.team.members[1].build } }
  report.damage = await request('/pokemon/calculations/engine-damage', damage)
  if (report.damage.engineVersion !== '0.12.0' || !(report.damage.range.minimum > 0)) throw new Error('Unexpected damage engine result')
  const invalidVersion = await request('/pokemon/reference/pokemon/squirtle?dataReleaseId=s0-invalid-version', undefined, 'GET', 400)
  const invalidMove = await request('/pokemon/calculations/engine-damage', { ...damage, move: 's0-made-up-move' }, 'POST', 400)
  report.negativeChecks = { invalidVersion, invalidMove }
  if (withModel) for (const demo of cases.cases.filter(demo => !caseId || demo.id === caseId)) await modelCase(demo)
  else report.cases = cases.cases.map(demo => ({ ...demo, status: 'MODEL_NOT_REQUESTED', mode: 'api-only', actualTools: [],
    note: 'Facts/team/engine APIs were exercised; no claim that a model selected these tools.' }))
  await request('/logout', undefined, 'POST')
  if ((await session()).authenticated) throw new Error('Logout failed')
  await request('/auth/local/login', { username, password }); await session()
  if ((await request(`/teams/${team.id}`)).id !== team.id) throw new Error('Team not retained after login')
  report.coreStatus = 'PASSED'
  report.modelStatus = !withModel ? 'NOT_REQUESTED' : report.cases.every(demo => demo.structuralPass) ? 'STRUCTURAL_PASS_REQUIRES_REVIEW' : 'HAS_FAILURES'
  await request('/logout', undefined, 'POST')
} catch (error) { report.coreStatus = 'FAILED'; report.error = error instanceof Error ? error.message : 'unknown error'; process.exitCode = 1 }
finally { report.finishedAt = new Date().toISOString(); save(); console.log(`S0 report: ${resolve(output, 'report.json')}`) }
if (withModel && report.modelStatus === 'HAS_FAILURES') process.exitCode = 1
