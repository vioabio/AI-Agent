import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { canUseApplication, createSessionController, normalizeSession } from '../src/auth/session-state.js'

const serverTime = '2026-10-12T00:00:00Z'
function snapshot(overrides = {}) {
  return { mode: 'oidc', authenticated: false, accessDenied: false,
    principalId: null, tenantId: null, roles: [], serverTime, absoluteExpiresAt: null,
    loginUrl: '/api/oauth2/authorization/vio', logoutUrl: '/api/logout',
    csrf: { enabled: true, available: true, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' },
    ...overrides }
}
function signed(overrides = {}) {
  return snapshot({ authenticated: true, principalId: 'alice', tenantId: 'tenant-a', roles: ['user'],
    absoluteExpiresAt: '2026-10-12T00:01:00Z', ...overrides })
}
function deferred() {
  let resolve
  let reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}
function harness(options = {}) {
  let currentTime = 1000
  let id = 0
  const timers = new Map()
  const cleared = []
  const broadcast = []
  const navigated = []
  const controller = createSessionController({
    fetchSession: async () => signed(), logout: async () => {}, now: () => currentTime,
    setTimer: (callback, delay) => { timers.set(++id, { callback, at: currentTime + delay }); return id },
    clearTimer: key => timers.delete(key), clearIdentity: () => cleared.push('cleared'),
    broadcast: reason => broadcast.push(reason), navigate: url => navigated.push(url), ...options
  })
  function advance(ms) {
    currentTime += ms
    for (const [key, timer] of [...timers]) {
      if (timer.at <= currentTime) { timers.delete(key); timer.callback() }
    }
  }
  return { controller, timers, cleared, broadcast, navigated, advance }
}

test('匿名OIDC关闭业务视图并只进入固定同源登录入口', async () => {
  const h = harness({ fetchSession: async () => snapshot() })
  await h.controller.refresh()
  assert.equal(canUseApplication(h.controller.state), false)
  assert.equal(h.controller.state.reason, 'anonymous')
  h.controller.startLogin()
  assert.deepEqual(h.navigated, ['/api/oauth2/authorization/vio'])
})

test('策略拒绝保留明确门禁；兼容模式不伪造个人身份', async () => {
  const h = harness({ fetchSession: async () => snapshot({ accessDenied: true }) })
  await h.controller.refresh()
  assert.equal(h.controller.state.reason, 'rejected')
  assert.equal(canUseApplication(h.controller.state), false)
  for (const mode of ['disabled', 'api_key']) {
    const compatible = normalizeSession(snapshot({ mode, loginUrl: null, logoutUrl: null,
      csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' } }))
    assert.equal(canUseApplication({ phase: 'ready', snapshot: compatible }), true)
    assert.equal(compatible.principalId, null)
    assert.throws(() => normalizeSession({ ...compatible, authenticated: true }))
  }
})

test('损坏身份、缺失nullable字段、外部/协议相对登录URL均关闭', () => {
  for (const value of [null, {}, snapshot({ principalId: undefined }), signed({ tenantId: null }),
    signed({ roles: [] }), signed({ absoluteExpiresAt: 'invalid' }),
    snapshot({ loginUrl: 'https://evil.example/login' }), snapshot({ loginUrl: '//evil.example' }),
    snapshot({ logoutUrl: '/logout' }), snapshot({ csrf: { enabled: true } })]) {
    assert.throws(() => normalizeSession(value))
  }
})

test('服务端相对期限消除客户端时钟偏差且包含请求延迟', async () => {
  let h
  h = harness({ fetchSession: async () => { h.advance(10000); return signed() } })
  await h.controller.refresh()
  assert.equal(canUseApplication(h.controller.state), true)
  h.advance(49999)
  assert.equal(canUseApplication(h.controller.state), true)
  h.advance(1)
  assert.equal(canUseApplication(h.controller.state), false)
  assert.equal(h.controller.state.reason, 'expired')
  assert.deepEqual(h.broadcast, ['expired'])
  assert.equal(h.cleared.length, 1)
})

test('身份准备跨过期限时不得短暂开放业务视图', async () => {
  let h
  h = harness({ prepareIdentity: async () => h.advance(60000) })
  await h.controller.refresh()
  assert.equal(canUseApplication(h.controller.state), false)
  assert.equal(h.controller.state.reason, 'expired')
  assert.equal(h.timers.size, 0)
})

test('后台刷新不能滑动延长相同登录的绝对到期', async () => {
  let response = signed()
  const h = harness({ fetchSession: async () => response })
  await h.controller.refresh()
  const viewVersion = h.controller.state.viewVersion
  h.advance(30000)
  response = signed({ serverTime: '2026-10-12T00:00:30Z' })
  await h.controller.refresh({ background: true })
  assert.equal(h.controller.state.viewVersion, viewVersion)
  h.advance(30000)
  assert.equal(h.controller.state.reason, 'expired')
  assert.equal(canUseApplication(h.controller.state), false)
})

test('账号变化先关闭旧视图，再等待隔离准备完成', async () => {
  const preparation = deferred()
  let response = signed()
  const h = harness({ fetchSession: async () => response,
    prepareIdentity: next => next.principalId === 'bob' ? preparation.promise : undefined })
  await h.controller.refresh()
  const oldVersion = h.controller.state.viewVersion
  response = signed({ principalId: 'bob', tenantId: 'tenant-b' })
  const refresh = h.controller.refresh({ background: true })
  await Promise.resolve()
  assert.equal(h.controller.state.phase, 'loading')
  assert.equal(canUseApplication(h.controller.state), false)
  assert.equal(h.cleared.length, 1)
  preparation.resolve()
  await refresh
  assert.equal(h.controller.state.snapshot.principalId, 'bob')
  assert.equal(h.controller.state.viewVersion, oldVersion + 1)
})

test('退出立即关闭业务视图；退出前旧GET不能复活账号', async () => {
  const oldGet = deferred()
  const signout = deferred()
  let requests = 0
  let signal
  const h = harness({ fetchSession: ({ signal: current }) => {
    requests++
    if (requests === 2) { signal = current; return oldGet.promise }
    return Promise.resolve(requests === 1 ? signed() : snapshot())
  }, logout: () => signout.promise })
  await h.controller.refresh()
  const pending = h.controller.refresh({ background: true })
  const out = h.controller.signOut()
  assert.equal(canUseApplication(h.controller.state), false)
  assert.equal(signal.aborted, true)
  oldGet.resolve(signed())
  await pending
  assert.equal(h.controller.state.phase, 'logging_out')
  signout.resolve()
  await out
  assert.equal(h.controller.state.snapshot.authenticated, false)
  assert.deepEqual(h.broadcast, ['signed_out'])
})

test('退出失败不能假报成功或重开业务，仍可明确重试', async () => {
  let attempts = 0
  let reads = 0
  const h = harness({ fetchSession: async () => { reads++; return signed() },
    logout: async () => { attempts++; throw new Error('503') } })
  await h.controller.refresh()
  await h.controller.signOut()
  assert.equal(h.controller.state.reason, 'logout_failed')
  assert.equal(h.controller.state.phase, 'error')
  assert.equal(canUseApplication(h.controller.state), false)
  assert.deepEqual(h.broadcast, [])
  await h.controller.refresh({ background: true })
  assert.equal(reads, 1)
  assert.equal(h.controller.state.reason, 'logout_failed')
  assert.equal(canUseApplication(h.controller.state), false)
  await h.controller.signOut()
  assert.equal(attempts, 2)
})

test('被更新响应覆盖的旧GET和旧身份准备不能改写新状态', async () => {
  const oldGet = deferred()
  let requests = 0
  const h = harness({ fetchSession: async () => ++requests === 1 ? oldGet.promise : signed({ principalId: 'bob' }) })
  const first = h.controller.refresh()
  await h.controller.refresh()
  oldGet.resolve(signed())
  await first
  assert.equal(h.controller.state.snapshot.principalId, 'bob')
  const preparation = deferred()
  let isCurrent
  const second = harness({ prepareIdentity: async (next, context) => {
    isCurrent = context.isCurrent
    await preparation.promise
  } })
  const pending = second.controller.refresh()
  await Promise.resolve()
  second.controller.dispose()
  assert.equal(isCurrent(), false)
  preparation.resolve()
  await pending
  assert.equal(canUseApplication(second.controller.state), false)
})

test('后台确认失败关闭已登录身份；dispose取消请求和到期计时器', async () => {
  let fail = false
  const h = harness({ fetchSession: async () => { if (fail) throw new Error('network'); return signed() } })
  await h.controller.refresh()
  fail = true
  await h.controller.refresh({ background: true })
  assert.equal(canUseApplication(h.controller.state), false)
  assert.equal(h.controller.state.phase, 'error')
  assert.equal(h.timers.size, 0)
  fail = false
  await h.controller.refresh()
  h.controller.dispose()
  assert.equal(h.timers.size, 0)
})

const apiSource = fs.readFileSync(new URL('../src/api/index.js', import.meta.url), 'utf8')
let moduleId = 0
async function loadApi(t, fetch, cookie = '') {
  const previousFetch = globalThis.fetch
  const previousDocument = globalThis.document
  globalThis.fetch = fetch
  globalThis.document = { cookie }
  t.after(() => {
    globalThis.fetch = previousFetch
    if (previousDocument === undefined) delete globalThis.document
    else globalThis.document = previousDocument
  })
  return import(`data:text/javascript;base64,${Buffer.from(`${apiSource}\n// test-${++moduleId}`).toString('base64')}`)
}
const response = (status, payload) => ({ ok: status >= 200 && status < 300, status, json: async () => payload })

test('匿名bootstrap始终同源、不缓存、不发送页面API Key或Bearer', async t => {
  const calls = []
  const api = await loadApi(t, async (url, options) => { calls.push({ url, options }); return response(200, snapshot()) })
  api.setApiKey('transient-secret')
  await api.fetchAuthSession()
  assert.equal(calls[0].url, '/api/auth/session')
  assert.equal(calls[0].options.credentials, 'include')
  assert.equal(calls[0].options.cache, 'no-store')
  assert.deepEqual(calls[0].options.headers, { Accept: 'application/json' })
})

test('退出先刷新CSRF，优先读取当前CSRF-TOKEN，204后清除临时密钥', async t => {
  const calls = []
  const api = await loadApi(t, async (url, options) => {
    calls.push({ url, options })
    if (url.endsWith('/auth/session')) {
      globalThis.document.cookie = 'XSRF-TOKEN=stale; CSRF-TOKEN=fresh%20value'
      return response(200, signed())
    }
    return response(204)
  }, 'CSRF-TOKEN=old')
  api.setApiKey('transient-secret')
  const controller = new AbortController()
  await api.logoutSession({ signal: controller.signal })
  assert.deepEqual(calls.map(call => call.url), ['/api/auth/session', '/api/logout'])
  assert.equal(calls[1].options.method, 'POST')
  assert.equal(calls[1].options.credentials, 'include')
  assert.equal(calls[1].options.signal, controller.signal)
  assert.deepEqual(calls[1].options.headers, { Accept: 'application/json', 'X-CSRF-TOKEN': 'fresh value' })
  assert.equal(api.hasApiKey(), false)
})

test('缺失CSRF禁止退出POST；200空响应不冒充204成功', async t => {
  const calls = []
  let available = false
  const api = await loadApi(t, async url => {
    calls.push(url)
    return url.endsWith('/auth/session') ? response(200, snapshot({ csrf: { ...snapshot().csrf, available } })) : response(200)
  }, 'CSRF-TOKEN=value')
  await assert.rejects(api.logoutSession(), /退出校验/)
  assert.deepEqual(calls, ['/api/auth/session'])
  available = true
  await assert.rejects(api.logoutSession(), /响应不符合契约/)
})

test('HTTP及Result的401触发身份失效，403权限不足不误退出', async t => {
  let result = response(403)
  const api = await loadApi(t, async () => result)
  let invalidations = 0
  api.setAuthenticationFailureHandler(() => invalidations++)
  await assert.rejects(api.fetchAuthSession(), error => error.status === 403)
  assert.equal(invalidations, 0)
  result = response(401)
  await assert.rejects(api.fetchAuthSession(), error => error.status === 401)
  assert.equal(invalidations, 1)
  result = response(200, { code: 403, message: 'forbidden' })
  await assert.rejects(api.fetchAuthSession(), error => error.status === 403)
  assert.equal(invalidations, 1)
  result = response(200, { code: 401, message: 'expired' })
  await assert.rejects(api.fetchAuthSession(), error => error.status === 401)
  assert.equal(invalidations, 2)
})

test('旧账号迟到HTTP/Result401不失效已确认的新账号', async t => {
  let pending = deferred()
  const api = await loadApi(t, () => pending.promise)
  let invalidations = 0
  api.setAuthenticationFailureHandler(() => invalidations++)
  for (const stale of [response(401), response(200, { code: 401, message: 'old account expired' })]) {
    pending = deferred()
    const oldRequest = api.fetchFavorites()
    api.invalidateAuthenticationRequests()
    pending.resolve(stale)
    await assert.rejects(oldRequest, error => error.status === 401)
    assert.equal(invalidations, 0)
  }
  pending = deferred()
  const current = api.fetchFavorites()
  pending.resolve(response(401))
  await assert.rejects(current, error => error.status === 401)
  assert.equal(invalidations, 1)
})

test('响应头已返回后切换账号，迟到JSON业务401也不能失效新身份', async t => {
  const body = deferred()
  const reading = deferred()
  const api = await loadApi(t, async () => ({ ok: true, status: 200, json: () => {
    reading.resolve()
    return body.promise
  } }))
  let invalidations = 0
  api.setAuthenticationFailureHandler(() => invalidations++)
  const oldRequest = api.fetchFavorites()
  await reading.promise
  api.invalidateAuthenticationRequests()
  body.resolve({ code: 401, message: 'old account expired' })
  await assert.rejects(oldRequest, error => error.status === 401)
  assert.equal(invalidations, 0)
})
