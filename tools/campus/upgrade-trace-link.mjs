import {readFileSync, writeFileSync, realpathSync, statSync} from 'node:fs'
import {resolve, relative, isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest} from './evaluation.mjs'

const names = new Set(['agent.run.admit', 'agent.run.dispatch', 'agent.run.execute', 'agent.run.authorize', 'agent.run.commit',
  'agent.tool.dispatch', 'agent.tool.callback', 'agent.model.call', 'agent.model.stream'])
const hex = (value, size) => typeof value === 'string' && new RegExp('^[a-f0-9]{' + size + '}$').test(value)

export function parseJaegerResponse(status, text) {
  const parsed = JSON.parse(text)
  if (status === 404 && parsed?.error?.httpCode === 404 && parsed.error.message === 'No traces found') {
    return {result:{resourceSpans:[]}}
  }
  if (status !== 200 || !Array.isArray(parsed?.result?.resourceSpans)) throw Error('QUERY_FAILED')
  return parsed
}

function attribute(span, key) {
  return (span.attributes || []).find(item => item.key === key)?.value?.stringValue
}

const attemptId = value => typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value)

function branch(runId, root, spans) {
  if (!hex(root.traceId, 32) || !hex(root.spanId, 16)) return {status:'INVALID_TRACE'}
  const selected = spans.filter(span => span.traceId === root.traceId)
  const byId = new Map()
  for (const span of selected) {
    const owner = attribute(span,'agent.run.id')
    if (!hex(span.spanId,16) || byId.has(span.spanId) || (span.parentSpanId && !hex(span.parentSpanId,16))
        || (owner !== undefined && owner !== runId)) return {status:'INVALID_TRACE'}
    byId.set(span.spanId,span)
  }
  // Admission may be an exported ancestor of dispatch or a synchronous worker.
  // All returned spans must share one acyclic tree, with at most one external parent.
  const tops = new Set()
  for (const span of selected) {
    let current = span
    const seen = new Set()
    while (true) {
      if (seen.has(current.spanId)) return {status:'INVALID_TRACE'}
      seen.add(current.spanId)
      const parent = byId.get(current.parentSpanId)
      if (!parent) { tops.add(current.spanId); break }
      current = parent
    }
  }
  if (tops.size !== 1) return {status:'INCOMPLETE_TRACE'}
  const exported = selected.filter(span => names.has(span.name)).map(span => ({
    spanId:span.spanId, parentSpanId:hex(span.parentSpanId,16)?span.parentSpanId:null,
    name:span.name, error:span.status?.code === 2
  }))
  return {status:'LINKED',traceId:root.traceId,spanId:root.spanId,traceUrl:'http://127.0.0.1:16686/trace/'+root.traceId,
    spans:exported,scope:'RETURNED_DOMAIN_SPANS'}
}

export function assessTrace(runId, response) {
  const spans = []
  for (const resource of response?.result?.resourceSpans || []) {
    for (const scope of resource.scopeSpans || []) spans.push(...(scope.spans || []))
  }
  if (spans.length > 10000) throw Error('TRACE_RESPONSE_TOO_LARGE')
  const owned = spans.filter(span => attribute(span,'agent.run.id') === runId)
  const roots = owned.filter(span => span.name === 'agent.run.execute')
  const dispatches = owned.filter(span => span.name === 'agent.run.dispatch')
  if (!roots.length && !dispatches.length) return {status:'NOT_FOUND'}
  const modern = [...roots,...dispatches].some(span => attribute(span,'agent.run.attempt.id') !== undefined)
  if (!modern) {
    if (roots.length !== 1) return {status:'AMBIGUOUS_RUN_ROOT'}
    return branch(runId,roots[0],spans)
  }
  const workers = new Map(), queues = new Map()
  for (const [rows,map] of [[roots,workers],[dispatches,queues]]) {
    for (const span of rows) {
      const id = attribute(span,'agent.run.attempt.id')
      if (!attemptId(id) || map.has(id)) return {status:'AMBIGUOUS_RUN_ROOT'}
      map.set(id,span)
    }
  }
  const attempts = []
  for (const id of [...new Set([...workers.keys(),...queues.keys()])].sort()) {
    const worker = workers.get(id), queue = queues.get(id)
    if (!queue) return {status:'INCOMPLETE_ATTEMPT'}
    const outcome = attribute(queue,'agent.run.dispatch.outcome')
    if (worker ? outcome !== 'STARTED' : !['CANCELLED','TIMED_OUT','FAILED','ABORTED'].includes(outcome))
      return {status:'INCOMPLETE_ATTEMPT'}
    const dispatch = branch(runId,queue,spans), execution = worker ? branch(runId,worker,spans) : null
    for (const value of [dispatch,execution]) if (value && value.status !== 'LINKED') return value
    attempts.push({attemptId:id,outcome,dispatch,execution})
  }
  const primary = attempts.length === 1 ? (attempts[0].execution || attempts[0].dispatch) : {}
  return {...primary,status:'LINKED',scope:'RETURNED_ATTEMPT_DOMAIN_SPANS',attempts}
}

