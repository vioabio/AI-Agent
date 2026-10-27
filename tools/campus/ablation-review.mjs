import { canonical, digest, sameJson } from './evaluation.mjs'
import { compareAblation, excerptEvidence } from './ablation.mjs'

export const bundleHash = bundle => digest(canonical(Object.fromEntries(Object.entries(bundle).filter(([key]) => key !== 'bundleSha256'))))
const text = (value, max) => typeof value === 'string' && value.trim().length > 0 && value.length <= max
const key = row => `${row.mode}:${row.caseId}`

export function createReviewBundle(plan, captures, documents, inputSha256) {
  const comparison = compareAblation(plan, captures)
  const rows = comparison.rows.map(row => {
    const record = captures.find(c => c.mode === row.mode).records.find(r => r.caseId === row.caseId)
    const item = plan.cases.find(c => c.id === row.caseId)
    const excerpts = excerptEvidence(record.answer || '', documents)
    if (!sameJson(excerpts, record.excerpts || [])) throw Error('Captured excerpt evidence differs')
    return { key: key(row), mode: row.mode, caseId: row.caseId, observationHash: row.observationHash,
      question: item.prompt, answer: record.answer || '', criteria: item.semanticCriteria.map((criterion, i) => ({ id: `C${i + 1}`, text: criterion })),
      excerpts: excerpts.map((excerpt, i) => ({ id: `Q${i + 1}`, ...excerpt })),
      engineeringPass: row.completed && row.toolBoundary && row.usageMatchesHistory && row.modelCalls > 0,
      modelCalls: row.modelCalls, targetDocumentIds: item.relevantDocumentIds }
  })
  const bundle = { schemaVersion: 'ablation-review-bundle-v1', scope: 'Fixed development answers; literal source checks and self-declared reviews, not reviewer identity authentication or heldout acceptance',
    planSha256: captures[0].planSha256, corpusSha256: captures[0].corpusSha256, inputSha256, denominator: rows.length,
    documents: documents.map(d => ({ id: d.id, title: d.title, author: d.author, updatedAt: d.updatedAt, content: d.content })), rows }
  bundle.bundleSha256 = bundleHash(bundle)
  validateReviewBundle(bundle)
  return bundle
}

export function validateReviewBundle(bundle) {
  if (bundle?.schemaVersion !== 'ablation-review-bundle-v1' || bundle.bundleSha256 !== bundleHash(bundle)
      || !Array.isArray(bundle.rows) || !bundle.rows.length || bundle.rows.length !== bundle.denominator
      || new Set(bundle.rows.map(row => row.key)).size !== bundle.denominator || !Array.isArray(bundle.documents)) throw Error('Changed or invalid review bundle')
  const ids = bundle.documents.map(d => d.id)
  if (new Set(ids).size !== ids.length || bundle.documents.some(d => !text(d.id, 100) || !text(d.title, 200) || !text(d.content, 20000))) throw Error('Invalid source documents')
  for (const row of bundle.rows) {
    if (row.key !== key(row) || !/^[a-f0-9]{64}$/.test(row.observationHash) || !text(row.question, 2000) || typeof row.answer !== 'string'
        || row.answer.length > 100000 || typeof row.engineeringPass !== 'boolean' || !row.criteria?.length
        || row.criteria.some((c, i) => c.id !== `C${i + 1}` || !text(c.text, 2000))
        || !sameJson(row.excerpts, excerptEvidence(row.answer, bundle.documents).map((e, i) => ({ id: `Q${i + 1}`, ...e })))) throw Error('Invalid question, answer or literal evidence')
  }
  return bundle
}

export function emptyReviews(bundle) {
  validateReviewBundle(bundle)
  return { schemaVersion: 'ablation-review-draft-v1', bundleSha256: bundle.bundleSha256, reviews: bundle.rows.map(row => ({
    key: row.key, observationHash: row.observationHash, reviewerType: 'human', reviewer: '', reviewedAt: null, note: '', attested: false,
    decisions: row.criteria.map(c => ({ criterionId: c.id, verdict: 'unknown', reason: '', sourceIds: [] })),
    citations: row.excerpts.map(e => ({ excerptId: e.id, verdict: 'unknown', reason: '', sourceIds: [] })) })) }
}

