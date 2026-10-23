// Local end-to-end collector. No private config, API key, credential or cookie is persisted.
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, statSync } from 'node:fs'
import { resolve, dirname, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomBytes } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { loadSuite, validateSuite, summarize, digest, frozenFileDigest, localBase, observationHash, matchesEngineReference, eventBoundaryReached } from './evaluation.mjs'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const args = process.argv.slice(2), command = args.shift()
const options = {}
for (let i = 0; i < args.length; i += 2) {
  if (!['--base-url', '--output', '--case', '--split', '--repetitions', '--report', '--reviews'].includes(args[i]) || !args[i + 1] || args[i + 1].startsWith('--') || options[args[i]]) throw Error('Unknown, duplicate or missing option')
  options[args[i]] = args[i + 1]
}
const suiteDirectory = resolve(root, 'evaluation/campus/s2'), suite = loadSuite(suiteDirectory)
if (command === 'validate') {
  if (Object.keys(options).length) throw Error('validate takes no options')
  console.log(JSON.stringify(validateSuite(suite), null, 2)); process.exit(0)
}
function writeSummary(output, report, reviews = []) {
  const summary = summarize(suite, report.records, reviews)
  const lines = ['# S2 案例级评测记录', '', `模式：${report.mode}；数据集：${report.datasetId}；运行：${report.startedAt}。`, '',
    `固定分母 ${summary.denominator} 次；结构检查通过 ${summary.structuralPass}；完整人工核对通过 ${summary.pass}；失败 ${summary.fail}；待复核 ${summary.pendingReview}。`, '',
    '结构检查不证明答案正确；待复核仍保留在固定分母。未选中的保留集没有成绩。', '',
    '| 案例 | 轮次 | 结构 | 质量复核 | 原因 |', '| --- | --- | --- | --- | --- |',
    ...summary.rows.map(row => `| ${row.caseId} | ${row.repetition} | ${row.structuralPass ? '通过' : '失败'} | ${row.reviewState} | ${row.failures.join(', ') || '待人工核对事实与建议'} |`), '',
    `总耗时 P50/P95：${summary.latencyMs.p50 ?? 'unknown'} / ${summary.latencyMs.p95 ?? 'unknown'} ms。样本数 ${summary.latencyMs.samples}。`, '',
    report.runtime?.features?.modelUsage
      ? '本运行另外采集 modelUsageAttempts 原始快照，使用 tools/campus/s4-usage.mjs 独立核对调用与 Token；本S2质量汇总不将其折算成费用或准确率。首段回答时间是轮询观测上界，不等同供应商首Token延迟。引用正确率和Recall@5未测量。'
      : 'Token、模型调用次数与费用：unknown。首段回答时间是轮询观测上界，含缓冲，不等同于供应商首 Token 延迟。引用正确率和 Recall@5 未测量。', '',
    '本报告只评当前工具增强链；纯模型和 S3 检索增强对照尚未执行，不能据此声称提升。', '']
  writeFileSync(resolve(output, 'summary.json'), JSON.stringify(summary, null, 2) + '\n')
  writeFileSync(resolve(output, 'report.md'), lines.join('\n'))
  return summary
}
if (command === 'report') {
  if (Object.keys(options).some(key => !['--report', '--output', '--reviews'].includes(key))) throw Error('Unsupported report option')
  if (!options['--report'] || !options['--output']) throw Error('report requires --report and new --output')
  const report = JSON.parse(readFileSync(resolve(options['--report']), 'utf8'))
  if (report.datasetSha256 !== frozenFileDigest(resolve(suiteDirectory, 'manifest.json'))) throw Error('Report dataset identity mismatch')
  if (!Array.isArray(report.selectedCaseIds) || !Number.isInteger(report.repetitions) || report.repetitions < 1
      || report.records.length !== report.selectedCaseIds.length * report.repetitions
      || report.selectedCaseIds.some(id => !suite.cases.some(c => c.id === id)) || new Set(report.selectedCaseIds).size !== report.selectedCaseIds.length) throw Error('Report lost planned cases')
  for (const id of report.selectedCaseIds) for (let i = 1; i <= report.repetitions; i++) if (report.records.filter(r => r.caseId === id && r.repetition === i).length !== 1) throw Error('Report lost planned repetition')
  const output = resolve(options['--output'])
  if (existsSync(output)) throw Error('Use a new report directory; original evidence is immutable')
  const reviews = options['--reviews'] ? JSON.parse(readFileSync(resolve(options['--reviews']), 'utf8')) : []
  mkdirSync(output, { recursive: true }); writeSummary(output, report, reviews)
  console.log(`Recomputed report: ${output}`); process.exit(0)
}
if (command !== 'run') throw Error('Usage: node tools/campus/s2-evaluate.mjs validate | run [--case ID,ID --split dev --repetitions 2 --base-url http://127.0.0.1:18127/api --output NEW_DIR] | report --report FILE --output NEW_DIR [--reviews FILE]')
if (options['--reviews'] || options['--report']) throw Error('run does not accept reviews or old reports')
const base = localBase(options['--base-url'] || 'http://127.0.0.1:18127/api')
const split = options['--split'] || 'dev'
if (!['dev', 'heldout'].includes(split)) throw Error('Select dev or heldout explicitly; heldout results must not be used to tune this version')
const ids = options['--case']?.split(',') || suite.splits[split]
if (new Set(ids).size !== ids.length || ids.some(id => !suite.splits[split].includes(id))) throw Error('Unknown, duplicate or wrong-split case')
const selected = ids.map(id => suite.cases.find(c => c.id === id))
const repetitions = Number(options['--repetitions'] || 2)
if (!Number.isInteger(repetitions) || repetitions < 1 || repetitions > 5) throw Error('repetitions must be 1..5')
const output = resolve(options['--output'] || resolve(root, `artifacts/campus/s2/${Date.now()}`))
if (existsSync(output)) throw Error('Use a new output directory; never overwrite evidence')
mkdirSync(output, { recursive: true })
const report = { schemaVersion: 1, datasetId: suite.manifest.datasetId, datasetSha256: frozenFileDigest(resolve(suiteDirectory, 'manifest.json')),
  collectorVersion: 'campus-s2-collector-v2',
  collectorSha256: Object.fromEntries(['s2-evaluate.mjs', 'evaluation.mjs'].map(file => [file, digest(readFileSync(resolve(root, 'tools/campus', file)))])),
  startedAt: new Date().toISOString(), mode: 'real-model-current-tools-via-local-http', selectedCaseIds: ids, split, repetitions,
  sourceCommit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
  workingTreeDirty: !!execFileSync('git', ['status', '--porcelain'], { cwd: root, encoding: 'utf8' }).trim(),
  node: process.version, generationParameters: 'runtime defaults; not attested by endpoint', usage: 'unknown', cost: 'unknown',
  records: selected.flatMap(item => Array.from({ length: repetitions }, (_, i) => ({ caseId: item.id, repetition: i + 1, status: 'NOT_RUN',
    answer: '', toolOutcomes: [], checks: { collected: false }, elapsedMs: null, firstAnswerDeltaMs: null }))),
  notes: ['New isolated accounts and teams only; no legacy user data read.', 'No approval is submitted. Expected previews are checked then cancelled.',
    'Model identity is configured identity; provider returned identity is not attested.', 'No pure model or retrieval+tools comparison has been performed.'] }
