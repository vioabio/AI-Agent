<template>
  <div class="chat-room">
    <!-- 消息列表 -->
    <div class="message-list" ref="messageListRef">
      <div
        v-for="(msg, index) in messages"
        :key="index"
        class="message-row"
        :class="msg.role === 'user' ? 'user-row' : 'assistant-row'"
      >
        <!-- AI 头像 -->
        <AiAvatarFallback
          v-if="msg.role === 'assistant'"
          :aiType="aiType"
        />
        <!-- 消息气泡 -->
        <div
          class="message-bubble"
          :class="msg.role === 'user' ? 'user-bubble' : 'assistant-bubble'"
        >
          {{ msg.content }}
        </div>
        <!-- 用户头像占位 -->
        <div v-if="msg.role === 'user'" class="user-avatar">
          👤
        </div>
      </div>

      <!-- 思考中动画 -->
      <div v-if="connectionStatus === 'connecting'" class="message-row assistant-row">
        <AiAvatarFallback :aiType="aiType" />
        <div class="message-bubble assistant-bubble thinking-bubble">
          <span class="dot"></span>
          <span class="dot"></span>
          <span class="dot"></span>
        </div>
      </div>
    </div>

    <!-- 输入区域 -->
    <div class="input-area">
      <textarea
        v-model="inputText"
        @keydown.enter.exact.prevent="doSend"
        placeholder="输入你的消息，Enter 发送..."
        :disabled="connectionStatus === 'connecting'"
        ref="inputRef"
        rows="2"
      ></textarea>
      <button
        class="send-btn"
        @click="doSend"
        :disabled="!inputText.trim() || connectionStatus === 'connecting'"
      >
        发送
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import AiAvatarFallback from '@/components/AiAvatarFallback.vue'

const props = defineProps({
  messages: { type: Array, required: true },
  connectionStatus: { type: String, default: 'idle' },
  aiType: { type: String, default: 'game' } // 'game' | 'super'
})

const emit = defineEmits(['send-message'])

const inputText = ref('')
const messageListRef = ref(null)
const inputRef = ref(null)

// 新消息自动滚动到底部
watch(
  () => props.messages.length,
  () => {
    nextTick(() => {
      if (messageListRef.value) {
        messageListRef.value.scrollTop = messageListRef.value.scrollHeight
      }
    })
  }
)

// 思考中状态也滚动
watch(
  () => props.connectionStatus,
  (val) => {
    if (val === 'connecting') {
      nextTick(() => {
        if (messageListRef.value) {
          messageListRef.value.scrollTop = messageListRef.value.scrollHeight
        }
      })
    }
  }
)

function doSend() {
  const text = inputText.value.trim()
  if (!text || props.connectionStatus === 'connecting') return
  emit('send-message', text)
  inputText.value = ''
  nextTick(() => inputRef.value?.focus())
}
</script>

<style scoped>
.chat-room {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ===== 消息列表 ===== */
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 24px 20px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.message-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  max-width: 85%;
}

.assistant-row {
  align-self: flex-start;
}

.user-row {
  align-self: flex-end;
  flex-direction: row-reverse;
}

/* ===== 消息气泡 ===== */
.message-bubble {
  padding: 12px 18px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
  white-space: pre-wrap;
}

.assistant-bubble {
  background: rgba(255, 255, 255, 0.06);
  color: #e0e0e0;
  border-top-left-radius: 4px;
}

.user-bubble {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  border-top-right-radius: 4px;
}

/* ===== 用户头像 ===== */
.user-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  background: rgba(255, 255, 255, 0.1);
  flex-shrink: 0;
}

/* ===== 思考中动画 ===== */
.thinking-bubble {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 14px 20px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.3);
  animation: dotPulse 1.4s infinite ease-in-out both;
}

.dot:nth-child(1) { animation-delay: -0.32s; }
.dot:nth-child(2) { animation-delay: -0.16s; }
.dot:nth-child(3) { animation-delay: 0s; }

@keyframes dotPulse {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

/* ===== 输入区域 ===== */
.input-area {
  display: flex;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(0, 0, 0, 0.2);
  flex-shrink: 0;
}

.input-area textarea {
  flex: 1;
  resize: none;
  padding: 12px 16px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.05);
  color: #e0e0e0;
  font-size: 14px;
  line-height: 1.5;
  transition: border-color 0.2s;
}

.input-area textarea:focus {
  border-color: rgba(255, 255, 255, 0.25);
}

.input-area textarea:disabled {
  opacity: 0.5;
}

.send-btn {
  padding: 0 24px;
  border-radius: 12px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  transition: all 0.2s;
}

.send-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 16px rgba(102, 126, 234, 0.3);
}

.send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>