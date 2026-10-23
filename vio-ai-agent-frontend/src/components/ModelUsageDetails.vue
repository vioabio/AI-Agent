<template>
  <details v-if="summary" class="model-usage-details" aria-label="模型用量与耗时">
    <summary>模型用量与耗时<span v-if="summary.modelCalls !== null"> · {{ summary.modelCalls }} 次调用</span></summary>
    <dl>
      <div><dt>模型调用</dt><dd>{{ summary.modelCalls ?? '未知' }} 次</dd></div>
      <div><dt>输入 Token</dt><dd>{{ summary.totals?.inputTokens ?? '未知' }}</dd></div>
      <div><dt>输出 Token</dt><dd>{{ summary.totals?.outputTokens ?? '未知' }}</dd></div>
      <div><dt>模型累计耗时</dt><dd>{{ duration(summary.modelDurationMs) }}</dd></div>
      <div><dt>费用</dt><dd>未知</dd></div>
    </dl>
    <p>{{ summary.callsWithUsage }} 次调用取得供应商用量；{{ summary.complete ? '本次统计完整。' : '统计不完整，未把缺失值记为 0。' }}</p>
    <p>次数包含资料规划和回答生成；耗时是应用观测值。未配置计费规则，未估算费用。</p>
    <ul>
      <template v-for="(attempt, index) in summary.attempts" :key="attempt.attemptId">
        <li v-for="call in attempt.calls" :key="`${index}-${call.callNo}`">
          {{ call.configuredModelId || '未知模型' }} · {{ call.mode === 'STREAM' ? '流式' : '非流式' }} · {{ outcome(call.outcome) }} · {{ duration(call.durationMs) }}
          <span v-if="call.mode === 'STREAM' && call.firstTextDeltaMs !== null"> · SDK 首段 {{ duration(call.firstTextDeltaMs) }}</span>
        </li>
      </template>
    </ul>
  </details>
</template>

<script setup>
import { computed } from 'vue'
import { summarizeModelUsage } from '@/api/model-usage'
const props = defineProps({ attempts: { type: Array, default: () => [] } })
const summary = computed(() => summarizeModelUsage(props.attempts))
const duration = value => value === null ? '未知' : `${(value / 1000).toFixed(2)} 秒`
const outcome = value => ({ RETURNED: '已返回', FAILED: '失败', CANCELLED: '已中断', IN_FLIGHT: '尚未返回' })[value] || '未知'
</script>

<style scoped>
.model-usage-details{margin-top:8px;border:1px solid #dce7e1;border-radius:8px;padding:8px 10px;font-size:12px;color:#617369;overflow-wrap:anywhere}
.model-usage-details summary{cursor:pointer;color:#426353;font-weight:600;list-style-position:inside}
dl{display:flex;flex-wrap:wrap;gap:10px 24px;margin:10px 0}dl div{display:grid;gap:3px}dt{color:#7b8981}dd{margin:0;color:#254a39;font-variant-numeric:tabular-nums}
p{margin:5px 0;line-height:1.6}ul{padding-left:18px;margin:6px 0;line-height:1.8}
</style>
