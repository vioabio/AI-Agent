<template>
  <p v-if="historyTrimmed" class="context-history-notice" role="status">部分历史未参与本次回答；关键限制请在当前问题中重申。</p>
  <details v-if="summary" class="model-usage-details" aria-label="模型用量与耗时">
    <summary>模型用量与耗时<span v-if="summary.modelCalls !== null"> · {{ summary.modelCalls }} 次调用</span></summary>
    <p v-if="clientObserved" class="context-history-notice client-response-timing">本机收到首段回答：{{ duration(clientObserved.firstAnswerReceivedMs) }}（包含网络、事件补读与审批等待，不代表模型首 Token 或页面绘制时间）。</p>
    <dl>
      <div><dt>模型调用</dt><dd>{{ summary.modelCalls ?? '未知' }} 次</dd></div>
      <div><dt>输入 Token</dt><dd>{{ summary.totals?.inputTokens ?? '未知' }}</dd></div>
      <div><dt>输出 Token</dt><dd>{{ summary.totals?.outputTokens ?? '未知' }}</dd></div>
      <div><dt>模型累计耗时</dt><dd>{{ duration(summary.modelDurationMs) }}</dd></div>
      <div><dt>参考费用</dt><dd>{{ costLabel }}</dd></div>
    </dl>
    <p>{{ summary.callsWithUsage }} 次调用取得供应商用量；{{ summary.complete ? '本次统计完整。' : '统计不完整，未把缺失值记为 0。' }}</p>
    <p>次数包含资料规划和回答生成；耗时是应用观测值。参考费用仅按保存的价格版本估算，不等于账单，也不包含工具费用。</p>
    <p v-if="summary.cost.status === 'PARTIAL'">仅 {{ summary.cost.pricedCalls }} / {{ summary.cost.totalCalls }} 次调用可估算，小计 {{ formatReferenceMoney(summary.cost.currency, summary.cost.knownSubtotal) }}；整项任务费用未知。</p>
    <p v-else-if="summary.cost.status === 'UNKNOWN'">费用未知：{{ costReasonLabel(summary.cost.reason) }}。</p>
    <div v-if="summary.taskBudget" class="context-budget-details">
      <template v-if="summary.taskBudget.stateKnown">
        <p>任务累计额度：模型 {{ summary.taskBudget.modelCalls }} / {{ summary.taskBudget.maxModelCalls }} 次，Token 额度占用 {{ summary.taskBudget.chargedTokens }} / {{ summary.taskBudget.tokenLimit }}。</p>
        <p>调用前预留输入估算与输出上限；实际用量更高时补记，失败或缺失用量不退回预留。额度占用不等于真实用量或费用。</p>
      </template>
      <p v-else>无法核实之前的任务额度，请重新发起任务。</p>
    </div>
    <template v-for="(attempt, index) in summary.attempts" :key="`context-${index}`">
      <div v-if="attempt.responseTiming" class="context-budget-details">
        <p>本次执行调度等待 {{ duration(attempt.responseTiming.dispatchWaitMs) }} · 服务端首段就绪 {{ duration(attempt.responseTiming.firstAnswerReadyMs) }}。</p>
        <p>从本次任务注册开始计时；就绪表示已准备公开文字，不代表网络送达。{{ attempt.responseTiming.answerPath === 'BUFFERED' ? '本次为完整答案就绪。' : '' }}</p>
      </div>
      <ul v-if="attempt.toolTimings.length" aria-label="工具耗时明细">
        <li v-for="tool in attempt.toolTimings" :key="tool.eventId">{{ tool.toolName }} · {{ formatToolTiming(tool.timing) }}</li>
      </ul>
      <p v-if="attempt.toolTimingsTruncated">工具计时明细已截断，不能据此合计完整任务耗时。</p>
      <div v-if="attempt.contextBudget" class="context-budget-details">
        <p>上下文预算（本地估算，非计费）：输入 {{ attempt.contextBudget.lastEstimate?.inputTokens ?? '未知' }} / {{ attempt.contextBudget.inputLimit }}，回答预留 {{ attempt.contextBudget.outputReserve }}。</p>
        <p>估算含 {{ attempt.contextBudget.safetyPercent - 100 }}% 余量，可能与模型实际计数不同。</p>
        <p v-if="attempt.contextBudget.omittedHistoryTurns">{{ attempt.contextBudget.omittedHistoryTurns }} 轮历史未回填，当前问题与本轮工具结果保持完整。</p>
        <p v-if="attempt.contextBudget.status === 'BLOCKED'">已触及预算或输入兼容边界，请缩小问题范围。</p>
      </div>
    </template>
    <ul>
      <template v-for="(attempt, index) in summary.attempts" :key="attempt.attemptId">
        <li v-for="call in attempt.calls" :key="`${index}-${call.callNo}`">
          {{ call.configuredModelId || '未知模型' }} · {{ call.mode === 'STREAM' ? '流式' : '非流式' }} · {{ outcome(call.outcome) }} · {{ duration(call.durationMs) }}
          <span v-if="call.mode === 'STREAM' && call.firstTextDeltaMs !== null"> · SDK 首段 {{ duration(call.firstTextDeltaMs) }}</span>
          <div v-if="call.tokenEstimateComparison?.status === 'PAIR_RECORDED'" class="token-estimate-comparison">
            输入Token对比：基础代理估算 {{ call.tokenEstimateComparison.baseInputTokens }} · 预算估算 {{ call.tokenEstimateComparison.budgetInputTokens }} · 供应商报告 {{ call.tokenEstimateComparison.providerInputTokens }}。
            {{ call.tokenEstimateComparison.budgetCovered ? '本次预算估算覆盖了报告用量。' : `本次低估 ${-call.tokenEstimateComparison.budgetErrorTokens} Token。` }}单次观测不代表其他请求已校准。
          </div>
          <div v-if="call.billingUsage">缓存命中 {{ call.billingUsage.cachedInputTokens ?? '未知' }} Token · 未命中 {{ call.billingUsage.uncachedInputTokens ?? '未知' }} Token · 推理 {{ call.billingUsage.reasoningOutputTokens ?? '未知' }} Token（已包含在输出中）</div>
          <div v-if="call.cost.status === 'ESTIMATED'">
            {{ call.cost.price.sourceType === 'TEST_FIXTURE' ? '测试价格' : '参考估算' }} {{ formatReferenceMoney(call.cost.currency, call.cost.amount) }} · 价格版本 {{ call.cost.price.version }}
            <span> · {{ call.cost.price.inputAccounting === 'SPLIT_CACHE' ? '按缓存拆分' : '全部输入统一价' }}</span>
            · <a :href="call.cost.price.sourceUrl" target="_blank" rel="noopener noreferrer">价格来源</a>
          </div>
          <div v-else-if="call.pricing?.version">调用时价格目录：{{ call.pricing.version }}；费用未知。</div>
        </li>
      </template>
    </ul>
  </details>
