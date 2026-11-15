import { computed, reactive } from 'vue'
import { clearApiKey, fetchAuthSession, invalidateAuthenticationRequests, logoutSession,
  setAuthenticationFailureHandler } from '@/api'
import { canUseApplication, createSessionController, workspaceRouteWithoutConversation } from '@/auth/session-state'
import { clearAllRunResume, syncRunResumeScope } from '@/composables/runPersistence'
import router from '@/router'

const state = reactive({})
let channel = null
let clearWorkspaceContext = false
const session = createSessionController({
  state, fetchSession: fetchAuthSession, logout: logoutSession,
  prepareIdentity: async (snapshot, guard) => {
    if (clearWorkspaceContext) {
      const route = router.currentRoute.value
      if (Object.hasOwn(route.query, 'conversation')) await router.replace(workspaceRouteWithoutConversation(route))
      if (!guard.isCurrent()) return
      clearWorkspaceContext = false
    }
    await syncRunResumeScope(snapshot, guard)
  },
  clearIdentity: () => {
    if (state.snapshot?.authenticated) clearWorkspaceContext = true
    invalidateAuthenticationRequests(); clearApiKey(); clearAllRunResume()
  },
  navigate: url => window.location.assign(url),
  broadcast: reason => channel?.postMessage({ type: 'session-ended', reason })
})

setAuthenticationFailureHandler(() => {
  if (state.phase !== 'logging_out') session.invalidate('expired', true)
})

const allowedPaths = new Set(['/', '/game-master', '/super-agent', '/pokemon-workbench'])
const returnKey = 'vio.auth.return-path.v1'
let initialized = false
export function initializeAuthSession() {
  if (initialized) return
  initialized = true
  if (typeof BroadcastChannel === 'function') {
    channel = new BroadcastChannel('vio.auth.session')
    channel.onmessage = event => {
      if (event.data?.type === 'session-ended') session.invalidate('expired')
    }
  }
  window.addEventListener('focus', () => session.refresh({ background: true }))
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') session.refresh({ background: true })
  })
  session.refresh()
}

async function login(path) {
  try {
    if (allowedPaths.has(path)) window.sessionStorage.setItem(returnKey, path)
  } catch { /* Login remains usable without browser storage. */ }
  if (state.snapshot?.accessDenied) await session.signOut()
  session.startLogin()
}

export function consumeLoginReturnPath() {
  try {
    const path = window.sessionStorage.getItem(returnKey)
    window.sessionStorage.removeItem(returnKey)
    return allowedPaths.has(path) ? path : null
  } catch { return null }
}

export function useAuthSession() {
  return { state, canUseApplication: computed(() => canUseApplication(state)),
    refresh: session.refresh, signOut: session.signOut, login }
}
