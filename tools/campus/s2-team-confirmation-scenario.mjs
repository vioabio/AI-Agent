// Automated approval tests on newly created local accounts/teams only. Never persists credentials.
import fs from 'node:fs'
import path from 'node:path'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { localBase, digest, eventBoundaryReached } from './evaluation.mjs'
import { confirmationPlan, teamDigest, summarizeConfirmations } from './team-confirmation.mjs'
import { attemptsFrom } from './answer-quality.mjs'
import { normalizeModelUsage } from '../../vio-ai-agent-frontend/src/api/model-usage.js'
const [baseArg, outArg, ...extra] = process.argv.slice(2)
if (!baseArg || !outArg || extra.length) throw Error('Usage: node tools/campus/s2-team-confirmation-scenario.mjs LOCAL_API NEW_OUTPUT')
const base = localBase(baseArg), out = path.resolve(outArg)
if (fs.existsSync(out)) throw Error('Never overwrite observations')
fs.mkdirSync(out, { recursive: true })
const report = { schemaVersion: 'team-confirmation-capture-v1', startedAt: new Date().toISOString(),
  mode: 'REAL_MODEL_LOCAL_HTTP_AUTOMATED_TEST_APPROVALS', humanSemanticAcceptance: 'NOT_PERFORMED',
  collectorSha256: digest(fs.readFileSync(fileURLToPath(import.meta.url))),
  planSha256: digest(JSON.stringify(confirmationPlan)), records: confirmationPlan.map(p => ({ caseId: p.id, collected: false })) }
