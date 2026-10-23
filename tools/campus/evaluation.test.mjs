import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, cpSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { execFileSync, spawnSync } from 'node:child_process'
import { loadSuite, validateSuite, assess, summarize, observationHash, localBase, sameJson, eventBoundaryReached, matchesEngineReference, frozenFileDigest } from './evaluation.mjs'

const suite = loadSuite(resolve('evaluation/campus/s2'))
const item = suite.cases.find(c => c.id === 'S2-FACTS-01')
const observation = () => ({ caseId: item.id, repetition: 1, status: 'COMPLETED', answer: '杰尼龟：待复核的回答',
  toolOutcomes: [{ tool: 'lookupPokemonReference', status: 'SUCCEEDED' }], checks: { historyMatches: true, storageUnchanged: true, storageUnchangedAfterCleanup: true }, elapsedMs: 100, firstAnswerDeltaMs: 70 })
const review = record => ({ caseId: record.caseId, repetition: record.repetition, observationSha256: observationHash(record),
  reviewerType: 'human', reviewer: 'test-only-human-fixture', reviewedAt: '2026-10-23T00:00:00Z', note: 'Synthetic review tests validation only; not real model quality evidence',
  decisions: item.expected.criteria.map(c => ({ criterionId: c.id, verdict: 'pass', reason: 'Synthetic assertion for review parser', sourceIds: c.sourceIds })) })