</template>

<script setup>
import { computed } from 'vue'
import { summarizeModelUsage } from '@/api/model-usage'
import { formatReferenceMoney, costReasonLabel } from '@/api/model-cost'
import { normalizeClientTiming, formatToolTiming } from '@/api/response-timing'
const props = defineProps({ attempts: { type: Array, default: () => [] }, clientTiming: { type: Object, default: null } })
const clientObserved = computed(() => normalizeClientTiming(props.clientTiming))
const summary = computed(() => summarizeModelUsage(props.attempts))
const costLabel = computed(() => summary.value?.cost.status === 'ESTIMATED'
  ? `约 ${formatReferenceMoney(summary.value.cost.currency, summary.value.cost.amount)}` : '未知')
const historyTrimmed = computed(() => summary.value?.attempts.some(attempt => attempt.contextBudget?.omittedHistoryTurns > 0))
const duration = value => value === null ? '未知' : `${(value / 1000).toFixed(2)} 秒`
const outcome = value => ({ RETURNED: '已返回', FAILED: '失败', CANCELLED: '已中断', IN_FLIGHT: '尚未返回' })[value] || '未知'
</script>

<style scoped>
.model-usage-details{margin-top:8px;border:1px solid #dce7e1;border-radius:8px;padding:8px 10px;font-size:12px;color:#617369;overflow-wrap:anywhere}
.model-usage-details summary{cursor:pointer;color:#426353;font-weight:600;list-style-position:inside}
.context-history-notice{font-size:12px;color:#786541;margin:6px 0;line-height:1.6}.context-budget-details{border-top:1px solid #e4ebe7;margin-top:8px;padding-top:4px}
dl{display:flex;flex-wrap:wrap;gap:10px 24px;margin:10px 0}dl div{display:grid;gap:3px}dt{color:#7b8981}dd{margin:0;color:#254a39;font-variant-numeric:tabular-nums}
p{margin:5px 0;line-height:1.6}ul{padding-left:18px;margin:6px 0;line-height:1.8}
</style>
