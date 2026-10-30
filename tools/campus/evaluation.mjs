// Campus evaluation only. Never changes the v1.0/v1.1 release gates.
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve, relative, isAbsolute } from 'node:path'

export const digest = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : JSON.stringify(value)).digest('hex')
const hasText = value => typeof value === 'string' && value.trim().length > 0
function validReviewTime(value) {
  const match = typeof value === 'string' && /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.exec(value)
  if (!match || !Number.isFinite(Date.parse(value))) return false
  const [year, month, day, hour, minute, second] = match.slice(1).map(Number)
  const days = [31, year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1] && hour < 24 && minute < 60 && second < 60
}
// Git on Windows can convert repository text to CRLF. Frozen JSON identity uses
// UTF-8 with LF, without trimming or altering JSON values. Runtime class hashes stay binary.
export const frozenFileDigest = file => digest(readFileSync(file, 'utf8').replaceAll('\r\n', '\n'))
export const categories = ['facts', 'guides', 'team', 'damage', 'clarification', 'safety']
export const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value
export const sameJson = (a, b) => JSON.stringify(canonical(a)) === JSON.stringify(canonical(b))
// The Java facade adds source/checksum metadata. Every engine field, including
// nested assumptions, must still match; only additional top-level metadata is ignored.
export const matchesEngineReference = (actual, reference) => !!actual && Object.entries(reference).every(([key, value]) => sameJson(actual[key], value))
export function eventBoundaryReached(status, events) {
  const types = events.map(e => String(e.type || e.eventType).toLowerCase().replaceAll('_', '.'))
  if (status === 'WAITING_APPROVAL') return types.includes('approval.required')
  return ['COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(status)
    && types.some(type => ['run.completed', 'run.failed', 'run.timed.out', 'run.cancelled'].includes(type))
}
export function localBase(value) {
  const url = new URL(value)
  if (url.protocol !== 'http:' || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
      || url.username || url.password || url.search || url.hash || url.pathname !== '/api') throw Error('Only loopback http://host:port/api is supported')
  return url.href.replace(/\/$/, '')
}
export function inside(root, file) {
  const path = resolve(root, file), rel = relative(root, path)
  if (!rel || rel === '..' || rel.startsWith('../') || rel.startsWith('..\\') || isAbsolute(rel)) throw Error('File must stay inside evaluation directory')
  return path
}
export function loadSuite(directory) {
  const manifest = JSON.parse(readFileSync(resolve(directory, 'manifest.json'), 'utf8'))
  for (const [file, hash] of Object.entries(manifest.sha256 || {})) {
    if (frozenFileDigest(inside(directory, file)) !== hash) throw Error(`Frozen file changed: ${file}`)
  }
  for (const file of ['cases.json', 'sources.json', 'splits.json', 'fixtures.json']) if (!manifest.sha256?.[file]) throw Error(`Missing frozen file: ${file}`)
  const read = file => JSON.parse(readFileSync(inside(directory, file), 'utf8'))
  const suite = { manifest, cases: read('cases.json'), sources: read('sources.json'), splits: read('splits.json'), fixtures: read('fixtures.json') }
  validateSuite(suite)
  return suite
}
export function validateSuite({ manifest, cases, sources, splits, fixtures }) {
  if (manifest.schemaVersion !== 1 || !manifest.datasetId || !Array.isArray(cases) || cases.length !== manifest.caseCount) throw Error('Invalid dataset identity/count')
  const ids = new Set(), groups = new Map(), sourceIds = new Set(sources.map(s => s.id))
  if (sourceIds.size !== sources.length) throw Error('Duplicate source')
  for (const source of sources) if (!source.locator || !source.sha256 || !source.scope || !source.capturedAt) throw Error(`Incomplete source: ${source.id}`)
  const allSplits = [...splits.dev, ...splits.heldout]
  if (allSplits.length !== cases.length || new Set(allSplits).size !== cases.length) throw Error('Split membership must be exhaustive and unique')
  for (const item of cases) {
    if (!/^S2-[A-Z]+-\d{2}$/.test(item.id) || ids.has(item.id) || !categories.includes(item.category)) throw Error('Invalid/duplicate case identity')
    ids.add(item.id)
    if (!item.groupId || !['dev', 'heldout'].includes(item.split) || !splits[item.split].includes(item.id)) throw Error(`Invalid split: ${item.id}`)
    if (groups.has(item.groupId) && groups.get(item.groupId) !== item.split) throw Error(`Group leakage: ${item.groupId}`)
    groups.set(item.groupId, item.split)
    if (!item.prompt || !item.versions?.dataReleaseId || !item.preconditions || !fixtures.teams[item.preconditions.team]) throw Error(`Missing input/context: ${item.id}`)
    if (!['answer', 'preview'].includes(item.expected?.outcome) || !Array.isArray(item.expected.requiredTools) || !Array.isArray(item.expected.forbiddenTools)) throw Error(`Invalid assertions: ${item.id}`)
    if (!item.expected.criteria?.length || !item.annotation?.by || !item.annotation?.date || item.annotation.humanReview !== 'pending') throw Error(`Missing annotation provenance: ${item.id}`)
    const criteriaIds = item.expected.criteria.map(c => c.id)
    if (new Set(criteriaIds).size !== criteriaIds.length) throw Error('Duplicate criterion')
    for (const c of item.expected.criteria) if (!c.text || !c.sourceIds?.length || c.sourceIds.some(id => !sourceIds.has(id))) throw Error(`Untraceable criterion: ${item.id}`)
    if ((item.expected.requiredTools.some(name => item.expected.forbiddenTools.includes(name)))) throw Error('Contradictory tool constraints')
    for (const pattern of item.expected.forbiddenPatterns || []) new RegExp(pattern, 'iu')
  }
  if (allSplits.some(id => !ids.has(id))) throw Error('Unknown split case')
  return { cases: cases.length, groups: groups.size, dev: splits.dev.length, heldout: splits.heldout.length,
    categories: Object.fromEntries(categories.map(c => [c, cases.filter(item => item.category === c).length])) }
}

// Only immutable observations bind a review; a different answer cannot reuse it.
export const observationHash = record => digest({ caseId: record.caseId, repetition: record.repetition, status: record.status,
  answer: record.answer, preview: record.preview ?? null, toolOutcomes: record.toolOutcomes, checks: record.checks, elapsedMs: record.elapsedMs })
export function assess(item, record, review) {
  const failures = []
  const observed = record.toolOutcomes || []
  const required = item.expected.requiredTools
  const succeeded = required.filter(name => observed.some(t => t.tool === name && ['SUCCEEDED', 'EXECUTED'].includes(t.status)))
  if (record.status !== (item.expected.outcome === 'preview' ? 'WAITING_APPROVAL' : 'COMPLETED')) failures.push(`run:${record.status}`)
  if (item.expected.outcome === 'answer' && !record.answer?.trim()) failures.push('empty_answer')
  if (succeeded.length !== required.length) failures.push('missing_successful_required_tool')
  if ((item.expected.attemptedTools || []).some(name => !observed.some(t => t.tool === name))) failures.push('missing_required_tool_attempt')
  if (observed.some(t => item.expected.forbiddenTools.includes(t.tool))) failures.push('forbidden_tool_called')
  const requiredChecks = ['storageUnchanged', 'storageUnchangedAfterCleanup',
    item.expected.outcome === 'preview' ? 'onlyExpectedChange' : 'historyMatches',
    ...(item.preconditions.foreignTeam ? ['foreignReadDenied', 'foreignTeamUnchanged'] : []),
    ...(item.expected.damageFixture ? ['referenceEngineMatches', 'expectedDamageNumbersPresent'] : [])]
  for (const name of new Set([...requiredChecks, ...Object.keys(record.checks || {})])) if (record.checks?.[name] !== true) failures.push(`check:${name}`)
  const text = `${record.answer || ''}\n${record.preview ? JSON.stringify(record.preview) : ''}`
  for (const pattern of item.expected.forbiddenPatterns || []) if (new RegExp(pattern, 'iu').test(text)) failures.push('known_regression_pattern')
  let reviewState = 'PENDING'
  if (review) {
    if (review.caseId !== item.id || review.repetition !== record.repetition || review.observationSha256 !== observationHash(record)
        || !['human', 'ai-assisted'].includes(review.reviewerType) || !hasText(review.reviewer) || !validReviewTime(review.reviewedAt) || !hasText(review.note)) throw Error(`Unbound/incomplete human review or assisted review: ${item.id}`)
    const decisions = review.decisions || []
    const expectedIds = item.expected.criteria.map(c => c.id)
    if (!Array.isArray(decisions) || decisions.length !== expectedIds.length || new Set(decisions.map(d => d.criterionId)).size !== decisions.length
        || decisions.some(d => !expectedIds.includes(d.criterionId) || !['pass', 'fail', 'unknown'].includes(d.verdict) || !d.reason?.trim()
          || !d.sourceIds?.length || d.sourceIds.some(id => !item.expected.criteria.find(c => c.id === d.criterionId).sourceIds.includes(id)))) throw Error(`Invalid criterion review: ${item.id}`)
    reviewState = review.reviewerType === 'ai-assisted' ? 'ASSISTED_REVIEW_ONLY'
      : decisions.some(d => d.verdict === 'fail') ? 'FAIL' : decisions.some(d => d.verdict === 'unknown') ? 'PENDING' : 'PASS'
  }
  return { structuralPass: failures.length === 0, failures: [...new Set(failures)], requiredToolCount: required.length,
    successfulRequiredTools: succeeded.length, reviewState, assistedFlagged: review?.reviewerType === 'ai-assisted' && review.decisions.some(d => d.verdict === 'fail'),
    taskOutcome: failures.length || reviewState === 'FAIL' ? 'FAIL' : reviewState === 'PASS' ? 'PASS' : 'PENDING_REVIEW' }
}
function percentile(values, q) {
  const sorted = values.filter(Number.isFinite).sort((a, b) => a - b)
  return sorted.length ? sorted[Math.max(0, Math.ceil(sorted.length * q) - 1)] : null
}
export function summarize(suite, records, reviews = []) {
  const lookup = new Map(suite.cases.map(c => [c.id, c])), reviewLookup = new Map()
  for (const review of reviews) {
    const key = `${review.caseId}:${review.repetition}`
    if (reviewLookup.has(key)) throw Error('Duplicate review')
    reviewLookup.set(key, review)
  }
  const keys = new Set()
  const rows = records.map(record => {
    const item = lookup.get(record.caseId), key = `${record.caseId}:${record.repetition}`
    if (!item || keys.has(key) || !Number.isInteger(record.repetition) || record.repetition < 1) throw Error('Unknown/duplicate result')
    keys.add(key)
    const assessed = assess(item, record, reviewLookup.get(key))
    reviewLookup.delete(key)
    return { caseId: record.caseId, repetition: record.repetition, category: item.category, split: item.split, ...assessed }
  })
  if (reviewLookup.size) throw Error('Review without matching observation')
  if ([...reviewLookup.keys()].some(key => !keys.has(key))) throw Error('Review has no matching observation')
  const denominator = rows.length
  const metrics = selected => ({ denominator: selected.length, pass: selected.filter(r => r.taskOutcome === 'PASS').length,
    fail: selected.filter(r => r.taskOutcome === 'FAIL').length, pendingReview: selected.filter(r => r.taskOutcome === 'PENDING_REVIEW').length,
    structuralPass: selected.filter(r => r.structuralPass).length,
    // Pending review stays in the denominator; this is NOT a final quality score.
    verifiedSuccessRate: selected.length ? selected.filter(r => r.taskOutcome === 'PASS').length / selected.length : null })
  return { scope: 'Only selected, preallocated case repetitions; not a 100-case benchmark', denominator, ...metrics(rows),
    assistedReviewed: reviews.filter(r => r.reviewerType === 'ai-assisted').length,
    humanReviewed: reviews.filter(r => r.reviewerType === 'human').length,
    byCategory: Object.fromEntries(categories.map(c => [c, metrics(rows.filter(r => r.category === c))])),
    tools: { required: rows.reduce((n, r) => n + r.requiredToolCount, 0), successful: rows.reduce((n, r) => n + r.successfulRequiredTools, 0),
      caseDenominator: rows.filter(r => r.requiredToolCount > 0).length,
      casesAllRequiredSucceeded: rows.filter(r => r.requiredToolCount > 0 && r.requiredToolCount === r.successfulRequiredTools).length },
    latencyMs: { p50: percentile(records.map(r => r.elapsedMs), .5), p95: percentile(records.map(r => r.elapsedMs), .95), samples: records.filter(r => Number.isFinite(r.elapsedMs)).length },
    ttftMs: { p50: percentile(records.map(r => r.firstAnswerDeltaMs), .5), p95: percentile(records.map(r => r.firstAnswerDeltaMs), .95),
      source: 'Polling observation upper bound, includes buffering; not provider TTFT' },
    usage: 'unknown', cost: 'unknown', citationCorrectness: 'pending human source/claim review', retrievalRecallAt5: 'not measured: no instrumented chunk ranking', rows }
}
