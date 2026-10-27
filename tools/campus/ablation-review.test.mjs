import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { modes, excerptEvidence } from './ablation.mjs'
import { createReviewBundle, emptyReviews, compileReviews, bundleHash, validateReviewBundle } from './ablation-review.mjs'
import { renderReviewHtml } from './review-workbench-html.mjs'
import { loadReviewEvidence, verifyFrozenBundle } from './review-workbench.mjs'
import { frozenFileDigest, digest } from './evaluation.mjs'
import path from 'node:path'

function unavailableCaptureFiles() {
  fs.mkdirSync('tmp', { recursive: true })
  const directory = fs.mkdtempSync(path.resolve('tmp/review-evidence-'))
  const planFile = 'evaluation/campus/ablation/manifest.json', plan = JSON.parse(fs.readFileSync(planFile))
  return modes.map(mode => {
    const folder = path.join(directory, mode); fs.mkdirSync(folder)
    const capture = { mode, sourcesUnchanged: true, planSha256: frozenFileDigest(planFile), corpusSha256: plan.corpusSha256, sourceSha256: { fixture: 'same' },
      runtime: { evaluationPolicy: { mode, active: true }, configuredProvider: 'fixture', configuredModelId: 'fixture', implementationSha256: { fixture: 'same' } },
      records: plan.cases.map(item => {
        const eventsFile = item.id + '.events.json', bytes = '[]'
        fs.writeFileSync(path.join(folder, eventsFile), bytes)
        return { caseId: item.id, collected: false, eventsFile, eventsSha256: digest(bytes) }
      }) }
    const file = path.join(folder, 'capture.json'); fs.writeFileSync(file, JSON.stringify(capture)); return file
  })
}

function fixture(unmatched = false) {
  const documents = [{ id: 'known', title: '项目默认值', author: 'fixture', updatedAt: '2026-10-26', content: '等级默认50；种族值与实际能力不同。' }]
  const plan = { schemaVersion: 'campus-tool-ablation-plan-v1', split: 'development-only', modes,
    cases: [{ id: 'CASE-ONE', prompt: '请说明本项目默认等级', relevantDocumentIds: ['known'], semanticCriteria: ['默认值需有来源', '默认值不代表最优'] }] }
  const captures = modes.map(mode => {
    const answer = unmatched && mode === 'MODEL_ONLY' ? '> 编造的原文' : '> 等级默认50；种族值与实际能力不同。'
    return { mode, sourcesUnchanged: true, planSha256: 'plan', corpusSha256: 'corpus', sourceSha256: { code: 'fixed' },
      runtime: { evaluationPolicy: { mode, active: true }, configuredProvider: 'fixture', configuredModelId: 'fixture', implementationSha256: { agent: 'fixed' } },
      records: [{ caseId: 'CASE-ONE', collected: true, status: 'COMPLETED', historyMatches: true, usageMatchesHistory: true, modelCalls: 1,
        startedTools: mode === 'MODEL_ONLY' ? [] : ['searchPokemonGuides'], runtimeTools: mode === 'MODEL_ONLY' ? [] : ['searchPokemonGuides'], answer, excerpts: excerptEvidence(answer, documents) }] }
  })
  return createReviewBundle(plan, captures, documents, ['capture-a', 'capture-b', 'capture-c'])
}
function submission(bundle, { reviewerType = 'human', verdict = 'pass', citation = 'supports' } = {}) {
  const draft = emptyReviews(bundle), r = draft.reviews[0]
  r.reviewer = 'SYNTHETIC_UNIT_FIXTURE_NOT_A_REAL_REVIEW'; r.reviewerType = reviewerType; r.attested = reviewerType === 'human'
  r.reviewedAt = '2026-10-26T01:00:00.000Z'; r.note = 'Synthetic parser test; never counted as project evidence'
  for (const d of r.decisions) { d.verdict = verdict; d.reason = 'Fixture reason'; d.sourceIds = ['known'] }
  for (const d of r.citations) { d.verdict = citation; d.reason = 'Fixture quote reason'; d.sourceIds = ['known'] }
  return { schemaVersion: 'ablation-reviews-v1', bundleSha256: bundle.bundleSha256, reviews: [r] }
}

