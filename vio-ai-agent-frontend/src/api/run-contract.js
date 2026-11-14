/**
 * v1.0 Agent Run 前端契约。
 *
 * 页面只消费 RunViewModel，不再自行拼接“思考中/已完成”等字符串。
 * 该文件不依赖 Vue，便于单元测试和断线恢复时重放事件。
 */

import { parsePokemonBlock } from './answer-block.js'
import { parseDamageBlock } from './damage-answer-block.js'
import { parseTeamBlock } from './team-answer-block.js'
import { parseTrainingBlock } from './training-answer-block.js'
import { upsertModelUsage } from './model-usage.js'

export const RUN_STATUS = Object.freeze({
  CREATING: 'creating',
  RUNNING: 'running',
  WAITING_APPROVAL: 'waiting_approval',
  RECONNECTING: 'reconnecting',
  COMPLETED: 'completed',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
  TIMED_OUT: 'timed_out'
})

export const TERMINAL_STATUSES = new Set([
  RUN_STATUS.COMPLETED,
  RUN_STATUS.FAILED,
  RUN_STATUS.CANCELLED,
  RUN_STATUS.TIMED_OUT
])

// AgentEvent.eventId 在 OpenAPI 中是 int64/minimum=1。浏览器收到 JSON
// 数字后可能已经丢失大于 2^53 的精度，因此线上游标统一按十进制字符串
// 处理；非数字、负数、小数、超出 int64 的值一律视为无效并 fail-closed。
const MAX_INT64 = 9223372036854775807n
const RUN_CONTEXT_KEYS = Object.freeze([
  'gameReleaseId', 'dlcId', 'language', 'battleFormatId', 'battleFormat',
  'rulesetVersionId', 'dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId',
  'contractVersion', 'lineageStatus'
])

function contextFields(source) {
  if (!source || typeof source !== 'object') return {}
  return Object.fromEntries(RUN_CONTEXT_KEYS
    .filter(key => source[key] !== undefined && source[key] !== null && source[key] !== '')
    .map(key => [key, source[key]]))
}

// 与 contracts/openapi-v1.1.json / AgentEventType 的 wire name 对齐。未知
// 事件不能按自由文本猜测字段；它们只会留下脱敏的协议告警并推进游标，
// 这样后续合法事件仍可继续重放，同时不会把未登记 payload 渲染给用户。
export const REGISTERED_RUN_EVENT_TYPES = new Set([
  'run.created',
  'run.started',
  'context.confirmed',
  'context.card',
  'route.selected',
  'retrieval.started',
  'retrieval.finished',
  'retrieval.citation.card',
  'fact.card',
  'calculation.card',
  'team.matrix',
  'activity.card',
  'tool.started',
  'tool.finished',
  'approval.required',
  'approval.resolved',
  'answer.delta',
  'answer.completed',
  'citation',
  'limitation.added',
  'feedback.card',
  'error.card',
  'usage',
  'message.user',
  'message.assistant',
  'checkpoint.saved',
  'run.completed',
  'run.failed',
  'run.cancelled',
  'run.timed_out'
])

