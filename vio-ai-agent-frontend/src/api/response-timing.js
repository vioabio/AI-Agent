const ms = value => Number.isSafeInteger(value) && value >= 0 && value <= 604800000
const durationKeys = ['queueWaitMs', 'authorizationMs', 'executionMs', 'observedMs']
export function normalizeToolTiming(value) {
  if (value?.schemaVersion !== 'tool-timing-v1') return null
  if (value.scope === 'BYPASS') return ['CACHED_RESULT', 'IDEMPOTENT_REPLAY'].includes(value.reason)
    ? { schemaVersion: value.schemaVersion, scope: value.scope, reason: value.reason } : null
  if (value.scope !== 'CALLBACK_ATTEMPTS' || !Array.isArray(value.attempts) || !value.attempts.length || value.attempts.length > 8) return null
  const attempts = value.attempts.map(row => {
    if (!row || ['accepted', 'callbackStarted', 'callbackCompleted'].some(key => typeof row[key] !== 'boolean')
        || durationKeys.some(key => row[key] !== null && !ms(row[key]))
        || row.callbackCompleted && !row.callbackStarted || row.callbackStarted && !row.accepted
        || row.callbackStarted && (row.executionMs === null || row.authorizationMs === null || row.queueWaitMs === null)
        || !row.callbackStarted && row.executionMs !== null || !row.accepted && row.queueWaitMs !== null) return null
    const sum = durationKeys.slice(0, 3).reduce((total, key) => total + (row[key] ?? 0), 0)
    if (row.observedMs === null ? sum > 0 : sum > row.observedMs + 3) return null
    return Object.fromEntries(['accepted', 'callbackStarted', 'callbackCompleted', ...durationKeys].map(key => [key, row[key]]))
  })
  if (attempts.some(row => !row) || value.complete !== attempts.every(row => row.callbackCompleted)) return null
  const sums = Object.fromEntries(durationKeys.map(key => [key, attempts.some(row => row[key] !== null)
    ? attempts.reduce((sum, row) => sum + (row[key] ?? 0), 0) : null]))
  if (durationKeys.some(key => sums[key] !== value[key])) return null
  return { schemaVersion: value.schemaVersion, scope: value.scope, complete: value.complete, attempts, ...sums }
}
export function normalizeResponseTiming(value) {
  if (value?.schemaVersion !== 'response-timing-v1' || value.scope !== 'EXECUTION_ATTEMPT' || value.origin !== 'TASK_REGISTERED'
      || value.deliveryMeasured !== false || !ms(value.dispatchWaitMs) || !ms(value.observedMs)
      || value.dispatchWaitMs > value.observedMs || value.firstAnswerReadyMs !== null && (!ms(value.firstAnswerReadyMs)
        || value.firstAnswerReadyMs < value.dispatchWaitMs || value.firstAnswerReadyMs > value.observedMs)
      || (value.firstAnswerReadyMs === null ? value.answerPath !== null : !['DELTA', 'BUFFERED'].includes(value.answerPath))) return null
  return { schemaVersion: value.schemaVersion, scope: value.scope, origin: value.origin, deliveryMeasured: false,
    dispatchWaitMs: value.dispatchWaitMs, observedMs: value.observedMs, firstAnswerReadyMs: value.firstAnswerReadyMs, answerPath: value.answerPath }
}
export function normalizeClientTiming(value) {
  return value?.schemaVersion === 'client-response-timing-v1' && value.source === 'BROWSER_OBSERVED'
    && ms(value.firstAnswerReceivedMs) ? { schemaVersion: value.schemaVersion, source: value.source, firstAnswerReceivedMs: value.firstAnswerReceivedMs } : null
}
export function formatToolTiming(value) {
  const timing = normalizeToolTiming(value)
  if (!timing) return ''
  if (timing.scope === 'BYPASS') return '本次未执行回调'
  const text = `排队 ${timing.queueWaitMs ?? '未知'} ms · 校验 ${timing.authorizationMs ?? '未知'} ms · 执行 ${timing.executionMs ?? '未观测'}${timing.executionMs === null ? '' : ' ms'}`
  return text + (timing.complete ? '' : '（观测截至停止，回调未执行或未确认结束）')
}
