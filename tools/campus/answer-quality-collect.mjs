import assert from 'node:assert/strict'
import {randomBytes} from 'node:crypto'
import {mkdirSync, readFileSync, writeFileSync, existsSync} from 'node:fs'
import {resolve} from 'node:path'
import {localEvaluationSession} from './local-evaluation-session.mjs'
import {digest, eventBoundaryReached, sameJson, localBase} from './evaluation.mjs'
import {publicFingerprint, collectionFailure} from './upgrade-baseline.mjs'
import {stage2Team} from './stage2-regression.mjs'

const [suiteDirectory, outputDirectory, url, mode = 'regression'] = process.argv.slice(2)
assert.ok(suiteDirectory && outputDirectory && url, 'Provide suite, new output directory and local API URL')
assert.ok(['regression', 'first-answer'].includes(mode), 'Use an explicit collection mode')
const suitePath = resolve(suiteDirectory)
assert.ok(mode !== 'first-answer' || !existsSync(resolve(suitePath, 'first')), 'First answers already archived; use regression mode')
const outputPath = resolve(outputDirectory)
assert.ok(!existsSync(outputPath), 'Preserve existing first answers')
const manifest = JSON.parse(readFileSync(resolve(suitePath, 'manifest.json'), 'utf8'))
assert.equal(manifest.schemaVersion, 'answer-quality-suite-v1')
for (const name of ['cases.json', 'rubric.json', 'oracles-input.json']) {
  assert.equal(digest(readFileSync(resolve(suitePath, name))), manifest.files[name], name)
}
const cases = JSON.parse(readFileSync(resolve(suitePath, 'cases.json'), 'utf8'))
assert.equal(cases.length, manifest.cases)
assert.equal(new Set(cases.map(item => item.id)).size, cases.length)
assert.equal(cases.reduce((sum, item) => sum + item.turns.length, 0), manifest.turns)
assert.ok(cases.length > 0 && cases.length <= 40)
mkdirSync(outputPath, {recursive: true})
const session = localEvaluationSession(localBase(url))
const api = session.api
const opened = new Set()
const report = {
  schemaVersion: 'answer-quality-first-answers-v1',
  collectionKind: mode === 'first-answer' ? 'NEW_FIRST_ANSWERS' : 'KNOWN_QUESTION_REGRESSION',
  startedAt: new Date().toISOString(),
  datasetSha256: manifest.files['cases.json'],
  rubricSha256: manifest.files['rubric.json'],
  collectorSha256: digest(readFileSync(new URL(import.meta.url))),
  reviewMethod: 'NOT_REVIEWED',
  records: cases.map(item => ({caseId: item.id, status: 'NOT_RUN', turns: []})),
  cleanupErrors: []
}
const save = () => writeFileSync(resolve(outputPath, 'first-answers.json'), JSON.stringify(report, null, 2) + '\n')
let authenticated = false

