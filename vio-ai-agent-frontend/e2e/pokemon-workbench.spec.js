import { test, expect } from '@playwright/test'

const FACT = {
  formId: 6,
  slug: 'charizard',
  nationalNo: 6,
  speciesNameZh: '喷火龙',
  formNameZh: '普通形态',
  formKey: 'default',
  type1: '火',
  type2: '飞行',
  hp: 78,
  attack: 84,
  defense: 78,
  spAttack: 109,
  spDefense: 85,
  speed: 100,
  dataReleaseId: 'pokemon-scarlet-violet-data-v1',
  gameReleaseId: 'pokemon-scarlet-violet',
  releaseStatus: 'PUBLISHED',
  checksum: 'sha256:fixture-charizard',
  sourceUrl: 'https://example.invalid/pokemon/charizard',
  evidenceText: 'fixture evidence only'
}

function initialState() {
  return {
    teams: [],
    favorites: [],
    permission: false,
    emptyFacts: false,
    unsafeSource: false,
    conversations: [{ id: 'conversation-1', title: '朱紫对战建议', updatedAt: '2026-09-03T00:00:00Z' }],
    messages: [{ id: 'message-1', role: 'assistant', content: '请先确认规则版本。', createdAt: '2026-09-03T00:01:00Z', runId: 'run-1' }]
  }
}

async function installApiFixture(page, state) {
  // Match the API root, excluding Vite modules under /src/api/.
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const url = new URL(request.url())
    const pathname = url.pathname
    if (pathname === '/api/auth/session') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        mode: 'disabled', authenticated: false, accessDenied: false,
        principalId: null, tenantId: null, roles: [], serverTime: new Date().toISOString(),
        absoluteExpiresAt: null, loginUrl: null, logoutUrl: null,
        csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' }
      }) })
      return
    }
    if (state.permission && pathname.endsWith('/pokemon/facts')) {
      await route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ message: 'permission denied' }) })
      return
    }
    if (pathname.endsWith('/pokemon/facts')) {
      const fact = state.unsafeSource ? { ...FACT, sourceUrl: 'javascript:alert(1)' } : FACT
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.emptyFacts ? [] : [fact]) })
      return
    }
    if (pathname.endsWith('/teams') && request.method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: state.teams, nextCursor: null }) })
      return
    }
    if (pathname.endsWith('/teams') && request.method() === 'POST') {
      const body = JSON.parse(request.postData() || '{}')
      const team = { id: 'team-1', ...body, members: body.members || [] }
      state.teams = [team]
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(team) })
      return
    }
    if (/\/teams\/[^/]+$/.test(pathname) && request.method() === 'PATCH') {
      const body = JSON.parse(request.postData() || '{}')
      state.teams = [{ ...(state.teams[0] || { id: 'team-1' }), ...body }]
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.teams[0]) })
      return
    }
    if (pathname.endsWith('/favorites') && request.method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: state.favorites, nextCursor: null }) })
      return
    }
    if (pathname.endsWith('/favorites') && request.method() === 'POST') {
      const body = JSON.parse(request.postData() || '{}')
      const favorite = { id: `favorite-${state.favorites.length + 1}`, ...body, createdAt: '2026-09-04T00:00:00Z', updatedAt: '2026-09-04T00:00:00Z' }
      state.favorites = [favorite, ...state.favorites]
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(favorite) })
      return
    }
    if (/\/favorites\/[^/]+$/.test(pathname) && request.method() === 'DELETE') {
      const id = decodeURIComponent(pathname.split('/').pop())
      state.favorites = state.favorites.filter(item => String(item.id) !== id)
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', timestamp: 1 }) })
      return
    }
    if (pathname.endsWith('/pokemon/activities')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
      return
    }
    if (pathname.endsWith('/conversations') && request.method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.conversations) })
      return
    }
    if (/\/chat\/conversations\/[^/]+\/messages$/.test(pathname)) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.messages) })
      return
    }
    await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ message: 'fixture route missing' }) })
  })
}

async function openWorkbench(page, state) {
  await installApiFixture(page, state)
  await page.goto('/pokemon-workbench')
  await expect(page.getByRole('heading', { name: '🧭 宝可梦资料工作台' })).toBeVisible()
}

