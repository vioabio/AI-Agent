import test from 'node:test'
import assert from 'node:assert/strict'
import {
  RUN_STATUS,
  createRunViewModel,
  reduceRunEvent,
  normalizeRunEvent,
  normalizeRunStatus,
  normalizeCursorId
} from '../src/api/run-contract.js'
import { toolFailureLabel } from '../src/api/run-contract.js'

function replay(events, seed = {}) {
  return events.reduce((model, event) => reduceRunEvent(model, event), createRunViewModel(seed))
}

test('上下文或输出预算不足保留部分正文并显示可读提示', () => {
  const model = replay([{ id: '1', type: 'answer.delta', data: '已有部分内容' },
    { id: '2', type: 'run.failed', data: { errorType: 'RUN_CONTEXT_BUDGET_EXCEEDED' } }])
  assert.equal(model.status, RUN_STATUS.FAILED)
  assert.equal(model.answer, '已有部分内容')
  assert.match(model.error, /上下文或回答长度预算/)
  assert.match(model.error, /重申关键限制/)
})

test('缓存命中无started也有一条完成记录，按调用ID去重并保留真实拒绝', () => {
  const model = replay([
    { id: '1', type: 'tool.finished', data: { toolName: 'searchPokemonGuides', governanceEventId: 'cached', status: 'SUCCEEDED', cached: true, durationMs: 2 } },
    { id: '2', type: 'tool.finished', data: { toolName: 'searchPokemonGuides', governanceEventId: 'cached', status: 'SUCCEEDED', cached: true, durationMs: 2 } },
    { id: '3', type: 'tool.finished', data: { toolName: 'searchPokemonGuides', governanceEventId: 'denied', status: 'REJECTED' } },
    { id: '4', type: 'tool.finished', data: { toolName: 'writeFile', governanceEventId: 'effect', status: 'DEDUPLICATED', cached: false } }
  ])
  assert.equal(model.toolTimeline.length, 3)
  assert.equal(model.toolTimeline[0].status, 'completed')
  assert.equal(model.toolTimeline[0].cached, true)
  assert.equal(model.toolTimeline[0].durationMs, 2)
  assert.equal(model.toolTimeline[1].status, 'failed')
  assert.equal(model.toolTimeline[2].status, 'completed')
  assert.equal(model.toolTimeline[2].cached, false)
})

test('同名工具按治理调用ID关联，失败后结算保留原因和耗时', () => {
  const model = replay([
    { id: '1', type: 'tool.started', data: { toolName: 'searchWeb', governanceEventId: 'call-a' } },
    { id: '2', type: 'tool.started', data: { toolName: 'searchWeb', governanceEventId: 'call-b' } },
    { id: '3', type: 'tool.finished', data: { toolName: 'searchWeb', governanceEventId: 'call-a', status: 'FAILED', error: 'SEARCH_PROVIDER_HTTP_401' } },
    { id: '4', type: 'tool.finished', data: { toolName: 'searchWeb', governanceEventId: 'call-a', status: 'FAILED', durationMs: 164 } },
    { id: '5', type: 'tool.finished', data: { toolName: 'searchWeb', governanceEventId: 'call-b', status: 'SUCCEEDED', durationMs: 704 } }
  ])
  assert.equal(model.toolTimeline[0].status, 'failed')
  assert.equal(model.toolTimeline[0].durationMs, 164)
  assert.equal(model.toolTimeline[0].error, 'SEARCH_PROVIDER_HTTP_401')
  assert.match(toolFailureLabel(model.toolTimeline[0]), /SearchAPI Key/)
  assert.equal(model.toolTimeline[1].status, 'completed')
  assert.equal(model.toolTimeline[1].durationMs, 704)
  assert.equal(toolFailureLabel(model.toolTimeline[1]), '')
})

