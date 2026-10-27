<template>
  <section v-if="run" class="run-panel" :class="{ 'has-team-preview': !!run.approval?.teamChange }" aria-live="polite">
    <div class="run-summary">
      <span class="run-status" :class="run.status">{{ statusLabel(run.status) }}</span>
      <span class="transport-badge" title="事件快照 · 游标可重连；按游标补读，支持断线重连">回答与资料记录</span>
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

    <details class="audit-details">
    <summary>资料版本与反馈（可选）</summary>
    <code v-if="run.runId">Run {{ run.runId }}</code>
    <code v-if="run.traceId" class="trace-id">trace {{ run.traceId }}</code>
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
    </details>

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

    <details v-if="run.limitations?.length" class="limitations-card" aria-label="回答限制">
      <summary>回答来源与限制</summary>
      <h4>回答限制</h4>
      <ul>
        <li v-for="(limitation, index) in run.limitations" :key="`limitation-${index}`">{{ limitation }}</li>
      </ul>
    </details>

    <div v-if="run.approval" class="approval-card">
      <strong>{{ run.approval.teamChange ? '队伍修改预览' : '需要人工审批' }}</strong>
      <template v-if="run.approval.teamChange">
        <div class="proposal-content">
        <p>队伍：{{ run.approval.teamChange.teamName }} · 确认前不会保存</p>
        <p class="proposal-reason">策略理由（待核实）：{{ run.approval.teamChange.rationale }}</p>
        <div class="proposal-table"><table><thead><tr><th>成员</th><th>配置</th><th>修改前</th><th>修改后</th></tr></thead><tbody>
          <tr v-for="(line,index) in run.approval.teamChange.changes" :key="index"><td><span v-if="line.slot">第{{ line.slot }}位 · </span>{{ line.member }}</td><td>{{ line.field }}</td><td>{{ line.before || '未配置' }}</td><td>{{ line.after || '未配置' }}</td></tr>
        </tbody></table></div>
        <details><summary>计算证据与适用边界</summary><p class="proposal-evidence">{{ run.approval.teamChange.calculationEvidence }}</p><p v-for="line in run.approval.teamChange.limitations" :key="line">{{ line }}</p></details>
        <small>有效期至 {{ formatDate(run.approval.teamChange.expiresAt) }}；队伍被修改后需重新生成预览。</small>
        </div>
      </template>
      <span v-else>审批号：{{ run.approval.approvalId || '待生成' }}</span>
      <p v-if="teamDecisionMessage" class="proposal-decision-state" role="status">{{ teamDecisionMessage }}</p>
      <div class="proposal-actions">
      <button type="button" :disabled="run.approvalSubmitting || !!teamDecisionMessage" @click="$emit('approve', { ...run.approval, approved: true })">{{ run.approvalSubmitting ? '处理中…' : run.approval.teamChange ? '确认保存' : '批准执行' }}</button>
      <button type="button" class="deny-btn" :disabled="run.approvalSubmitting || !!teamDecisionMessage" @click="$emit('approve', { ...run.approval, approved: false })">{{ run.approval.teamChange ? '取消修改' : '拒绝' }}</button>
      <button v-if="teamDecisionMessage" type="button" :disabled="run.approvalSubmitting" @click="$emit('reconnect', run.runId)">刷新任务状态</button>
      </div>
    </div>

    <ModelUsageDetails :attempts="run.modelUsageAttempts || []" :client-timing="run.clientTiming" />
    <details v-if="run.toolTimeline?.length" class="process-details"><summary>查看执行过程 · {{ run.toolTimeline.length }} 次工具调用</summary>
    <ol class="tool-timeline">
      <li v-for="(item, index) in run.toolTimeline" :key="item.eventId || index">
        <span class="tool-name">{{ item.toolName || 'tool' }}</span>
        <span :class="['tool-state', item.status]">{{ toolStatus(item.status) }}</span>
        <small v-if="item.cached === true">复用本次任务结果</small>
        <small v-if="formatToolTiming(item.timing)">{{ formatToolTiming(item.timing) }}</small>
        <small v-else-if="item.durationMs != null">处理 {{ item.durationMs }} ms（未拆分）</small>
        <small v-if="item.status === 'failed'" class="tool-failure-reason">{{ toolFailureLabel(item) }}</small>
      </li>
    </ol>
    </details>

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

<style scoped>
.has-team-preview .approval-card{flex-wrap:nowrap}
.proposal-decision-state{font-size:12px;color:#52695f;margin:6px 0}.proposal-actions{flex-wrap:wrap}
.proposal-table{width:100%;overflow-x:auto}.proposal-table table{border-collapse:collapse;width:100%;font-size:12px}.proposal-table th,.proposal-table td{padding:7px;border:1px solid #dce7e1;text-align:left;overflow-wrap:anywhere;min-width:56px}.proposal-reason,.proposal-evidence{white-space:pre-wrap;overflow-wrap:anywhere}.run-panel.has-team-preview{max-height:54%;min-height:200px;display:flex;flex-direction:column;overflow:hidden}.has-team-preview .approval-card{display:flex;flex-direction:column;flex:1 1 auto;min-height:0;overflow:hidden}.proposal-content{min-height:0;overflow:auto;width:100%;flex:1 1 auto}.proposal-content p{margin:5px 0}.proposal-actions{display:flex;gap:8px;flex-shrink:0;padding-top:6px}.approval-card button:disabled{opacity:.55;cursor:wait}
</style>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import ModelUsageDetails from '@/components/ModelUsageDetails.vue'
import { formatToolTiming } from '@/api/response-timing'
import { fetchAnswerFeedback } from '@/api'
import { statusLabel, toolFailureLabel } from '@/api/run-contract'
import { useAuthSession } from '@/composables/useAuthSession'

const { state: authState } = useAuthSession()
const localChat = computed(() => authState.snapshot?.mode === 'disabled')

const props = defineProps({
  run: { type: Object, default: null }
})

defineEmits(['cancel', 'reconnect', 'approve', 'feedback'])

const canCancel = computed(() => ['creating', 'running', 'waiting_approval', 'reconnecting'].includes(props.run?.status))
const teamDecisionMessage = computed(() => props.run?.approval?.teamChange
  && props.run.teamDecisionState?.approvalId === props.run.approval.approvalId
  ? props.run.teamDecisionState.message : '')
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
  if (!normalizedAnswerId || props.run?.status !== 'completed'
    || (localChat.value && props.run?.lineageStatus !== 'COMPLETE')) {
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
.tool-timeline li { flex-wrap: wrap; }
.tool-failure-reason { flex-basis: 100%; color: var(--danger); overflow-wrap: anywhere; }
</style>
