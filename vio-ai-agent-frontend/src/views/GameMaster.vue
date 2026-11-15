<template>
  <div class="game-master-page workspace-shell reading-workspace" :class="{ 'team-mode': workspaceMode === 'team', 'nav-collapsed': navCollapsed, 'drawer-open': resourcesOpen && !wideViewport }" :style="{ '--resource-width': resourceWidth + 'px' }" :inert="settingsOpen || undefined">
    <button v-if="mobileNavOpen" class="nav-scrim" aria-label="关闭历史菜单" @click="mobileNavOpen = false"></button>
    <aside class="workspace-nav" :class="{ 'mobile-open': mobileNavOpen }" aria-label="工作区导航">
      <RouterLink to="/" class="workspace-brand" title="VIO 宝可梦助手"><VioMark /><span><strong>VIO</strong></span></RouterLink>
      <button class="new-chat" type="button" title="新对话" aria-label="新对话" :disabled="workspaceBusy || historyLoading" @click="newConversation"><UiIcon name="plus" /><span>新对话</span></button>
      <button class="workspace-entry" type="button" title="对话" aria-label="助手工作区" @click="mobileNavOpen = false"><UiIcon name="chat" /><span>对话</span></button>
      <button class="compact-history" type="button" title="历史对话" aria-label="历史对话" @click="openResourceSection('history')"><UiIcon name="history" /></button>
      <div class="recent-heading"><span>最近对话</span><button type="button" aria-label="刷新会话历史" @click="workbenchRef?.refreshHistory()"><UiIcon name="history" /></button></div>
      <div class="recent-conversations">
        <p v-if="sidebarHistory.status === 'loading'" class="sidebar-empty">正在读取历史…</p>
        <div v-else-if="['error','permission'].includes(sidebarHistory.status)" class="sidebar-empty">历史暂不可用<button type="button" @click="workbenchRef?.refreshHistory()">重试</button></div>
        <p v-else-if="!sidebarHistory.items.length" class="sidebar-empty">从一个问题开始。</p>
        <section v-for="group in conversationGroups" :key="group.label" class="history-date-group">
          <h2>{{ group.label }}</h2>
          <button v-for="conversation in group.items" :key="conversation.id" class="recent-item" :class="{ selected: conversation.id === chatId }" type="button" :title="conversation.title || '未命名对话'" :disabled="workspaceBusy || historyLoading" @click="openRecentConversation(conversation)"><span>{{ conversation.title || '未命名对话' }}</span></button>
        </section>
        <button v-if="sidebarHistory.items.length" class="all-history" type="button" @click="openResourceSection('history')">查看全部历史<UiIcon name="arrow" /></button>
      </div>
      <div class="nav-footer"><button type="button" title="收藏" aria-label="收藏" @click="openResourceSection('favorites')"><UiIcon name="star" /><span>收藏</span></button><button type="button" title="工作区设置" aria-label="工作区设置" @click="openSettings"><UiIcon name="settings" /><span>设置</span></button></div>
      <div class="nav-account"><LocalAccountMenu v-if="authState.snapshot?.mode === 'local'" /><div v-else class="workspace-account"><template v-if="authState.snapshot?.mode === 'oidc'"><span>{{ authState.snapshot.principalId }}</span><button type="button" @click="signOut">退出登录</button></template><small v-else>{{ authState.snapshot?.mode === 'api_key' ? '受限演示' : '本地工作区' }}</small></div></div>
    </aside>

    <main class="workspace-main">
      <header class="workspace-header">
        <button class="navigation-toggle" type="button" :aria-expanded="smallViewport ? mobileNavOpen : !navCollapsed" :aria-label="smallViewport ? '打开历史菜单' : navCollapsed ? '展开导航' : '收起导航'" @click="toggleNavigation"><UiIcon :name="navCollapsed || smallViewport ? 'menu' : 'collapse'" /></button>
        <h1 :title="conversationTitle">{{ conversationTitle }}</h1>
        <section class="scenario-bar" aria-label="回答场景">
          <label title="游戏版本"><UiIcon name="pokeball" /><span>游戏</span><select v-model="scenario.gameReleaseId" aria-label="游戏/资料版本" :disabled="workspaceBusy"><option value="pokemon-scarlet-violet">朱／紫</option></select></label>
          <label title="对战规则"><UiIcon name="team" /><span>规则</span><select v-model="scenario.battleFormat" aria-label="赛制" :disabled="workspaceBusy"><option v-for="option in battleFormatOptions" :key="option.alias" :value="option.alias">{{ option.label }}</option></select></label>
        </section>
        <ConversationPreferences compact :conversation-id="chatId" :disabled="workspaceBusy || historyLoading || !!historyError" @busy="preferencesBusy = $event" />
        <button class="resource-toggle" type="button" :aria-expanded="resourcesOpen" aria-controls="workspace-resources" @click="toggleResources"><UiIcon name="book" /><span>{{ resourcesOpen ? '收起资料' : '打开资料' }}</span><span class="resource-switch" :class="{ on: resourcesOpen }"></span></button>
      </header>
      <p v-if="workspaceNotice && workspaceWarning" class="workspace-notice" role="status">{{ workspaceNotice }}</p>
      <p v-if="referenceRelease && scenario.dataReleaseId !== referenceRelease" class="workspace-notice">当前会话使用 {{ scenario.dataReleaseId }}。<button type="button" :disabled="workspaceBusy || historyLoading" @click="newReferenceConversation">使用本地参考资料开启新对话</button></p>
      <p v-if="historyError" class="workspace-notice notice-error" role="alert">{{ historyError }} 请点击“新对话”或刷新重试。</p>
      <div class="workspace-body" :class="{ 'resources-hidden': !resourcesOpen }">
        <section class="conversation-pane" :aria-label="workspaceMode === 'team' ? 'AI 搭配建议' : '助手对话'" :inert="resourcesOpen && !wideViewport || mobileNavOpen || undefined">
          <header v-if="workspaceMode === 'team'" class="conversation-pane-heading"><h2>AI 搭配建议</h2><button type="button" @click="openResourceSection('facts')">返回图鉴</button></header>
          <ChatRoom :messages="messages" :connectionStatus="historyLoading || historyError ? 'creating' : connectionStatus" :draft-request="draftRequest" :pokemon-names="pokemonNames" :selected-pokemon="resourcesOpen && resourceSection === 'facts' ? selectedPokemon : null" :developer-mode="developerMode" compact :runViewModel="activeRun" aiType="game" @send-message="handleSend" @cancel-run="handleCancelRun" @reconnect-run="handleReconnectRun" @approve-run="handleApproveRun" @feedback="handleFeedback" @resource-section="openResourceSection" @import-training-draft="importTrainingDraft"><template #suggestions><div v-if="workspaceMode === 'team'" class="team-advice-actions"><button type="button" :disabled="workspaceBusy || historyLoading" @click="workbenchRef?.analyzeCurrentTeam()"><UiIcon name="team" />分析当前队伍</button></div></template></ChatRoom>
        </section>
        <div v-if="resourcesOpen && wideViewport && workspaceMode !== 'team'" class="resource-resizer" role="separator" tabindex="0" aria-label="调整资料面板宽度" aria-orientation="vertical" :aria-valuenow="resourceWidth" aria-valuemin="320" :aria-valuemax="maxResourceWidth" @pointerdown="startResize" @pointermove="resizePanel" @pointerup="stopResize" @pointercancel="stopResize" @keydown.left.prevent="changeResourceWidth(20)" @keydown.right.prevent="changeResourceWidth(-20)"><span>⋮</span></div>
        <button v-if="resourcesOpen && !wideViewport" class="resource-scrim" aria-label="关闭资料抽屉" @click="closeResources"></button>
        <aside v-if="resourcesMounted" v-show="resourcesOpen" id="workspace-resources" ref="resourceElement" class="resource-sidebar" :role="wideViewport ? undefined : 'dialog'" :aria-modal="!wideViewport || undefined" aria-label="资料与队伍侧栏" tabindex="-1" @keydown.esc.stop="closeResources" @keydown.tab="trapDrawerFocus">
          <div class="resource-sidebar-heading"><h2><UiIcon name="book" />{{ resourceSectionTitle }}</h2><button class="close-resources" type="button" aria-label="关闭资料侧栏" @click="closeResources"><UiIcon name="close" /></button></div>
          <PokemonWorkbench ref="workbenchRef" embedded :scenario="scenario" :pokemon-names="pokemonNames" :workspace-mode="workspaceMode" :busy="workspaceBusy || historyLoading || !!historyError" @ask="prepareQuestion" @continue-conversation="resumeHistory" @history-change="sidebarHistory = $event" @mode-change="workspaceMode = $event" @section-change="resourceSection = $event" @fact-selected="selectedPokemon = $event" />
        </aside>
      </div>
    </main>
  </div>
  <Teleport to="body"><div v-if="settingsOpen" class="workspace-settings-overlay" @click.self="closeSettings"><section ref="settingsElement" class="workspace-settings-dialog" role="dialog" aria-modal="true" aria-labelledby="workspace-settings-title" tabindex="-1" @keydown.esc="closeSettings" @keydown.tab="trapSettingsFocus"><header><h2 id="workspace-settings-title">工作区设置</h2><button type="button" aria-label="关闭工作区设置" @click="closeSettings"><UiIcon name="close" /></button></header><label>回答语言<select v-model="scenario.language" aria-label="语言" :disabled="workspaceBusy"><option value="zh-CN">简体中文</option><option value="en-US">English</option><option value="ja-JP">日本語</option></select></label><label class="developer-setting"><span><strong>开发者模式</strong><small>显示模型用量、执行过程与调试标识</small></span><input v-model="developerMode" type="checkbox" role="switch" aria-label="开发者模式" /></label><details><summary>资料版本与高级设置</summary><p>本地参考资料不代表排位合法性；修改版本前请先保存队伍。</p><label>规则版本<input v-model="scenario.rulesetVersionId" maxlength="64" aria-label="规则版本" :disabled="workspaceBusy" /></label><label>数据版本<input v-model="scenario.dataReleaseId" maxlength="128" aria-label="数据版本" :disabled="workspaceBusy" /></label><small v-if="developerMode">会话 {{ chatId }}</small></details></section></div></Teleport>
