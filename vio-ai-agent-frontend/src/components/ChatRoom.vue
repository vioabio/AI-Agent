<template>
  <div class="chat-room">
    <!-- 消息列表 -->
    <div
      class="message-list"
      ref="messageListRef"
      role="log"
      aria-label="聊天消息"
      aria-live="polite"
      aria-relevant="additions text"
    >
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
        <div class="message-content">
          <span v-if="msg.role === 'assistant'" class="message-author">VIO <small>宝可梦助手</small></span>
          <div
            class="message-bubble"
            :class="msg.role === 'user' ? 'user-bubble' : 'assistant-bubble'"
          >
            <MessageBody v-if="msg.role === 'assistant'" :content="msg.content" /><template v-else>{{ msg.content }}</template>
          </div>
          <div v-if="msg.role === 'assistant' && msg.content && index === messages.length - 1" class="response-actions"><button type="button" @click="$emit('resource-section','facts')"><UiIcon name="book" />查看图鉴</button><button type="button" @click="$emit('resource-section','teams')"><UiIcon name="team" />搭配建议</button><button type="button" @click="$emit('resource-section','guides')"><UiIcon name="search" />查找攻略</button></div>
          <time v-if="msg.createdAt" class="message-time" :datetime="msg.createdAt">{{ messageTime(msg.createdAt) }}</time>
          <div v-if="msg.content" class="message-actions">
            <button v-if="msg.role === 'user'" type="button" :disabled="isBusy" @click="editMessage(msg)"><UiIcon name="edit" />编辑消息</button>
            <button type="button" @click="copyMessage(msg)"><UiIcon name="copy" />复制消息</button>
          </div>
        </div>
        <!-- 用户头像占位 -->
        <div v-if="msg.role === 'user'" class="user-avatar">
          👤
        </div>
      </div>

      <slot name="suggestions" />
      <!-- 思考中动画 -->
      <div
        v-if="connectionStatus === 'connecting' || connectionStatus === 'reconnecting'"
        class="message-row assistant-row"
        role="status"
        aria-live="polite"
      >
        <AiAvatarFallback :aiType="aiType" />
        <div class="message-bubble assistant-bubble thinking-bubble">
          <span class="dot"></span>
          <span class="dot"></span>
          <span class="dot"></span>
        </div>
      </div>
    </div>

    <!-- v1.0 结构化 Run 回放；未传入 run 时完全兼容旧聊天页面。 -->
    <RunTimeline
      v-if="runViewModel"
      :run="runViewModel"
      @cancel="$emit('cancel-run', $event)"
      @reconnect="$emit('reconnect-run', $event)"
      @approve="$emit('approve-run', $event)"
      @feedback="$emit('feedback', $event)"
    />

    <!-- 输入区域 -->
    <p class="action-status" role="status">{{ actionStatus }}</p>
    <div v-if="isEditing" class="edit-notice">
      <span>正在编辑历史消息，发送后作为新消息，原消息保留。</span>
      <button type="button" :disabled="isBusy" @click="cancelEdit">取消编辑</button>
    </div>
    <div class="input-area">
      <textarea
        v-model="inputText"
        @keydown.enter.exact.prevent="doSend"
        placeholder="继续提问，或结合当前资料分析…"
        aria-label="输入消息"
        :disabled="isBusy"
        ref="inputRef"
        rows="2"
      ></textarea>
      <button
        class="send-btn"
        type="button"
        @click="doSend"
        :disabled="!inputText.trim() || isBusy"
      >
        <UiIcon name="send" /><span class="sr-only">发送</span>
      </button>
    </div>
    <p class="composer-hint">Enter 发送 · Shift + Enter 换行</p>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, computed } from 'vue'
import AiAvatarFallback from '@/components/AiAvatarFallback.vue'
import RunTimeline from '@/components/RunTimeline.vue'
import UiIcon from '@/components/UiIcon.vue'
import MessageBody from '@/components/MessageBody.vue'

const props = defineProps({
  messages: { type: Array, required: true },
  connectionStatus: { type: String, default: 'idle' },
  aiType: { type: String, default: 'game' }, // 'game' | 'super'
  runViewModel: { type: Object, default: null },
  draftRequest: { type: Object, default: null }
})

const emit = defineEmits(['send-message', 'cancel-run', 'reconnect-run', 'approve-run', 'feedback', 'resource-section'])
function messageTime(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})
}

const inputText = ref('')
const messageListRef = ref(null)
const inputRef = ref(null)
const isEditing = ref(false)
const actionStatus = ref('')
let savedDraft = ''
let copyRequest = 0
const busyStatuses = new Set(['creating', 'connecting', 'connected', 'reconnecting', 'waiting_approval'])
const isBusy = computed(() => busyStatuses.has(props.connectionStatus))

watch(() => props.draftRequest, request => {
  if (!request || isBusy.value) return
  inputText.value = request.text
  isEditing.value = false
  savedDraft = ''
  nextTick(() => inputRef.value?.focus())
})

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

function editMessage(message) {
  if (isBusy.value) return
  if (!isEditing.value) savedDraft = inputText.value
  isEditing.value = true
  inputText.value = message.content
  nextTick(() => inputRef.value?.focus())
}

function cancelEdit() {
  if (isBusy.value) return
  inputText.value = savedDraft
  savedDraft = ''
  isEditing.value = false
  nextTick(() => inputRef.value?.focus())
}

async function copyMessage(message) {
  const request = ++copyRequest
  actionStatus.value = ''
  try {
    await navigator.clipboard.writeText(message.content)
    if (request === copyRequest) actionStatus.value = '消息已复制'
  } catch {
    if (request === copyRequest) actionStatus.value = '复制失败，请手动选择消息文字复制。'
  }
}

function doSend() {
  const text = inputText.value.trim()
  if (!text || isBusy.value) return
  emit('send-message', text)
  isEditing.value = false
  savedDraft = ''
  inputText.value = ''
  nextTick(() => inputRef.value?.focus())
}
</script>
