// New isolated local accounts only. No approval, write workflow or private configuration is read.
import fs from 'node:fs'
import path from 'node:path'
import { randomBytes } from 'node:crypto'
import { localBase, digest, frozenFileDigest, sameJson, eventBoundaryReached } from './evaluation.mjs'
import { attemptsFrom } from './answer-quality.mjs'
import { normalizeModelUsage } from '../../vio-ai-agent-frontend/src/api/model-usage.js'
import { validateAblationPlan, modes, allowedTools, excerptEvidence, evidenceHash, compareAblation } from './ablation.mjs'
const planFile = 'evaluation/campus/ablation/manifest.json', plan = validateAblationPlan(JSON.parse(fs.readFileSync(planFile)))
const corpus = JSON.parse(fs.readFileSync(plan.corpusFile)), planSha256 = frozenFileDigest(planFile)
if (frozenFileDigest(plan.corpusFile) !== plan.corpusSha256) throw Error('Frozen corpus changed')
const [command, ...args] = process.argv.slice(2)
const write = (dir, file, value) => fs.writeFileSync(path.join(dir, file), JSON.stringify(value, null, 2) + '\n')
if (command === 'compare') {
  if (args.length !== 4) throw Error('compare MODEL_CAPTURE GUIDE_CAPTURE READONLY_CAPTURE NEW_OUTPUT')
  const captures = args.slice(0, 3).map(file => JSON.parse(fs.readFileSync(file))), output = path.resolve(args[3])
  if (fs.existsSync(output)) throw Error('Never overwrite results')
  if (captures.some(c => c.planSha256 !== planSha256 || c.corpusSha256 !== plan.corpusSha256)) throw Error('Wrong fixed plan/corpus')
  for (let i = 0; i < captures.length; i++) for (const record of captures[i].records) {
    if (!record.eventsFile || !/^[A-Z0-9-]+\.events\.json$/.test(record.eventsFile)) throw Error('Missing event identity')
    const bytes = fs.readFileSync(path.join(path.dirname(args[i]), record.eventsFile))
    if (digest(bytes) !== record.eventsSha256) throw Error('Changed captured events')
    const events = JSON.parse(bytes), answer = events.filter(e => e.type === 'answer.delta').map(e => e.payload?.text || '').join('')
    if (record.collected && answer !== record.answer) throw Error('Changed answer capture')
    if(record.collected) {
      if(!/^[A-Z0-9-]+\.history\.json$/.test(record.historyFile||''))throw Error('Missing history identity')
      const historyBytes=fs.readFileSync(path.join(path.dirname(args[i]),record.historyFile))
      if(digest(historyBytes)!==record.historySha256)throw Error('Changed history')
      const history=JSON.parse(historyBytes),stored=history.find(m=>m.runId===record.runId&&m.role==='assistant')
      const item=plan.cases.find(c=>c.id===record.caseId),attempts=attemptsFrom(events)
      record.historyMatches=stored?.content===answer&&history.some(m=>m.runId===record.runId&&m.role==='user'&&m.content===item.prompt)
      record.usageMatchesHistory=Boolean(stored)&&sameJson(attempts.map(normalizeModelUsage),(stored.metadata?.modelUsageAttempts||[]).map(normalizeModelUsage))
      record.startedTools=events.filter(e=>e.type==='tool.started').map(e=>e.payload.toolName)
      record.runtimeTools=captures[i].runtime.evaluationPolicy.effectiveTools
      record.modelCalls=attempts.reduce((n,a)=>n+a.modelCallCount,0);record.modelUsageAttempts=attempts;record.excerpts=excerptEvidence(answer,corpus.documents)
    }
  }
  const summary = compareAblation(plan, captures)
  const reviews = summary.rows.map(row => ({ mode: row.mode, caseId: row.caseId, observationHash: row.observationHash,
    reviewer: null, reviewedAt: null, criteria: plan.cases.find(c => c.id === row.caseId).semanticCriteria.map(criterion => ({ criterion, pass: null, evidence: '' })) }))
  summary.inputSha256=args.slice(0,3).map(file=>digest(fs.readFileSync(file)))
  fs.mkdirSync(output, { recursive: true }); write(output, 'comparison.json', summary); write(output, 'review-template.json', reviews)
  const lines = ['# 三种工具配置的开发对照', '', `固定分母${summary.denominator}条；工程检查${summary.engineeringPass}条通过，人工语义验收待完成。`, '',
    '| 模式 | 案例 | 完成/回读 | 工具边界 | 调用数 | 输入/输出Token | 耗时ms | 引文逐字匹配 | 指定文档原文出现 |', '| --- | --- | --- | --- | ---: | --- | ---: | --- | --- |',
    ...summary.rows.map(r => `| ${r.mode} | ${r.caseId} | ${r.completed} | ${r.toolBoundary} | ${r.modelCalls ?? 'unknown'} | ${r.tokens ? `${r.tokens.inputTokens}/${r.tokens.outputTokens}` : 'unknown'} | ${r.elapsedMs ?? 'unknown'} | ${r.corpusMatchedBlocks}/${r.quotedBlocks} | ${r.relevantDocumentExcerptPresent} |`), '', summary.scope, '费用未与实际账单核对，不生成金额或降本比例。', '']
  fs.writeFileSync(path.join(output, 'report.md'), lines.join('\n'))
  console.log(JSON.stringify(summary)); if (summary.engineeringPass !== summary.denominator) process.exitCode = 1
} else if (command === 'run') {
  if (args.length !== 3 || !modes.includes(args[1])) throw Error('run LOCAL_API MODE NEW_OUTPUT')
  const base = localBase(args[0]), mode = args[1], output = path.resolve(args[2])
  if (new URL(base).port === '8123') throw Error('Use a dedicated evaluation instance')
  if (fs.existsSync(output)) throw Error('Never overwrite observations'); fs.mkdirSync(output, { recursive: true })
  const cookies = new Map(), active = new Set()
  const capture = { schemaVersion: 'tool-ablation-capture-v1', mode, planSha256, corpusSha256: plan.corpusSha256,
    startedAt: new Date().toISOString(), sourceSha256: {}, records: plan.cases.map(item => ({ caseId: item.id, collected: false })) }
  function sourceHashes() {
    const result = {}, walk = dir => { for (const e of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const file = path.join(dir, e.name); if (e.isDirectory()) walk(file); else if (file.endsWith('.java')) result[file.replaceAll('\\', '/')] = digest(fs.readFileSync(file))
    } }
    walk('src/main/java')
    for (const file of [planFile, 'pom.xml', 'tools/campus/ablation.mjs', 'tools/campus/s2-tool-ablation.mjs', 'tools/campus/answer-quality.mjs', 'tools/campus/evaluation.mjs', 'vio-ai-agent-frontend/src/api/model-usage.js']) result[file] = digest(fs.readFileSync(file))
    return result
  }
  async function api(route, body, method = body === undefined ? 'GET' : 'POST', expected = 200) {
    const response = await fetch(base + route, { method, redirect: 'error', signal: AbortSignal.timeout(30000), headers: {
      Origin: 'http://localhost:3000', 'Content-Type': 'application/json', Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join('; '),
      ...(cookies.get('CSRF-TOKEN') ? { 'X-CSRF-TOKEN': cookies.get('CSRF-TOKEN') } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    for (const line of response.headers.getSetCookie()) { const [key, ...value] = line.split(';')[0].split('='); cookies.set(key, value.join('=')) }
    if (response.status !== expected) throw Error(`HTTP ${response.status}; expected ${expected}`)
    if (response.status === 204) return null
    const value = await response.json(); return value && Object.hasOwn(value, 'data') && Object.hasOwn(value, 'code') ? value.data : value
  }
  try {
    if ((await api('/auth/session')).mode !== 'local') throw Error('Local account mode required')
    const username = 'ablation_' + randomBytes(6).toString('hex'), password = randomBytes(24).toString('base64url')
    await api('/auth/local/register', { username, password, nickname: '工具配置对照' }, 'POST', 201); await api('/auth/local/login', { username, password })
    capture.runtime = await api('/chat/runtime'); capture.guideCatalog = await api('/pokemon/guides/catalog')
    if (capture.runtime.evaluationPolicy?.mode !== mode || capture.runtime.evaluationPolicy.active !== true
      || !allowedTools(mode, capture.runtime.evaluationPolicy.effectiveTools) || capture.runtime.features.mcpClient
      || capture.runtime.generationDefaults?.temperature !== 0 || capture.guideCatalog.corpusSha256 !== plan.corpusSha256) throw Error('Runtime arm/generation/corpus differs from plan')
    capture.sourceSha256 = sourceHashes()
    for (const [name, expected] of Object.entries(capture.runtime.implementationSha256)) {
      const file = Object.keys(capture.sourceSha256).find(f => f.endsWith('/' + name + '.java'))
      if (!file || digest(fs.readFileSync(file.replace('src/main/java/', 'target/classes/').replace(/\.java$/, '.class'))) !== expected) throw Error('Stale runtime class')
    }
    for (const item of plan.cases) {
      const record = capture.records.find(r => r.caseId === item.id), events = [], seen = new Set(), start = Date.now()
      let run
      try {
        run = await api('/chat/runs', { ...plan.versions, prompt: item.prompt, conversationId: 'ablation-' + randomBytes(10).toString('hex'),
          timeoutSeconds: 90, idempotencyKey: randomBytes(16).toString('hex') }, 'POST', 202)
        record.runId = run.runId; active.add(run.runId); let cursor = '0'
        while (Date.now() - start < 110000) {
          const page = await api(`/chat/runs/${run.runId}/events?afterEventId=${cursor}`)
          for (const event of page.events || []) if (!seen.has(String(event.eventId))) { seen.add(String(event.eventId)); events.push(event); cursor = event.eventId }
          const view = await api(`/chat/runs/${run.runId}`); record.status = view.status
          if (view.status === 'WAITING_APPROVAL') throw Error('Unexpected approval')
          if (eventBoundaryReached(view.status, events)) { active.delete(run.runId); break }
          await new Promise(resolve => setTimeout(resolve, 250))
        }
        record.answer = events.filter(e => e.type === 'answer.delta').map(e => e.payload?.text || '').join('')
        const history = await api(`/chat/conversations/${run.conversationId}/messages`), answer = history.find(m => m.runId === run.runId && m.role === 'assistant')
        record.historyMatches = record.status === 'COMPLETED' && answer?.content === record.answer
        const historyRaw=JSON.stringify(history,null,2)+'\n';record.historyFile=item.id+'.history.json';record.historySha256=digest(historyRaw)
        fs.writeFileSync(path.join(output,record.historyFile),historyRaw)
        const attempts = attemptsFrom(events); record.modelUsageAttempts = attempts
        record.modelCalls = attempts.reduce((sum, attempt) => sum + attempt.modelCallCount, 0)
        record.usageMatchesHistory = Boolean(answer) && sameJson(attempts.map(normalizeModelUsage), (answer.metadata?.modelUsageAttempts || []).map(normalizeModelUsage))
        record.startedTools = events.filter(e => e.type === 'tool.started').map(e => e.payload.toolName)
        record.runtimeTools = capture.runtime.evaluationPolicy.effectiveTools
        record.excerpts = excerptEvidence(record.answer, corpus.documents); record.collected = true
      } catch { record.error = 'CASE_COLLECTION_FAILED' }
      finally {
        if (run && active.has(run.runId)) try { await api(`/chat/runs/${run.runId}/cancel`, {}); active.delete(run.runId) } catch { record.cleanupFailed = true }
        const raw = JSON.stringify(events, null, 2) + '\n'; record.eventsFile = item.id + '.events.json'; record.eventsSha256 = digest(raw)
        fs.writeFileSync(path.join(output, record.eventsFile), raw); record.elapsedMs = Date.now() - start
        write(output, 'capture.json', capture)
      }
    }
    capture.sourcesUnchanged = sameJson(sourceHashes(), capture.sourceSha256)
  } catch { capture.setupError = 'SETUP_OR_IDENTITY_FAILED' }
  finally {
    for (const id of active) try { await api(`/chat/runs/${id}/cancel`, {}) } catch { capture.cleanupFailed = true }
    try { await api('/logout', undefined, 'POST', 204) } catch { capture.cleanupFailed = true }
    capture.finishedAt = new Date().toISOString(); write(output, 'capture.json', capture)
  }
  const ok = !capture.setupError && !capture.cleanupFailed && capture.sourcesUnchanged && capture.records.every(r => r.collected && r.historyMatches && r.usageMatchesHistory && !r.cleanupFailed && allowedTools(mode, r.startedTools))
  console.log(JSON.stringify({ mode, engineeringPass: ok, records: capture.records.map(r => ({ caseId: r.caseId, status: r.status, modelCalls: r.modelCalls, historyMatches: r.historyMatches })) }))
  if (!ok) process.exitCode = 1
} else throw Error('Usage: s2-tool-ablation.mjs run|compare ...')
