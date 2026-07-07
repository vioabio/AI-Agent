<template>
  <div class="super-agent-page">
    <header class="chat-header">
      <button class="back-btn" @click="$router.push('/')">← 返回</button>
      <h1>🤖 AI 超级智能体</h1>
      <span class="status-badge" :class="connectionStatus">
        {{ statusLabel }}
      </span>
      <button
        v-if="connectionStatus === 'connecting' || connectionStatus === 'connected'"
        class="stop-btn"
        @click="handleStop"
      >
        ⏹ 停止
      </button>
    </header>

    <ChatRoom
      :messages="messages"
      :connectionStatus="connectionStatus"
      aiType="super"
      @send-message="handleSend"
    />
  </div>
</template>

<script setup>
import { ref, onBeforeUnmount } from 'vue'
import ChatRoom from '@/components/ChatRoom.vue'
import { chatWithManus, stopManus } from '@/api'

const messages = ref([])
const connectionStatus = ref('idle')
const statusLabel = ref('就绪')
let currentEventSource = null
let currentSessionId = null
let bubbleBuffer = ''
let lastBubbleTime = 0

// 欢迎消息
messages.value.push({
  role: 'assistant',
  content: '你好！我是 VioManus 🤖，一个拥有自主规划能力的 AI 超级智能体。\n\n我可以搜索网络、读写文件、抓取网页、生成 PDF、执行终端命令——将复杂任务分解为多步骤并逐步完成。\n\n例如你可以让我：「搜索上海的约会地点，保存为文件，并生成一份 PDF 报告」'
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

function handleSend(message) {
  messages.value.push({ role: 'user', content: message })
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

  const checkDone = setInterval(() => {
    if (currentEventSource && currentEventSource.readyState === 2) {
      clearInterval(checkDone)
      flushBuffer()
      connectionStatus.value = 'done'
      statusLabel.value = '已完成'
      currentSessionId = null
    }
  }, 200)
}

onBeforeUnmount(() => {
  if (currentEventSource) {
    currentEventSource.close()
  }
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