const save = () => fs.writeFileSync(path.join(out, 'capture.json'), JSON.stringify(report, null, 2) + '\n')
function client() {
  const cookies = new Map()
  return async (route, body, method = body === undefined ? 'GET' : 'POST', expected = 200) => {
    const response = await fetch(base + route, { method, redirect: 'error', signal: AbortSignal.timeout(30000),
      headers: { Origin: 'http://localhost:3000', 'Content-Type': 'application/json', Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join('; '),
        ...(cookies.get('CSRF-TOKEN') ? { 'X-CSRF-TOKEN': cookies.get('CSRF-TOKEN') } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    for (const line of response.headers.getSetCookie()) { const [key, ...value] = line.split(';')[0].split('='); cookies.set(key, value.join('=')) }
    if (response.status !== expected) throw Error(`HTTP ${response.status}; expected ${expected}`)
    const text = await response.text(), value = text ? JSON.parse(text) : null
    return expected < 400 && value && Object.hasOwn(value, 'code') && Object.hasOwn(value, 'data') ? value.data : value
  }
}
const a = client(), b = client(), open = new Set(), allEvents = {}
async function account(api) {
  if ((await api('/auth/session')).mode !== 'local') throw Error('Dedicated local account mode required')
  const username = 'confirm_' + randomBytes(6).toString('hex'), password = randomBytes(24).toString('base64url')
  await api('/auth/local/register', { username, password, nickname: '队伍确认专项回归' }, 'POST', 201)
  await api('/auth/local/login', { username, password })
  if (!(await api('/auth/session')).authenticated) throw Error('Dedicated login failed')
}
async function wait(runId, waiting) {
  const events = allEvents[runId] ||= [], known = new Set(events.map(e => String(e.eventId)))
  let cursor = events.at(-1)?.eventId || '0'
  const until = Date.now() + 135000
  while (Date.now() < until) {
    const page = await a(`/chat/runs/${runId}/events?afterEventId=${cursor}`)
    for (const event of page.events || []) if (!known.has(String(event.eventId))) { known.add(String(event.eventId)); events.push(event); cursor = event.eventId }
    const run = await a(`/chat/runs/${runId}`)
    if (waiting && run.status === 'WAITING_APPROVAL' && events.some(e => e.type === 'approval.required') && events.some(e => e.type === 'usage')) return run
    if (['COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(run.status) && eventBoundaryReached(run.status, events)) {
      if (waiting) throw Error('Run ended before preview: ' + run.status)
      return run
    }
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw Error('Scenario deadline exceeded')
}
try {
  await account(a); await account(b)
  report.runtime = await a('/chat/runtime'); report.sourceSha256 = {}
  function walk(dir) { for (const e of fs.readdirSync(dir, { withFileTypes: true })) { const f = path.join(dir, e.name); if (e.isDirectory()) walk(f); else if (f.endsWith('.java')) report.sourceSha256[f.replaceAll('\\', '/')] = digest(fs.readFileSync(f)) } }
  walk('src/main/java')
  for (const file of ['pom.xml', 'tools/campus/team-confirmation.mjs', 'tools/campus/s2-team-confirmation-scenario.mjs']) report.sourceSha256[file] = digest(fs.readFileSync(file))
  for (const [name, expected] of Object.entries(report.runtime.implementationSha256)) {
    const source = Object.keys(report.sourceSha256).find(f => f.endsWith('/' + name + '.java'))
    if (!source || digest(fs.readFileSync(source.replace('src/main/java/', 'target/classes/').replace(/\.java$/, '.class'))) !== expected) throw Error('Runtime implementation mismatch')
  }
  if (!['readCurrentTeam', 'previewTeamChanges'].every(t => report.runtime.tools.includes(t))) throw Error('Workflow tools are unavailable')
  for (const record of report.records) {
    let current
    try {
      const team = await a('/teams', { name: '专项练习队-' + record.caseId, rulesetId: 'casual-v1', dataReleaseId: 'pokeapi-reference-v1',
        members: [{ formId: 7, slot: 1, build: { level: 50, nature: 'modest', ability: 'torrent', item: 'leftovers', moves: ['surf'],
          evs: { hp: 4, atk: 0, def: 0, spa: 252, spd: 0, spe: 252 }, ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } } }] }, 'POST', 201)
      record.before = await a(`/teams/${team.id}`)
      const conversationId = 'confirm-' + randomBytes(10).toString('hex')
      const run = await a('/chat/runs', { conversationId, timeoutSeconds: 120, idempotencyKey: randomBytes(16).toString('hex'),
        gameReleaseId: 'pokemon-scarlet-violet', battleFormat: 'casual-singles', language: 'zh-CN', rulesetVersionId: 'casual-v1', dataReleaseId: 'pokeapi-reference-v1',
        prompt: `请先单独调用 readCurrentTeam 读取当前账号的队伍编号 ${team.id}，然后调用 previewTeamChanges，只把第1位成员等级从50改为51，其余所有字段保留。只生成一个等级差异的预览，等我通过界面选择。不要计算伤害或查询攻略，不推断策略最优性。` }, 'POST', 202)
      current = run.runId; open.add(current); record.runId = current
      await wait(current, true)
      record.preview = (await a(`/chat/runs/${current}/team-change`)).preview
      record.beforeDecision = await a(`/teams/${team.id}`)
      record.decisionApprovalId = record.preview.proposalId
      record.planningModelCalls = attemptsFrom(allEvents[current]).reduce((sum, attempt) => sum + attempt.modelCallCount, 0)
      if (record.caseId === 'confirm') {
        await b(`/teams/${team.id}`, undefined, 'GET', 404)
        await b(`/chat/runs/${current}/team-change`, undefined, 'GET', 404)
        await b(`/chat/runs/${current}/team-change/decision`, { approvalId: record.preview.proposalId, approved: true }, 'POST', 404)
        await a(`/chat/runs/${current}/team-change/decision`, { approvalId: 'unknown-approval', approved: true }, 'POST', 404)
        await a(`/hitl/approvals/${record.preview.proposalId}?runId=${current}&approved=true`, {}, 'POST', 403)
        record.accountBoundaryChecked = true
      }
      if (record.caseId === 'conflict') {
        await a(`/teams/${team.id}`, { ...team, name: '另一窗口编辑' }, 'PATCH')
        await a(`/teams/${team.id}`, team, 'PATCH'); record.concurrentEditThenRestore = true
      }
      if (record.caseId === 'cancel') await a(`/chat/runs/${current}/cancel`, {})
      const expected = confirmationPlan.find(p => p.id === record.caseId).decisionStatus
      await a(`/chat/runs/${current}/team-change/decision`, { approvalId: record.preview.proposalId, approved: record.caseId !== 'deny' }, 'POST', expected)
      record.decisionStatus = expected
      record.status = (await wait(current, false)).status; open.delete(current)
      record.outcome = await a(`/chat/runs/${current}/team-change`); record.after = await a(`/teams/${team.id}`)
      if (record.caseId === 'confirm') {
        await a(`/chat/runs/${current}/team-change/decision`, { approvalId: record.preview.proposalId, approved: true }, 'POST', 409)
        record.repeatDecisionStatus = 409; record.repeatReadbackMatches = teamDigest(record.after) === teamDigest(await a(`/teams/${team.id}`))
      }
      const events = allEvents[current], terminal = events.filter(e => ['run.completed', 'run.failed', 'run.cancelled', 'run.timed_out'].includes(e.type))
      record.terminalEvents = terminal.map(e => e.type)
      record.answer = events.filter(e => e.type === 'answer.delta').map(e => e.payload?.text || '').join('')
      const messages = await a(`/chat/conversations/${conversationId}/messages`), answer = messages.find(m => m.runId === current && m.role === 'assistant')
      record.historyAnswer = answer?.content || ''
      record.completedTools = events.filter(e => e.type === 'tool.finished' && e.payload?.status === 'SUCCEEDED').map(e => e.payload.toolName)
      const attempts = attemptsFrom(events); record.modelCalls = attempts.reduce((sum, attempt) => sum + attempt.modelCallCount, 0)
      record.noModelCallOnApprovalResume = record.planningModelCalls === record.modelCalls
      record.usageMatchesHistory = !answer || teamDigest(attempts.map(normalizeModelUsage)) === teamDigest((answer.metadata?.modelUsageAttempts || []).map(normalizeModelUsage))
      record.collected = true
    } catch (error) { record.error = /^(HTTP \d{3}; expected \d{3}|Run ended before preview: [A-Z_]+|Scenario deadline exceeded)$/.test(error.message) ? error.message : 'CASE_COLLECTION_FAILED'; if (current) try { await a(`/chat/runs/${current}/cancel`, {}); open.delete(current) } catch { record.cleanupFailed = true } }
    save()
  }
} catch { report.error = 'SETUP_OR_RUNTIME_IDENTITY_FAILED' }
finally {
  for (const run of open) try { await a(`/chat/runs/${run}/cancel`, {}) } catch { report.cleanupFailed = true }
  for (const api of [a, b]) try { await api('/logout', undefined, 'POST', 204) } catch { report.cleanupFailed = true }
  const raw = JSON.stringify(allEvents, null, 2) + '\n'; fs.writeFileSync(path.join(out, 'events.json'), raw)
  report.eventsSha256 = digest(raw); report.finishedAt = new Date().toISOString(); report.summary = summarizeConfirmations(report.records)
  report.pass = report.summary.passed === confirmationPlan.length && !report.error && !report.cleanupFailed
    && report.records.every(r => r.noModelCallOnApprovalResume && r.usageMatchesHistory && !r.cleanupFailed)
  save()
}
console.log(JSON.stringify({ pass: report.pass, denominator: report.summary.denominator, passed: report.summary.passed,
  cases: report.summary.rows.map(row => ({ caseId: row.caseId, pass: row.pass, failures: row.failures })) }))
if (!report.pass) process.exitCode = 1
