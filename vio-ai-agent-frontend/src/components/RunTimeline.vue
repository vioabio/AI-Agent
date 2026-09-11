<template>
  <section v-if="run" class="run-panel" aria-live="polite">
    <div class="run-summary">
      <span class="run-status" :class="run.status">{{ statusLabel(run.status) }}</span>
      <code v-if="run.runId">Run {{ run.runId }}</code>
      <code v-if="run.traceId" class="trace-id">trace {{ run.traceId }}</code>
      <span class="transport-badge" title="当前后端以事件快照提供可恢复订阅，不代表长连接实时推送">事件快照 · 游标可重连</span>
      <span v-if="run.streamError === 'UNREGISTERED_EVENT_TYPE'" class="protocol-warning" role="status">
        检测到未登记事件，已忽略不展示
      </span>
      <button
        v-if="canCancel"
        class="cancel-btn"
        type="button"
        @click="$emit('cancel', run.runId)"
      >
        停止任务
      </button>
      <button
        v-if="run.status === 'reconnecting'"
        class="retry-btn"
        type="button"
        @click="$emit('reconnect', run.runId)"
      >
        继续连接
      </button>
    </div>

    <div v-if="canGiveFeedback" class="feedback-card" aria-label="回答反馈">
      <span>这次回答对你有帮助吗？</span>
      <button type="button" class="feedback-btn" @click="$emit('feedback', { answerId: run.answerId, feedbackType: 'HELPFUL' })">
        👍 有帮助
      </button>
      <button type="button" class="feedback-btn feedback-negative" @click="$emit('feedback', { answerId: run.answerId, feedbackType: 'NOT_HELPFUL' })">
        👎 需要改进
      </button>
    </div>
    <p v-else-if="run.feedbackSubmitted" class="feedback-confirm">感谢反馈，已记录到纠错闭环。</p>
    <p v-else-if="run.status === 'completed' && run.lineageStatus !== 'COMPLETE'" class="feedback-unavailable">该答案缺少完整版本血缘，暂不能提交可追溯反馈。</p>

    <section v-if="feedbackHistoryState !== 'idle'" class="feedback-history" aria-label="当前答案反馈历史">
      <div class="feedback-history-heading">
        <strong>反馈历史</strong>
        <span class="feedback-history-status">{{ feedbackHistoryLabel(feedbackHistoryState) }}</span>
      </div>
      <div v-if="feedbackHistoryState === 'loading'" class="feedback-history-state" role="status">正在读取当前答案的反馈状态…</div>
      <div v-else-if="feedbackHistoryState === 'permission'" class="feedback-history-state warning" role="alert">当前身份无法读取反馈历史。</div>
      <div v-else-if="feedbackHistoryState === 'error'" class="feedback-history-state warning" role="alert">
        {{ feedbackHistoryError || '反馈历史暂不可用。' }}
        <button type="button" class="feedback-retry" @click="reloadFeedbackHistory">重试</button>
      </div>
      <div v-else-if="feedbackHistoryState === 'empty'" class="feedback-history-state">当前答案还没有反馈记录。</div>
      <ul v-else class="feedback-history-list">
        <li v-for="(item, index) in feedbackHistory" :key="item.feedbackId || `${item.answerId || props.run?.answerId}-${index}`" class="feedback-history-item">
          <span class="feedback-type">{{ feedbackTypeLabel(item.feedbackType) }}</span>
          <span class="feedback-status">{{ feedbackStatusLabel(item.status) }}</span>
          <small>{{ formatDate(item.updatedAt || item.createdAt) }}</small>
        </li>
      </ul>
    </section>

    <div v-if="run.context" class="context-card" aria-label="本次运行上下文">
      <span>版本：{{ run.context.gameReleaseId || '未指定' }}</span>
      <span>赛制：{{ run.context.battleFormat || run.context.battleFormatId || '未指定' }}</span>
      <span>语言：{{ run.context.language || '未指定' }}</span>
      <span v-if="run.context.rulesetVersionId">规则：{{ run.context.rulesetVersionId }}</span>
      <span v-if="run.context.dataReleaseId">数据：{{ run.context.dataReleaseId }}</span>
      <span v-if="run.context.knowledgeReleaseId">知识：{{ run.context.knowledgeReleaseId }}</span>
      <span v-if="run.context.indexReleaseId">索引：{{ run.context.indexReleaseId }}</span>
    </div>

    <section v-if="hasStructuredCards" class="structured-cards" aria-label="结构化领域卡片">
      <h4>领域卡片</h4>
      <article v-for="(card, index) in run.cards?.facts || []" :key="`fact-${card.factId || index}`" class="structured-card fact-structured-card">
        <div class="structured-card-heading"><strong>图鉴事实 · {{ card.name || card.speciesNameZh || '未命名' }}</strong><span>{{ card.gameReleaseId || '未指定版本' }}</span></div>
        <p v-if="card.types">属性：{{ Array.isArray(card.types) ? card.types.join(' / ') : card.types }}</p>
        <p v-if="card.baseStats">种族值：{{ formatValue(card.baseStats) }}</p>
        <p v-if="card.evidenceText" class="evidence">证据：{{ card.evidenceText }}</p>
        <a v-if="citationUrl(card)" :href="citationUrl(card)" target="_blank" rel="noopener noreferrer">查看来源 ↗</a>
        <small>data release：{{ card.dataReleaseId || '未返回' }}</small>
      </article>
      <article v-for="(card, index) in run.cards?.calculations || []" :key="`calculation-${card.calculationId || index}`" class="structured-card">
        <div class="structured-card-heading"><strong>确定性计算 · {{ card.calculationType || 'calculation' }}</strong><span>{{ card.gameReleaseId || '未指定版本' }}</span></div>
        <p class="structured-result">结果：{{ formatValue(card.result) }}</p>
        <small>规则：{{ card.rulesetVersionId || '未返回' }} · data release：{{ card.dataReleaseId || '未返回' }}</small>
      </article>
      <article v-for="(card, index) in run.cards?.teams || []" :key="`team-${card.teamId || index}`" class="structured-card">
        <div class="structured-card-heading"><strong>队伍矩阵 · {{ card.teamId || '未命名队伍' }}</strong><span>{{ card.dataReleaseId || '未返回版本' }}</span></div>
        <p>成员：{{ formatValue(card.members) }}</p>
        <small>规则：{{ card.rulesetVersionId || '未返回' }}</small>
      </article>
      <article v-for="(card, index) in run.cards?.activities || []" :key="`activity-${card.noticeId || index}`" class="structured-card">
        <div class="structured-card-heading"><strong>活动资讯 · {{ card.title || card.noticeId || '未命名' }}</strong><span class="freshness">{{ card.freshness || '未返回 freshness' }}</span></div>
        <p v-if="card.effectiveFrom">生效：{{ effectiveWindow(card) }}</p>
        <a v-if="citationUrl(card)" :href="citationUrl(card)" target="_blank" rel="noopener noreferrer">查看来源 ↗</a>
      </article>
      <article v-for="(card, index) in run.cards?.citations || []" :key="`retrieval-${card.chunkId || index}`" class="structured-card citation-structured-card">
        <div class="structured-card-heading"><strong>检索证据</strong><span>{{ card.documentVersion || '未返回文档版本' }}</span></div>
        <p v-if="card.evidenceText">{{ card.evidenceText }}</p>
        <a v-if="citationUrl(card)" :href="citationUrl(card)" target="_blank" rel="noopener noreferrer">{{ card.sourceUrl }}</a>
        <small>chunk：{{ card.chunkId || '未返回' }} · index：{{ card.indexReleaseId || '未返回' }}</small>
      </article>
      <article v-for="(card, index) in run.cards?.errors || []" :key="`error-${card.code || index}`" class="structured-card error-structured-card" role="alert">
        <strong>{{ card.code || 'ERROR' }}</strong><span>{{ card.message || '结构化事件报告错误' }}</span>
      </article>
      <article v-for="(card, index) in run.cards?.feedback || []" :key="`feedback-${card.feedbackId || index}`" class="structured-card feedback-structured-card">
        <div class="structured-card-heading"><strong>反馈记录 · {{ card.feedbackType || card.feedbackId || 'feedback' }}</strong><span>{{ card.status || '已登记' }}</span></div>
        <p v-if="card.errorType">问题类型：{{ card.errorType }}</p>
        <small v-if="card.answerId">answer：{{ card.answerId }}</small>
      </article>
    </section>

    <section v-if="run.limitations?.length" class="limitations-card" aria-label="回答限制">
      <h4>回答限制</h4>
      <ul>
        <li v-for="(limitation, index) in run.limitations" :key="`limitation-${index}`">{{ limitation }}</li>
      </ul>
    </section>

    <div v-if="run.approval" class="approval-card">
      <strong>需要人工审批</strong>
      <span>审批号：{{ run.approval.approvalId || '待生成' }}</span>
      <button type="button" @click="$emit('approve', { ...run.approval, approved: true })">批准执行</button>
      <button type="button" class="deny-btn" @click="$emit('approve', { ...run.approval, approved: false })">拒绝</button>
    </div>

    <ol v-if="run.toolTimeline?.length" class="tool-timeline">
      <li v-for="(item, index) in run.toolTimeline" :key="item.eventId || index">
        <span class="tool-name">{{ item.toolName || 'tool' }}</span>
        <span :class="['tool-state', item.status]">{{ toolStatus(item.status) }}</span>
        <small v-if="item.durationMs">{{ item.durationMs }} ms</small>
      </li>
    </ol>

    <ul v-if="run.citations?.length" class="citation-list">
      <li v-for="(citation, index) in run.citations" :key="citation.id || citation.url || index">
        <a v-if="citationUrl(citation)" :href="citationUrl(citation)" target="_blank" rel="noopener noreferrer">
          {{ citation.title || citation.sourceTitle || citationUrl(citation) }}
        </a>
        <span v-else>{{ citation.title || citation.sourceTitle || citation.text || citation.evidenceText || citation }}</span>
      </li>
    </ul>

    <p v-if="run.error" class="run-error">{{ run.error }}</p>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchAnswerFeedback } from '@/api'