test('搜索失败提示区分额度和超时，未知异常不泄露原始内容', () => {
  const failed = error => ({ toolName: 'searchWeb', status: 'failed', error })
  assert.match(toolFailureLabel(failed('SEARCH_PROVIDER_HTTP_429')), /额度/)
  assert.match(toolFailureLabel(failed('SEARCH_TIMEOUT')), /网络/)
  assert.match(toolFailureLabel(failed('TOOL_EXECUTION_TIMEOUT')), /排队/)
  assert.doesNotMatch(toolFailureLabel(failed('private-provider-body secret=private')), /private|secret/)
})

test('问答总超时显示可读原因，保留部分回答且不标为完成', () => {
  for (const answer of ['', '已经生成的部分回答']) {
    const model = replay([
      { id: '1', type: 'answer.delta', data: answer },
      { id: '2', type: 'run.timed_out', data: { reason: 'deadline_exceeded' } }
    ])
    assert.equal(model.status, RUN_STATUS.TIMED_OUT)
    assert.equal(model.answer, answer)
    assert.match(model.error, /本次问答已超时/)
    assert.doesNotMatch(model.error, /deadline_exceeded/)
    if (answer) assert.match(model.error, /尚未完成/)
  }
  const specific = replay([
    { id: '1', type: 'run.timed_out', data: { message: '队列等待超时' } }
  ])
  assert.equal(specific.error, '队列等待超时')
})

test('tool.finished必须按业务结果区分成功与失败', () => {
  for (const status of ['FAILED', 'AUDIT_FAILED', 'UNKNOWN', 'REJECTED', 'CANCELLED', 'TIMED_OUT']) {
    const model = replay([
      { id: '1', type: 'tool.started', data: { toolName: 'searchWeb' } },
      { id: '2', type: 'tool.finished', data: { toolName: 'searchWeb', status } }
    ])
    assert.equal(model.toolTimeline[0].status, 'failed', status)
  }
  const model = replay([
    { id: '1', type: 'tool.started', data: { toolName: 'getTypeChart' } },
    { id: '2', type: 'tool.finished', data: { toolName: 'getTypeChart', status: 'SUCCEEDED' } }
  ])
  assert.equal(model.toolTimeline[0].status, 'completed')
})

test('Run 事件按 eventId 去重并保持游标单调', () => {
  const first = replay([
    { id: '1', type: 'run.started', data: { startedAt: '2026-09-02T00:00:00Z' } },
    { id: '2', type: 'answer.delta', data: '甲' },
    { id: '2', type: 'answer.delta', data: '甲' },
    { id: '3', type: 'answer.delta', data: '乙' }
  ], { runId: 'run-1' })

  assert.equal(first.status, RUN_STATUS.RUNNING)
  assert.equal(first.answer, '甲乙')
  assert.equal(first.lastEventId, '3')
  assert.equal(first.events.length, 3)
})

test('大于 JS 安全整数的 int64 游标按字符串精确比较', () => {
  const model = replay([
    { id: '9007199254740992', type: 'answer.delta', data: '甲' },
    { id: '9007199254740993', type: 'answer.delta', data: '乙' },
    { id: '9007199254740992', type: 'answer.delta', data: '不应重复' }
  ], { runId: 'run-large-cursor' })
  assert.equal(model.answer, '甲乙')
  assert.equal(model.lastEventId, '9007199254740993')
  assert.equal(model.events.length, 2)
})

test('协议外或超范围 eventId fail-closed，不污染恢复游标', () => {
  const model = replay([
    { id: 0, type: 'answer.delta', data: 'zero' },
    { id: '-1', type: 'answer.delta', data: 'negative' },
    { id: 'abc', type: 'answer.delta', data: 'text-id' },
    { id: 'NaN', type: 'answer.delta', data: 'nan-id' },
    { id: '9223372036854775808', type: 'answer.delta', data: 'overflow' },
    { id: '1.5', type: 'answer.delta', data: 'fraction' },
    { id: '1', type: 'answer.delta', data: 'valid' }
  ], { runId: 'run-invalid-cursor', lastEventId: 'abc' })

  assert.equal(model.answer, 'valid')
  assert.equal(model.events.length, 1)
  assert.equal(model.lastEventId, '1')
  assert.equal(normalizeCursorId('0001'), '1')
  assert.equal(normalizeCursorId('0', { allowZero: true }), '0')
  assert.equal(normalizeCursorId('9223372036854775807'), '9223372036854775807')
})