</template>

<script setup>
import { ref, onBeforeUnmount, onMounted, watch, reactive, computed, nextTick } from 'vue'
import ChatRoom from '@/components/ChatRoom.vue'
import ConversationPreferences from '@/components/ConversationPreferences.vue'
import LocalAccountMenu from '@/components/LocalAccountMenu.vue'
import PokemonWorkbench from '@/views/PokemonWorkbench.vue'
import VioMark from '@/components/VioMark.vue'
import UiIcon from '@/components/UiIcon.vue'
import { readLayoutPreference, saveLayoutPreference, groupConversations, trapFocusWithin } from '@/api/workspace-layout'
import { readableUserMessage } from '@/api/reference-prompts'
import { useRoute, useRouter } from 'vue-router'
import { chatWithGameApp, decideRunApproval, decideTeamChange, submitAnswerFeedback, fetchConversationMessages, fetchPokemonReferenceStatus, fetchPokemonNames, fetchRunTeamChange } from '@/api'
import { BATTLE_FORMAT_OPTIONS, serializeRunContext, conversationContextFromHistory } from '@/api/version-registry'
import { useRunStream } from '@/composables/useRunStream'
import { getOrCreateConversationId, selectConversationId } from '@/composables/runPersistence'
import { RUN_STATUS, TERMINAL_STATUSES, answerDisplayText, canSubmitRunApproval } from '@/api/run-contract'
import { useAuthSession } from '@/composables/useAuthSession'
const { state: authState, signOut } = useAuthSession()

