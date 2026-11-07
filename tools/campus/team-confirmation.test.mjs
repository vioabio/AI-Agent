import test from 'node:test'
import assert from 'node:assert/strict'
import { confirmationPlan, confirmationChecks, summarizeConfirmations, teamDigest } from './team-confirmation.mjs'
function record(id) {
  const p = confirmationPlan.find(p => p.id === id)
  const before = { id: 1, ownerId: 'fixture-owner', name: '练习队', members: [{ slot: 1, build: { level: 50, nature: 'modest' } }] }
  const after = structuredClone(before); if (id === 'confirm') after.members[0].build.level = 51
  return { caseId: id, collected: true, before, beforeDecision: structuredClone(before), after,
    preview: { proposalId: 'fixture-approval', changes: [{ slot: 1, field: '等级', before: '50', after: '51' }] },
    decisionApprovalId: 'fixture-approval', decisionStatus: p.decisionStatus, status: p.terminal, terminalEvents: [p.event],
    outcome: { status: p.outcome, message: 'confirm conflict'.includes(id) ? 'fixture receipt' : null },
    answer: ['confirm', 'conflict'].includes(id) ? 'fixture receipt' : '', historyAnswer: ['confirm', 'conflict'].includes(id) ? 'fixture receipt' : '',
    completedTools: ['readCurrentTeam'], repeatDecisionStatus: 409, repeatReadbackMatches: true,
    concurrentEditThenRestore: true, accountBoundaryChecked: true }
}
test('four explicit workflow cases pass only with matching storage and receipts', () => {
  const summary = summarizeConfirmations(confirmationPlan.map(p => record(p.id)))
  assert.equal(summary.denominator, 4); assert.equal(summary.passed, 4); assert.equal(summary.semanticAcceptance, 'NOT_PERFORMED')
  assert.equal(teamDigest({ b: 1, a: 2 }), teamDigest({ a: 2, b: 1 }))
})
test('failed or unrun planned cases remain in the denominator', () => {
  const rows = confirmationPlan.map(p => record(p.id)); rows[2] = { caseId: 'cancel', collected: false }
  const summary = summarizeConfirmations(rows); assert.equal(summary.denominator, 4); assert.equal(summary.passed, 3)
  assert.throws(() => summarizeConfirmations(rows.slice(1)), /Missing/)
  assert.throws(() => summarizeConfirmations([...rows.slice(0, 3), rows[0]]), /Missing/)
})
test('extra changed fields and writes before confirmation fail the storage contract', () => {
  const r = record('confirm'); r.after.members[0].build.nature = 'adamant'
  assert.equal(confirmationChecks(r).exactStoredTeam, false)
  r.beforeDecision.members[0].build.level = 51; assert.equal(confirmationChecks(r).noWriteBeforeDecision, false)
})
test('cancelled run with HTTP 200 or a false saved answer cannot pass', () => {
  const r = record('cancel'); r.decisionStatus = 200; r.historyAnswer = '已保存'; r.answer = '已保存'
  assert.equal(confirmationChecks(r).boundDecision, false); assert.equal(confirmationChecks(r).approvalReceipt, false)
})
test('mismatched approval, duplicate terminals and missing real read are not accepted', () => {
  const r = record('confirm'); r.decisionApprovalId = 'other'; r.terminalEvents.push('run.failed'); r.completedTools = []
  const checks = confirmationChecks(r); assert.equal(checks.boundDecision, false); assert.equal(checks.expectedTerminal, false); assert.equal(checks.actualRead, false)
})
test('conflict and account isolation require explicit probes, not just an answer label', () => {
  const c = record('conflict'); delete c.concurrentEditThenRestore; assert.equal(confirmationChecks(c).conflictWasExercised, false)
  const r = record('confirm'); delete r.accountBoundaryChecked; assert.equal(confirmationChecks(r).accountBoundary, false)
  r.historyAnswer = 'different'; assert.equal(confirmationChecks(r).approvalReceipt, false)
})