test('终态事件后的旧游标事件不会重复渲染', () => {
  const model = replay([
    { id: 1, type: 'answer.delta', data: '临时答案' },
    { id: 2, type: 'run.completed', data: { answer: '最终答案', citations: [{ id: 'c1' }] } },
    { id: 2, type: 'answer.delta', data: '不应追加' }
  ], { runId: 'run-2' })

  assert.equal(model.status, RUN_STATUS.COMPLETED)
  assert.equal(model.answer, '最终答案')
  assert.equal(model.lastEventId, '2')
  assert.deepEqual(model.citations, [{ id: 'c1' }])
})

test('终态后的更高游标事件也被丢弃，不能把 Run 重新打开', () => {
  const model = replay([
    { id: 1, type: 'run.completed', data: { answer: '最终答案' } },
    { id: 2, type: 'answer.delta', data: '不应追加' },
    { id: 3, type: 'tool.started', data: { toolName: 'unexpected-tool' } }
  ], { runId: 'run-terminal' })

  assert.equal(model.status, RUN_STATUS.COMPLETED)
  assert.equal(model.answer, '最终答案')
  assert.equal(model.lastEventId, '1')
  assert.equal(model.events.length, 1)
  assert.equal(model.toolTimeline.length, 0)
})

test('缺少 eventId 的现代事件被丢弃，避免断线重连重复拼接匿名增量', () => {
  const model = replay([
    { type: 'answer.delta', data: '匿名增量' },
    { id: 1, type: 'answer.delta', data: '可追踪增量' }
  ], { runId: 'run-anonymous' })
  assert.equal(model.answer, '可追踪增量')
  assert.equal(model.events.length, 1)
  assert.equal(model.lastEventId, '1')
})

test('未登记事件不进入答案或卡片，只记录脱敏协议告警并推进游标', () => {
  const model = replay([
    { id: 1, type: 'future.experimental', data: { secret: 'must-not-render' } },
    { id: 2, type: 'answer.delta', data: '可继续消费' }
  ], { runId: 'run-unknown-event' })
  assert.equal(model.answer, '可继续消费')
  assert.equal(model.events.length, 1)
  assert.equal(model.lastEventId, '2')
  assert.deepEqual(model.ignoredEvents, [{ id: '1', type: 'future.experimental' }])
  assert.equal(model.streamError, 'UNREGISTERED_EVENT_TYPE')
  assert.doesNotMatch(JSON.stringify(model), /must-not-render/)
})

test('多种事件命名风格被归一化', () => {
  const event = normalizeRunEvent({ eventType: 'retrieval_finished', eventId: 8, payload: { count: 2 } })
  assert.equal(event.type, 'retrieval.finished')
  assert.equal(event.id, '8')
  assert.deepEqual(event.data, { count: 2 })
})

test('后端大写 Run 状态和事件类型归一化为前端状态机值', () => {
  assert.equal(normalizeRunStatus('CREATED'), RUN_STATUS.CREATING)
  assert.equal(normalizeRunStatus('WAITING_APPROVAL'), RUN_STATUS.WAITING_APPROVAL)
  assert.equal(normalizeRunStatus('PAUSED_BY_UNKNOWN_SERVER'), RUN_STATUS.FAILED)
  assert.equal(normalizeRunStatus(undefined), RUN_STATUS.CREATING)
  assert.equal(normalizeRunEvent({ type: 'RUN_COMPLETED', id: 9 }).type, 'run.completed')
  assert.equal(normalizeRunEvent({ type: 'RUN_TIMED_OUT', id: 10 }).type, 'run.timed_out')
})

