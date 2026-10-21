import { test, expect } from '@playwright/test'

test.use({ video: 'off' })

const WORKBENCH_PATH = '/pokemon-workbench'
const WORKBENCH_TITLE = '宝可梦助手'
const LOGIN_PATH = '/api/oauth2/authorization/vio'
const LOGOUT_PATH = '/api/logout'
const SESSION_PATH = '/api/auth/session'
const RUNS_KEY = 'vio.agent.run-resume.v1'
const CONVERSATION_KEY = 'vio.agent.conversation.v1.game-master'
const UNRELATED_KEY = 'test.unrelated-preference'
const OLD_CSRF = 'fixture-csrf-initial-secret'
const FRESH_CSRF = 'fixture-csrf-refreshed-secret'

function session(overrides = {}) {
  const now = Date.now()
  return {
    mode: 'oidc',
    authenticated: true,
    accessDenied: false,
    principalId: 'fixture-user-a',
    tenantId: 'fixture-tenant',
    roles: ['USER'],
    serverTime: new Date(now).toISOString(),
    absoluteExpiresAt: new Date(now + 60_000).toISOString(),
    loginUrl: LOGIN_PATH,
    logoutUrl: LOGOUT_PATH,
    csrf: {
      enabled: true,
      available: true,
      cookieName: 'CSRF-TOKEN',
      headerName: 'X-CSRF-TOKEN'
    },
    ...overrides
  }
}

function anonymous(overrides = {}) {
  return session({
    authenticated: false,
    principalId: null,
    tenantId: null,
    roles: [],
    absoluteExpiresAt: null,
    ...overrides
  })
}

function fixture(overrides = {}) {
  return {
    session: session(),
    sessionStatus: 200,
    logoutStatus: 204,
    factsStatus: 200,
    sessionReads: 0,
    requests: [],
    businessRequests: [],
    ...overrides
  }
}

async function respondJson(route, body, status = 200, headers = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(body) })
}

async function installFixture(page, state) {
  // Match backend pathnames only: a glob containing /api/ would also intercept
  // /src/api/*.js imported by Vite and replace the application code with JSON.
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const url = new URL(request.url())
    const entry = { path: url.pathname, method: request.method(), headers: request.headers(), url: url.href }
    state.requests.push(entry)

    if (url.pathname === SESSION_PATH) {
      state.sessionReads += 1
      const cookie = state.sessionReads === 1 ? OLD_CSRF : FRESH_CSRF
      await respondJson(route, state.session, state.sessionStatus, {
        'Cache-Control': 'no-store',
        'Set-Cookie': `CSRF-TOKEN=${cookie}; Path=/; SameSite=Lax`
      })
      return
    }
    if (url.pathname === LOGOUT_PATH) {
      if (state.logoutStatus === 204) {
        state.session = anonymous()
        await route.fulfill({ status: 204 })
      } else {
        await respondJson(route, { message: 'fixture logout rejected' }, state.logoutStatus)
      }
      return
    }
    if (url.pathname === LOGIN_PATH) {
      // Keep the navigation on a fixture document; never contact an IdP.
      await route.fulfill({ status: 200, contentType: 'text/html', body: '<h1>Fixture login handoff</h1>' })
      return
    }

    state.businessRequests.push(entry)
    if (url.pathname === '/api/pokemon/facts') {
      await respondJson(route, state.factsStatus === 200 ? [] : { message: 'fixture resource denied' }, state.factsStatus)
      return
    }
    if (['/api/teams', '/api/favorites'].includes(url.pathname)) {
      await respondJson(route, { items: [], nextCursor: null })
      return
    }
    if (['/api/pokemon/activities', '/api/conversations'].includes(url.pathname)) {
      await respondJson(route, [])
      return
    }
    // Unexpected business requests are recorded and fail explicitly. No
    // backend, model, provider token or real user data is used by this suite.
    await respondJson(route, { message: 'fixture route missing' }, 404)
  })
}

async function open(page, state) {
  await installFixture(page, state)
  await page.goto(WORKBENCH_PATH)
}