test('36 source-linked cases and 20 groups split 14/22 without group leakage', () => {
  assert.deepEqual(validateSuite(suite), { cases: 36, groups: 20, dev: 14, heldout: 22,
    categories: { facts: 6, guides: 8, team: 6, damage: 4, clarification: 8, safety: 4 } })
})
test('changing frozen case content is detected before collection', () => {
  const directory = mkdtempSync(join(tmpdir(), 's2-manifest-'))
  try {
    cpSync('evaluation/campus/s2', directory, { recursive: true })
    writeFileSync(join(directory, 'cases.json'), readFileSync(join(directory, 'cases.json'), 'utf8') + '\n')
    assert.throws(() => loadSuite(directory), /Frozen file changed/)
  } finally { rmSync(directory, { recursive: true, force: true }) }
})
test('Windows Git CRLF checkout preserves frozen JSON identity', () => {
  const directory = mkdtempSync(join(tmpdir(), 's2-crlf-'))
  try {
    cpSync('evaluation/campus/s2', directory, { recursive: true })
    for (const file of ['manifest.json', 'cases.json', 'sources.json', 'splits.json', 'fixtures.json']) {
      writeFileSync(join(directory, file), readFileSync(join(directory, file), 'utf8').replaceAll('\r\n', '\n').replaceAll('\n', '\r\n'))
    }
    assert.equal(loadSuite(directory).cases.length, 36)
  } finally { rmSync(directory, { recursive: true, force: true }) }
})
test('same group cannot leak into heldout under another question', () => {
  const changed = structuredClone(suite)
  changed.cases.find(c => c.split === 'heldout').groupId = item.groupId
  assert.throws(() => validateSuite(changed), /Group leakage/)
})
test('every expected claim needs an identified source', () => {
  const changed = structuredClone(suite)
  changed.cases[0].expected.criteria[0].sourceIds = ['made-up-source']
  assert.throws(() => validateSuite(changed), /Untraceable/)
})
test('successful execution remains pending until every semantic criterion is reviewed', () => {
  const result = assess(item, observation())
  assert.equal(result.structuralPass, true)
  assert.equal(result.taskOutcome, 'PENDING_REVIEW')
  assert.equal(result.reviewState, 'PENDING')
})
test('a tool start or unsuccessful finish cannot satisfy tool coverage', () => {
  for (const status of ['STARTED', 'FAILED', 'REJECTED', 'TIMED_OUT']) {
    const record = observation(); record.toolOutcomes[0].status = status
    assert.equal(assess(item, record).taskOutcome, 'FAIL')
  }
})
test('empty completed answer fails even with a positive human fixture', () => {
  const record = observation(); record.answer = ''
  assert.equal(assess(item, record, review(record)).taskOutcome, 'FAIL')
})
test('timeout, not run and network failure remain in the denominator', () => {
  const records = ['COMPLETED', 'TIMED_OUT', 'NOT_RUN', 'COLLECTION_ERROR'].map((status, index) => ({ ...observation(), status, repetition: index + 1 }))
  const result = summarize(suite, records)
  assert.equal(result.denominator, 4); assert.equal(result.fail, 3); assert.equal(result.pendingReview, 1)
  assert.equal(result.verifiedSuccessRate, 0)
})
test('review cannot be reused for different answer, run status, tool result, checks or repetition', () => {
  const original = observation(), completedReview = review(original)
  for (const change of [{ answer: 'changed' }, { status: 'FAILED' }, { checks: { writeOccurred: true } }, { toolOutcomes: [] }, { repetition: 2 }]) {
    assert.throws(() => assess(item, { ...original, ...change }, completedReview), /Unbound/)
  }
})
test('LLM review cannot be labelled human review by a different reviewerType', () => {
  const record = observation(), judge = review(record); judge.reviewerType = 'llm'
  assert.throws(() => assess(item, record, judge), /human review/)
})
test('review must cover all criteria once and cite their approved sources', () => {
  const record = observation()
  const missing = review(record); missing.decisions.pop()
  const duplicate = review(record); duplicate.decisions[1] = duplicate.decisions[0]
  const invented = review(record); invented.decisions[0].sourceIds = ['not-annotated']
  for (const invalid of [missing, duplicate, invented]) assert.throws(() => assess(item, record, invalid), /Invalid criterion/)
})
test('unknown human verdict is pending, negative verdict fails', () => {
  const record = observation(), annotated = review(record)
  annotated.decisions[0].verdict = 'unknown'
  assert.equal(assess(item, record, annotated).taskOutcome, 'PENDING_REVIEW')
  annotated.decisions[0].verdict = 'fail'
  assert.equal(assess(item, record, annotated).taskOutcome, 'FAIL')
})
test('bound synthetic human fixture can pass; proves parser only', () => {
  const record = observation()
  assert.equal(assess(item, record, review(record)).taskOutcome, 'PASS')
})
test('known hardy mistranslation is retained as failure despite completed run', () => {
  const target = suite.cases.find(c => c.id === 'S2-CLARIFICATION-01')
  const record = { ...observation(), caseId: target.id, answer: '固执（无能力修正）', toolOutcomes: [{ tool: 'readCurrentTeam', status: 'SUCCEEDED' }] }
  assert.ok(assess(target, record).failures.includes('known_regression_pattern'))
})
test('forbidden calculation in clarification and any missing side-effect check fail', () => {
  const target = suite.cases.find(c => c.id === 'S2-CLARIFICATION-01')
  const record = { ...observation(), caseId: target.id, toolOutcomes: [{ tool: 'readCurrentTeam', status: 'SUCCEEDED' }, { tool: 'calculateBattleDamage', status: 'FAILED' }] }
  assert.ok(assess(target, record).failures.includes('forbidden_tool_called'))
  assert.ok(assess(item, { ...observation(), checks: {} }).failures.includes('check:storageUnchanged'))
})
test('duplicate case repetition cannot inflate scores', () => {
  assert.throws(() => summarize(suite, [observation(), observation()]), /duplicate result/)
  assert.throws(() => summarize(suite, [observation()], [review(observation()), review(observation())]), /Duplicate review/)
})
test('unknown usage/cost/recall stay unknown; polling TTFT label is explicit', () => {
  const result = summarize(suite, [observation()])
  assert.equal(result.cost, 'unknown'); assert.equal(result.usage, 'unknown')
  assert.match(result.retrievalRecallAt5, /not measured/); assert.match(result.ttftMs.source, /Polling observation upper bound/)
})
test('nearest-rank percentiles retain failed request times', () => {
  const result = summarize(suite, [1, 2, 3, 1000].map((elapsedMs, index) => ({ ...observation(), repetition: index + 1, status: index === 3 ? 'TIMED_OUT' : 'COMPLETED', elapsedMs })))
  assert.equal(result.latencyMs.p50, 2); assert.equal(result.latencyMs.p95, 1000)
})
test('loopback collector rejects remote URLs, credentials, query and deceptive localhost names', () => {
  assert.equal(localBase('http://127.0.0.1:18127/api'), 'http://127.0.0.1:18127/api')
  for (const value of ['https://example.com/api', 'http://localhost.example.com/api', 'http://user:pass@localhost/api', 'http://localhost/api?x=1', 'http://localhost/other']) assert.throws(() => localBase(value))
})
test('CLI rejects wrong-split and unknown selection before network or output creation', () => {
  for (const args of [['--case', 'S2-FACTS-04'], ['--case', 'missing'], ['--repetitions', '0'], ['--mode', 'fake']]) {
    const result = spawnSync(process.execPath, ['tools/campus/s2-evaluate.mjs', 'run', ...args], { encoding: 'utf8' })
    assert.notEqual(result.status, 0)
  }
})
test('report reproduction detects silently removed planned observations', () => {
  const directory = mkdtempSync(join(tmpdir(), 's2-report-'))
  try {
    const report = { datasetSha256: frozenFileDigest('evaluation/campus/s2/manifest.json'), selectedCaseIds: [item.id], repetitions: 2, records: [observation()] }
    writeFileSync(join(directory, 'input.json'), JSON.stringify(report))
    const result = spawnSync(process.execPath, ['tools/campus/s2-evaluate.mjs', 'report', '--report', join(directory, 'input.json'), '--output', join(directory, 'out')], { encoding: 'utf8' })
    assert.notEqual(result.status, 0); assert.match(result.stderr, /lost planned cases/)
  } finally { rmSync(directory, { recursive: true, force: true }) }
})
test('completed status alone cannot truncate terminal answer events', () => {
  assert.equal(eventBoundaryReached('COMPLETED', [{ type: 'tool.finished' }]), false)
  assert.equal(eventBoundaryReached('COMPLETED', [{ type: 'answer.delta' }, { type: 'run.completed' }]), true)
  assert.equal(eventBoundaryReached('WAITING_APPROVAL', []), false)
  assert.equal(eventBoundaryReached('WAITING_APPROVAL', [{ type: 'approval.required' }]), true)
})
test('structured numerical reference ignores object key order but not numeric value or array order', () => {
  assert.equal(sameJson({ range: { minimum: 1, maximum: 2 } }, { range: { maximum: 2, minimum: 1 } }), true)
  assert.equal(sameJson({ x: 1 }, { x: '1' }), false)
  assert.equal(sameJson([1, 2], [2, 1]), false)
})
test('Java source metadata does not fail numerical engine comparison; all actual engine fields must match', () => {
  const reference = suite.fixtures.damage.surf.result
  const facade = { ...structuredClone(reference), attackerSource: 'https://pokeapi.co/api/v2/pokemon/7/', attackerChecksum: 'fixture' }
  assert.equal(matchesEngineReference(facade, reference), true)
  facade.range.maximum++
  assert.equal(matchesEngineReference(facade, reference), false)
})