export function createRunViewModel(seed = {}) {
  const seedCards = seed.cards && typeof seed.cards === 'object' ? seed.cards : {}
  const cardList = (value) => Array.isArray(value) ? [...value] : (value ? [value] : [])
  const cursorValue = (value) => normalizeCursorId(value, { allowZero: true })
  // RunView exposes frozen context fields at the top level. 复制到同一个
  // context 投影，避免只收到快照（尚未重放 context.card）时页面丢失版本
  // 血缘，反馈提交也不会因为 context 尚未初始化而漏传 release IDs。
  const topLevelContext = contextFields(seed)
  const context = seed.context && typeof seed.context === 'object'
    ? { ...topLevelContext, ...contextFields(seed.context) }
    : Object.keys(topLevelContext).length ? topLevelContext : null
  return {
    runId: seed.runId || null,
    answerId: seed.answerId || null,
    conversationId: seed.conversationId || null,
    traceId: seed.traceId || null,
    status: normalizeRunStatus(seed.status),
    lastEventId: cursorValue(seed.lastEventId),
    // 刷新恢复时记录 Storage 提供的游标，便于诊断；实际 ViewModel 仍以
    // 服务端重放出的最新游标为准。
    resumeCursor: cursorValue(seed.resumeCursor),
    answer: seed.answer || '',
    citations: Array.isArray(seed.citations) ? [...seed.citations] : [],
    limitations: Array.isArray(seed.limitations) ? [...seed.limitations] : [],
    context,
    lineageStatus: seed.lineageStatus || context?.lineageStatus || null,
    lineage: seed.lineage && typeof seed.lineage === 'object' ? { ...seed.lineage } : null,
    // N4/N5 结构化卡片只保存服务端已登记事件的 payload；自由文本仍走 answer。
    // 统一数组形态便于重放、去重和在页面中表达“没有该卡片”的空态。
    cards: {
      context: cardList(seedCards.context),
      facts: cardList(seedCards.facts),
      calculations: cardList(seedCards.calculations),
      teams: cardList(seedCards.teams),
      activities: cardList(seedCards.activities),
      citations: cardList(seedCards.citations),
      feedback: cardList(seedCards.feedback),
      errors: cardList(seedCards.errors)
    },
    toolTimeline: Array.isArray(seed.toolTimeline) ? [...seed.toolTimeline] : [],
    modelUsageAttempts: (Array.isArray(seed.modelUsageAttempts) ? seed.modelUsageAttempts : []).reduce(upsertModelUsage, []),
    events: Array.isArray(seed.events) ? [...seed.events] : [],
    error: seed.error || null,
    approval: seed.approval || null,
    startedAt: seed.startedAt || null,
    finishedAt: seed.finishedAt || null,
    reconnectAttempts: Number(seed.reconnectAttempts || 0),
    // 传输层错误与业务 Run 终态分离；例如事件保留期结束时要求重新打开会话。
    streamError: seed.streamError || null,
    ignoredEvents: Array.isArray(seed.ignoredEvents) ? [...seed.ignoredEvents] : []
  }
}

/** 将后端 RunView 的大写枚举归一化为前端状态机值。 */
export function normalizeRunStatus(value) {
  const normalized = String(value ?? '').trim().toLowerCase()
  if (!normalized) return RUN_STATUS.CREATING
  if (normalized === 'created') return RUN_STATUS.CREATING
  if (Object.values(RUN_STATUS).includes(normalized)) return normalized
  // An explicit but unknown server status is a protocol error. Treat it as a
  // closed failure instead of reconnecting forever or re-enabling mutations.
  return RUN_STATUS.FAILED
}

/**
 * 将后端事件统一归一化。兼容 JSON data、纯文本 data 和不同命名风格。
 */
export function normalizeRunEvent(raw, fallbackId = null) {
  let payload = raw
  if (typeof raw === 'string') {
    try {
      payload = JSON.parse(raw)
    } catch {
      payload = { type: 'answer.delta', data: raw }
    }
  }
  if (!payload || typeof payload !== 'object') {
    payload = { type: 'answer.delta', data: String(payload ?? '') }
  }
  const rawType = payload.type || payload.eventType || payload.event || 'message'
  const type = typeof rawType === 'string'
    ? normalizeRunEventType(rawType)
    : rawType
  const data = payload.data !== undefined ? payload.data : payload.payload
  const rawId = payload.id ?? payload.eventId ?? fallbackId
  return {
    ...payload,
    type,
    // Invalid IDs remain null so reduceRunEvent drops the event instead of
    // persisting an attacker-controlled cursor such as "abc" or "NaN".
    id: normalizeCursorId(rawId),
    data
  }
}