const route = useRoute()
const router = useRouter()
const requestedConversation = typeof route.query.conversation === 'string' && /^[a-zA-Z0-9._:-]{1,128}$/.test(route.query.conversation)
  ? route.query.conversation : null
const conversationScope = route.query.scope === 'super-agent' ? 'super-agent' : 'game'
const chatId = ref(requestedConversation || getOrCreateConversationId(conversationScope))
const desktopMedia = window.matchMedia('(min-width: 1280px)')
const pinnedNavMedia = window.matchMedia('(min-width: 768px)')
const wideViewport = ref(desktopMedia.matches)
const smallViewport = ref(!pinnedNavMedia.matches)
const navCollapsed = ref(readLayoutPreference('nav-collapsed', !desktopMedia.matches))
const developerMode = ref(readLayoutPreference('developer-mode', false))
const resourceWidth = ref(Math.max(320, Math.min(520, readLayoutPreference('resource-width', 360))))
const viewportWidth = ref(window.innerWidth)
const maxResourceWidth = computed(() => Math.max(320, Math.min(560, viewportWidth.value - (navCollapsed.value ? 64 : 192) - 500)))
const resourcesOpen = ref(route.query.panel === 'resources' || desktopMedia.matches)
const resourcesMounted = ref(true)
const workspaceMode = ref('conversation')
const workbenchRef = ref(null)
const pokemonNames = ref([])
const mobileNavOpen = ref(false)
const resourceElement = ref(null)
const settingsElement = ref(null)
const selectedPokemon = ref(null)
const resourceSection = ref('facts')
const resourceSectionTitle = computed(() => ({ facts: '图鉴', teams: '队伍配置', calculations: '伤害计算', guides: '攻略检索', history: '历史对话', favorites: '收藏', activities: '活动' }[resourceSection.value] || '资料'))
let settingsTrigger, resourceTrigger, resizeOrigin
function syncWorkspaceViewport(event) {
  wideViewport.value = event.matches
  resourcesOpen.value = event.matches || workspaceMode.value === 'team'
  navCollapsed.value = !event.matches
  mobileNavOpen.value = false
}
function syncPinnedNavigation() {
  smallViewport.value = !pinnedNavMedia.matches
  mobileNavOpen.value = false
}
desktopMedia.addEventListener('change', syncWorkspaceViewport)
pinnedNavMedia.addEventListener('change', syncPinnedNavigation)
function syncWindowWidth() { viewportWidth.value = window.innerWidth; resourceWidth.value = Math.min(resourceWidth.value, maxResourceWidth.value) }
window.addEventListener('resize', syncWindowWidth)
watch(navCollapsed, value => { saveLayoutPreference('nav-collapsed', value); syncWindowWidth() })
watch(developerMode, value => saveLayoutPreference('developer-mode', value))
watch(resourceWidth, value => saveLayoutPreference('resource-width', value))
function toggleNavigation() { if (smallViewport.value) mobileNavOpen.value = !mobileNavOpen.value; else navCollapsed.value = !navCollapsed.value }
function changeResourceWidth(delta) { resourceWidth.value = Math.max(320, Math.min(maxResourceWidth.value, resourceWidth.value + delta)) }
function startResize(event) { if (event.button !== 0) return; event.preventDefault(); event.currentTarget.setPointerCapture(event.pointerId); resizeOrigin = { x: event.clientX, width: resourceWidth.value } }
function resizePanel(event) { if (resizeOrigin) resourceWidth.value = Math.max(320, Math.min(maxResourceWidth.value, resizeOrigin.width + resizeOrigin.x - event.clientX)) }
function stopResize() { resizeOrigin = null }
async function openSettings() { settingsTrigger = document.activeElement; settingsOpen.value = true; await nextTick(); settingsElement.value?.focus() }
function closeSettings() { settingsOpen.value = false; nextTick(() => settingsTrigger?.focus?.()) }
function trapSettingsFocus(event) { trapFocusWithin(event, settingsElement.value) }
function trapDrawerFocus(event) { if (!wideViewport.value) trapFocusWithin(event, resourceElement.value) }
function closeResources() { resourcesOpen.value = false; nextTick(() => resourceTrigger?.focus?.()) }
function toggleResources() { if (resourcesOpen.value) closeResources(); else { resourceTrigger = document.activeElement; resourcesOpen.value = true } }
watch([resourcesOpen, wideViewport], async ([open, wide]) => { if (open && !wide) { await nextTick(); resourceElement.value?.focus() } })