async function expectWorkbench(page) {
  await expect(page.getByRole('heading', { name: WORKBENCH_TITLE })).toBeVisible()
  await expect(page.getByTestId('auth-gate')).toHaveCount(0)
}

async function seedPointers(page) {
  await page.evaluate(({ runsKey, conversationKey, unrelatedKey }) => {
    sessionStorage.setItem(runsKey, JSON.stringify({ 'old-conversation': { runId: 'old-run', lastEventId: '9', savedAt: Date.now() } }))
    sessionStorage.setItem(conversationKey, 'old-conversation')
    sessionStorage.setItem(unrelatedKey, 'keep-me')
    localStorage.setItem(unrelatedKey, 'keep-local')
  }, { runsKey: RUNS_KEY, conversationKey: CONVERSATION_KEY, unrelatedKey: UNRELATED_KEY })
}

async function expectClearedPointers(page) {
  const storage = await page.evaluate(({ runsKey, conversationKey, unrelatedKey }) => ({
    runs: sessionStorage.getItem(runsKey),
    conversation: sessionStorage.getItem(conversationKey),
    unrelated: sessionStorage.getItem(unrelatedKey),
    unrelatedLocal: localStorage.getItem(unrelatedKey)
  }), { runsKey: RUNS_KEY, conversationKey: CONVERSATION_KEY, unrelatedKey: UNRELATED_KEY })
  expect(storage.runs === null || storage.runs === '{}').toBe(true)
  expect(storage.conversation).toBeNull()
  expect(storage.unrelated).toBe('keep-me')
  expect(storage.unrelatedLocal).toBe('keep-local')
}

async function expectNoStoredSecrets(page) {
  const stored = await page.evaluate(() => JSON.stringify({
    session: Object.entries(sessionStorage),
    local: Object.entries(localStorage)
  }))
  expect(stored).not.toContain(OLD_CSRF)
  expect(stored).not.toContain(FRESH_CSRF)
}

async function expectLogoutIncomplete(page, state) {
  // A failed server logout is still unsafe to treat as an active account:
  // the UI closes business access, reports the failure and offers a retry.
  await expect(page.getByTestId('auth-gate')).toBeVisible()
  await expect(page.getByRole('heading', { name: '退出未完成', exact: true })).toBeVisible()
  const reads = state.sessionReads
  await page.evaluate(() => {
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))
  })
  expect(state.sessionReads).toBe(reads)
  await expect(page.getByRole('heading', { name: '退出未完成', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '重试退出', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: WORKBENCH_TITLE })).toHaveCount(0)
  await expect(page.getByTestId('session-toolbar')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '登录后继续', exact: true })).toHaveCount(0)
  expect(state.requests.filter(item => item.path === LOGIN_PATH)).toHaveLength(0)
}

