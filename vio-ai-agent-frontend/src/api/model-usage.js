// Whitelisted metadata only. Missing usage is unknown, never an inferred zero or price.
const number = value => Number.isSafeInteger(value) && value >= 0 ? value : null
const modelName = value => typeof value === 'string' && /^[A-Za-z0-9_.:/-]{1,100}$/.test(value) ? value : null
const tokens = value => value && number(value.inputTokens) !== null && number(value.outputTokens) !== null
  && number(value.totalTokens) !== null && value.inputTokens + value.outputTokens === value.totalTokens
  ? { inputTokens: value.inputTokens, outputTokens: value.outputTokens, totalTokens: value.totalTokens } : null
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
    return { callNo: call.callNo, mode: call.mode, outcome: call.outcome,
      configuredModelId: modelName(call.configuredModelId), responseModelId: modelName(call.responseModelId),
      durationMs: call.durationMs, firstTextDeltaMs: number(call.firstTextDeltaMs),
      usageStatus: call.outcome === 'RETURNED' && call.usageStatus === 'PROVIDER_REPORTED' && tokens(call.tokens) ? 'PROVIDER_REPORTED' : 'UNKNOWN',
      tokens: call.outcome === 'RETURNED' && call.usageStatus === 'PROVIDER_REPORTED' ? tokens(call.tokens) : null }
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
    knownSubtotal: sums, modelDurationMs: number(value.modelDurationMs), calls, recordsTruncated: value.recordsTruncated === true, cost: { status: 'UNKNOWN' } }
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
  return { modelCalls: observed ? rows.reduce((sum, row) => sum + row.modelCallCount, 0) : null,
    complete, attempts: rows, totals: complete ? rows.reduce((sum, row) => ({
      inputTokens: sum.inputTokens + row.totals.inputTokens, outputTokens: sum.outputTokens + row.totals.outputTokens,
      totalTokens: sum.totalTokens + row.totals.totalTokens }), { inputTokens: 0, outputTokens: 0, totalTokens: 0 }) : null,
    callsWithUsage: rows.reduce((sum, row) => sum + row.callsWithUsage, 0),
    modelDurationMs: rows.every(row => row.modelDurationMs !== null) ? rows.reduce((sum, row) => sum + row.modelDurationMs, 0) : null }
}
