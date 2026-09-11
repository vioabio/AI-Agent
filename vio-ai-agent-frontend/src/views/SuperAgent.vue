<template>
  <div class="super-agent-page">
    <header class="chat-header">
      <button class="back-btn" @click="$router.push('/')">← 返回</button>
      <h1>🤖 AI 超级智能体</h1>
      <span class="status-badge" :class="connectionStatus">
        {{ statusLabel }}
      </span>
      <button
        v-if="['connecting', 'connected', 'reconnecting', 'waiting_approval'].includes(connectionStatus)"
        class="stop-btn"
        @click="handleStop"
      >
        ⏹ 停止
      </button>
    </header>

    <ChatRoom
      :messages="messages"
      :connectionStatus="connectionStatus"
      :runViewModel="activeRun"
      aiType="super"
      @send-message="handleSend"
      @cancel-run="handleCancelRun"
      @reconnect-run="handleReconnectRun"
      @approve-run="handleApproveRun"
      @feedback="handleFeedback"
    />
  </div>
</template>

<script setup>
import { ref, onBeforeUnmount, onMounted, watch } from 'vue'
import ChatRoom from '@/components/ChatRoom.vue'
import { chatWithManus, stopManus, decideRunApproval, submitAnswerFeedback } from '@/api'
import { serializeRunContext } from '@/api/version-registry'
import { useRunStream } from '@/composables/useRunStream'
import { getOrCreateConversationId } from '@/composables/runPersistence'
import { RUN_STATUS, TERMINAL_STATUSES, statusLabel as runStatusLabel } from '@/api/run-contract'

const messages = ref([])
const connectionStatus = ref('idle')
const statusLabel = ref('就绪')
const chatId = ref(getOrCreateConversationId('super-agent'))
let currentEventSource = null
let legacyDoneTimer = null
let currentSessionId = null
const activeRun = ref(null)
const modernRunEnabled = import.meta.env.VITE_V1_RUNS_ENABLED !== 'false'
const runStream = useRunStream()
let stopAnswerWatch = null

// 结构化 Run 的生命周期直接驱动页面状态，避免 SSE 断开、审批等待或
// 重连时仍允许用户创建第二个任务。
function applyRunStatus(status) {
  if (!status) return
  if (TERMINAL_STATUSES.has(status)) {
    connectionStatus.value = 'done'
    statusLabel.value = status === RUN_STATUS.CANCELLED ? '已停止' : runStatusLabel(status)
  } else if (status === RUN_STATUS.RECONNECTING) {
    connectionStatus.value = 'reconnecting'
    statusLabel.value = '重连中'
  } else if (status === RUN_STATUS.WAITING_APPROVAL) {
    connectionStatus.value = 'waiting_approval'
    statusLabel.value = '等待审批'
  } else {
    connectionStatus.value = 'connecting'
    statusLabel.value = status === RUN_STATUS.CREATING ? '创建中' : '执行中'
  }
}

watch(() => activeRun.value?.status, applyRunStatus)
let bubbleBuffer = ''
let lastBubbleTime = 0

// 欢迎消息
messages.value.push({
  role: 'assistant',
  content: '你好！我是 VioManus 🤖，一个受治理的宝可梦领域智能体。\n\n当前训练家入口默认提供版本化事实、确定性计算和白名单搜索；文件写入、下载、网页抓取、终端等运营能力不会在公网入口默认开放，需单独授权并经过审批。\n\n例如你可以让我：「查询朱/紫规则下的配招建议，并说明引用来源」'
})

function splitBubble(chunk) {
  bubbleBuffer += chunk
  const now = Date.now()
  const minInterval = 800

  const endPunctuation = bubbleBuffer.match(/[。！？…\n]/)
  const isLengthThreshold = bubbleBuffer.length > 40

  if (endPunctuation || isLengthThreshold) {
    const idx = endPunctuation
      ? endPunctuation.index + 1
      : Math.min(bubbleBuffer.length, 40)

    const bubbleContent = bubbleBuffer.slice(0, idx).trim()
    bubbleBuffer = bubbleBuffer.slice(idx)

    if (bubbleContent) {
      if (now - lastBubbleTime < minInterval) {
        setTimeout(() => {
          messages.value.push({ role: 'assistant', content: bubbleContent })
          lastBubbleTime = Date.now()
        }, minInterval - (now - lastBubbleTime))
      } else {
        messages.value.push({ role: 'assistant', content: bubbleContent })
        lastBubbleTime = now
      }
    }
  }
}

function flushBuffer() {
  if (bubbleBuffer.trim()) {
    messages.value.push({ role: 'assistant', content: bubbleBuffer.trim() })
    bubbleBuffer = ''
  }
}

function handleStop() {
  if (activeRun.value?.runId && !TERMINAL_STATUSES.has(activeRun.value.status)) {
    handleCancelRun(activeRun.value.runId)
    return
  }
  if (currentSessionId) {
    stopManus(currentSessionId).catch(console.error)
  }
  if (currentEventSource) {
    currentEventSource.close()
    currentEventSource = null
  }
  flushBuffer()
  connectionStatus.value = 'done'
  statusLabel.value = '已停止'
  messages.value.push({
    role: 'assistant',
    content: '⏹ 任务已手动停止'
  })
}