test('engineering success and literal matching alone cannot produce semantic passes', () => {
  const bundle = fixture(), report = compileReviews(bundle)
  assert.equal(report.denominator, 3); assert.equal(report.engineeringPass, 3)
  assert.equal(report.verifiedTaskPass, 0); assert.equal(report.pendingReview, 3)
  assert.throws(() => compileReviews(bundle, emptyReviews(bundle)), /draft/)
})
test('known unmatched quote is a separate failure while all planned cases remain', () => {
  const bundle = fixture(true), report = compileReviews(bundle)
  assert.equal(report.engineeringPass, 3); assert.equal(report.literalMismatchCases, 1)
  assert.equal(report.fail, 1); assert.equal(report.pendingReview, 2); assert.equal(report.denominator, 3)
})
test('only complete bound self-declared human decisions may pass, AI assistance stays pending', () => {
  const bundle = fixture()
  assert.equal(compileReviews(bundle, submission(bundle)).verifiedTaskPass, 1)
  const ai = compileReviews(bundle, submission(bundle, { reviewerType: 'ai-assisted' }))
  assert.equal(ai.assistedReviewed, 1); assert.equal(ai.humanReviewed, 0); assert.equal(ai.verifiedTaskPass, 0)
  assert.equal(ai.pendingReview, 3)
})
test('unknown decisions stay pending; irrelevant but literal quotes fail human relevance review', () => {
  const bundle = fixture()
  assert.equal(compileReviews(bundle, submission(bundle, { verdict: 'unknown', citation: 'unknown' })).pendingReview, 3)
  const failed = compileReviews(bundle, submission(bundle, { citation: 'irrelevant' }))
  assert.equal(failed.fail, 1); assert.equal(failed.verifiedTaskPass, 0)
  const assisted = compileReviews(bundle, submission(bundle, { reviewerType: 'ai-assisted', citation: 'irrelevant' }))
  assert.equal(assisted.fail, 0); assert.equal(assisted.rows[0].assistedFlagged, true)
})
test('changed answers, bundle identities, duplicate and cross-observation reviews are rejected', () => {
  const bundle = fixture(), review = submission(bundle)
  const changed = structuredClone(bundle); changed.rows[0].answer += ' changed'
  assert.throws(() => compileReviews(changed, review), /bundle/)
  const wrong = structuredClone(review); wrong.reviews[0].observationHash = '0'.repeat(64)
  assert.throws(() => compileReviews(bundle, wrong), /Unbound/)
  const duplicate = structuredClone(review); duplicate.reviews.push(duplicate.reviews[0])
  assert.throws(() => compileReviews(bundle, duplicate), /duplicate/)
  const other = structuredClone(review); other.bundleSha256 = 'other'
  assert.throws(() => compileReviews(bundle, other), /Wrong bundle/)
})
test('missing attestation, timestamp, reason, decisions or source evidence cannot become a pass', () => {
  const bundle = fixture()
  for (const mutate of [r => { r.attested = false }, r => { r.reviewedAt = 'invalid' }, r => { r.decisions[0].reason = '' },
    r => { r.decisions.pop() }, r => { r.decisions[0].sourceIds = [] }, r => { r.decisions[0].sourceIds = ['invented'] }, r => { r.citations = [] }]) {
    const review = submission(bundle); mutate(review.reviews[0])
    assert.throws(() => compileReviews(bundle, review))
  }
  assert.throws(() => compileReviews(fixture(true), submission(fixture(true))), /Passing reviews/)
})
test('literal evidence cannot be overwritten even by rehashing the bundle', () => {
  const bundle = fixture(true); bundle.rows[0].excerpts[0].documentIds = ['known']; bundle.bundleSha256 = bundleHash(bundle)
  assert.throws(() => validateReviewBundle(bundle), /literal evidence/)
})
test('offline reviewer safely embeds untrusted answers and blocks network and script injection', () => {
  const bundle = fixture(); bundle.rows[0].answer = '</script><img src=x onerror="window.hacked=1">'; bundle.rows[0].excerpts = []; bundle.bundleSha256 = bundleHash(bundle)
  const html = renderReviewHtml(bundle)
  assert.ok(!html.includes(bundle.rows[0].answer)); assert.ok(html.includes('\\u003c/script\\u003e'))
  assert.ok(html.includes("connect-src 'none'")); assert.ok(html.includes("script-src 'sha256-"))
  assert.ok(!html.includes('innerHTML')); assert.ok(!html.includes('localStorage'))
})

test('raw evidence reader preserves unavailable cases and rejects changed bytes or traversal', () => {
  const files = unavailableCaptureFiles(), bundle = loadReviewEvidence(files)
  assert.equal(bundle.denominator, 6); assert.equal(compileReviews(bundle).fail, 6)
  const capture = JSON.parse(fs.readFileSync(files[0]))
  fs.writeFileSync(path.join(path.dirname(files[0]), capture.records[0].eventsFile), '[{}]')
  assert.throws(() => loadReviewEvidence(files), /Changed evidence/)
  capture.records[0].eventsFile = '../private.events.json'; fs.writeFileSync(files[0], JSON.stringify(capture))
  assert.throws(() => loadReviewEvidence(files), /Invalid evidence path/)
})

test('re-render and report bind frozen questions, criteria and full source content', () => {
  const bundle = loadReviewEvidence(unavailableCaptureFiles()); verifyFrozenBundle(bundle)
  for (const mutate of [b => { b.documents[0].content += ' changed' }, b => { b.rows[0].question += ' changed' }, b => { b.rows[0].criteria[0].text += ' changed' }]) {
    const altered = structuredClone(bundle); mutate(altered); altered.bundleSha256 = bundleHash(altered)
    assert.throws(() => verifyFrozenBundle(altered), /frozen|Changed question/)
  }
})