const settingsOpen = ref(false)
const sidebarHistory = ref({ items: [], status: 'loading' })
const conversationGroups = computed(() => groupConversations(sidebarHistory.value.items))
const conversationTitle = computed(() => sidebarHistory.value.items.find(item => item.id === chatId.value)?.title
  || readableUserMessage(messages.value.find(item => item.role === 'user')?.content).split('\n')[0].slice(0, 42) || '新对话')
watch(resourcesOpen, open => { if (open) resourcesMounted.value = true })

async function openResourceSection(section, pokemonQuery) {
  resourceTrigger = document.activeElement
  resourcesOpen.value = true
  mobileNavOpen.value = false
  workspaceMode.value = 'conversation'
  await nextTick()
  if (section === 'facts' && pokemonQuery) await workbenchRef.value?.openPokemon(pokemonQuery)
  else workbenchRef.value?.showSection(section)
}
async function importTrainingDraft(block) {
  if (workspaceBusy.value || historyLoading.value || historyError.value) return
  resourcesOpen.value = true
  mobileNavOpen.value = false
  await nextTick()
  await workbenchRef.value?.importTrainingDraft(block)
}
function openRecentConversation(conversation) {
  if (workspaceBusy.value || historyLoading.value) return
  mobileNavOpen.value = false
  workbenchRef.value?.resumeConversation(conversation)
}
const draftRequest = ref(null)
const workspaceNotice = ref(requestedConversation ? '请确认当前会话的版本和赛制后继续。' : '')
const workspaceWarning = computed(() => /未启用|无法连接|失败/.test(workspaceNotice.value))
const historyLoading = ref(true)
const historyError = ref('')
const referenceRelease = ref('')
const preferencesBusy = ref(false)
const workspaceBusy = computed(() => preferencesBusy.value || ['creating', 'connecting', 'connected', 'reconnecting', 'waiting_approval'].includes(connectionStatus.value))
const messages = ref([])
const connectionStatus = ref('idle') // 'idle' | 'connecting' | 'done'
let currentEventSource = null
let legacyDoneTimer = null
const activeRun = ref(null)
const teamApprovalRuns = new Set()
watch(() => activeRun.value?.approval, approval => {
  if (approval?.teamChange && activeRun.value?.runId) teamApprovalRuns.add(activeRun.value.runId)
})
const modernRunEnabled = import.meta.env.VITE_V1_RUNS_ENABLED !== 'false'
const runStream = useRunStream()
let stopAnswerWatch = null
const historyController = new AbortController()
let userHasSent = false
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
    const runId = activeRun.value.runId
    if (teamApprovalRuns.has(runId)) {
      teamApprovalRuns.delete(runId)
      fetchRunTeamChange(runId).then(result => {
        if (activeRun.value?.runId === runId && result.status === 'APPLIED') return workbenchRef.value?.refreshConfirmedTeam(result.savedTeam)
        if (activeRun.value?.runId === runId && result.message) activeRun.value.error = result.message
      }).catch(error => { if (activeRun.value?.runId === runId) activeRun.value.error = `请手动回读队伍核对保存结果：${error.message}` })
    }
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
  welcome: true,
  content: '你好，我是 VIO。告诉我你想了解的宝可梦，或从右侧图鉴开始，一起完善你的队伍。'
})

