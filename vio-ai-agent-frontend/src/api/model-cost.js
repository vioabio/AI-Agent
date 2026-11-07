// Fixed 14-decimal integer arithmetic: rates have at most 8 decimals and are per million tokens.
const SCALE = 100000000000000n
const count = value => Number.isSafeInteger(value) && value >= 0
const money = value => typeof value === 'string' && /^(?:0|[1-9]\d{0,15})(?:\.\d{1,14})?$/.test(value)
  ? BigInt(value.split('.')[0]) * SCALE + BigInt((value.split('.')[1] || '').padEnd(14, '0')) : null
const decimal = value => {
  const fraction = (value % SCALE).toString().padStart(14, '0').replace(/0+$/, '')
  return `${value / SCALE}${fraction ? `.${fraction}` : ''}`
}
const unknown = reason => ({ schemaVersion: 'model-cost-v1', status: 'UNKNOWN', kind: 'REFERENCE_ESTIMATE_NOT_INVOICE',
  reason, currency: null, amount: null })
const reasons = new Set(['PRICING_DISABLED', 'NO_MATCHING_PRICE', 'MODEL_CALL_INCOMPLETE', 'USAGE_UNKNOWN',
  'BILLING_DETAILS_MISSING', 'INVALID_OR_UNSUPPORTED_BILLING_DETAILS', 'INPUT_TIER_UNSUPPORTED', 'NO_MODEL_CALLS',
  'NOT_INSTRUMENTED', 'MIXED_CURRENCIES', 'RECORDS_TRUNCATED', 'INCOMPLETE_CALL_COSTS', 'INVALID_COST_METADATA', 'LEGACY_NO_PRICE'])
