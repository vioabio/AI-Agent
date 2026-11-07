const count = value => Number.isSafeInteger(value) && value >= 0
const unknown = reason => ({ schemaVersion: 'token-estimate-comparison-v1', status: 'UNKNOWN', reason })
export function normalizeTokenComparison(value, context, tokens) {
  if (value == null) return null // Old history did not record this observation.
  if (value.schemaVersion !== 'token-estimate-comparison-v1') return unknown('INVALID_COMPARISON')
  const base = context?.baseEstimate?.inputTokens, budget = context?.lastEstimate?.inputTokens, native = tokens?.inputTokens
  const comparable = count(base) && count(budget) && count(native) && base > 0 && native > 0 && base <= budget
  if (value.status === 'UNKNOWN') return !comparable && ['ESTIMATE_NOT_RECORDED', 'INVALID_ESTIMATE', 'USAGE_UNAVAILABLE', 'UNSUPPORTED_COUNT', 'NONPOSITIVE_INPUT'].includes(value.reason)
    ? unknown(value.reason) : unknown('INVALID_COMPARISON')
  if (value.status !== 'PAIR_RECORDED' || !comparable || value.baseInputTokens !== base || value.budgetInputTokens !== budget
      || value.providerInputTokens !== native || value.baseErrorTokens !== base - native || value.budgetErrorTokens !== budget - native
      || value.budgetCovered !== (budget >= native)) return unknown('INVALID_COMPARISON')
  return { schemaVersion: value.schemaVersion, status: value.status, baseInputTokens: base, budgetInputTokens: budget,
    providerInputTokens: native, baseErrorTokens: base - native, budgetErrorTokens: budget - native, budgetCovered: budget >= native }
}