async function handleSend(message) {
  if (historyLoading.value || historyError.value || workspaceBusy.value) return
  connectionStatus.value = 'creating'
  userHasSent = true
  messages.value = messages.value.filter(item => !item.welcome)
  // 添加用户消息
  messages.value.push({ role: 'user', content: message })

  // 创建 AI 消息气泡（初始为空，SSE 增量填充）
  const aiMessage = reactive({ role: 'assistant', content: '' })
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
  stopAnswerWatch = watch(() => [activeRun.value?.answer, activeRun.value?.status, activeRun.value?.modelUsageAttempts], () => {
    aiMessage.content = answerDisplayText(activeRun.value)
    if(activeRun.value) {
      aiMessage.runId=activeRun.value.runId
      if(activeRun.value.modelUsageAttempts?.length)aiMessage.metadata={ ...aiMessage.metadata, modelUsageAttempts: activeRun.value.modelUsageAttempts }
    }
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
  if (!activeRun.value?.runId || !canSubmitRunApproval(activeRun.value, approval)) return
  if (approval.teamChange && activeRun.value.teamDecisionState?.approvalId === approval.approvalId) return
  const runId = activeRun.value.runId
  activeRun.value.approvalSubmitting = true
  try {
    const response = approval.teamChange ? await decideTeamChange(runId,approval.approvalId,approval.approved !== false) : await decideRunApproval(approval.approvalId, {
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
    if (approval.teamChange && activeRun.value?.runId === runId) {
      activeRun.value.teamDecisionState = { approvalId: approval.approvalId,
        message: '决定已提交，正在核对任务结果；尚未确认保存。' }
      activeRun.value.approvalSubmitting = false
    }
    // 不在 HTTP 决策成功后提前清空审批卡：只有重放到
    // approval.resolved/run.failed 事件，才能确认 Run 已收敛。若随后网络
    // 断开，保留卡片；已提交的队伍决定禁止重复发送，改为刷新状态。
    // 拒绝也会产生 approval.resolved/run.failed 终态事件；恢复消费才能
    // 让时间线从 waiting_approval 正确收敛到最终状态。
    await runStream.reconnect(runId)
  } catch (error) {
    if (activeRun.value?.runId === runId) {
      activeRun.value.error = error.message
      if (approval.teamChange && [404, 409, 410].includes(Number(error.status))) {
        activeRun.value.teamDecisionState = { approvalId: approval.approvalId,
          message: Number(error.status) === 410 ? '预览已过期，请重新发起修改任务。'
            : '此预览已失效或决定已处理，请刷新任务状态并回读队伍核对。' }
        activeRun.value.approvalSubmitting = false
        // A failed decision request is not a Run terminal event or proof of a saved team.
        try { await runStream.reconnect(runId) } catch { /* Keep the card and manual refresh. */ }
      }
    }
  } finally {
    if (activeRun.value?.runId === runId) activeRun.value.approvalSubmitting = false
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

function applyContext(value) {
  if (!value) return
  for (const field of ['gameReleaseId', 'language', 'rulesetVersionId', 'dataReleaseId', 'battleFormat']) {
    if (typeof value[field] === 'string' && value[field]) scenario[field] = value[field]
  }
}

function prepareQuestion({ prompt, context }) {
  if (workspaceBusy.value || historyLoading.value || historyError.value) return
  applyContext(context)
  draftRequest.value = { text: prompt, nonce: Date.now() + Math.random() }
  workspaceNotice.value = '资料已带入问题，请确认版本、赛制和内容后发送。'
  if (!desktopMedia.matches) {
    resourcesOpen.value = false
    workspaceMode.value = 'conversation'
  }
}

function resumeHistory({ conversationId, context }) {
  if (workspaceBusy.value || historyLoading.value) return
  const query = { conversation: conversationId }
  // Only opaque identifiers and version selections enter the URL; no prompts or answers.
  if (context) for (const field of ['gameReleaseId', 'language', 'rulesetVersionId', 'dataReleaseId', 'battleFormat']) {
    if (typeof context[field] === 'string') query[field] = context[field]
  }
  router.push({ path: '/game-master', query })
}

function newConversation() {
  if (workspaceBusy.value || historyLoading.value) return
  router.push({ path: '/game-master', query: { conversation: `game_${crypto.randomUUID()}` } })
}

function newReferenceConversation() {
  if (workspaceBusy.value || historyLoading.value || !referenceRelease.value) return
  router.push({ path: '/game-master', query: {
    conversation: `game_${crypto.randomUUID()}`, dataReleaseId: referenceRelease.value, panel: 'resources'
  } })
}

applyContext(route.query)

onMounted(async () => {
  fetchPokemonNames({ signal: historyController.signal }).then(names => {
    if (!historyController.signal.aborted && Array.isArray(names)) pokemonNames.value = names
  }).catch(() => { /* Exact name/number lookup remains available if the index is unavailable. */ })
  let hasStoredUser = false
  try {
    const stored = await fetchConversationMessages(chatId.value, { signal: historyController.signal })
    if (!historyController.signal.aborted) applyContext({ ...conversationContextFromHistory(stored), ...route.query })
    if (!historyController.signal.aborted && messages.value.length === 1 && Array.isArray(stored)) {
      const history = stored.filter(m => ['user', 'assistant'].includes(m.role) && typeof m.content === 'string')
      if (history.length) messages.value.splice(0, 1, ...history)
    }
    hasStoredUser = Array.isArray(stored) && stored.some(m => m.role === 'user')
  } catch (error) {
    if (requestedConversation && error.status !== 404 && !historyController.signal.aborted) {
      historyError.value = '历史消息读取失败，暂不能在此会话发送消息。'
    }
  }
    if (!historyController.signal.aborted) {
      try {
        const reference = await fetchPokemonReferenceStatus({ signal: historyController.signal })
        if (!historyController.signal.aborted && reference.enabled && reference.status === 'LOCAL_REFERENCE') {
          referenceRelease.value = reference.dataReleaseId
          if (!route.query.dataReleaseId && !hasStoredUser && !historyError.value) {
            scenario.dataReleaseId = reference.dataReleaseId
            workspaceNotice.value = '已启用 PokéAPI 本地参考资料；首次查询需联网，缓存后可离线读取，不代表排位合法性。'
          }
        }
      } catch (error) {
        if (!historyController.signal.aborted) workspaceNotice.value = Number(error.status) === 404
          ? '当前后端未启用本地资料接口，图鉴可能无法查询。请更新并重启后端，启用 local 配置；若显式关闭过资料功能，请设置 VIO_POKEMON_REFERENCE_ENABLED=true。'
          : '本地资料服务暂时无法连接，请检查后端后重试；当前会话版本保持不变。'
      }
    }
  historyLoading.value = false
  if (!historyController.signal.aborted && !historyError.value) selectConversationId(conversationScope, chatId.value)
  if (historyController.signal.aborted || userHasSent) return
  try {
    const restored = await runStream.restoreConversation(chatId.value)
    if (!restored) return
    activeRun.value = restored
    const aiMessage = messages.value.find(m => m.role === 'assistant' && m.runId === restored.runId) || reactive({
      role: 'assistant',
      runId: restored.runId,
      content: restored.answer || '已恢复上次未完成的任务，正在继续接收事件…'
    })
    if (!messages.value.includes(aiMessage)) messages.value.push(aiMessage)
    bindAnswerBubble(aiMessage)
  } catch (error) {
    // 恢复失败不阻塞新会话；保留错误提示便于用户手动重试。
    messages.value.push({ role: 'assistant', content: `恢复任务失败：${error.message}` })
  }
})

// 页面卸载时关闭 SSE 连接
onBeforeUnmount(() => {
  desktopMedia.removeEventListener('change', syncWorkspaceViewport)
  pinnedNavMedia.removeEventListener('change', syncPinnedNavigation)
  window.removeEventListener('resize', syncWindowWidth)
  historyController.abort()
  stopAnswerWatch?.()
  if (currentEventSource) {
    currentEventSource.close()
  }
  if (legacyDoneTimer) clearInterval(legacyDoneTimer)
  legacyDoneTimer = null
  if (activeRun.value?.runId) runStream.pause(activeRun.value.runId)
})
</script>
