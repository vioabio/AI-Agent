import { createHash } from 'node:crypto'

// Separate development matrix. The frozen S2 dataset and heldout split are not changed.
export const confirmationPlan = Object.freeze([
  Object.freeze({ id: 'confirm', terminal: 'COMPLETED', event: 'run.completed', outcome: 'APPLIED', decisionStatus: 200 }),
  Object.freeze({ id: 'deny', terminal: 'FAILED', event: 'run.failed', outcome: 'DENIED', decisionStatus: 200 }),
  Object.freeze({ id: 'cancel', terminal: 'CANCELLED', event: 'run.cancelled', outcome: 'CANCELLED', decisionStatus: 409 }),
  Object.freeze({ id: 'conflict', terminal: 'COMPLETED', event: 'run.completed', outcome: 'CONFLICT', decisionStatus: 200 })
])
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]))
  return value
}
export const teamDigest = value => createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex')
export function confirmationChecks(record) {
  const plan = confirmationPlan.find(p => p.id === record?.caseId)
  if (!plan) throw Error('Unknown confirmation case')
  let expected = record.before ? structuredClone(record.before) : null
  if (expected && plan.id === 'confirm' && expected.members?.[0]?.build) expected.members[0].build.level = 51
  const change = record.preview?.changes, saved = plan.outcome === 'APPLIED'
  return {
    collected: record.collected === true,
    originalLevel: record.before?.members?.[0]?.build?.level === 50,
    previewOnlyRequestedChange: Array.isArray(change) && change.length === 1 && change[0].slot === 1
      && change[0].field === '等级' && change[0].before === '50' && change[0].after === '51',
    noWriteBeforeDecision: Boolean(record.before && record.beforeDecision) && teamDigest(record.before) === teamDigest(record.beforeDecision),
    boundDecision: typeof record.preview?.proposalId === 'string' && record.preview.proposalId.length > 0
      && record.decisionApprovalId === record.preview.proposalId && record.decisionStatus === plan.decisionStatus,
    expectedTerminal: record.status === plan.terminal && Array.isArray(record.terminalEvents)
      && record.terminalEvents.length === 1 && record.terminalEvents[0] === plan.event,
    expectedOutcome: record.outcome?.status === plan.outcome,
    exactStoredTeam: Boolean(expected && record.after) && teamDigest(record.after) === teamDigest(expected),
    actualRead: record.completedTools?.includes('readCurrentTeam') === true,
    repeatDecisionRejected: !saved || record.repeatDecisionStatus === 409 && record.repeatReadbackMatches === true,
    approvalReceipt: ['confirm', 'conflict'].includes(plan.id)
      ? typeof record.outcome?.message === 'string' && record.outcome.message.length > 0
        && record.historyAnswer === record.outcome.message && record.answer === record.outcome.message
      : record.historyAnswer === '' && record.answer === '',
    conflictWasExercised: plan.id !== 'conflict' || record.concurrentEditThenRestore === true,
    accountBoundary: plan.id !== 'confirm' || record.accountBoundaryChecked === true
  }
}
export function summarizeConfirmations(records) {
  if (!Array.isArray(records) || records.length !== confirmationPlan.length
    || confirmationPlan.some(plan => records.filter(r => r?.caseId === plan.id).length !== 1)) throw Error('Missing, duplicate or unplanned cases')
  const rows = confirmationPlan.map(plan => {
    const record = records.find(r => r.caseId === plan.id), checks = confirmationChecks(record)
    return { caseId: plan.id, pass: Object.values(checks).every(value => value === true), checks,
      failures: Object.entries(checks).filter(([, pass]) => !pass).map(([name]) => name) }
  })
  return { schemaVersion: 'team-confirmation-summary-v1', denominator: confirmationPlan.length,
    passed: rows.filter(row => row.pass).length, rows, semanticAcceptance: 'NOT_PERFORMED',
    scope: 'Four development workflow cases; automated approvals on dedicated accounts, not human strategy acceptance' }
}
