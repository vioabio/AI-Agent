import { normalizeContextBudget } from '../../vio-ai-agent-frontend/src/api/model-usage.js'
export function checkContextAttempts(attempts) {
  if (!Array.isArray(attempts) || !attempts.length) return { pass: false, reason: 'MISSING_ATTEMPT', rows: [] }
  const rows = attempts.map(attempt => {
    const budget = normalizeContextBudget(attempt.contextBudget)
    const calls = (attempt.calls || []).map(call => ({ budget: normalizeContextBudget(call.contextBudget),
      reportedInputTokens: call.tokens?.inputTokens ?? null }))
    const pass = !!budget && budget.requestsChecked >= attempt.modelCallCount
      && (budget.status !== 'BLOCKED' || budget.blockReason !== null)
      && calls.length === attempt.modelCallCount && calls.every(call => call.budget?.status === 'CHECKED')
    return { pass, status: budget?.status ?? 'UNKNOWN', requestsChecked: budget?.requestsChecked ?? null,
      modelCalls: attempt.modelCallCount, omittedHistoryTurns: budget?.omittedHistoryTurns ?? null,
      availableHistoryTurns: budget?.availableHistoryTurns ?? null, blockReason: budget?.blockReason ?? null,
      calls: calls.map(call => ({ estimatedInputTokens: call.budget?.lastEstimate?.inputTokens ?? null,
        inputLimit: call.budget?.inputLimit ?? null, outputReserve: call.budget?.outputReserve ?? null,
        providerInputTokens: call.reportedInputTokens, estimator: call.budget?.estimator ?? 'UNKNOWN', calibration: 'PROXY_UNCALIBRATED' })) }
  })
  return { pass: rows.every(row => row.pass), rows, cost: 'UNKNOWN', scope: 'Request preflight estimates, separate from provider usage and billing' }
}