/** 兼容 Java enum 风格名称，同时保留 wire name 中有意存在的 timed_out。 */
function normalizeRunEventType(value) {
  const normalized = value.trim().toLowerCase()
  // Keep the canonical wire name (`run.timed_out`) intact.  It is the only
  // registered event name that intentionally contains an underscore after a
  // dot; blindly replacing all underscores would turn it into the
  // unregistered `run.timed.out` and drop the terminal event during replay.
  if (normalized === 'run_timed_out'
    || normalized === 'run.timed_out'
    || normalized === 'run.timed.out') return 'run.timed_out'
  return normalized.replaceAll('_', '.')
}

/**
 * 纯 reducer：事件重放得到同一视图模型，重复 eventId 不会重复渲染。
 */
export function reduceRunEvent(model, rawEvent) {
  const event = normalizeRunEvent(rawEvent)
  // 现代 Run 事件契约要求 eventId；没有游标的增量无法在重连时可靠去重，
  // 因此宁可丢弃并等待服务端的带 ID 重放，也不把匿名文本拼入答案。
  if (!event.id) return model
  if (event.id && event.id === model.lastEventId) return model
  // 事件游标单调递增；重连/代理重放旧事件时直接丢弃，避免答案重复拼接。
  const cursorOrder = compareCursorIds(event.id, model.lastEventId)
  if (cursorOrder !== null && cursorOrder <= 0) return model

  // 服务端以唯一终态约束禁止终态后的新事件；客户端回放也必须 fail-closed，
  // 终态后的任意更高游标（包括未来/未知类型）都不能重新打开 Run。
  if (TERMINAL_STATUSES.has(model.status)) return model

  if (!REGISTERED_RUN_EVENT_TYPES.has(event.type)) {
    const warning = { id: event.id, type: String(event.type || 'unknown') }
    const previous = Array.isArray(model.ignoredEvents) ? model.ignoredEvents : []
    return {
      ...model,
      lastEventId: event.id,
      ignoredEvents: [...previous, warning].slice(-50),
      streamError: model.streamError || 'UNREGISTERED_EVENT_TYPE'
    }
  }

  const next = {
    ...model,
    lastEventId: event.id || model.lastEventId,
    traceId: event.traceId || model.traceId,
    events: [...model.events, event]
  }
  const data = event.data && typeof event.data === 'object' ? event.data : {}
  const text = typeof event.data === 'string'
    ? event.data
    : (data.text || data.delta || event.text || '')
  // Terminal events use a few intentionally distinct fields across the
  // backend paths (approval denial, restart recovery, model exceptions). Keep
  // the user-facing error useful without rendering arbitrary payload fields.
  const eventErrorMessage = data.message || data.reason || data.recoveryReason
    || data.errorMessage || event.message || text
  const timeoutMessage = eventErrorMessage === 'deadline_exceeded' || !eventErrorMessage
    ? (next.answer
        ? '本次问答已超时，以上为尚未完成的回答。可以缩小问题范围后重试。'
        : '本次问答已超时，工具完成后仍可能需要模型生成回答。请缩小问题范围后重试。')
    : eventErrorMessage
  const cardPayload = data.card && typeof data.card === 'object' ? data.card : data
  const mergedCards = data.cards && typeof data.cards === 'object'
    ? Object.fromEntries(Object.entries(next.cards).map(([key, current]) => [
      key,
      Array.isArray(data.cards[key]) ? [...data.cards[key]] : current
    ]))
    : next.cards

  if (data.modelUsage) next.modelUsageAttempts = upsertModelUsage(next.modelUsageAttempts, data.modelUsage)
  switch (event.type) {
    case 'usage':
      return { ...next, modelUsageAttempts: upsertModelUsage(next.modelUsageAttempts, data) }
    case 'run.created':
      return { ...next, runId: data.runId || next.runId, conversationId: data.conversationId || next.conversationId, status: RUN_STATUS.CREATING }
    case 'run.started':
    case 'run.running':
      return { ...next, status: RUN_STATUS.RUNNING, startedAt: data.startedAt || next.startedAt || new Date().toISOString() }
    case 'context.confirmed':
      return { ...next, context: { ...next.context, ...data }, lineageStatus: data.lineageStatus || next.lineageStatus }
    case 'context.card':
      return {
        ...next,
        context: { ...next.context, ...cardPayload },
        lineageStatus: cardPayload.lineageStatus || next.lineageStatus,
        cards: { ...next.cards, context: [...next.cards.context, cardPayload] }
      }
    case 'approval.required':
      return { ...next, status: RUN_STATUS.WAITING_APPROVAL, approval: { ...data, approvalId: data.approvalId || event.approvalId } }
    case 'route.selected':
    case 'retrieval.started':
    case 'retrieval.completed':
    case 'retrieval.finished':
      return { ...next, status: RUN_STATUS.RUNNING }
    case 'fact.card': {
      if(cardPayload.answerBlock) {
        const block=parsePokemonBlock(cardPayload.answerBlock)
        if(!block || (next.context?.gameReleaseId && block.gameReleaseId!==next.context.gameReleaseId) || (next.context?.dataReleaseId && block.dataReleaseId!==next.context.dataReleaseId))return next
        return {...next,cards:{...next.cards,facts:[...next.cards.facts.filter(c=>c.answerBlock?.blockId!==block.blockId),{...cardPayload,answerBlock:block}]}}
      }
      return { ...next, status: RUN_STATUS.RUNNING, cards: { ...next.cards, facts: [...next.cards.facts, cardPayload] } }
    }
    case 'calculation.card': {
      if(cardPayload.answerBlock) {
        const block=parseDamageBlock(cardPayload.answerBlock)
        if(!block || (next.context?.gameReleaseId && block.gameReleaseId!==next.context.gameReleaseId) || (next.context?.dataReleaseId && block.dataReleaseId!==next.context.dataReleaseId))return next
        return {...next,cards:{...next.cards,calculations:[...next.cards.calculations.filter(c=>c.answerBlock?.blockId!==block.blockId),{...cardPayload,answerBlock:block}]}}
      }
      return { ...next, status: RUN_STATUS.RUNNING, cards: { ...next.cards, calculations: [...next.cards.calculations, cardPayload] } }
    }
    case 'team.matrix':
      if(cardPayload.answerBlock) {
        const block=cardPayload.answerBlock.kind==='trainingPlan'?parseTrainingBlock(cardPayload.answerBlock):parseTeamBlock(cardPayload.answerBlock)
        if(!block || (next.context?.gameReleaseId && block.gameReleaseId!==next.context.gameReleaseId) || (next.context?.dataReleaseId && block.dataReleaseId!==next.context.dataReleaseId) || (block.kind==='trainingPlan' && next.context?.rulesetVersionId && block.rulesetVersionId!==next.context.rulesetVersionId))return next
        return {...next,cards:{...next.cards,teams:[...next.cards.teams.filter(c=>c.answerBlock?.blockId!==block.blockId),{...cardPayload,answerBlock:block}]}}
      }
      return { ...next, status: RUN_STATUS.RUNNING, cards: { ...next.cards, teams: [...next.cards.teams, cardPayload] } }
    case 'activity.card':
      return { ...next, status: RUN_STATUS.RUNNING, cards: { ...next.cards, activities: [...next.cards.activities, cardPayload] } }
    case 'retrieval.citation.card':
      return { ...next, status: RUN_STATUS.RUNNING, cards: { ...next.cards, citations: [...next.cards.citations, cardPayload] } }
    case 'tool.started':
      return {
        ...next,
        status: RUN_STATUS.RUNNING,
        toolTimeline: [...next.toolTimeline, { ...data, toolName: data.toolName || event.toolName, status: 'running', eventId: event.id }]
      }
    case 'tool.completed':
    case 'tool.finished':
    case 'tool.failed':
    case 'tool.rejected': {
      const name = data.toolName || event.toolName
      // A call emits both failed/completed and finished. Correlate by the
      // governance call ID so its final duration is retained, without updating
      // another concurrent or later invocation of the same tool.
      const matches = item => item.toolName === name
        && (data.governanceEventId
          ? item.governanceEventId === data.governanceEventId
          : item.status === 'running')
      const status = ['FAILED', 'AUDIT_FAILED', 'UNKNOWN', 'REJECTED', 'CANCELLED', 'TIMED_OUT']
          .includes(String(data.status || '').toUpperCase()) ? 'failed'
          : (event.type === 'tool.completed' || event.type === 'tool.finished') ? 'completed' : 'failed'
      const timeline = next.toolTimeline.map(item => matches(item) ? { ...item, ...data, status } : item)
      // Cached/rejected calls can finish without entering a callback. Keep those
      // attempts visible and correlate repeated completion events by their call ID.
      if (data.governanceEventId && !next.toolTimeline.some(matches)) timeline.push({ ...data, toolName: name, status, eventId: event.id })
      return { ...next, toolTimeline: timeline }
    }
    case 'answer.delta':
    case 'message':
      return { ...next, status: RUN_STATUS.RUNNING, answer: next.answer + text }
    case 'answer.completed':
      return { ...next, status: RUN_STATUS.RUNNING, answer: data.answer ?? (text || next.answer), answerId: data.answerId || next.answerId, lineageStatus: data.lineageStatus || next.lineageStatus, lineage: data.lineage || next.lineage }
    case 'citation.added':
    case 'citation':
      return { ...next, citations: [...next.citations, data.citation || data].filter(Boolean) }
    case 'limitation.added':
      return { ...next, limitations: [...next.limitations, data.text || text].filter(Boolean) }
    case 'feedback.card':
      return { ...next, cards: { ...next.cards, feedback: [...next.cards.feedback, cardPayload] } }
    case 'error.card':
      return { ...next, status: RUN_STATUS.RUNNING, cards: { ...next.cards, errors: [...next.cards.errors, cardPayload] } }
    case 'approval.resolved':
      // 后端拒绝审批时会先发 approval.resolved，再发唯一的 run.failed。
      // 结论事件本身不是 Run 终态；提前标记 FAILED 会让终态门禁丢掉
      // 后续 run.failed 及其错误原因，破坏回放和审计顺序。
      return { ...next, status: RUN_STATUS.RUNNING, approval: null }
    case 'run.completed':
      return { ...next, status: RUN_STATUS.COMPLETED, answer: data.answer ?? next.answer, answerId: data.answerId || next.answerId, lineageStatus: data.lineageStatus || next.lineageStatus, lineage: data.lineage || next.lineage, context: { ...next.context, ...contextFields(data.context), ...contextFields(data) }, cards: mergedCards, citations: data.citations || next.citations, limitations: data.limitations || next.limitations, finishedAt: data.finishedAt || new Date().toISOString() }
    case 'run.cancelled':
      return { ...next, status: RUN_STATUS.CANCELLED, finishedAt: data.finishedAt || new Date().toISOString() }
    case 'run.timed_out':
      return { ...next, status: RUN_STATUS.TIMED_OUT, error: timeoutMessage, finishedAt: data.finishedAt || new Date().toISOString() }
    case 'run.failed':
    case 'error':
      return { ...next, status: RUN_STATUS.FAILED, error: data.errorType === 'RUN_CONTEXT_BUDGET_EXCEEDED'
        ? '本次请求超过上下文或回答长度预算。请缩小问题范围，或开启新对话并重申关键限制；已显示的回答可能不完整。'
        : data.errorType === 'RUN_TASK_BUDGET_EXCEEDED'
          ? '本次任务已达到模型调用或 Token 额度上限。请缩小任务范围后重新发起；已显示的回答可能不完整。'
          : data.errorType === 'RUN_TASK_BUDGET_UNAVAILABLE'
            ? '无法核实本次任务的剩余模型额度，请重新发起任务。'
        : eventErrorMessage || '任务执行失败', finishedAt: data.finishedAt || new Date().toISOString() }
    default:
      return next
  }
}

