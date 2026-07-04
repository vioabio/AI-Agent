<template>
  <div class="love-master-page">
    <header class="chat-header">
      <button class="back-btn" @click="$router.push('/')">← 返回</button>
      <h1>💕 AI 恋爱大师</h1>
      <span class="chat-id-badge">{{ chatId }}</span>
    </header>

    <ChatRoom
      :messages="messages"
      :connectionStatus="connectionStatus"
      aiType="love"
      @send-message="handleSend"
    />
  </div>
</template>

<script setup>
import { ref, onBeforeUnmount } from 'vue'
import ChatRoom from '@/components/ChatRoom.vue'
import { chatWithLoveApp } from '@/api'

const chatId = ref('love_' + Math.random().toString(36).slice(2, 10))
const messages = ref([])
const connectionStatus = ref('idle') // 'idle' | 'connecting' | 'done'
let currentEventSource = null

// 欢迎消息
messages.value.push({
  role: 'assistant',
  content: '你好！我是 AI 恋爱大师 💕，深耕恋爱心理领域。请告诉我你的恋爱状态（单身/恋爱中/已婚），以及你遇到的困惑，我会为你提供专属建议~'
})

function handleSend(message) {
  // 添加用户消息
  messages.value.push({ role: 'user', content: message })

  // 创建 AI 消息气泡（初始为空，SSE 增量填充）
  const aiMessage = { role: 'assistant', content: '' }
  messages.value.push(aiMessage)

  connectionStatus.value = 'connecting'

  currentEventSource = chatWithLoveApp(
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
  const checkDone = setInterval(() => {
    if (currentEventSource && currentEventSource.readyState === 2) {
      clearInterval(checkDone)
      connectionStatus.value = 'done'
    }
  }, 200)
}

// 页面卸载时关闭 SSE 连接
onBeforeUnmount(() => {
  if (currentEventSource) {
    currentEventSource.close()
  }
})
</script>

<style scoped>
.love-master-page {
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
  background: linear-gradient(135deg, #ff6b9d, #c44dff);
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