export function normalizeBillingUsage(value, tokens) {
  if (value?.schemaVersion !== 'billing-usage-v1' || !['REPORTED', 'UNKNOWN', 'INVALID', 'UNSUPPORTED'].includes(value.status)
      || value.reasoningIncludedInOutput !== true) return null
  const keys = ['cachedInputTokens', 'uncachedInputTokens', 'reasoningOutputTokens']
  if (keys.some(key => value[key] !== null && !count(value[key]))) return null
  if ((value.cachedInputTokens === null) !== (value.uncachedInputTokens === null)) return null
  if (!tokens && keys.some(key => value[key] !== null)) return null
  if (tokens && (value.cachedInputTokens !== null && value.cachedInputTokens + value.uncachedInputTokens !== tokens.inputTokens
      || value.reasoningOutputTokens !== null && value.reasoningOutputTokens > tokens.outputTokens)) return null
  return { schemaVersion: value.schemaVersion, status: value.status, reasoningIncludedInOutput: true,
    ...Object.fromEntries(keys.map(key => [key, value[key]])) }
}
const id = value => typeof value === 'string' && /^[A-Za-z0-9_.:/-]{1,100}$/.test(value)
const tag = value => typeof value === 'string' && /^[A-Za-z0-9_.:-]{1,100}$/.test(value)
export function normalizePricingSelection(value) {
  if (!value || typeof value.selectedAt !== 'string' || value.selectedAt.length > 40
      || !/Z$/.test(value.selectedAt) || !Number.isFinite(Date.parse(value.selectedAt))) return null
  const disabled = value.version === null && value.sha256 === null && value.scope === null && value.matchedCardId === null
  if (!disabled && (!tag(value.version) || !tag(value.scope) || !/^[a-f0-9]{64}$/.test(value.sha256)
      || value.matchedCardId !== null && !tag(value.matchedCardId))) return null
  return { version: value.version, sha256: value.sha256, scope: value.scope, selectedAt: value.selectedAt, matchedCardId: value.matchedCardId }
}
function normalizePrice(value) {
  if (!value || !tag(value.version) || !tag(value.cardId) || !tag(value.scope) || !id(value.requestedModelId)
      || !['deepseek', 'dashscope'].includes(value.provider) || !['OFFICIAL_LIST', 'LOCAL_CONTRACT', 'TEST_FIXTURE'].includes(value.sourceType)
      || !/^[a-f0-9]{64}$/.test(value.sha256) || value.modelBasis !== 'REQUESTED_MODEL_NOT_ATTESTED'
      || value.unitTokens !== 1000000 || !count(value.maxInputTokens) || value.maxInputTokens < 1 || value.maxInputTokens > 1000000
      || !['ALL_INPUT', 'SPLIT_CACHE'].includes(value.inputAccounting)) return null
  const rates = ['inputPerMillion', 'outputPerMillion', ...(value.inputAccounting === 'SPLIT_CACHE' ? ['cachedInputPerMillion'] : [])]
  if (rates.some(key => money(value[key]) === null || money(value[key]) > 1000000n * SCALE || (value[key].split('.')[1] || '').length > 8)
      || value.inputAccounting === 'ALL_INPUT' && value.cachedInputPerMillion !== null) return null
  if (typeof value.sourceUrl !== 'string' || value.sourceUrl.length > 2048) return null
  try {
    const url = new URL(value.sourceUrl)
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) return null
  } catch { return null }
  const dates = ['selectedAt', 'validFrom', 'validUntil']
  if (dates.some(key => typeof value[key] !== 'string' || value[key].length > 40 || !/Z$/.test(value[key]) || !Number.isFinite(Date.parse(value[key])))
      || Date.parse(value.selectedAt) < Date.parse(value.validFrom) || Date.parse(value.selectedAt) >= Date.parse(value.validUntil)) return null
  const keys = ['version', 'cardId', 'scope', 'provider', 'requestedModelId', 'sourceType', 'sourceUrl', 'sha256', 'modelBasis',
    'unitTokens', 'maxInputTokens', 'inputAccounting', ...dates, 'inputPerMillion', 'cachedInputPerMillion', 'outputPerMillion']
  return Object.fromEntries(keys.map(key => [key, value[key]]))
}
export function normalizeCallCost(value, tokens, billing, requestedModel) {
  if (value?.schemaVersion !== 'model-cost-v1' || value.kind !== 'REFERENCE_ESTIMATE_NOT_INVOICE') return unknown('LEGACY_NO_PRICE')
  if (value.status === 'UNKNOWN') return unknown(reasons.has(value.reason) ? value.reason : 'INVALID_COST_METADATA')
  const price = normalizePrice(value.price)
  if (value.status !== 'ESTIMATED' || !['USD', 'CNY'].includes(value.currency) || !price || !tokens || !billing
      || ['INVALID', 'UNSUPPORTED'].includes(billing.status) || price.requestedModelId !== requestedModel
      || tokens.inputTokens > price.maxInputTokens) return unknown('INVALID_COST_METADATA')
  const split = price.inputAccounting === 'SPLIT_CACHE'
  if (split && billing.cachedInputTokens === null) return unknown('INVALID_COST_METADATA')
  const input = BigInt(split ? billing.uncachedInputTokens : tokens.inputTokens) * money(price.inputPerMillion) / 1000000n
  const cached = split ? BigInt(billing.cachedInputTokens) * money(price.cachedInputPerMillion) / 1000000n : 0n
  const output = BigInt(tokens.outputTokens) * money(price.outputPerMillion) / 1000000n
  if (money(value.amount) !== input + cached + output || money(value.components?.input) !== input
      || money(value.components?.cachedInput) !== cached || money(value.components?.output) !== output) return unknown('INVALID_COST_METADATA')
  return { schemaVersion: value.schemaVersion, kind: value.kind, status: 'ESTIMATED', currency: value.currency,
    amount: decimal(input + cached + output), price, components: { input: decimal(input), cachedInput: decimal(cached), output: decimal(output) } }
}
export function summarizeModelCosts(costs, totalCalls, instrumented, truncated = false) {
  if (!instrumented) return unknown('NOT_INSTRUMENTED')
  if (!totalCalls) return unknown('NO_MODEL_CALLS')
  const priced = costs.filter(cost => cost.status === 'ESTIMATED'), currencies = new Set(priced.map(cost => cost.currency))
  if (currencies.size > 1) return unknown('MIXED_CURRENCIES')
  if (!priced.length) return costs[0] || unknown('USAGE_UNKNOWN')
  const subtotal = decimal(priced.reduce((sum, cost) => sum + money(cost.amount), 0n))
  const complete = !truncated && costs.length === totalCalls && priced.length === totalCalls
  return { schemaVersion: 'model-cost-v1', kind: 'REFERENCE_ESTIMATE_NOT_INVOICE', status: complete ? 'ESTIMATED' : 'PARTIAL',
    currency: priced[0].currency, amount: complete ? subtotal : null, knownSubtotal: subtotal,
    pricedCalls: priced.length, totalCalls, reason: complete ? null : truncated ? 'RECORDS_TRUNCATED' : 'INCOMPLETE_CALL_COSTS' }
}
export function verifyCostSummary(raw, calculated) {
  if (raw?.schemaVersion !== 'model-cost-v1') return unknown('LEGACY_NO_PRICE')
  if (raw.status === 'UNKNOWN' && raw.reason === 'LEGACY_NO_PRICE') return unknown('LEGACY_NO_PRICE')
  if (raw.kind !== calculated.kind || raw.status !== calculated.status || raw.currency !== calculated.currency
      || raw.amount !== calculated.amount || raw.reason !== calculated.reason) return unknown('INVALID_COST_METADATA')
  if (calculated.status !== 'UNKNOWN' && (raw.knownSubtotal !== calculated.knownSubtotal
      || raw.pricedCalls !== calculated.pricedCalls || raw.totalCalls !== calculated.totalCalls)) return unknown('INVALID_COST_METADATA')
  return calculated
}
export function formatReferenceMoney(currency, amount) {
  const value = money(amount)
  if (!['USD', 'CNY'].includes(currency) || value === null) return '未知'
  if (value > 0n && value < 100000000n) return `< ${currency} 0.000001`
  const rounded = (value + 50000000n) / 100000000n * 100000000n
  return `${currency} ${decimal(rounded)}`
}
export function costReasonLabel(reason) {
  return ({ PRICING_DISABLED: '尚未启用价格目录', NO_MATCHING_PRICE: '未找到适用于当前模型、范围和时间的价格',
    BILLING_DETAILS_MISSING: '供应商或 SDK 未提供所需缓存明细', INVALID_OR_UNSUPPORTED_BILLING_DETAILS: '计费明细不完整或包含未支持类别',
    MODEL_CALL_INCOMPLETE: '包含未完整返回的模型调用', USAGE_UNKNOWN: '供应商用量未知', INPUT_TIER_UNSUPPORTED: '输入长度超出已配置价格档位',
    MIXED_CURRENCIES: '包含不同币种，未合并换算', RECORDS_TRUNCATED: '调用明细被截断', INCOMPLETE_CALL_COSTS: '仅部分调用可估算',
    NO_MODEL_CALLS: '本次未调用模型', NOT_INSTRUMENTED: '该执行路径未完整观测', LEGACY_NO_PRICE: '历史记录未保存价格依据',
    INVALID_COST_METADATA: '费用记录未通过一致性校验' })[reason] || '费用依据不足'
}