import { statusLabel } from '@/api/run-contract'

const props = defineProps({
  run: { type: Object, default: null }
})

defineEmits(['cancel', 'reconnect', 'approve', 'feedback'])

const canCancel = computed(() => ['creating', 'running', 'waiting_approval', 'reconnecting'].includes(props.run?.status))
const canGiveFeedback = computed(() => props.run?.status === 'completed' && Boolean(props.run?.answerId) && props.run?.lineageStatus === 'COMPLETE' && !props.run?.feedbackSubmitted)
const feedbackHistory = ref([])
const feedbackHistoryState = ref('idle')
const feedbackHistoryError = ref('')
let feedbackRequestController = null
let feedbackRequestSequence = 0
const hasStructuredCards = computed(() => {
  const cards = props.run?.cards
  if (!cards || typeof cards !== 'object') return false
  return ['facts', 'calculations', 'teams', 'activities', 'citations', 'feedback', 'errors']
    .some(key => Array.isArray(cards[key]) && cards[key].length > 0)
})

function feedbackHistoryLabel(state) {
  return {
    idle: '未查询',
    loading: '读取中',
    ready: '已同步',
    empty: '暂无记录',
    permission: '无权限',
    error: '读取失败'
  }[state] || state
}

function feedbackTypeLabel(type) {
  return {
    HELPFUL: '有帮助',
    NOT_HELPFUL: '需要改进',
    CORRECTION: '纠错'
  }[String(type || '').toUpperCase()] || String(type || '未指定反馈')
}