test('canonical run.timed_out 事件不会被下划线归一化误判为未知事件', () => {
  const normalized = normalizeRunEvent({
    type: 'run.timed_out',
    id: 11,
    data: { reason: 'deadline_exceeded' }
  })
  assert.equal(normalized.type, 'run.timed_out')

  const model = replay([normalized], { runId: 'run-timeout' })
  assert.equal(model.status, RUN_STATUS.TIMED_OUT)
  assert.match(model.error, /本次问答已超时/)
  assert.equal(model.events[0].data.reason, 'deadline_exceeded')
  assert.equal(model.lastEventId, '11')
  assert.equal(model.ignoredEvents.length, 0)
})

test('RunView 顶层版本字段会初始化 context，快照恢复不会丢失血缘', () => {
  const model = createRunViewModel({
    runId: 'run-snapshot',
    status: 'COMPLETED',
    gameReleaseId: 'pokemon-scarlet-violet',
    battleFormatId: 'casual-singles',
    rulesetVersionId: 'casual-v1',
    dataReleaseId: 'data-v1',
    knowledgeReleaseId: 'knowledge-v1',
    indexReleaseId: 'index-v1',
    lineageStatus: 'COMPLETE'
  })
  assert.equal(model.context.gameReleaseId, 'pokemon-scarlet-violet')
  assert.equal(model.context.battleFormatId, 'casual-singles')
  assert.equal(model.context.dataReleaseId, 'data-v1')
  assert.equal(model.lineageStatus, 'COMPLETE')
})

test('上下文确认事件保留版本与赛制，支持回放展示', () => {
  const model = replay([
    { id: 1, type: 'context.confirmed', data: {
      gameReleaseId: 'scarlet-violet', battleFormat: 'casual-singles', language: 'zh-CN'
    } }
  ])
  assert.deepEqual(model.context, {
    gameReleaseId: 'scarlet-violet', battleFormat: 'casual-singles', language: 'zh-CN'
  })
})

test('完成事件只允许用完整血缘答案开启反馈', () => {
  const model = replay([
    { id: 1, type: 'run.completed', data: {
      answerId: 'answer-run-4',
      lineageStatus: 'COMPLETE',
      dataReleaseId: 'data-4',
      knowledgeReleaseId: 'knowledge-4',
      indexReleaseId: 'index-4',
      answer: '答案'
    } }
  ], { runId: 'run-4' })
  assert.equal(model.answerId, 'answer-run-4')
  assert.equal(model.lineageStatus, 'COMPLETE')
  assert.equal(model.context.dataReleaseId, 'data-4')
  assert.equal(model.context.knowledgeReleaseId, 'knowledge-4')
  assert.equal(model.context.indexReleaseId, 'index-4')
})

test('完成事件单独携带完整 lineage 时也恢复上下文字段', () => {
  const model = replay([
    { id: 1, type: 'run.completed', data: {
      answerId: 'answer-lineage-only',
      lineageStatus: 'COMPLETE',
      gameReleaseId: 'pokemon-scarlet-violet',
      dlcId: 'treasure-of-zero',
      language: 'zh-CN',
      battleFormatId: 'casual-singles-v1',
      rulesetVersionId: 'casual-v1',
      dataReleaseId: 'data-v1',
      knowledgeReleaseId: 'knowledge-v1',
      indexReleaseId: 'index-v1',
      contractVersion: 'v1.1',
      answer: '答案'
    } }
  ], { runId: 'run-lineage-only' })
  assert.equal(model.context.gameReleaseId, 'pokemon-scarlet-violet')
  assert.equal(model.context.dlcId, 'treasure-of-zero')
  assert.equal(model.context.language, 'zh-CN')
  assert.equal(model.context.battleFormatId, 'casual-singles-v1')
  assert.equal(model.context.rulesetVersionId, 'casual-v1')
  assert.equal(model.context.contractVersion, 'v1.1')
})

