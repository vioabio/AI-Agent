const MODES = new Set(['oidc', 'api_key', 'disabled'])
const LOGIN_URL = '/api/oauth2/authorization/vio'
const LOGOUT_URL = '/api/logout'

/** Validate the server contract before an identity can open a business view. */
export function normalizeSession(value) {
  const invalid = () => { throw new Error('登录状态响应不符合契约') }
  if (!value || !MODES.has(value.mode) || typeof value.authenticated !== 'boolean'
    || typeof value.accessDenied !== 'boolean' || !Array.isArray(value.roles)
    || value.roles.some(role => typeof role !== 'string' || !role || role.length > 128)
    || value.roles.length > 64 || !Number.isFinite(Date.parse(value.serverTime))) invalid()
  for (const field of ['principalId', 'tenantId']) {
    if (value[field] !== null && (typeof value[field] !== 'string' || !value[field] || value[field].length > 256)) invalid()
  }
  const csrf = value.csrf
  if (!csrf || typeof csrf.enabled !== 'boolean' || typeof csrf.available !== 'boolean'
    || csrf.cookieName !== 'CSRF-TOKEN' || csrf.headerName !== 'X-CSRF-TOKEN') invalid()
  if (value.mode === 'oidc') {
    if (value.loginUrl !== LOGIN_URL || value.logoutUrl !== LOGOUT_URL || !csrf.enabled) invalid()
    if (value.authenticated) {
      if (value.accessDenied || !value.principalId || !value.tenantId || !value.roles.length
        || !Number.isFinite(Date.parse(value.absoluteExpiresAt))) invalid()
    } else if (value.principalId !== null || value.tenantId !== null || value.roles.length
      || value.absoluteExpiresAt !== null) invalid()
  } else if (value.authenticated || value.accessDenied || value.principalId !== null
    || value.tenantId !== null || value.roles.length || value.absoluteExpiresAt !== null
    || value.loginUrl !== null || value.logoutUrl !== null || csrf.enabled) invalid()
  return {
    mode: value.mode, authenticated: value.authenticated, accessDenied: value.accessDenied,
    principalId: value.principalId, tenantId: value.tenantId, roles: [...value.roles].sort(),
    serverTime: value.serverTime, absoluteExpiresAt: value.absoluteExpiresAt,
    loginUrl: value.loginUrl, logoutUrl: value.logoutUrl, csrf: { ...csrf }
  }
}

function identityKey(snapshot) {
  return JSON.stringify([snapshot.mode, snapshot.authenticated, snapshot.principalId,
    snapshot.tenantId, snapshot.roles, snapshot.accessDenied])
}

/** Browser controller with injected transport/clock so races and expiry are testable. */
export function createSessionController({ fetchSession, logout, state = {}, now = Date.now,
  setTimer = setTimeout, clearTimer = clearTimeout, prepareIdentity = async () => {},
  clearIdentity = () => {}, navigate = () => {}, broadcast = () => {} }) {
  Object.assign(state, { phase: 'loading', snapshot: null, reason: '', error: '', viewVersion: 0 })
  let generation = 0
  let timer = null
  let request = null
  let disposed = false

  const clearExpiry = () => {
    if (timer !== null) clearTimer(timer)
    timer = null
  }
  const cancelRequest = () => { request?.abort(); request = null }
  function closeIdentity(reason) {
    generation++
    cancelRequest()
    clearExpiry()
    clearIdentity()
    state.viewVersion++
    state.reason = reason
    state.snapshot = state.snapshot ? { ...state.snapshot, authenticated: false,
      accessDenied: false, principalId: null, tenantId: null, roles: [], absoluteExpiresAt: null } : null
  }
  function invalidate(reason = 'expired', notify = false) {
    if (disposed || state.snapshot?.mode !== 'oidc') return
    closeIdentity(reason)
    state.phase = 'ready'
    state.error = ''
    if (notify) broadcast(reason)
  }
  async function refresh({ background = false } = {}) {
    if (disposed || state.phase === 'logging_out' || (background && state.reason === 'logout_failed')) return
    cancelRequest()
    const revision = ++generation
    const startedAt = now()
    const controller = new AbortController()
    request = controller
    if (!background || state.phase !== 'ready') state.phase = 'loading'
    state.error = ''
    try {
      const next = normalizeSession(await fetchSession({ signal: controller.signal }))
      if (disposed || revision !== generation) return
      const lifetime = next.authenticated ? Date.parse(next.absoluteExpiresAt) - Date.parse(next.serverTime) : null
      // Start at the beginning of the request: latency cannot extend server expiry.
      if (next.authenticated && startedAt + lifetime <= now()) {
        state.snapshot = next
        invalidate('expired')
        return
      }
      const changed = !state.snapshot || identityKey(state.snapshot) !== identityKey(next)
      if (changed && state.snapshot) { state.phase = 'loading'; clearIdentity() }
      await prepareIdentity(next, { isCurrent: () => !disposed && revision === generation })
      if (disposed || revision !== generation) return
      if (next.authenticated && startedAt + lifetime <= now()) {
        state.snapshot = next
        invalidate('expired')
        return
      }
      clearExpiry()
      state.snapshot = next
      if (changed) state.viewVersion++
      state.phase = 'ready'
      state.reason = next.accessDenied ? 'rejected' : next.authenticated ? '' : state.reason || 'anonymous'
      if (next.authenticated) {
        timer = setTimer(() => invalidate('expired', true), Math.max(0, startedAt + lifetime - now()))
      }
    } catch (error) {
      if (disposed || revision !== generation || error?.name === 'AbortError') return
      closeIdentity('unavailable')
      state.phase = 'error'
      state.error = '无法确认登录状态，请稍后重试。'
    } finally {
      if (request === controller) request = null
    }
  }
  async function signOut() {
    if (disposed || state.snapshot?.mode !== 'oidc' || state.phase === 'logging_out') return
    closeIdentity('signed_out')
    const revision = generation
    state.phase = 'logging_out'
    state.error = ''
    request = new AbortController()
    try {
      await logout({ signal: request.signal })
      if (disposed || revision !== generation) return
      state.phase = 'ready'
      broadcast('signed_out')
      await refresh()
    } catch {
      if (disposed || revision !== generation) return
      state.phase = 'error'
      state.reason = 'logout_failed'
      state.error = '退出未完成，请重试；当前页面已停止读取账号数据。'
    }
  }
  function startLogin() {
    if (state.phase === 'ready' && state.snapshot?.mode === 'oidc'
      && state.snapshot.loginUrl === LOGIN_URL) navigate(LOGIN_URL)
  }
  function dispose() { disposed = true; generation++; cancelRequest(); clearExpiry() }
  return { state, refresh, signOut, startLogin, invalidate, dispose }
}

export function canUseApplication(state) {
  return state.phase === 'ready' && Boolean(state.snapshot)
    && (state.snapshot.mode !== 'oidc' || (state.snapshot.authenticated && !state.snapshot.accessDenied))
}
