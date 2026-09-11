<template>
  <div class="game-master-page">
    <header class="chat-header">
      <button class="back-btn" @click="$router.push('/')">← 返回</button>
      <h1>🎮 AI 宝可梦大师</h1>
      <span class="chat-id-badge">{{ chatId }}</span>
    </header>

    <section class="scenario-bar" aria-label="回答场景">
      <label>
        游戏/资料版本
        <select v-model="scenario.gameReleaseId">
          <option value="pokemon-scarlet-violet">朱/紫（本体）</option>
          <!-- DLC 版本必须先进入共享 registry；未登记值不能送入现代 Run。 -->
          <option value="scarlet-violet-teal-mask" disabled>朱/紫：碧之假面 DLC（版本待登记）</option>
          <option value="scarlet-violet-indigo-disk" disabled>朱/紫：蓝之圆盘 DLC（版本待登记）</option>
        </select>
      </label>
      <label>
        赛制
        <select v-model="scenario.battleFormat">
          <option v-for="option in battleFormatOptions" :key="option.alias" :value="option.alias">{{ option.label }}</option>
          <option value="ranked-singles" disabled>排位单打（赛制 ID 待登记）</option>
          <option value="ranked-doubles" disabled>排位双打（赛制 ID 待登记）</option>
        </select>
      </label>
      <label>
        语言
        <select v-model="scenario.language">
          <option value="zh-CN">简体中文</option>
          <option value="en-US">English</option>
          <option value="ja-JP">日本語</option>
        </select>
      </label>
      <label>
        规则版本
        <input v-model="scenario.rulesetVersionId" maxlength="64" aria-label="规则版本" />
      </label>
      <label>
        数据版本
        <input v-model="scenario.dataReleaseId" maxlength="128" aria-label="数据版本" />
      </label>
    </section>

    <ChatRoom
      :messages="messages"
      :connectionStatus="connectionStatus"
      :runViewModel="activeRun"
      aiType="game"
      @send-message="handleSend"
      @cancel-run="handleCancelRun"
      @reconnect-run="handleReconnectRun"
      @approve-run="handleApproveRun"
      @feedback="handleFeedback"
    />
  </div>
</template>

<script setup>
import { ref, onBeforeUnmount, onMounted, watch, reactive } from 'vue'
import ChatRoom from '@/components/ChatRoom.vue'
import { chatWithGameApp, decideRunApproval, submitAnswerFeedback } from '@/api'
import { BATTLE_FORMAT_OPTIONS, serializeRunContext } from '@/api/version-registry'
import { useRunStream } from '@/composables/useRunStream'
import { getOrCreateConversationId } from '@/composables/runPersistence'
import { RUN_STATUS, TERMINAL_STATUSES } from '@/api/run-contract'

const chatId = ref(getOrCreateConversationId('game'))
const messages = ref([])
const connectionStatus = ref('idle') // 'idle' | 'connecting' | 'done'
let currentEventSource = null
let legacyDoneTimer = null
const activeRun = ref(null)
const modernRunEnabled = import.meta.env.VITE_V1_RUNS_ENABLED !== 'false'
const runStream = useRunStream()
let stopAnswerWatch = null
const scenario = reactive({
  gameReleaseId: 'pokemon-scarlet-violet',
  battleFormat: 'casual-singles',
  language: 'zh-CN',
  // 使用 fixture/registry 已登记的规则与数据版本；未登记的 sv-ou-v1
  // 会在现代 Run 入口被后端拒绝，无法作为默认演示配置。
  rulesetVersionId: 'casual-v1',
  dataReleaseId: 'pokemon-scarlet-violet-data-v1'
})
const battleFormatOptions = BATTLE_FORMAT_OPTIONS

// Run 状态是输入区和停止按钮的唯一事实来源；等待审批/重连期间也必须
// 保持 single-flight，避免用户再次创建并发 Run。
watch(() => activeRun.value?.status, status => {
  if (!status) return
  if (TERMINAL_STATUSES.has(status)) {
    connectionStatus.value = 'done'
  } else if (status === RUN_STATUS.RECONNECTING) {
    connectionStatus.value = 'reconnecting'
  } else if (status === RUN_STATUS.WAITING_APPROVAL) {
    connectionStatus.value = 'waiting_approval'
  } else {
    connectionStatus.value = 'connecting'
  }
})

