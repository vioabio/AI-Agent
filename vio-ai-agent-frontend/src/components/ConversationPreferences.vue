<template>
  <button class="preferences-toggle" :class="{ compact }" type="button" aria-label="对话偏好" title="对话偏好" :disabled="disabled" @click="open"><UiIcon v-if="compact" name="settings" /><span v-else>对话偏好</span></button>
  <div v-if="opened" class="preferences-overlay" @keydown.esc="close" @keydown.tab="trapFocus">
    <section role="dialog" aria-modal="true" aria-labelledby="preferences-title" class="preferences-dialog">
      <header><h2 id="preferences-title">本次对话偏好</h2><button ref="closeButton" type="button" aria-label="关闭对话偏好" :disabled="saving" @click="close">×</button></header>
      <p>主动保存的要求会随当前对话保留，并用于之后的新问题。新对话单独设置。</p>
      <form @submit.prevent="save" @input="saved = false">
        <fieldset :disabled="loading || saving || !!loadError">
          <label>回答详略<select v-model="draft.responseStyle"><option value="STANDARD">适中</option><option value="CONCISE">简洁重点</option><option value="DETAILED">详细解释</option></select></label>
          <label>培养成本<select v-model="draft.trainingBudget"><option value="UNRESTRICTED">不限制</option><option value="LOW">优先低成本培养</option></select></label>
          <label class="preferences-check"><input v-model="draft.avoidLegendary" type="checkbox" />建议避开传说宝可梦</label>
          <label>补充要求<textarea v-model="draft.notes" maxlength="800" rows="3" placeholder="例如：以新手能理解的方式解释，优先考虑水属性成员。"></textarea></label>
          <small>{{ draft.notes.length }} / 800 · 当前问题的具体要求优先；偏好不会更改赛制或工具权限。</small>
        </fieldset>
        <p v-if="loading" role="status">正在读取…</p>
        <p v-if="error || loadError" role="alert">{{ error || loadError }}</p>
        <p v-if="saved" role="status">已保存，将用于之后的新问题；正在执行的任务使用原设置。</p>
        <footer>
          <button type="button" :disabled="loading || saving" @click="load">重新读取</button>
          <button type="button" :disabled="loading || saving || !!loadError" @click="reset">恢复默认</button>
          <button class="preferences-save" type="submit" :disabled="loading || saving || !!loadError">{{ saving ? '正在保存…' : '保存偏好' }}</button>
        </footer>
      </form>
    </section>
  </div>
</template>

<script setup>
import { ref, nextTick, watch, onBeforeUnmount } from 'vue'
import { fetchConversationConstraints, saveConversationConstraints } from '@/api'
import { defaultConstraints, normalizeConstraints } from '@/api/conversation-constraints'
import UiIcon from '@/components/UiIcon.vue'
const props = defineProps({ conversationId: { type: String, required: true }, disabled: Boolean, compact: Boolean })
const emit = defineEmits(['busy'])
const opened = ref(false), loading = ref(false), saving = ref(false), saved = ref(false)
const error = ref(''), loadError = ref(''), draft = ref(defaultConstraints()), closeButton = ref(null)
let controller, generation = 0, previousFocus
function cancel() { generation++; controller?.abort(); loading.value = false; saving.value = false; emit('busy', false) }
async function load() {
  cancel(); const current = generation; controller = new AbortController()
  loading.value = true; saved.value = false; error.value = ''; loadError.value = ''; emit('busy', true)
  try {
    let value
    try { value = await fetchConversationConstraints(props.conversationId, { signal: controller.signal }) }
    catch (failure) { if (failure.status !== 404) throw failure; value = defaultConstraints() }
    if (current !== generation) return
    const normalized = normalizeConstraints(value)
    if (!normalized) throw Error('对话偏好响应无效，请重新读取。')
    draft.value = normalized
  } catch (failure) { if (current === generation && failure.name !== 'AbortError') loadError.value = failure.message }
  finally { if (current === generation) { loading.value = false; emit('busy', false) } }
}
async function open() {
  previousFocus = document.activeElement; opened.value = true
  await nextTick(); closeButton.value?.focus(); await load()
}
function close() {
  if (saving.value) return
  cancel(); opened.value = false; previousFocus?.focus?.()
}
function trapFocus(event) {
  const targets = [...event.currentTarget.querySelectorAll('button:enabled,select:enabled,textarea:enabled,input:enabled')]
  const first = targets[0], last = targets.at(-1)
  if (!targets.length) { event.preventDefault(); return }
  if (!targets.includes(document.activeElement)) { event.preventDefault(); first.focus() }
  else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
function reset() { draft.value = { ...defaultConstraints(), revision: draft.value.revision }; saved.value = false; error.value = '' }
async function save() {
  const value = normalizeConstraints(draft.value)
  if (!value) { error.value = '请检查偏好选项和补充要求。'; return }
  const current = generation; controller = new AbortController(); saving.value = true; saved.value = false; error.value = ''; emit('busy', true)
  try {
    const response = await saveConversationConstraints(props.conversationId, value, { signal: controller.signal })
    if (current !== generation) return
    const normalized = normalizeConstraints(response)
    if (!normalized || normalized.revision !== value.revision + 1) throw Error('保存结果无法核实，请重新读取后确认。')
    draft.value = normalized; saved.value = true
  } catch (failure) {
    if (current === generation && failure.name !== 'AbortError') error.value = failure.status === 409
      ? '偏好已在其他页面更新。请先重新读取，再确认修改，避免覆盖新设置。' : `${failure.message}；保存状态未确认，请重新读取。`
  } finally { if (current === generation) { saving.value = false; emit('busy', false) } }
}
watch(() => props.conversationId, () => { cancel(); opened.value = false; draft.value = defaultConstraints() })
onBeforeUnmount(cancel)
</script>

<style scoped>
.preferences-toggle{background:transparent;border:1px solid #dce7e1;border-radius:8px;padding:7px 10px;color:#315d49;white-space:nowrap;font:inherit;font-size:12px;cursor:pointer}
.preferences-overlay{position:fixed;inset:0;background:#153d2d55;z-index:100;display:grid;place-items:center;padding:16px}
.preferences-dialog{width:min(480px,100%);max-height:calc(100dvh - 32px);overflow:auto;box-sizing:border-box;border-radius:16px;background:#fff;padding:22px;box-shadow:0 20px 70px #102a2926;color:#244537;font-size:14px}
header{display:flex;align-items:center;justify-content:space-between;gap:10px}h2{font-size:19px;margin:0}header button{font-size:24px;border:0;background:none;color:#668573;cursor:pointer}
p,small{line-height:1.6}p{font-size:13px;color:#64776d}p[role=alert]{color:#a44032}fieldset{border:0;padding:0;margin:0;display:grid;gap:12px}label{display:grid;gap:6px}select,textarea{width:100%;box-sizing:border-box;border:1px solid #d3e1d9;border-radius:8px;padding:9px;font:inherit;background:#fff;color:#244537}textarea{resize:vertical;min-height:70px;max-height:160px}
.preferences-check{display:flex;align-items:center;gap:8px}small{color:#718377;font-size:12px}footer{display:flex;flex-wrap:wrap;gap:8px;margin-top:18px;justify-content:flex-end}footer button{font:inherit;font-size:13px;line-height:1.4;border:1px solid #d3e1d9;border-radius:8px;background:#fff;padding:8px 12px;color:#315d49;cursor:pointer}footer .preferences-save{background:#2d7057;color:white;border-color:#2d7057}button:disabled{opacity:.55;cursor:default}button:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid #388a68;outline-offset:2px}
</style>