function feedbackStatusLabel(status) {
  return {
    SUBMITTED: '已提交',
    TRIAGED: '已分诊',
    IN_PROGRESS: '处理中',
    RESOLVED: '已解决',
    CLOSED: '已关闭'
  }[String(status || '').toUpperCase()] || String(status || '已登记')
}

function formatDate(value) {
  if (!value) return '时间未提供'
  try {
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return '时间无效'
    return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
  } catch {
    return '时间无效'
  }
}

function feedbackItems(payload) {
  if (Array.isArray(payload)) return payload
  if (Array.isArray(payload?.items)) return payload.items
  if (Array.isArray(payload?.data)) return payload.data
  return []
}

function isAbortError(error) {
  return error?.name === 'AbortError' || error?.code === 20
}

async function loadFeedbackHistory(answerId = props.run?.answerId) {
  const sequence = ++feedbackRequestSequence
  feedbackRequestController?.abort()
  feedbackRequestController = null
  const normalizedAnswerId = String(answerId || '').trim()
  if (!normalizedAnswerId || props.run?.status !== 'completed') {
    feedbackHistory.value = []
    feedbackHistoryError.value = ''
    feedbackHistoryState.value = 'idle'
    return
  }
  const controller = new AbortController()
  feedbackRequestController = controller
  feedbackHistoryState.value = 'loading'
  feedbackHistoryError.value = ''
  try {
    const result = await fetchAnswerFeedback(normalizedAnswerId, { limit: 50, signal: controller.signal })
    if (sequence !== feedbackRequestSequence || controller.signal.aborted) return
    feedbackHistory.value = feedbackItems(result)
    feedbackHistoryState.value = feedbackHistory.value.length ? 'ready' : 'empty'
  } catch (error) {
    if (isAbortError(error) || sequence !== feedbackRequestSequence) return
    feedbackHistory.value = []
    feedbackHistoryError.value = error?.message || '读取反馈历史失败'
    feedbackHistoryState.value = [401, 403].includes(Number(error?.status)) ? 'permission' : 'error'
  } finally {
    if (feedbackRequestController === controller) feedbackRequestController = null
  }
}