async function handleSend(message) {
  messages.value.push({ role: 'user', content: message })
  const aiMessage = { role: 'assistant', content: '' }
  messages.value.push(aiMessage)

  if (modernRunEnabled) {
    try {
      activeRun.value = await runStream.startRun({
        prompt: message,
        conversationId: chatId.value,
        ...serializeRunContext({
          gameReleaseId: 'scarlet-violet',
          battleFormat: 'casual-singles'
        }),
        language: 'zh-CN',
        // 采用当前 fixture/registry 已登记的默认规则与事实版本；
        // 未登记的 sv-ou-v1 会让现代 Run 在入口直接失败。
        rulesetVersionId: 'casual-v1',
        dataReleaseId: 'pokemon-scarlet-violet-data-v1',
        idempotencyKey: makeRunIdempotencyKey('super-agent')
      })
      connectionStatus.value = 'connecting'
      statusLabel.value = '执行中'
      bindAnswerBubble(aiMessage)
      return
    } catch (error) {
      if (![404, 405, 501].includes(error.status)) {
        aiMessage.content = `创建 Run 失败：${error.message}`
        connectionStatus.value = 'done'
        statusLabel.value = '失败'
        return
      }
    }
  }

  // 旧 SSE 会自行按句子创建气泡，移除现代契约预留的空气泡。
  const placeholderIndex = messages.value.indexOf(aiMessage)
  if (placeholderIndex >= 0) messages.value.splice(placeholderIndex, 1)
  handleLegacySend(message)
}

function bindAnswerBubble(aiMessage) {
  stopAnswerWatch?.()
  stopAnswerWatch = watch(() => activeRun.value?.answer, value => {
    aiMessage.content = value || ''
    if (activeRun.value) applyRunStatus(activeRun.value.status)
  }, { immediate: true })
}

// 跨标签页/快速重试需要真正随机的幂等键；不把 prompt、token 等敏感数据
// 拼入键值，避免它们进入服务端审计或错误日志。
function makeRunIdempotencyKey(scope) {
  try {
    if (globalThis.crypto?.randomUUID) return `${scope}-${globalThis.crypto.randomUUID()}`
  } catch {
    // 旧浏览器没有 randomUUID 时退化为非敏感的随机后缀。
  }
  return `${scope}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function handleLegacySend(message) {
  if (legacyDoneTimer) clearInterval(legacyDoneTimer)
  bubbleBuffer = ''
  lastBubbleTime = 0
  connectionStatus.value = 'connecting'
  statusLabel.value = '思考中...'

  currentEventSource = chatWithManus(
    message,
    // onMessage
    (data) => {
      splitBubble(data)
      connectionStatus.value = 'connected'
      statusLabel.value = '执行中'
    },
    // onSession
    (sessionId) => {
      currentSessionId = sessionId
    },
    // onError
    () => {
      flushBuffer()
      connectionStatus.value = 'done'
      statusLabel.value = '已完成'
      currentSessionId = null
      if (messages.value.filter(m => m.role === 'assistant').length === 1) {
        messages.value.push({
          role: 'assistant',
          content: '⚠️ 连接断开，未收到回复。请检查后端日志排查错误。'
        })
      }
    }
  )

  legacyDoneTimer = setInterval(() => {
    if (currentEventSource && currentEventSource.readyState === 2) {
      clearInterval(legacyDoneTimer)
      legacyDoneTimer = null
      flushBuffer()
      connectionStatus.value = 'done'
      statusLabel.value = '已完成'
      currentSessionId = null
    }
  }, 200)
}

async function handleCancelRun(runId) {
  try {
    await runStream.cancelRun(runId)
    connectionStatus.value = 'done'
    statusLabel.value = '已停止'
  } catch (error) {
    if (activeRun.value) activeRun.value.error = error.message
  }
}

async function handleReconnectRun(runId) {
  await runStream.reconnect(runId)
  applyRunStatus(activeRun.value?.status)
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
    applyRunStatus(activeRun.value?.status)
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
    messages.value.push({ role: 'assistant', content: `恢复任务失败：${error.message}` })
  }
})

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
.super-agent-page {
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
  background: linear-gradient(135deg, #00ccff, #6633ff);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

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

.stop-btn {
  margin-left: auto;
  background: rgba(255, 77, 79, 0.15);
  color: #ff4d4f;
  padding: 6px 14px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  transition: all 0.2s;
}

.stop-btn:hover {
  background: rgba(255, 77, 79, 0.3);
  color: #ff7875;
}

.status-badge {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.4);
  padding: 4px 10px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.05);
}

.status-badge.connecting {
  color: #ffa940;
  background: rgba(255, 169, 64, 0.1);
}

.status-badge.connected {
  color: #52c41a;
  background: rgba(82, 196, 26, 0.1);
}

.status-badge.done {
  color: rgba(255, 255, 255, 0.3);
}
</style>
