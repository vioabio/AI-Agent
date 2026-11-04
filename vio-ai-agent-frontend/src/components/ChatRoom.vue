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
          <div v-if="msg.role === 'assistant' && msg.content" class="response-actions pokemon-message-cards">
            <button v-for="pokemon in messagePokemon(msg)" :key="pokemon.nationalNo" class="pokemon-message-card" type="button" :aria-label="`查看图鉴 · ${pokemon.nameZh}`" @click="$emit('resource-section','facts', String(pokemon.nationalNo))"><PokemonPortrait :form-id="pokemon.nationalNo" :name="pokemon.nameZh" /><span><small>#{{ String(pokemon.nationalNo).padStart(3, '0') }}</small><strong>{{ pokemon.nameZh }}</strong></span><span class="pokemon-card-link">{{ String(selectedPokemon?.nationalNo) === String(pokemon.nationalNo) ? '已在右侧打开' : '打开图鉴' }}<UiIcon name="arrow" /></span></button>
            <button v-if="!messagePokemon(msg).length && index === messages.length - 1" class="generic-dex-link" type="button" @click="$emit('resource-section','facts')"><UiIcon name="book" />查看图鉴</button>
          </div>
          <div
            class="message-bubble"
            :class="msg.role === 'user' ? 'user-bubble' : 'assistant-bubble'"
          >
            <MessageBody v-if="msg.role === 'assistant'" :content="msg.content" /><template v-else>{{ readableUserMessage(msg.content) }}</template>
          </div>
          <p v-if="trimmedHistory(msg)" class="message-context-notice" role="status">部分历史未参与本次回答；关键限制请在当前问题中重申。</p>
          <ModelUsageDetails v-if="(!compact || developerMode) && msg.role === 'assistant' && msg.metadata?.modelUsageAttempts && !isRunMessage(msg, index)" :attempts="msg.metadata.modelUsageAttempts" />
          <RunTimeline v-if="isRunMessage(msg, index)" :run="runViewModel" :compact="compact" :developer-mode="developerMode" @cancel="$emit('cancel-run', $event)" @reconnect="$emit('reconnect-run', $event)" @approve="$emit('approve-run', $event)" @feedback="$emit('feedback', $event)" />
          <time v-if="msg.createdAt" class="message-time" :datetime="msg.createdAt">{{ messageTime(msg.createdAt) }}</time>
          <div v-if="msg.content" class="message-actions" aria-label="消息操作">
            <button v-if="msg.role === 'user'" type="button" aria-label="编辑消息" title="编辑消息" :disabled="isBusy" @click="editMessage(msg)"><UiIcon name="edit" /><span>编辑消息</span></button>
            <button type="button" aria-label="复制消息" title="复制消息" @click="copyMessage(msg)"><UiIcon name="copy" /><span>复制消息</span></button>
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

    <div v-if="compact && runViewModel && ['creating','running','waiting_approval','reconnecting'].includes(runViewModel.status)" class="run-live-controls" role="status">
      <span>{{ statusLabel(runViewModel.status) }}</span>
      <button v-if="runViewModel.approval" type="button" @click="showRunDetails">查看修改预览</button>
      <button v-if="runViewModel.status === 'reconnecting'" type="button" @click="$emit('reconnect-run', runViewModel.runId)">继续连接</button>
      <button type="button" @click="$emit('cancel-run', runViewModel.runId)">停止任务</button>
    </div>
    <p v-if="compact && runViewModel?.error" class="run-live-error" role="alert">{{ runViewModel.error }}</p>
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
import { readableUserMessage } from '@/api/reference-prompts'
import { pokemonTargetsForMessage } from '@/api/pokemon-navigation'
import { ref, watch, nextTick, computed } from 'vue'
import AiAvatarFallback from '@/components/AiAvatarFallback.vue'
import RunTimeline from '@/components/RunTimeline.vue'
import ModelUsageDetails from '@/components/ModelUsageDetails.vue'
import UiIcon from '@/components/UiIcon.vue'
import MessageBody from '@/components/MessageBody.vue'
import PokemonPortrait from '@/components/PokemonPortrait.vue'
import { summarizeModelUsage } from '@/api/model-usage'
import { statusLabel } from '@/api/run-contract'

const props = defineProps({
  messages: { type: Array, required: true },
  connectionStatus: { type: String, default: 'idle' },
  aiType: { type: String, default: 'game' }, // 'game' | 'super'
  runViewModel: { type: Object, default: null },
  draftRequest: { type: Object, default: null },
  pokemonNames: { type: Array, default: () => [] },
  selectedPokemon: { type: Object, default: null },
  developerMode: Boolean,
  compact: Boolean
})

const emit = defineEmits(['send-message', 'cancel-run', 'reconnect-run', 'approve-run', 'feedback', 'resource-section'])
const targetCache = new WeakMap()
const messageTargets = computed(() => props.messages.map(msg => {
  const saved = targetCache.get(msg)
  if (saved?.content === msg.content && saved.names === props.pokemonNames) return saved.targets
  const targets = pokemonTargetsForMessage(msg.content, props.pokemonNames)
  targetCache.set(msg, { content: msg.content, names: props.pokemonNames, targets })
  return targets
}))
const runMessageIndex = computed(() => {
  if (!props.runViewModel) return -1
  const exact = props.messages.findIndex(msg => msg.role === 'assistant' && msg.runId === props.runViewModel.runId)
  return exact >= 0 ? exact : props.messages.findLastIndex(msg => msg.role === 'assistant')
})
function isRunMessage(msg, index) { return msg.role === 'assistant' && index === runMessageIndex.value }
function showRunDetails() { messageListRef.value?.querySelector('.approval-card')?.scrollIntoView({ block: 'start', behavior: 'auto' }) }
function trimmedHistory(msg) {
  return props.compact && msg.role === 'assistant' && !props.developerMode && summarizeModelUsage(msg.metadata?.modelUsageAttempts || [])?.attempts.some(attempt => attempt.contextBudget?.omittedHistoryTurns > 0)
}
function messagePokemon(msg) { return messageTargets.value[props.messages.indexOf(msg)] || [] }
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
  inputText.value = readableUserMessage(message.content)
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
    await navigator.clipboard.writeText(message.role === 'user' ? readableUserMessage(message.content) : message.content)
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
