// Whitelisted metadata only. Missing usage is unknown, never an inferred zero or price.
import { normalizeBillingUsage, normalizeCallCost, summarizeModelCosts, verifyCostSummary, normalizePricingSelection } from './model-cost.js'
import { normalizeResponseTiming, normalizeToolTiming } from './response-timing.js'
import { normalizeTokenComparison } from './token-estimate.js'
const number = value => Number.isSafeInteger(value) && value >= 0 ? value : null
const modelName = value => typeof value === 'string' && /^[A-Za-z0-9_.:/-]{1,100}$/.test(value) ? value : null
const tokens = value => value && number(value.inputTokens) !== null && number(value.outputTokens) !== null
  && number(value.totalTokens) !== null && value.inputTokens + value.outputTokens === value.totalTokens
  ? { inputTokens: value.inputTokens, outputTokens: value.outputTokens, totalTokens: value.totalTokens } : null
export function normalizeContextBudget(value) {
  if (value?.schemaVersion !== 'context-budget-v1' || value.estimator !== 'cl100k-base-proxy-v1'
      || value.calibration !== 'PROXY_UNCALIBRATED' || !['CHECKED', 'BLOCKED'].includes(value.status)) return null
  const keys = ['contextTokens', 'inputLimit', 'outputReserve', 'historyLimit', 'availableHistoryTurns', 'omittedHistoryTurns', 'requestsChecked', 'safetyPercent']
  if (keys.some(key => number(value[key]) === null) || value.contextTokens !== value.inputLimit + value.outputReserve
      || value.historyLimit > value.inputLimit || value.omittedHistoryTurns > value.availableHistoryTurns
      || value.safetyPercent < 100 || value.safetyPercent > 200) return null
  const reasons = ['HISTORY_SHAPE', 'INPUT_LIMIT', 'OUTPUT_LIMIT', 'INVALID_OUTPUT_LIMIT', 'UNSUPPORTED_OPTIONS',
    'UNRESOLVED_TOOL_SCHEMA', 'AMBIGUOUS_TOOL_SCHEMA', 'UNSUPPORTED_OR_OVERSIZED_INPUT', 'UNSUPPORTED_OPTIONS_OR_TOOL_SCHEMA']
  const estimateKeys = ['system', 'history', 'toolDefinitions', 'toolResults', 'currentMessages', 'inputTokens']
  const estimate = value.lastEstimate && estimateKeys.every(key => number(value.lastEstimate[key]) !== null)
    && estimateKeys.slice(0, -1).reduce((sum, key) => sum + value.lastEstimate[key], 0) === value.lastEstimate.inputTokens
    ? Object.fromEntries(estimateKeys.map(key => [key, value.lastEstimate[key]])) : null
  if (value.requestsChecked < 1 || value.outputReserve < 1 || (value.status === 'CHECKED'
      && (!estimate || estimate.inputTokens > value.inputLimit || estimate.history > value.historyLimit))) return null
  const base = value.baseEstimate
  const baseEstimate = value.estimateSchemaVersion === 'context-estimate-pair-v1' && estimate && base
    && estimateKeys.every(key => number(base[key]) !== null && base[key] <= estimate[key])
    && estimateKeys.slice(0, -1).reduce((sum, key) => sum + base[key], 0) === base.inputTokens
    ? Object.fromEntries(estimateKeys.map(key => [key, base[key]])) : null
  return { schemaVersion: value.schemaVersion, estimator: value.estimator, calibration: value.calibration, status: value.status,
    ...Object.fromEntries(keys.map(key => [key, value[key]])), lastEstimate: estimate,
    baseEstimate, estimateSchemaVersion: baseEstimate ? 'context-estimate-pair-v1' : null,
    blockReason: reasons.includes(value.blockReason) ? value.blockReason : null }
}
export function normalizeTaskBudget(value) {
  if (value?.schemaVersion !== 'task-budget-v1' || value.scope !== 'RUN'
      || value.accounting !== 'MAX_RESERVATION_OR_PROVIDER_V1' || typeof value.budgetId !== 'string'
      || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value.budgetId)
      || !['ACTIVE', 'BLOCKED'].includes(value.status) || typeof value.closed !== 'boolean'
      || typeof value.stateKnown !== 'boolean' || number(value.maxModelCalls) === null
      || value.maxModelCalls < 1 || value.maxModelCalls > 64 || number(value.tokenLimit) === null
      || value.tokenLimit < 1 || value.tokenLimit > 100000000) return null
  const counters = ['modelCalls', 'reservedTokens', 'chargedTokens', 'remainingModelCalls', 'remainingTokens']
  const reasons = ['CALL_LIMIT', 'TOKEN_LIMIT', 'STATE_UNAVAILABLE', 'ESTIMATE_UNAVAILABLE']
  if (value.status === 'ACTIVE' ? value.blockReason !== null : !reasons.includes(value.blockReason)) return null
  if (!value.stateKnown) {
    if (value.blockReason !== 'STATE_UNAVAILABLE' || counters.some(key => value[key] !== null)) return null
  } else if (counters.some(key => number(value[key]) === null) || value.blockReason === 'STATE_UNAVAILABLE'
      || value.modelCalls > 64 || value.chargedTokens < value.reservedTokens
      || value.remainingModelCalls !== Math.max(0, value.maxModelCalls - value.modelCalls)
      || value.remainingTokens !== Math.max(0, value.tokenLimit - value.chargedTokens)
      || (value.status === 'ACTIVE' && (value.modelCalls > value.maxModelCalls || value.chargedTokens > value.tokenLimit))) return null
  return { schemaVersion: value.schemaVersion, scope: value.scope, accounting: value.accounting, budgetId: value.budgetId,
    maxModelCalls: value.maxModelCalls, tokenLimit: value.tokenLimit, stateKnown: value.stateKnown,
    status: value.status, closed: value.closed, blockReason: value.blockReason,
    ...Object.fromEntries(counters.map(key => [key, value[key]])) }
}
export function normalizeModelUsage(value) {
  if (value?.schemaVersion !== 'model-usage-v1' || value.scope !== 'EXECUTION_ATTEMPT'
      || typeof value.attemptId !== 'string' || !/^[a-f0-9-]{36}$/.test(value.attemptId)
      || !['INSTRUMENTED', 'NOT_INSTRUMENTED'].includes(value.coverage) || number(value.modelCallCount) === null
      || number(value.callsWithUsage) === null || value.callsWithUsage > value.modelCallCount
      || !Array.isArray(value.calls) || value.calls.length > 64 || value.calls.length > value.modelCallCount) return null
  const ids = new Set()
  const calls = value.calls.map(call => {
    if (!call || typeof call !== 'object' || number(call.callNo) === null || call.callNo < 1 || call.callNo > value.modelCallCount || ids.has(call.callNo)
        || !['CALL', 'STREAM'].includes(call.mode) || !['RETURNED', 'FAILED', 'CANCELLED', 'IN_FLIGHT'].includes(call.outcome)
        || number(call.durationMs) === null) return null
    ids.add(call.callNo)
    const nativeTokens = call.outcome === 'RETURNED' && call.usageStatus === 'PROVIDER_REPORTED' ? tokens(call.tokens) : null
    const billing = normalizeBillingUsage(call.billingUsage, nativeTokens)
    const requestedModel = modelName(call.requestedModelId)
    const context = normalizeContextBudget(call.contextBudget)
    return { callNo: call.callNo, mode: call.mode, outcome: call.outcome,
      configuredModelId: modelName(call.configuredModelId), responseModelId: modelName(call.responseModelId),
      requestedModelId: requestedModel, pricing: normalizePricingSelection(call.pricing),
      durationMs: call.durationMs, firstTextDeltaMs: number(call.firstTextDeltaMs),
      usageStatus: call.outcome === 'RETURNED' && call.usageStatus === 'PROVIDER_REPORTED' && tokens(call.tokens) ? 'PROVIDER_REPORTED' : 'UNKNOWN',
      tokens: nativeTokens, contextBudget: context, tokenEstimateComparison: normalizeTokenComparison(call.tokenEstimateComparison, context, nativeTokens),
      billingUsage: billing, cost: normalizeCallCost(call.cost, nativeTokens, billing, requestedModel) }
  })
  if (calls.some(call => !call)) return null
  const known = calls.filter(call => call.tokens), sums = known.reduce((sum, call) => ({
    inputTokens: sum.inputTokens + call.tokens.inputTokens, outputTokens: sum.outputTokens + call.tokens.outputTokens,
    totalTokens: sum.totalTokens + call.tokens.totalTokens }), { inputTokens: 0, outputTokens: 0, totalTokens: 0 })
  const total = tokens(value.totals)
  const complete = value.coverage === 'INSTRUMENTED' && value.usageStatus === 'COMPLETE' && !value.recordsTruncated
    && calls.length === value.modelCallCount && known.length === value.modelCallCount && total
    && Object.keys(sums).every(key => sums[key] === total[key])
  return { schemaVersion: value.schemaVersion, attemptId: value.attemptId, scope: value.scope,
    coverage: value.coverage, modelCallCount: value.modelCallCount, callsWithUsage: known.length,
    usageStatus: complete ? 'COMPLETE' : known.length ? 'PARTIAL' : 'UNKNOWN', totals: complete ? total : null,
    knownSubtotal: sums, modelDurationMs: number(value.modelDurationMs), calls, recordsTruncated: value.recordsTruncated === true,
    contextBudget: normalizeContextBudget(value.contextBudget), taskBudget: normalizeTaskBudget(value.taskBudget),
    responseTiming: normalizeResponseTiming(value.responseTiming),
    toolTimings: (Array.isArray(value.toolTimings) ? value.toolTimings.slice(0, 64) : []).flatMap(row => {
      const timing = normalizeToolTiming(row?.timing)
      return timing && typeof row.toolName === 'string' && /^[A-Za-z0-9_.:-]{1,100}$/.test(row.toolName)
        && typeof row.eventId === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(row.eventId)
        ? [{ eventId: row.eventId, toolName: row.toolName, timing }] : []
    }), toolTimingsTruncated: value.toolTimingsTruncated === true,
    cost: verifyCostSummary(value.cost, summarizeModelCosts(calls.map(call => call.cost), value.modelCallCount,
      value.coverage === 'INSTRUMENTED', value.recordsTruncated === true)) }
}
export function upsertModelUsage(attempts, payload) {
  const next = normalizeModelUsage(payload)
  if (!next) return attempts || []
  return [...(attempts || []).filter(value => value.attemptId !== next.attemptId), next]
}
export function summarizeModelUsage(attempts) {
  const values = new Map()
  for (const candidate of attempts || []) {
    const value = normalizeModelUsage(candidate)
    if (value) values.set(value.attemptId, value)
  }
  const rows = [...values.values()]
  if (!rows.length) return null
  const observed = rows.every(row => row.coverage === 'INSTRUMENTED')
  const complete = observed && rows.every(row => row.usageStatus === 'COMPLETE')
  const budgets = rows.map(row => row.taskBudget)
  // Each attempt contains a cumulative Run snapshot. Select the latest consistent snapshot, never add them.
  const taskBudget = budgets.length && budgets.every(budget => budget && budget.budgetId === budgets[0].budgetId)
    && budgets.every((budget, i) => !i || !budget.stateKnown || !budgets[i - 1].stateKnown
      || budget.modelCalls >= budgets[i - 1].modelCalls && budget.chargedTokens >= budgets[i - 1].chargedTokens)
    ? budgets.at(-1) : null
  return { modelCalls: observed ? rows.reduce((sum, row) => sum + row.modelCallCount, 0) : null,
    cost: rows.some(row => row.cost.reason === 'INVALID_COST_METADATA') ? { schemaVersion: 'model-cost-v1', kind: 'REFERENCE_ESTIMATE_NOT_INVOICE', status: 'UNKNOWN',
      currency: null, amount: null, reason: 'INVALID_COST_METADATA' } : summarizeModelCosts(rows.flatMap(row => row.calls.map(call => call.cost)),
      rows.reduce((sum, row) => sum + row.modelCallCount, 0), observed, rows.some(row => row.recordsTruncated)),
    complete, attempts: rows, taskBudget, totals: complete ? rows.reduce((sum, row) => ({
      inputTokens: sum.inputTokens + row.totals.inputTokens, outputTokens: sum.outputTokens + row.totals.outputTokens,
      totalTokens: sum.totalTokens + row.totals.totalTokens }), { inputTokens: 0, outputTokens: 0, totalTokens: 0 }) : null,
    callsWithUsage: rows.reduce((sum, row) => sum + row.callsWithUsage, 0),
    modelDurationMs: rows.every(row => row.modelDurationMs !== null) ? rows.reduce((sum, row) => sum + row.modelDurationMs, 0) : null }
}