function reloadFeedbackHistory() {
  return loadFeedbackHistory(props.run?.answerId)
}

watch(
  () => [props.run?.runId, props.run?.answerId, props.run?.status, props.run?.feedbackSubmitted],
  () => { void loadFeedbackHistory() },
  { immediate: true }
)

onBeforeUnmount(() => {
  feedbackRequestSequence += 1
  feedbackRequestController?.abort()
  feedbackRequestController = null
})

function toolStatus(status) {
  return { running: '执行中', completed: '完成', failed: '失败' }[status] || status || '未知'
}

// 引用来自模型/检索结果，不能直接把任意 scheme 绑定到 href；只允许
// http(s) 外链，其他值仍以纯文本展示，避免 javascript/data URL 注入。
function citationUrl(citation) {
  const raw = citation?.url || citation?.sourceUrl
  if (!raw || typeof raw !== 'string') return ''
  try {
    // 来源契约要求绝对 HTTP(S) URL；相对路径不能因为当前 origin 被拼接
    // 后就变成“可信来源”。后端仍负责 allowlist/许可/freshness 门禁。
    const parsed = new URL(raw)
    // citation 只能作为公开来源链接；HTTP(S) userinfo 可能携带凭据，
    // 不能因为协议合法就把它渲染成可点击 URL。
    return ['http:', 'https:'].includes(parsed.protocol) && Boolean(parsed.hostname)
      && !parsed.username && !parsed.password
      ? parsed.href : ''
  } catch {
    return ''
  }
}

function formatValue(value) {
  if (value === null || value === undefined || value === '') return '未返回'
  if (typeof value === 'string') return value
  try { return JSON.stringify(value) } catch { return String(value) }
}

function effectiveWindow(card) {
  if (!card?.effectiveFrom) return '未返回'
  return card.effectiveTo ? `${card.effectiveFrom} – ${card.effectiveTo}` : `${card.effectiveFrom} 起`
}
</script>