/** Known diagnostic codes only: never render arbitrary provider error bodies. */
export function toolFailureLabel(item) {
  if (item?.status !== 'failed') return ''
  const error = String(item.error || item.reason || '')
  const labels = [
    ['SEARCH_NOT_CONFIGURED', '未配置独立的 SearchAPI 搜索凭证'],
    ['SEARCH_PROVIDER_HTTP_401', '搜索凭证无效或已失效，请检查 SearchAPI Key'],
    ['SEARCH_PROVIDER_HTTP_403', '搜索服务拒绝访问，请检查凭证权限'],
    ['SEARCH_PROVIDER_HTTP_429', '搜索请求受限或额度不足，请检查 SearchAPI 用量'],
    ['SEARCH_TIMEOUT', '搜索服务响应超时，请检查网络后重试'],
    ['TOOL_EXECUTION_TIMEOUT', '工具执行超时（包含排队等待）'],
    ['SEARCH_INTERRUPTED', '搜索已中止'],
    ['SEARCH_INVALID_RESPONSE', '搜索服务返回无效数据'],
    ['SEARCH_PROVIDER_REJECTED', '搜索服务未返回有效搜索结果'],
    ['SEARCH_UNAVAILABLE', '搜索服务暂不可用，请检查网络连接']
  ]
  return labels.find(([code]) => error.includes(code))?.[1]
    || (item.toolName === 'searchWeb'
      ? '网页搜索失败，可继续使用已读取的图鉴资料；请检查搜索服务配置或后端日志'
      : '工具执行失败，请查看后端日志中的对应 Run / trace')
}