async function collectCase(item, record) {
  const conversationId = 'qa17-' + randomBytes(12).toString('hex')
  record.status = 'COLLECTING'
  for (const prompt of item.turns) {
    const started = Date.now()
    const run = await api('/chat/runs', {
      ...item.scope, dataReleaseId: 'pokeapi-reference-v1', prompt,
      conversationId, timeoutSeconds: 120, idempotencyKey: randomBytes(16).toString('hex')
    }, 202)
    opened.add(run.runId)
    const events = []
    const seen = new Set()
    let cursor = '0'
    let view
    let ttftMs = null
    while (Date.now() - started < 140000) {
      const page = await api('/chat/runs/' + run.runId + '/events?afterEventId=' + cursor)
      for (const event of page.events || []) {
        const id = String(event.eventId ?? event.id)
        if (seen.has(id)) continue
        seen.add(id)
        cursor = id
        events.push(event)
        if (event.type === 'answer.delta' && event.payload?.text && ttftMs === null) ttftMs = Date.now() - started
      }
      view = await api('/chat/runs/' + run.runId)
      if (eventBoundaryReached(view.status, events) || view.status === 'WAITING_APPROVAL') break
      await new Promise(resolve => setTimeout(resolve, 300))
    }
    const eventFile = item.id + '-' + record.turns.length + '-events.json'
    const eventBytes = JSON.stringify(events, null, 2) + '\n'
    writeFileSync(resolve(outputPath, eventFile), eventBytes)
    record.turns.push({
      runId: run.runId, conversationId, prompt, status: view?.status,
      answer: events.filter(event => event.type === 'answer.delta').map(event => event.payload?.text || '').join(''),
      finalAnswer: view?.answer, ttftMs, totalMs: Date.now() - started,
      eventFile, eventsSha256: digest(eventBytes),
      tools: events.filter(event => event.type === 'tool.finished').map(event => event.payload),
      runCompleted: eventBoundaryReached(view?.status, events)
    })
    save()
    if (!eventBoundaryReached(view?.status, events)) {
      await api('/chat/runs/' + run.runId + '/cancel', {reason: 'quality_collection_cleanup'})
      opened.delete(run.runId)
      record.status = 'INCOMPLETE'
      return
    }
    opened.delete(run.runId)
  }
  record.teamUnchanged = sameJson(await api('/pokemon/teams/' + report.testTeam.id), report.testTeam)
  record.status = 'COLLECTED_PENDING_CONTENT_REVIEW'
}

try {
  await session.login('回答质量独立测试')
  authenticated = true
  const runtime = await api('/chat/runtime')
  report.runtime = publicFingerprint(runtime)
  report.implementationSha256 = runtime.implementationSha256
  report.semanticBefore = await api('/pokemon/guides/semantic')
  report.guideCatalog = await api('/pokemon/guides/catalog')
  const created = await api('/pokemon/teams', stage2Team(), 201)
  report.testTeam = await api('/pokemon/teams/' + created.id)
  const inputs = JSON.parse(readFileSync(resolve(suitePath, 'oracles-input.json'), 'utf8'))
  report.damageOracles = {}
  for (const [name, input] of Object.entries(inputs)) {
    report.damageOracles[name] = {input, result: await api('/pokemon/calculations/engine-damage', input)}
  }
  save()
  let next = 0
  async function worker() {
    while (next < cases.length) {
      const index = next++
      try {
        await collectCase(cases[index], report.records[index])
      } catch (error) {
        report.records[index].status = 'COLLECTION_ERROR'
        report.records[index].failure = collectionFailure(error)
      }
      save()
      console.log(report.records[index].caseId + ' ' + report.records[index].status)
    }
  }
  await Promise.all([worker(), worker()])
} catch (error) {
  report.setupFailure = collectionFailure(error)
} finally {
  for (const id of opened) {
    try { await api('/chat/runs/' + id + '/cancel', {reason: 'quality_collection_cleanup'}) }
    catch { report.cleanupErrors.push('CANCEL_FAILED') }
  }
  if (authenticated) {
    try {
      if (report.testTeam) report.teamUnchanged = sameJson(await api('/pokemon/teams/' + report.testTeam.id), report.testTeam)
      report.runtimeAfter = publicFingerprint(await api('/chat/runtime'))
      report.configurationUnchanged = sameJson(report.runtime, report.runtimeAfter)
      report.semanticAfter = await api('/pokemon/guides/semantic')
    } catch { report.cleanupErrors.push('FINAL_STATE_UNVERIFIED') }
    try { await session.logout() } catch { report.cleanupErrors.push('LOGOUT_FAILED') }
  }
  report.finishedAt = new Date().toISOString()
  report.summary = {
    groups: cases.length,
    collected: report.records.filter(item => item.status === 'COLLECTED_PENDING_CONTENT_REVIEW').length,
    turns: report.records.reduce((sum, item) => sum + item.turns.length, 0),
    contentVerdict: 'PENDING_AI_ASSISTED_REVIEW'
  }
  save()
  console.log(JSON.stringify(report.summary))
  if (report.summary.collected !== cases.length || report.cleanupErrors.length || report.teamUnchanged !== true || report.configurationUnchanged !== true) process.exitCode = 1
}