test('审批拒绝先解析结论再收敛 run.failed，不吞掉终态事件', () => {
  const model = replay([
    { id: 1, type: 'approval.required', data: { approvalId: 'approval-1', toolName: 'write' } },
    { id: 2, type: 'approval.resolved', data: { approved: false, reason: '用户拒绝' } },
    { id: 3, type: 'run.failed', data: { errorType: 'APPROVAL_DENIED', message: '用户拒绝工具调用' } }
  ], { runId: 'run-approval-denied' })
  assert.equal(model.status, RUN_STATUS.FAILED)
  assert.equal(model.lastEventId, '3')
  assert.equal(model.events.length, 3)
  assert.equal(model.approval, null)
  assert.equal(model.error, '用户拒绝工具调用')
})

test('终态失败事件兼容 reason/recoveryReason/errorMessage，保留可操作错误原因', () => {
  const denied = replay([
    { id: 1, type: 'run.failed', data: { errorType: 'APPROVAL_DENIED', reason: '需要重新审批' } }
  ], { runId: 'run-denied-reason' })
  assert.equal(denied.error, '需要重新审批')

  const recovered = replay([
    { id: 1, type: 'run.failed', data: { errorType: 'SERVICE_RESTARTED', recoveryReason: '租约无法恢复' } }
  ], { runId: 'run-recovery-reason' })
  assert.equal(recovered.error, '租约无法恢复')
})

test('结构化领域事件进入对应卡片集合，不依赖自由文本猜字段', () => {
  const model = replay([
    { id: 1, type: 'context.card', data: { gameReleaseId: 'pokemon-scarlet-violet', dataReleaseId: 'data-v1' } },
    { id: 2, type: 'fact.card', data: { factId: 'fact-1', name: '喷火龙', evidenceText: '证据' } },
    { id: 3, type: 'calculation.card', data: { calculationId: 'calc-1', result: '免疫' } },
    { id: 4, type: 'team.matrix', data: { teamId: 'team-1', members: [] } },
    { id: 5, type: 'activity.card', data: { noticeId: 'activity-1', freshness: 'FRESH' } },
    { id: 6, type: 'retrieval.citation.card', data: { sourceUrl: 'https://example.com/source', evidenceText: '摘录' } },
    { id: 7, type: 'error.card', data: { code: 'NO_EVIDENCE', message: '没有证据' } }
  ])

  assert.equal(model.context.gameReleaseId, 'pokemon-scarlet-violet')
  assert.equal(model.cards.facts[0].factId, 'fact-1')
  assert.equal(model.cards.calculations[0].calculationId, 'calc-1')
  assert.equal(model.cards.teams[0].teamId, 'team-1')
  assert.equal(model.cards.activities[0].freshness, 'FRESH')
  assert.equal(model.cards.citations[0].sourceUrl, 'https://example.com/source')
  assert.equal(model.cards.errors[0].code, 'NO_EVIDENCE')
})
test('task credit failures keep partial text and show stable messages without provider bodies', () => {
  for (const code of ['RUN_TASK_BUDGET_EXCEEDED', 'RUN_TASK_BUDGET_UNAVAILABLE']) {
    let view = createRunViewModel()
    view = reduceRunEvent(view, { id: '1', type: 'answer.delta', data: { text: '部分回答' } })
    view = reduceRunEvent(view, { id: '2', type: 'run.failed', data: { errorType: code, message: 'private provider body' } })
    assert.equal(view.status, RUN_STATUS.FAILED)
    assert.equal(view.answer, '部分回答')
    assert.match(view.error, /额度/)
    assert.doesNotMatch(view.error, /private/)
  }
})