export function statusLabel(status) {
  return {
    [RUN_STATUS.CREATING]: '创建中',
    [RUN_STATUS.RUNNING]: '执行中',
    [RUN_STATUS.WAITING_APPROVAL]: '等待审批',
    [RUN_STATUS.RECONNECTING]: '重连中',
    [RUN_STATUS.COMPLETED]: '已完成',
    [RUN_STATUS.FAILED]: '失败',
    [RUN_STATUS.CANCELLED]: '已取消',
    [RUN_STATUS.TIMED_OUT]: '已超时'
  }[status] || '未知状态'
}

/**
 * 比较服务端游标而不把 int64 转成可能失真的 JS Number。后端当前通常从
 * 1 开始递增；协议外的游标直接视为无效，不猜测字典序。
 * 返回 null 表示无法建立可靠顺序。
 */
export function compareCursorIds(left, right) {
  const leftCursor = normalizeCursorId(left, { allowZero: true })
  const rightCursor = normalizeCursorId(right, { allowZero: true })
  if (leftCursor === null || rightCursor === null) return null
  try {
    const leftBig = BigInt(leftCursor)
    const rightBig = BigInt(rightCursor)
    return leftBig < rightBig ? -1 : leftBig > rightBig ? 1 : 0
  } catch {
    return null
  }
}

/**
 * Normalize an event cursor without converting an int64 to an imprecise
 * Number. `allowZero` is used for the initial afterEventId cursor and for
 * compatibility EventPage cursors; AgentEvent IDs themselves are positive.
 */
export function normalizeCursorId(value, { allowZero = false } = {}) {
  if (value === null || value === undefined || value === '') return null
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return null
  const text = String(value).trim()
  // Leading zeroes are accepted and canonicalized (e.g. "0007" -> "7");
  // they are still decimal-only and bounded by the int64 range.
  const pattern = /^\d+$/
  if (!pattern.test(text)) return null
  try {
    const cursor = BigInt(text)
    if (cursor < (allowZero ? 0n : 1n) || cursor > MAX_INT64) return null
    return cursor.toString()
  } catch {
    return null
  }
}