export async function linkBaseline(baseline, query) {
  if (baseline?.schemaVersion !== 'upgrade-baseline-v1' || !Array.isArray(baseline.records)
      || baseline.records.length > 100) throw Error('BASELINE_REQUIRED')
  const records = []
  const cases = new Set()
  const runs = new Set()
  let selected = null
  if (baseline.selectedCaseIds !== undefined) {
    if (!Array.isArray(baseline.selectedCaseIds) || !baseline.selectedCaseIds.length
        || new Set(baseline.selectedCaseIds).size !== baseline.selectedCaseIds.length
        || baseline.selectedCaseIds.some(id => typeof id !== 'string' || !/^[A-Z0-9-]{1,80}$/.test(id))) throw Error('INVALID_SELECTION')
    selected = new Set(baseline.selectedCaseIds)
  }
  for (const record of baseline.records) {
    if (typeof record.caseId !== 'string' || !/^[A-Z0-9-]{1,80}$/.test(record.caseId)
        || cases.has(record.caseId)) throw Error('INVALID_CASE')
    cases.add(record.caseId)
    if (selected && !selected.has(record.caseId)) continue
    if (!Array.isArray(record.turns) || record.turns.length > 20) throw Error('INVALID_CASE')
    for (const [turnIndex, turn] of record.turns.entries()) {
      if (typeof turn.runId !== 'string' || !/^run-[a-zA-Z0-9-]{1,76}$/.test(turn.runId)
          || runs.has(turn.runId) || runs.size >= 500) throw Error('INVALID_RUN')
      runs.add(turn.runId)
      let result
      try { result = assessTrace(turn.runId, await query(turn.runId)) }
      catch { result = {status:'QUERY_UNAVAILABLE'} }
      records.push({caseId:record.caseId, turnIndex, runId:turn.runId, ...result})
    }
  }
  if (selected && [...selected].some(id => !cases.has(id))) throw Error('INVALID_SELECTION')
  const summary = {}
  for (const row of records) summary[row.status] = (summary[row.status] || 0) + 1
  return {schemaVersion:'upgrade-trace-links-v1',scope:'RECORDED_RUN_TRACE_ASSOCIATIONS',
    releaseDecision:'NOT_EVALUATED',records,summary,total:records.length}
}

function artifact(file) {
  const base = realpathSync('artifacts')
  const target = realpathSync(file)
  const within = relative(base, target)
  if (!within || within.startsWith('..') || isAbsolute(within) || statSync(target).size > 16777216) throw Error('ARTIFACT_REQUIRED')
  return readFileSync(target, 'utf8')
}

async function main() {
  const [baselineFile, outputFile] = process.argv.slice(2)
  if (!baselineFile || !outputFile) throw Error('Usage: upgrade-trace-link.mjs BASELINE_JSON NEW_REPORT_JSON')
  const bytes = artifact(baselineFile)
  const output = resolve(outputFile)
  const within = relative(realpathSync('artifacts'), realpathSync(resolve(output, '..')))
  if (within.startsWith('..') || isAbsolute(within)) throw Error('ARTIFACT_OUTPUT_REQUIRED')
  const query = async () => {
    const endpoint = new URL('http://127.0.0.1:16686/api/v3/traces')
    endpoint.searchParams.set('query.service_name', 'vio-agent-local')
    endpoint.searchParams.set('query.start_time_min', new Date(Date.now() - 3600000).toISOString())
    endpoint.searchParams.set('query.start_time_max', new Date(Date.now() + 30000).toISOString())
    const response = await fetch(endpoint, {redirect:'error',signal:AbortSignal.timeout(5000)})
    const text = await response.text()
    if (Buffer.byteLength(text) > 16777216) throw Error('QUERY_TOO_LARGE')
    return parseJaegerResponse(response.status, text)
  }
  // Reuse one bounded search snapshot for all Run associations in this report.
  let snapshot
  try { snapshot = await query() } catch { snapshot = null }
  const report = await linkBaseline(JSON.parse(bytes), async () => {
    if (snapshot === null) throw Error('QUERY_UNAVAILABLE')
    return snapshot
  })
  report.baselineSha256 = digest(bytes)
  report.queryWindowMinutes = 60
  report.capturedAt = new Date().toISOString()
  writeFileSync(output, JSON.stringify(report, null, 2) + '\n', {flag:'wx'})
  console.log(JSON.stringify({total:report.total,summary:report.summary,releaseDecision:report.releaseDecision}))
  process.exitCode = report.records.every(row => row.status === 'LINKED') && report.total > 0 ? 0 : 1
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main()