export function compileReviews(bundle, submitted = { schemaVersion: 'ablation-reviews-v1', bundleSha256: bundle.bundleSha256, reviews: [] }) {
  validateReviewBundle(bundle)
  if (submitted.schemaVersion !== 'ablation-reviews-v1' || submitted.bundleSha256 !== bundle.bundleSha256 || !Array.isArray(submitted.reviews)) throw Error('Wrong bundle or draft; only submitted reviews can be imported')
  const lookup = new Map(), sourceIds = new Set(bundle.documents.map(d => d.id))
  for (const review of submitted.reviews) {
    const row = bundle.rows.find(r => r.key === review.key)
    if (!row || lookup.has(review.key) || review.observationHash !== row.observationHash || !['human', 'ai-assisted'].includes(review.reviewerType)
        || !text(review.reviewer, 80) || !text(review.note, 2000) || !text(review.reviewedAt, 80)
        || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(review.reviewedAt) || !Number.isFinite(Date.parse(review.reviewedAt))
        || (review.reviewerType === 'human' && review.attested !== true)) throw Error('Unbound, duplicate or incomplete reviewer')
    const check = (values, expected, idName, verdicts) => {
      if (!Array.isArray(values) || values.length !== expected.length || new Set(values.map(d => d[idName])).size !== expected.length
          || values.some(d => !expected.some(e => e.id === d[idName]) || !verdicts.includes(d.verdict) || !text(d.reason, 2000)
            || !Array.isArray(d.sourceIds) || new Set(d.sourceIds).size !== d.sourceIds.length || d.sourceIds.some(id => !sourceIds.has(id)))) throw Error('Invalid review decisions or sources')
    }
    check(review.decisions, row.criteria, 'criterionId', ['pass', 'fail', 'unknown'])
    check(review.citations, row.excerpts, 'excerptId', ['supports', 'irrelevant', 'unsupported', 'unknown'])
    if (review.decisions.some(d => d.verdict === 'pass' && !d.sourceIds.length)
        || review.citations.some(d => d.verdict === 'supports' && (!d.sourceIds.length
          || d.sourceIds.some(id => !row.excerpts.find(e => e.id === d.excerptId).documentIds.includes(id))))) throw Error('Passing reviews need actual allowed sources')
    lookup.set(review.key, review)
  }
  const rows = bundle.rows.map(row => {
    const review = lookup.get(row.key), literalFailures = row.excerpts.filter(e => !e.documentIds.length).map(e => e.id)
    const human = review?.reviewerType === 'human'
    const failed = review?.decisions.some(d => d.verdict === 'fail') || review?.citations.some(d => ['irrelevant', 'unsupported'].includes(d.verdict))
    const completePass = review?.decisions.every(d => d.verdict === 'pass') && review?.citations.every(d => d.verdict === 'supports')
    const reviewState = !review ? 'PENDING_REVIEW' : !human ? 'ASSISTED_REVIEW_ONLY' : failed ? 'FAIL' : completePass ? 'PASS' : 'PENDING_REVIEW'
    return { key: row.key, observationHash: row.observationHash, engineeringPass: row.engineeringPass, literalFailures, reviewState,
      assistedFlagged: Boolean(review && !human && failed),
      taskOutcome: !row.engineeringPass || literalFailures.length || reviewState === 'FAIL' ? 'FAIL' : reviewState === 'PASS' ? 'PASS' : 'PENDING_REVIEW' }
  })
  return { schemaVersion: 'ablation-review-report-v1', bundleSha256: bundle.bundleSha256, denominator: rows.length,
    engineeringPass: rows.filter(r => r.engineeringPass).length, literalMismatchCases: rows.filter(r => r.literalFailures.length).length,
    humanReviewed: submitted.reviews.filter(r => r.reviewerType === 'human').length, assistedReviewed: submitted.reviews.filter(r => r.reviewerType === 'ai-assisted').length,
    verifiedTaskPass: rows.filter(r => r.taskOutcome === 'PASS').length, fail: rows.filter(r => r.taskOutcome === 'FAIL').length,
    pendingReview: rows.filter(r => r.taskOutcome === 'PENDING_REVIEW').length, rows,
    scope: bundle.scope, reviewerIdentity: 'SELF_DECLARED_NOT_AUTHENTICATED' }
}
