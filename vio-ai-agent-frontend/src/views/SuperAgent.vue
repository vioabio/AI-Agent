<template>
  <div class="super-agent-page">
    <header class="chat-header">
      <button class="back-btn" @click="$router.push('/')">← 返回</button>
      <h1>🤖 AI 超级智能体</h1>
      <span class="status-badge" :class="connectionStatus">
        {{ statusLabel }}
      </span>
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
import { ref, reactive, onBeforeUnmount } from 'vue'
import ChatRoom from '@/components/ChatRoom.vue'
import { chatWithManus } from '@/api'

const messages = ref([])
const connectionStatus = ref('idle')
const statusLabel = ref('就绪')
let currentEventSource = null
let bubbleBuffer = ''
let lastBubbleTime = 0

// 欢迎消息
messages.value.push({
  role: 'assistant',
  content: '你好！我是 VioManus 🤖，一个拥有自主规划能力的 AI 超级智能体。\n\n我可以搜索网络、读写文件、抓取网页、生成 PDF、执行终端命令——将复杂任务分解为多步骤并逐步完成。\n\n例如你可以让我：「搜索上海的约会地点，保存为文件，并生成一份 PDF 报告」'
})

/**
 * 智能拆分 SSE 流式数据为多个消息气泡
 *
 * 拆分规则：
 * 1. 遇到中文结束标点（。！？…）→ 拆分
 * 2. 缓冲内容超过 40 字 → 拆分
 * 3. 两次拆分间隔至少 800ms（模拟自然思考节奏）
 */
function splitBubble(chunk) {
  bubbleBuffer += chunk
  const now = Date.now()
  const minInterval = 800

  // 检查是否有结束标点
  const endPunctuation = bubbleBuffer.match(/[。！？…]/)
  const isLengthThreshold = bubbleBuffer.length > 40

  if (endPunctuation || isLengthThreshold) {
    const idx = endPunctuation
      ? endPunctuation.index + 1
      : Math.min(bubbleBuffer.length, 40)

    const bubbleContent = bubbleBuffer.slice(0, idx).trim()
    bubbleBuffer = bubbleBuffer.slice(idx)

    if (bubbleContent) {
      // 保证气泡间最小间隔
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

/**
 * 清空剩余缓冲（SSE 连接关闭后调用）
 */
function flushBuffer() {
  if (bubbleBuffer.trim()) {
    messages.value.push({ role: 'assistant', content: bubbleBuffer.trim() })
    bubbleBuffer = ''
  }
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
    // onError
    () => {
      flushBuffer()
      connectionStatus.value = 'done'
      statusLabel.value = '已完成'
    }
  )

  // 检测连接关闭
  const checkDone = setInterval(() => {
    if (currentEventSource && currentEventSource.readyState === 2) {
      clearInterval(checkDone)
      flushBuffer()
      connectionStatus.value = 'done'
      statusLabel.value = '已完成'
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

.status-badge {
  margin-left: auto;
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