// 欢迎消息
messages.value.push({
  role: 'assistant',
  content: '你好！我是 AI 宝可梦大师 🎮，深耕宝可梦对战与养成。请告诉我你想了解的宝可梦、版本或对战策略～'
})

async function handleSend(message) {
  // 添加用户消息
  messages.value.push({ role: 'user', content: message })

  // 创建 AI 消息气泡（初始为空，SSE 增量填充）
  const aiMessage = { role: 'assistant', content: '' }
  messages.value.push(aiMessage)

  if (modernRunEnabled) {
    try {
      activeRun.value = await runStream.startRun({
        prompt: message,
        conversationId: chatId.value,
        ...serializeRunContext({
          gameReleaseId: scenario.gameReleaseId,
          battleFormat: scenario.battleFormat
        }),
        language: scenario.language,
        rulesetVersionId: scenario.rulesetVersionId,
        dataReleaseId: scenario.dataReleaseId,
        idempotencyKey: makeRunIdempotencyKey(chatId.value)
      })
      connectionStatus.value = 'connecting'
      // 结构化 Run 的答案增量统一投影到兼容气泡。
      bindAnswerBubble(aiMessage)
      return
    } catch (error) {
      // 后端尚未升级或返回 404 时回退到旧只读 SSE，不丢失用户输入。
      if (![404, 405, 501].includes(error.status)) {
        aiMessage.content = `创建 Run 失败：${error.message}`
        connectionStatus.value = 'done'
        return
      }
    }
  }

  handleLegacySend(message, aiMessage)
}

function bindAnswerBubble(aiMessage) {
  stopAnswerWatch?.()
  stopAnswerWatch = watch(() => activeRun.value?.answer, value => {
    aiMessage.content = value || ''
    if (activeRun.value && TERMINAL_STATUSES.has(activeRun.value.status)) {
      connectionStatus.value = 'done'
    }
  }, { immediate: true })
}