test.describe('浏览器会话门禁（本地 fixture）', () => {
  test('匿名只读取会话，显式登录才导航到固定同源入口', async ({ page }) => {
    const state = fixture({ session: anonymous() })
    await open(page, state)
    await expect(page.getByTestId('auth-gate')).toBeVisible()
    await expect(page.getByRole('heading', { name: '登录后继续', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: WORKBENCH_TITLE })).toHaveCount(0)
    expect(state.businessRequests).toHaveLength(0)
    expect(state.requests.filter(item => item.path === LOGIN_PATH)).toHaveLength(0)
    const origin = new URL(page.url()).origin
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(`${origin}${LOGIN_PATH}`)
    await expect(page.getByRole('heading', { name: 'Fixture login handoff' })).toBeVisible()
    expect(state.businessRequests).toHaveLength(0)
  })

  test('缺少资源授权时显示切换账号且不挂载业务页面', async ({ page }) => {
    const state = fixture({ session: anonymous({ accessDenied: true }) })
    await open(page, state)
    await expect(page.getByRole('heading', { name: '当前账号未获授权', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '切换账号', exact: true })).toBeVisible()
    expect(state.businessRequests).toHaveLength(0)
    expect(state.requests.filter(item => item.path === LOGIN_PATH)).toHaveLength(0)
  })

  test('会话服务异常保持门禁，重试成功后才挂载业务页面', async ({ page }) => {
    const state = fixture({ sessionStatus: 503 })
    await open(page, state)
    await expect(page.getByRole('heading', { name: '登录状态暂时不可用', exact: true })).toBeVisible()
    expect(state.businessRequests).toHaveLength(0)
    state.sessionStatus = 200
    await page.getByRole('button', { name: '重试', exact: true }).click()
    await expectWorkbench(page)
    await expect(page.getByTestId('session-toolbar')).toContainText('fixture-user-a')
    expect(state.sessionReads).toBe(2)
  })

  test('个人身份退出先刷新 CSRF 再 POST，清除旧 Run 且不保存凭据', async ({ page }) => {
    const state = fixture()
    await open(page, state)
    await expectWorkbench(page)
    await expect(page.getByTestId('session-toolbar')).toContainText('fixture-user-a')
    await seedPointers(page)
    await expectNoStoredSecrets(page)
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await expect(page.getByRole('heading', { name: '登录后继续', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: WORKBENCH_TITLE })).toHaveCount(0)
    const index = state.requests.findIndex(item => item.path === LOGOUT_PATH)
    expect(index).toBeGreaterThan(0)
    expect(state.requests[index - 1]).toMatchObject({ path: SESSION_PATH, method: 'GET' })
    expect(state.requests[index]).toMatchObject({ path: LOGOUT_PATH, method: 'POST' })
    expect(state.requests[index].headers['x-csrf-token']).toBe(FRESH_CSRF)
    expect(state.requests[index].headers.authorization).toBeUndefined()
    expect(state.requests[index].headers['x-api-key']).toBeUndefined()
    await expectClearedPointers(page)
    await expectNoStoredSecrets(page)
  })

  for (const status of [500, 200]) {
    test(`退出返回 ${status} 而非 204 时显示失败，不伪造匿名会话`, async ({ page }) => {
      const state = fixture({ logoutStatus: status })
      await open(page, state)
      await expectWorkbench(page)
      await seedPointers(page)
      await page.getByRole('button', { name: '退出登录', exact: true }).click()
      await expectLogoutIncomplete(page, state)
      await expectClearedPointers(page)
    })
  }

  test('刷新 CSRF 失败时不发送退出 POST，保留明确失败状态', async ({ page }) => {
    const state = fixture()
    await open(page, state)
    await expectWorkbench(page)
    await seedPointers(page)
    state.sessionStatus = 503
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await expectLogoutIncomplete(page, state)
    await expectClearedPointers(page)
    expect(state.requests.filter(item => item.path === LOGOUT_PATH)).toHaveLength(0)
  })

  test('绝对到期时间触发门禁并清除旧 Run，之后不再请求业务数据', async ({ page }) => {
    const now = new Date('2026-10-12T08:00:00Z')
    await page.clock.install({ time: now })
    const state = fixture({ session: session({ serverTime: now.toISOString(), absoluteExpiresAt: new Date(now.getTime() + 2_000).toISOString() }) })
    await open(page, state)
    await expectWorkbench(page)
    await seedPointers(page)
    await page.clock.fastForward(2_100)
    await expect(page.getByRole('heading', { name: '登录已过期', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: WORKBENCH_TITLE })).toHaveCount(0)
    await expectClearedPointers(page)
    const requestsAfterExpiry = state.businessRequests.length
    await page.clock.fastForward(60_000)
    expect(state.businessRequests).toHaveLength(requestsAfterExpiry)
  })

  test('业务 401 使会话失效，清缓存并停止业务页面挂载', async ({ page }) => {
    const state = fixture({ factsStatus: 401 })
    await open(page, state)
    await expectWorkbench(page)
    await seedPointers(page)
    await page.locator('.facts-panel button[type=submit]').click()
    await expect(page.getByRole('heading', { name: '登录已过期', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: WORKBENCH_TITLE })).toHaveCount(0)
    await expect(page.getByTestId('session-toolbar')).toHaveCount(0)
    await expectClearedPointers(page)
    expect(state.requests.filter(item => item.path === LOGIN_PATH)).toHaveLength(0)
  })

  test('业务 403 保留个人会话，展示资源权限错误而不退出', async ({ page }) => {
    const state = fixture({ factsStatus: 403 })
    await open(page, state)
    await expectWorkbench(page)
    await page.locator('.facts-panel button[type=submit]').click()
    await expect(page.getByRole('alert')).toContainText('需要登录或资源权限')
    await expectWorkbench(page)
    await expect(page.getByTestId('session-toolbar')).toContainText('fixture-user-a')
    expect(state.requests.filter(item => item.path === LOGOUT_PATH || item.path === LOGIN_PATH)).toHaveLength(0)
  })

  for (const [name, body] of [
    ['认证字段类型损坏', () => anonymous({ authenticated: 'false' })],
    ['未知认证模式', () => anonymous({ mode: 'unexpected' })],
    ['个人会话缺少身份', () => session({ principalId: null })],
    ['角色字段损坏', () => session({ roles: 'USER' })],
    ['到期时间损坏', () => session({ absoluteExpiresAt: 'not-a-date' })],
    ['外部登录地址', () => anonymous({ loginUrl: 'https://idp.example.invalid/authorize' })],
    ['外部退出地址', () => session({ logoutUrl: 'https://idp.example.invalid/logout' })]
  ]) {
    test(`${name}保持关闭并提供重试`, async ({ page }) => {
      const state = fixture({ session: body() })
      await open(page, state)
      await expect(page.getByRole('heading', { name: '登录状态暂时不可用', exact: true })).toBeVisible()
      await expect(page.getByRole('button', { name: '重试', exact: true })).toBeVisible()
      await expect(page.getByRole('heading', { name: WORKBENCH_TITLE })).toHaveCount(0)
      expect(state.businessRequests).toHaveLength(0)
      expect(state.requests.filter(item => item.path === LOGIN_PATH || item.path === LOGOUT_PATH)).toHaveLength(0)
      await expect(page).toHaveURL(/\/game-master(?:\?|$)/)
    })
  }

  for (const mode of ['api_key', 'disabled']) {
    test(`${mode}模式允许业务页面但不伪装个人身份`, async ({ page }) => {
      const state = fixture({ session: anonymous({
        mode,
        loginUrl: null,
        logoutUrl: null,
        csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' }
      }) })
      await open(page, state)
      await expectWorkbench(page)
      await expect(page.getByTestId('session-toolbar')).toContainText(mode === 'api_key' ? '受限演示' : '开发模式')
      await expect(page.getByTestId('session-toolbar')).not.toContainText('fixture-user-a')
      await expect(page.getByRole('button', { name: '退出登录', exact: true })).toHaveCount(0)
      await expect(page.getByText('fixture-user-a', { exact: true })).toHaveCount(0)
      expect(state.businessRequests.length).toBeGreaterThan(0)
      await expectNoStoredSecrets(page)
    })
  }

  test('退出后切换账号不恢复旧账号 Run，保留无关的页面偏好', async ({ page }) => {
    const state = fixture()
    await open(page, state)
    await expectWorkbench(page)
    await seedPointers(page)
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await expect(page.getByRole('heading', { name: '登录后继续', exact: true })).toBeVisible()
    await expectClearedPointers(page)
    state.session = session({ principalId: 'fixture-user-b', tenantId: 'fixture-other-tenant' })
    await page.reload()
    await expectWorkbench(page)
    await expect(page.getByTestId('session-toolbar')).toContainText('fixture-user-b')
    await expect(page.getByTestId('session-toolbar')).not.toContainText('fixture-user-a')
    await expectClearedPointers(page)
    expect(state.businessRequests.some(item => /old-run|old-conversation/.test(item.url))).toBe(false)
    await expectNoStoredSecrets(page)
  })
})