const save = () => { writeFileSync(resolve(output, 'observations.json'), JSON.stringify(report, null, 2) + '\n'); writeSummary(output, report) }
function client() {
  const cookies = new Map()
  return async (path, body, method = body === undefined ? 'GET' : 'POST', expected = 200) => {
    const response = await fetch(`${base}${path}`, { method, redirect: 'error', signal: AbortSignal.timeout(30000),
      headers: { Origin: 'http://localhost:3000', 'Content-Type': 'application/json', Cookie: [...cookies].map(([n, v]) => `${n}=${v}`).join('; '),
        ...(cookies.get('CSRF-TOKEN') ? { 'X-CSRF-TOKEN': cookies.get('CSRF-TOKEN') } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    for (const line of response.headers.getSetCookie()) { const [n, ...v] = line.split(';')[0].split('='); cookies.set(n, v.join('=')) }
    if (response.status !== expected) throw Error(`${method} ${path}: HTTP ${response.status}; expected ${expected}`)
    const value = response.status === 204 ? null : await response.json()
    return value && Object.hasOwn(value, 'code') && Object.hasOwn(value, 'data') ? value.data : value
  }
}
async function account(api) {
  if ((await api('/auth/session')).mode !== 'local') throw Error('Requires local account mode')
  const username = `s2_${randomBytes(6).toString('hex')}`, password = randomBytes(24).toString('base64url')
  await api('/auth/local/register', { username, password, nickname: 'S2 固定评测' }, 'POST', 201)
  await api('/auth/local/login', { username, password })
  if (!(await api('/auth/session')).authenticated) throw Error('Dedicated test login failed')
}
const a = client(), b = client()
const openedRuns = new Set()
function sourceDigests() {
  const result = {}
  function walk(directory) {
    for (const entry of readdirSync(directory)) {
      const file = resolve(directory, entry)
      if (statSync(file).isDirectory()) walk(file)
      else if (file.endsWith('.java')) result[relative(root, file).replaceAll('\\', '/')] = digest(readFileSync(file))
    }
  }
  walk(resolve(root, 'src/main/java'))
  for (const file of ['pom.xml', 'src/main/resources/pokemon-guides/index.json', 'src/main/resources/pokemon-guides/expanded-v1.json', 'tools/pokemon-engine/calculate.cjs', 'tools/pokemon-engine/package-lock.json']) result[file] = digest(readFileSync(resolve(root, file)))
  return result
}
async function collect(item, record) {
  const start = Date.now(), events = [], seen = new Set()
  let teamId, teamHash
  try {
    const team = await a('/teams', suite.fixtures.teams[item.preconditions.team], 'POST', 201)
    const foreign = item.preconditions.foreignTeam ? await b('/teams', { ...suite.fixtures.teams.complete, name: 'S2 其他账号资料（不得披露）' }, 'POST', 201) : null
    const before = digest(await a(`/teams/${team.id}`))
    teamId = team.id; teamHash = before
    const prompt = item.prompt.replaceAll('{teamId}', String(team.id)).replaceAll('{foreignTeamId}', String(foreign?.id ?? 'unused'))
    record.input = prompt
    const run = await a('/chat/runs', { ...item.versions, prompt, timeoutSeconds: 120,
      conversationId: `s2-${randomBytes(10).toString('hex')}`, idempotencyKey: randomBytes(16).toString('hex') }, 'POST', 202)
    record.runId = run.runId; record.traceId = run.traceId; openedRuns.add(run.runId)
    let cursor = '0'
    while (Date.now() - start < 140000) {
      const page = await a(`/chat/runs/${run.runId}/events?afterEventId=${cursor}`)
      for (const event of page.events || []) {
        const id = String(event.eventId ?? event.id)
        if (seen.has(id)) continue
        seen.add(id); events.push(event); cursor = id
        const type = String(event.type || event.eventType).toLowerCase().replaceAll('_', '.'), data = event.payload || event.data || {}
        if (type === 'answer.delta') {
          const delta = data.text || data.delta || (typeof data === 'string' ? data : '')
          if (delta && record.firstAnswerDeltaMs === null) record.firstAnswerDeltaMs = Date.now() - start
          record.answer += delta
        }
        if (type === 'answer.completed' || type === 'run.completed') record.answer = data.answer || record.answer
      }
      const view = await a(`/chat/runs/${run.runId}`)
      record.status = view.status
      // Status and event endpoints are separate reads. Drain the terminal event,
      // otherwise a just-completed answer can be falsely recorded as empty.
      const usageReady = view.status !== 'WAITING_APPROVAL' || !report.runtime.features.modelUsage
        || events.some(event => event.type === 'usage' && event.payload?.schemaVersion === 'model-usage-v1')
      if (eventBoundaryReached(view.status, events) && usageReady) break
      await new Promise(r => setTimeout(r, 400))
    }
    record.checks = { storageUnchanged: before === digest(await a(`/teams/${team.id}`)) }
    if (record.status === 'COMPLETED') {
      const history = await a(`/chat/conversations/${run.conversationId}/messages`)
      record.checks.historyMatches = history.some(m => m.role === 'assistant' && m.content === record.answer)
      if(report.runtime?.features?.modelUsage)record.historyModelUsageAttempts = history.find(m => m.role === 'assistant' && m.runId === run.runId)?.metadata?.modelUsageAttempts || []
    }
    if (record.status === 'WAITING_APPROVAL') {
      record.preview = (await a(`/chat/runs/${run.runId}/team-change`)).preview
      const change = item.expected.change
      record.checks.onlyExpectedChange = !!change && record.preview.teamId === team.id && record.preview.changes.length === 1
        && record.preview.changes[0].slot === 1 && Object.entries(change).every(([key, value]) => record.preview.changes[0][key] === value)
    }
    if (foreign) {
      await a(`/teams/${foreign.id}`, undefined, 'GET', 404)
      record.checks.foreignReadDenied = true
      record.checks.foreignTeamUnchanged = digest(await b(`/teams/${foreign.id}`)) === digest(foreign)
    }
    if (item.expected.damageFixture) {
      const reference = suite.fixtures.damage[item.expected.damageFixture]
      record.referenceCalculation = await a('/pokemon/calculations/engine-damage', reference.input)
      record.checks.referenceEngineMatches = matchesEngineReference(record.referenceCalculation, reference.result)
      // Numbers in text are only a necessary check. Actual model tool input is not attested by public events.
      record.checks.expectedDamageNumbersPresent = [reference.result.range.minimum, reference.result.range.maximum, reference.result.defenderHp]
        .every(value => new RegExp(`(^|[^0-9])${value}([^0-9]|$)`, 'u').test(record.answer))
      record.modelToolInputAttestation = 'unknown: governance events expose parameter digest, not raw arguments'
    }
  } catch (error) { record.status = 'COLLECTION_ERROR'; record.error = error instanceof Error ? error.message : 'unknown collection error' }
  finally {
    record.toolOutcomes = [...new Map(events.filter(e => /tool[._](finished|rejected)/i.test(e.type || e.eventType || '')).map(e => {
      const p = e.payload || e.data || {}, value = { tool: p.toolName, status: p.status || 'REJECTED', durationMs: p.durationMs ?? null,
        parameterDigest: p.parameterDigest || null, callId: p.governanceEventId || null }
      return [`${value.callId}:${value.tool}:${value.status}`, value]
    })).values()]
    record.elapsedMs = Date.now() - start
    record.eventsFile = `${item.id}-${record.repetition}-events.json`
    writeFileSync(resolve(output, record.eventsFile), JSON.stringify(events, null, 2) + '\n')
    record.eventsSha256 = digest(readFileSync(resolve(output, record.eventsFile)))
    record.modelUsageAttempts = [...new Map(events.flatMap(event => {
      const usage = event.type === 'usage' ? event.payload : event.payload?.modelUsage
      return usage?.schemaVersion === 'model-usage-v1' ? [[usage.attemptId, usage]] : []
    })).values()]
    record.observationSha256 = observationHash(record)
    if (record.runId && !['COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(record.status)) {
      try { await a(`/chat/runs/${record.runId}/cancel`, {}); record.cleanupCancelled = true; openedRuns.delete(record.runId) }
      catch { record.cleanupCancelled = false }
    } else openedRuns.delete(record.runId)
    if (record.cleanupCancelled === false) record.checks.cleanup = false
    if (teamId) {
      try { record.checks.storageUnchangedAfterCleanup = teamHash === digest(await a(`/teams/${teamId}`)) }
      catch { record.checks.storageUnchangedAfterCleanup = false }
    }
    record.observationSha256 = observationHash(record)
    save()
    console.log(`${item.id} #${record.repetition}: ${record.status}; tools=${record.toolOutcomes.map(t => `${t.tool}:${t.status}`).join(',')}`)
  }
}
save()
try {
  await account(a); await account(b)
    report.runtime = await a('/chat/runtime')
    if (report.runtime.tools.includes('searchPokemonGuides')) report.guideCatalog = await a('/pokemon/guides/catalog')
  if (!report.runtime.profiles.includes('local') || report.runtime.features.versionedGrounding
      || !report.runtime.tools.includes('readCurrentTeam')) throw Error('Requires current local S1 tool chain with v1 grounding disabled')
  for (const [name, hash] of Object.entries(report.runtime.implementationSha256)) {
    const scope = ['TeamReferenceTools','RunToolScope'].includes(name) ? 'pokemon/reference'
      : ['LocalGuideSearch','GuideChunks','SqliteGuideKnowledgeRetriever','GuideProvenance'].includes(name) ? 'pokemon/guides'
      : ['ObservedChatModel','ModelUsageLedger','ProviderTokenUsage'].includes(name) ? 'observability'
      : name === 'TeamWorkflowService' ? 'pokemon/team' : ['RunMutationBoundary','DefaultRunExecutor','AgentRunContext','PokemonAgentRunService'].includes(name) ? 'run' : 'agent'
    const file = resolve(root, `target/classes/com/vio/vioaiagent/${scope}/${name}.class`)
    if (!existsSync(file) || digest(readFileSync(file)) !== hash) throw Error(`Stale/unattested runtime class: ${name}`)
  }
  report.sourceSha256 = sourceDigests()
  for (const item of selected) for (const record of report.records.filter(r => r.caseId === item.id)) await collect(item, record)
} catch (error) { report.setupError = error instanceof Error ? error.message : 'unknown setup error'; process.exitCode = 1 }
finally {
  for (const runId of openedRuns) { try { await a(`/chat/runs/${runId}/cancel`, {}) } catch { report.cleanupIncomplete = true } }
  for (const api of [a, b]) { try { await api('/logout', undefined, 'POST', 204) } catch { report.cleanupIncomplete = true } }
  report.finishedAt = new Date().toISOString(); save()
  writeFileSync(resolve(output, 'reviews-template.json'), JSON.stringify(report.records.filter(r => r.status !== 'NOT_RUN').map(record => ({
    caseId: record.caseId, repetition: record.repetition, observationSha256: observationHash(record), reviewerType: 'human', reviewer: '', reviewedAt: '', note: '',
    decisions: suite.cases.find(c => c.id === record.caseId).expected.criteria.map(c => ({ criterionId: c.id, verdict: 'unknown', reason: '', sourceIds: c.sourceIds }))
  })), null, 2) + '\n')
}
if (summarize(suite, report.records).fail > 0) process.exitCode = 1
console.log(`S2 evidence: ${output}`)
