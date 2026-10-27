import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { auditQuestionIntake } from './question-intake.mjs'
import { loadSuite, frozenFileDigest } from './evaluation.mjs'
const intake = () => JSON.parse(fs.readFileSync('evaluation/campus/question-intake/candidates-v1.json'))
const base = loadSuite('evaluation/campus/s2'), hash = frozenFileDigest('evaluation/campus/s2/manifest.json')
test('two traceable user reports remain candidates and do not become a 38 or 100-case benchmark', () => {
  const report = auditQuestionIntake(intake(), base, hash)
  assert.equal(report.frozenFormalCases, 36); assert.equal(report.pendingCandidates, 2)
  assert.equal(report.userReportedCandidates, 2); assert.equal(report.newBenchmarkAdmissions, 0)
  assert.equal(report.unfilledFormalSlots, 64); assert.equal(report.groupingReview, 'PENDING_SEMANTIC_DEDUPLICATION')
})
test('changing frozen identity, publishing or assigning heldout membership is rejected', () => {
  for (const mutate of [v => { v.base.manifestSha256 = 'wrong' }, v => { v.candidates[0].status = 'accepted' }, v => { v.candidates[0].split = 'heldout' }]) {
    const v = intake(); mutate(v); assert.throws(() => auditQuestionIntake(v, base, hash))
  }
})
test('untraceable origins and mislabeled AI variants cannot count as real user questions', () => {
  const v = intake(); v.candidates[0].origin.excerpt = 'unrelated'; assert.throws(() => auditQuestionIntake(v, base, hash), /origin/)
  const a = intake(); a.candidates[0].origin.kind = 'AI_AUTHORED_VARIANT'; assert.throws(() => auditQuestionIntake(a, base, hash), /variants/)
  a.candidates[0].origin.derivedFrom = a.candidates[1].id
  const report = auditQuestionIntake(a, base, hash); assert.equal(report.userReportedCandidates, 1); assert.equal(report.aiAuthoredVariants, 1)
})
test('exact duplicates are flagged without leaking heldout prompts or silently deleting candidates', () => {
  const v = intake(), duplicate = structuredClone(v.candidates[0]); duplicate.id = 'CQ-DUPLICATE'; v.candidates.push(duplicate)
  const report = auditQuestionIntake(v, base, hash); assert.equal(report.pendingCandidates, 3); assert.equal(report.exactDuplicateCandidates, 1)
  assert.equal(report.rows[2].exactCandidateMatch, v.candidates[0].id); assert.ok(!JSON.stringify(report).includes(duplicate.promptOriginal))
  v.candidates[0].promptOriginal = base.cases[0].prompt; v.candidates[0].origin.excerpt = base.cases[0].prompt
  assert.equal(auditQuestionIntake(v, base, hash).rows[0].exactSeedMatch, base.cases[0].id)
})