// 幂等键不应仅依赖毫秒时间戳；跨标签页/快速重试时使用随机 UUID，
// 且只包含本地 scope，不把 prompt 或认证信息放入请求/日志。
function makeRunIdempotencyKey(scope) {
  try {
    if (globalThis.crypto?.randomUUID) return `${scope}-${globalThis.crypto.randomUUID()}`
  } catch {
    // 旧浏览器没有 randomUUID 时退化为非敏感的随机后缀。
  }
  return `${scope}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function handleLegacySend(message, aiMessage) {

  if (legacyDoneTimer) clearInterval(legacyDoneTimer)

  connectionStatus.value = 'connecting'

  currentEventSource = chatWithGameApp(
    message,
    chatId.value,
    // onMessage — 增量追加到同一个气泡
    (data) => {
      aiMessage.content += data
      connectionStatus.value = 'connected'
    },
    // onError
    () => {
      if (aiMessage.content === '') {
        aiMessage.content = '抱歉，连接出错了，请重试。'
      }
      connectionStatus.value = 'done'
    }
  )

  // EventSource 没有完成回调，通过 onerror 和 [DONE] 信号处理关闭
  legacyDoneTimer = setInterval(() => {
    if (currentEventSource && currentEventSource.readyState === 2) {
      clearInterval(legacyDoneTimer)
      legacyDoneTimer = null
      connectionStatus.value = 'done'
    }
  }, 200)
}

async function handleCancelRun(runId) {
  try {
    await runStream.cancelRun(runId)
    connectionStatus.value = 'done'
  } catch (error) {
    if (activeRun.value) activeRun.value.error = error.message
  }
}

async function handleReconnectRun(runId) {
  await runStream.reconnect(runId)
  const status = activeRun.value?.status
  connectionStatus.value = TERMINAL_STATUSES.has(status) ? 'done'
    : status === RUN_STATUS.RECONNECTING ? 'reconnecting'
      : status === RUN_STATUS.WAITING_APPROVAL ? 'waiting_approval' : 'connecting'
}

async function handleApproveRun(approval) {
  if (!approval?.approvalId || !activeRun.value?.runId) return
  const runId = activeRun.value.runId
  try {
    const response = await decideRunApproval(approval.approvalId, {
      runId,
      approved: approval.approved !== false,
      reason: approval.approved === false ? '用户拒绝工具调用' : ''
    })
    const decision = response?.data || response || {}
    // HTTP 200 也必须满足与用户选择一致的结构化结果：批准必须真的
    // allowed=true，拒绝必须真的 allowed=false。过期/错绑/并发冲突由
    // 后端返回 4xx，此时保留审批卡，避免 UI 丢失可恢复入口。
    const expectedAllowed = approval.approved !== false
    if (Boolean(decision.allowed) !== expectedAllowed) {
      throw new Error(decision.reason || '审批结果未被服务端确认')
    }
    // 不在 HTTP 决策成功后提前清空审批卡：只有重放到
    // approval.resolved/run.failed 事件，才能确认 Run 已收敛。若随后网络
    // 断开，保留卡片让用户可以继续重试，而不是丢失唯一操作入口。
    // 拒绝也会产生 approval.resolved/run.failed 终态事件；恢复消费才能
    // 让时间线从 waiting_approval 正确收敛到最终状态。
    await runStream.reconnect(runId)
  } catch (error) {
    activeRun.value.error = error.message
  }
}

async function handleFeedback(feedback) {
  if (!feedback?.answerId || !activeRun.value) return
  try {
    await submitAnswerFeedback(feedback.answerId, {
      feedbackType: feedback.feedbackType,
      evidence: {
        runId: activeRun.value.runId,
        status: activeRun.value.status,
        citationCount: activeRun.value.citations?.length || 0
      },
      traceId: activeRun.value.traceId || activeRun.value.lineage?.traceId,
      dataReleaseId: activeRun.value.context?.dataReleaseId,
      knowledgeReleaseId: activeRun.value.context?.knowledgeReleaseId,
      indexReleaseId: activeRun.value.context?.indexReleaseId
    })
    activeRun.value.feedbackSubmitted = feedback.feedbackType
  } catch (error) {
    activeRun.value.error = error.message || '反馈提交失败'
  }
}

onMounted(async () => {
  try {
    const restored = await runStream.restoreConversation(chatId.value)
    if (!restored) return
    activeRun.value = restored
    const aiMessage = {
      role: 'assistant',
      content: restored.answer || '已恢复上次未完成的任务，正在继续接收事件…'
    }
    messages.value.push(aiMessage)
    bindAnswerBubble(aiMessage)
  } catch (error) {
    // 恢复失败不阻塞新会话；保留错误提示便于用户手动重试。
    messages.value.push({ role: 'assistant', content: `恢复任务失败：${error.message}` })
  }
})

// 页面卸载时关闭 SSE 连接
onBeforeUnmount(() => {
  stopAnswerWatch?.()
  if (currentEventSource) {
    currentEventSource.close()
  }
  if (legacyDoneTimer) clearInterval(legacyDoneTimer)
  legacyDoneTimer = null
  if (activeRun.value?.runId) runStream.pause(activeRun.value.runId)
})
</script>

<style scoped>
.game-master-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #0f0f1a 0%, #1a1a2e 100%);
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 24px;
  background: rgba(255, 255, 255, 0.03);
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}

.chat-header h1 {
  font-size: 18px;
  font-weight: 600;
  background: linear-gradient(135deg, #ffcb05, #3b5ba7);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.scenario-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  align-items: end;
  padding: 10px 24px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(255, 255, 255, 0.02);
}

.scenario-bar label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: rgba(255, 255, 255, 0.55);
  font-size: 11px;
}

.scenario-bar select,
.scenario-bar input {
  min-width: 138px;
  height: 30px;
  padding: 0 8px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  background: #1b1b30;
  color: #e0e0e0;
  font-size: 12px;
}

.scenario-bar input { min-width: 110px; }

.back-btn {
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.7);
  padding: 6px 14px;
  border-radius: 8px;
  font-size: 13px;
  transition: all 0.2s;
}

.back-btn:hover {
  background: rgba(255, 255, 255, 0.15);
  color: #fff;
}

.chat-id-badge {
  margin-left: auto;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.3);
  font-family: monospace;
  background: rgba(255, 255, 255, 0.05);
  padding: 4px 10px;
  border-radius: 6px;
}
</style>