test.describe('N5 宝可梦工作台关键交互', () => {
  test('创建上下文后展示版本化事实、来源和 release', async ({ page }) => {
    const state = initialState()
    await openWorkbench(page, state)
    await page.getByLabel('查询目标').fill('charizard')
    await page.locator('button.context-submit').click()
    await expect(page.getByRole('heading', { name: '喷火龙' })).toBeVisible()
    await expect(page.getByText('pokemon-scarlet-violet-data-v1')).toBeVisible()
    await expect(page.getByText('fixture evidence only')).toBeVisible()
  })

  test('重复队伍成员在客户端被拒绝且不发送写请求', async ({ page }) => {
    const state = initialState()
    await openWorkbench(page, state)
    await page.getByLabel('队伍名称').fill('重复测试队伍')
    await page.getByLabel('规则键').fill('casual-v1')
    await page.getByLabel('数据版本').last().fill('pokemon-scarlet-violet-data-v1')
    await page.getByLabel('第 1 个成员 formId').fill('6')
    await page.getByLabel('第 2 个成员 formId').fill('6')
    await page.getByRole('button', { name: '保存队伍' }).click()
    await expect(page.getByText('队伍不能重复使用同一 formId', { exact: true })).toBeVisible()
    expect(state.teams).toHaveLength(0)
  })

  test('保存队伍携带幂等语义并可编辑回读', async ({ page }) => {
    const state = initialState()
    await openWorkbench(page, state)
    await page.getByLabel('队伍名称').fill('朱紫休闲单打')
    await page.getByLabel('规则键').fill('casual-v1')
    await page.getByLabel('数据版本').last().fill('pokemon-scarlet-violet-data-v1')
    await page.getByLabel('第 1 个成员 formId').fill('6')
    await page.getByRole('button', { name: '保存队伍' }).click()
    await expect(page.getByRole('heading', { name: '朱紫休闲单打' })).toBeVisible()
    await page.getByRole('button', { name: '编辑' }).click()
    await page.getByLabel('队伍名称').fill('朱紫休闲单打（更新）')
    await page.getByRole('button', { name: '更新队伍' }).click()
    await expect(page.getByRole('heading', { name: '朱紫休闲单打（更新）' })).toBeVisible()
  })

  test('历史会话点击后只展示服务端消息', async ({ page }) => {
    const state = initialState()
    await openWorkbench(page, state)
    await page.getByRole('button', { name: /朱紫对战建议/ }).click()
    await expect(page.getByText('请先确认规则版本。')).toBeVisible()
    await expect(page.getByText('Run run-1')).toBeVisible()
  })

  test('权限失败显示权限态，不回退到示例事实', async ({ page }) => {
    const state = initialState()
    state.permission = true
    await openWorkbench(page, state)
    await page.locator('button.context-submit').click()
    await expect(page.getByRole('alert')).toContainText('需要登录或资源权限')
    await expect(page.getByText('喷火龙')).toHaveCount(0)
  })

  test('危险来源协议不会变成可点击外链', async ({ page }) => {
    const state = initialState()
    state.unsafeSource = true
    await openWorkbench(page, state)
    await page.locator('button.context-submit').click()
    await expect(page.getByRole('heading', { name: '喷火龙' })).toBeVisible()
    await expect(page.locator('a[href^="javascript:"]')).toHaveCount(0)
    await expect(page.getByText('服务端未返回可验证 HTTP(S) 来源')).toBeVisible()
  })

  test('空结果保持明确空态，不伪造样例内容', async ({ page }) => {
    const state = initialState()
    state.emptyFacts = true
    await openWorkbench(page, state)
    await page.locator('button.context-submit').click()
    await expect(page.getByText('该 data release 没有可展示的事实')).toBeVisible()
    await expect(page.getByText('喷火龙')).toHaveCount(0)
  })

  test('收藏资源可创建、从服务端回读并删除', async ({ page }) => {
    const state = initialState()
    await openWorkbench(page, state)
    await page.getByLabel('收藏资源类型').selectOption('pokemon')
    await page.getByLabel('收藏资源键').fill('charizard')
    await page.getByLabel('收藏展示备注').fill('我的喷火龙')
    await page.getByRole('button', { name: '加入收藏' }).click()
    await expect(page.getByRole('heading', { name: '我的喷火龙' })).toBeVisible()
    expect(state.favorites).toHaveLength(1)

    page.once('dialog', dialog => dialog.accept())
    await page.getByRole('button', { name: '删除收藏 我的喷火龙' }).click()
    await expect(page.getByText('当前主体还没有收藏资源。')).toBeVisible()
    expect(state.favorites).toHaveLength(0)
  })
})