<style scoped>
.run-panel { margin: 10px 20px; padding: 12px 14px; border: 1px solid rgba(255,255,255,.1); border-radius: 12px; background: rgba(255,255,255,.035); font-size: 12px; color: rgba(255,255,255,.7); }
.run-summary { display: flex; align-items: center; gap: 10px; }
.run-summary code { color: rgba(255,255,255,.35); font-size: 10px; }
.transport-badge { color: rgba(255,255,255,.42); font-size: 10px; }
.protocol-warning { color: #ffd591; font-size: 10px; }
.context-card { display: flex; flex-wrap: wrap; gap: 6px 12px; margin-top: 8px; color: rgba(255,255,255,.55); }
.context-card span { padding: 3px 7px; border-radius: 5px; background: rgba(105,192,255,.08); }
.run-status { padding: 3px 8px; border-radius: 6px; background: rgba(255,255,255,.08); }
.run-status.running { color: #52c41a; background: rgba(82,196,26,.12); }
.run-status.waiting_approval { color: #faad14; background: rgba(250,173,20,.12); }
.run-status.reconnecting { color: #40a9ff; background: rgba(64,169,255,.12); }
.run-status.failed, .run-status.timed_out { color: #ff7875; background: rgba(255,77,79,.12); }
.cancel-btn, .retry-btn, .approval-card button { margin-left: auto; padding: 4px 9px; border: 0; border-radius: 6px; color: #fff; background: rgba(255,77,79,.2); cursor: pointer; }
.retry-btn { background: rgba(64,169,255,.2); }
.approval-card { display: flex; align-items: center; gap: 8px; margin-top: 10px; padding: 8px; border-radius: 8px; background: rgba(250,173,20,.1); }
.approval-card button { background: #ad6800; }
.approval-card .deny-btn { background: rgba(255,77,79,.55); }
.tool-timeline { margin: 10px 0 0; padding-left: 18px; }
.tool-timeline li { display: flex; gap: 8px; margin: 4px 0; }
.tool-name { color: #b37feb; }
.tool-state.completed { color: #73d13d; }
.tool-state.failed { color: #ff7875; }
.citation-list { margin: 8px 0 0; padding-left: 18px; }
.citation-list a { color: #69c0ff; }
.run-error { margin: 8px 0 0; color: #ff7875; }
.feedback-card { display: flex; align-items: center; gap: 8px; margin-top: 10px; padding-top: 8px; border-top: 1px solid rgba(255,255,255,.08); }
.feedback-btn { padding: 4px 8px; border: 1px solid rgba(82,196,26,.35); border-radius: 6px; color: #b7eb8f; background: rgba(82,196,26,.08); cursor: pointer; }
.feedback-negative { border-color: rgba(255,120,117,.35); color: #ffaaa5; background: rgba(255,77,79,.08); }
.feedback-confirm { margin: 8px 0 0; color: #95de64; }
.feedback-unavailable { margin: 8px 0 0; color: #faad14; font-size: 12px; }
.feedback-history { margin-top: 9px; padding-top: 8px; border-top: 1px solid rgba(255,255,255,.08); }
.feedback-history-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: rgba(255,255,255,.7); font-size: 11px; }
.feedback-history-status { color: rgba(255,255,255,.4); font-size: 10px; }
.feedback-history-state { margin-top: 6px; color: rgba(255,255,255,.48); font-size: 11px; line-height: 1.5; }
.feedback-history-state.warning { color: #ffaaa5; }
.feedback-retry { margin-left: 6px; padding: 0; border: 0; color: #91d5ff; background: none; cursor: pointer; text-decoration: underline; font: inherit; }
.feedback-history-list { display: grid; gap: 5px; margin: 6px 0 0; padding: 0; list-style: none; }
.feedback-history-item { display: flex; align-items: center; gap: 7px; padding: 6px 8px; border-radius: 6px; background: rgba(149,222,100,.045); }
.feedback-type { color: #b7eb8f; }
.feedback-status { color: rgba(255,255,255,.62); }
.feedback-history-item small { margin-left: auto; color: rgba(255,255,255,.35); font-size: 10px; }
.structured-cards { margin-top: 10px; padding-top: 9px; border-top: 1px solid rgba(255,255,255,.08); }
.structured-cards h4 { margin: 0 0 7px; color: rgba(255,255,255,.72); font-size: 12px; }
.structured-card { display: grid; gap: 4px; margin: 6px 0; padding: 8px 9px; border: 1px solid rgba(105,192,255,.14); border-radius: 8px; background: rgba(105,192,255,.045); }
.structured-card p { margin: 0; color: rgba(255,255,255,.65); line-height: 1.5; }
.structured-card small { color: rgba(255,255,255,.4); }
.structured-card a { width: fit-content; color: #69c0ff; overflow-wrap: anywhere; }
.structured-card-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; color: #fff; }
.structured-card-heading span { color: rgba(255,255,255,.42); font-size: 10px; }
.structured-result { color: #b7eb8f !important; }
.evidence { color: rgba(255,255,255,.54) !important; }
.freshness { color: #95de64 !important; }
.error-structured-card { display: flex; gap: 7px; color: #ffaaa5; border-color: rgba(255,77,79,.2); background: rgba(255,77,79,.06); }
.feedback-structured-card { border-color: rgba(149,222,100,.2); background: rgba(149,222,100,.05); }
.limitations-card { margin-top: 10px; padding: 8px 9px; border: 1px solid rgba(250,173,20,.2); border-radius: 8px; background: rgba(250,173,20,.06); color: rgba(255,255,255,.62); }
.limitations-card h4 { margin: 0 0 4px; color: #ffd591; font-size: 11px; }
.limitations-card ul { margin: 0; padding-left: 17px; line-height: 1.5; }
</style>
