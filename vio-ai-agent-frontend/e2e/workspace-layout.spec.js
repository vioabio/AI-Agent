import { test, expect } from '@playwright/test'
test.use({ video: 'off' })

const fact = { nationalNo: 445, formId: 445, speciesNameZh: '烈咬陆鲨', slug: 'garchomp', type1: '龙', type2: '地面',
  hp: 108, attack: 130, defense: 95, spAttack: 80, spDefense: 85, speed: 102, dataReleaseId: 'pokeapi-reference-v1',
  gameReleaseId: 'pokemon-scarlet-violet', sourceUrl: 'https://pokeapi.co/api/v2/pokemon/445/', releaseStatus: 'LOCAL_REFERENCE' }
const answer = '### 图鉴事实\n烈咬陆鲨是龙／地面属性宝可梦，速度种族值为 **102**。图鉴资料用于核对基础信息，不能证明当前热门程度。\n\n### 培养思路\n- 先明确队伍需要的角色，再分配攻击、速度与耐久。\n- 招式、性格和道具需要结合所选规则及实际对局核对。\n\n### 队友搭配\n| 队友角色 | 考虑方向 |\n| --- | --- |\n| 抗性补充 | 检查全队共同弱点 |\n| 节奏支持 | 根据实际招式与对局选择 |\n\n可以继续补充你的队伍配置，再逐项核对搭配。'
const usage = { schemaVersion: 'model-usage-v1', scope: 'EXECUTION_ATTEMPT', attemptId: 'a1111111-1111-4111-a111-111111111111',
  coverage: 'INSTRUMENTED', modelCallCount: 0, callsWithUsage: 0, usageStatus: 'COMPLETE',
  totals: { inputTokens: 0, outputTokens: 0, totalTokens: 0 }, calls: [], modelDurationMs: 0, recordsTruncated: false, cost: { status: 'UNKNOWN' } }
async function fixture(page) {
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  let run
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (path === '/api/auth/session') return route.fulfill({ json: { mode: 'disabled', authenticated: false, accessDenied: false,
      principalId: null, tenantId: null, roles: [], serverTime: new Date().toISOString(), absoluteExpiresAt: null, loginUrl: null, logoutUrl: null,
      csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' } } })
    if (path === '/api/pokemon/reference/status') return route.fulfill({ json: { enabled: true, status: 'LOCAL_REFERENCE', dataReleaseId: 'pokeapi-reference-v1' } })
    if (path === '/api/pokemon/reference/names') return route.fulfill({ json: [{ nationalNo: 445, nameZh: '烈咬陆鲨', slug: 'garchomp', aliases: ['烈咬陆鲨'] }] })
    if (path === '/api/pokemon/facts') return route.fulfill({ json: { items: [fact] } })
    if (path.endsWith('/messages')) return route.fulfill({ json: [] })
    if (path === '/api/conversations') return route.fulfill({ json: { items: [{ id: 'ui-layout', title: '烈咬陆鲨的培养与搭配', updatedAt: new Date().toISOString() }] } })
    if (path === '/api/chat/runs') {
      run = { ...route.request().postDataJSON(), runId: 'run-layout', status: 'RUNNING' }
      return route.fulfill({ status: 202, json: run })
    }
    if (path === '/api/chat/runs/run-layout/events') return route.fulfill({ json: { events: [
      { eventId: 1, type: 'run.started', payload: {} },
      { eventId: 2, type: 'tool.finished', payload: { toolName: 'lookupPokemonReference', governanceEventId: 'layout-tool', status: 'SUCCEEDED', durationMs: 20 } },
      { eventId: 3, type: 'answer.delta', payload: { text: answer } },
      { eventId: 4, type: 'run.completed', payload: { modelUsage: usage, citations: [{ title: 'PokéAPI', url: fact.sourceUrl }], limitations: ['参考资料不代表排位合法性。'] } }
    ] } })
    if (path === '/api/chat/runs/run-layout') return route.fulfill({ json: run })
    return route.fulfill({ json: { items: [] } })
  })
  await page.goto('/game-master?conversation=ui-layout&dataReleaseId=pokeapi-reference-v1')
  const input = page.getByRole('textbox', { name: '输入消息' })
  await expect(input).toBeEnabled()
  await input.fill('介绍烈咬陆鲨，并给我一些培养和队友搭配建议。')
  await input.press('Enter')
  await expect(page.locator('.assistant-bubble').filter({ hasText: '图鉴事实' })).toContainText('速度种族值为 102')
  await expect(input).toBeEnabled()
  return errors
}

test('宽屏：回复内元信息、图鉴卡片联动、可调宽度和默认隐藏调试', async ({ page }) => {
  await page.setViewportSize({ width: 1586, height: 992 })
  const errors = await fixture(page)
  const list = page.locator('.message-list'), sidebar = page.locator('.resource-sidebar')
  expect((await list.boundingBox()).height).toBeGreaterThan(750)
  const footer = page.locator('.message-content .compact-run')
  expect((await footer.boundingBox()).height).toBeLessThan(55)
  await expect(page.getByLabel('模型用量与耗时')).toHaveCount(0)
  await expect(page.locator('.process-details')).toHaveCount(0)
  await page.getByRole('button', { name: '查看图鉴 · 烈咬陆鲨', exact: true }).click()
  await expect(sidebar.locator('.fact-card h3')).toHaveText('烈咬陆鲨')
  await expect(page.locator('.pokemon-card-link')).toContainText('已在右侧打开')
  const statColors = await sidebar.locator('.stat-track>span').evaluateAll(bars => bars.map(bar => getComputedStyle(bar).backgroundColor))
  expect(new Set(statColors).size).toBe(6)
  const track = await sidebar.locator('.stat-track').nth(1).boundingBox()
  const bar = await sidebar.locator('.stat-track>span').nth(1).boundingBox()
  expect(bar.width / track.width).toBeCloseTo(130 / 255, 1)
  const handle = page.getByRole('separator', { name: '调整资料面板宽度' })
  const before = (await sidebar.boundingBox()).width
  await handle.focus(); await page.keyboard.press('ArrowLeft')
  expect((await sidebar.boundingBox()).width).toBeGreaterThan(before)
  const grip = await handle.boundingBox()
  await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2)
  await page.mouse.down(); await page.mouse.move(grip.x - 30, grip.y + grip.height / 2); await page.mouse.up()
  expect((await sidebar.boundingBox()).width).toBeGreaterThan(before + 20)
  await page.getByRole('button', { name: '来源 1' }).click()
  await expect(page.getByRole('link', { name: 'PokéAPI', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '来源 1' }).click()
  await list.evaluate(element => { element.scrollTop = 0 })
  await page.screenshot({ path: '../artifacts/workspace-redesign-2026-11-04/desktop.png', animations: 'disabled' })
  expect(errors).toEqual([])
})

test('1080窗口：默认收起资料，抽屉焦点、关闭返回、导航折叠与开发者设置', async ({ page }) => {
  await page.setViewportSize({ width: 1080, height: 800 })
  const errors = await fixture(page)
  await expect(page.locator('.resource-sidebar')).toBeHidden()
  expect((await page.locator('.conversation-pane').boundingBox()).width).toBeGreaterThan(950)
  await page.getByRole('button', { name: '查看图鉴 · 烈咬陆鲨', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '资料与队伍侧栏' })
  await expect(drawer).toBeVisible()
  await expect(drawer.locator('.fact-card h3')).toHaveText('烈咬陆鲨')
  await page.keyboard.press('Shift+Tab')
  expect(await drawer.evaluate(element => element.contains(document.activeElement))).toBe(true)
  await page.keyboard.press('Escape')
  await expect(drawer).toBeHidden()
  await expect(page.getByRole('button', { name: '查看图鉴 · 烈咬陆鲨', exact: true })).toBeFocused()
  await page.getByRole('button', { name: '展开导航' }).click()
  await expect(page.locator('.recent-conversations')).toBeVisible()
  await page.getByRole('button', { name: '收起导航' }).click()
  await page.getByRole('button', { name: '工作区设置', exact: true }).click()
  const settings = page.getByRole('dialog', { name: '工作区设置' })
  await expect(settings.getByRole('switch', { name: '开发者模式' })).not.toBeChecked()
  await settings.getByRole('switch', { name: '开发者模式' }).check()
  await settings.getByRole('button', { name: '关闭工作区设置' }).click()
  await expect(page.getByLabel('模型用量与耗时')).toBeVisible()
  await expect(page.locator('.process-details')).toBeVisible()
  await page.getByRole('button', { name: '工作区设置', exact: true }).click()
  await settings.getByRole('switch', { name: '开发者模式' }).uncheck()
  await page.keyboard.press('Escape')
  await expect(page.getByLabel('模型用量与耗时')).toHaveCount(0)
  await page.locator('.message-list').evaluate(element => { element.scrollTop = 0 })
  await page.screenshot({ path: '../artifacts/workspace-redesign-2026-11-04/narrow.png', animations: 'disabled' })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(errors).toEqual([])
})

for (const width of [390, 640, 760, 820, 1080, 1280, 1440, 1920]) {
  test(`${width}网页宽度：导航收起无残留文字，筛选标签单行，输入框保持可见`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 })
    const errors = await fixture(page)
    if (width < 768) await expect(page.locator('.workspace-nav')).toBeHidden()
    const header = page.locator('.workspace-header')
    expect((await header.boundingBox()).height).toBeLessThanOrEqual(width < 768 ? 110 : 64)
    const labels = await page.locator('.scenario-bar label>span').evaluateAll(elements =>
      elements.map(element => ({ height: element.getBoundingClientRect().height, fontSize: parseFloat(getComputedStyle(element).fontSize) })))
    for (const label of labels) expect(label.height).toBeLessThanOrEqual(label.fontSize * 1.6)
    for (const selector of ['.navigation-toggle', '.scenario-bar', '.preferences-toggle', '.resource-toggle']) {
      const box = await page.locator(selector).boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width).toBeLessThanOrEqual(width + 1)
    }
    const composer = await page.locator('.input-area').boundingBox()
    expect(composer.y + composer.height).toBeLessThanOrEqual(800)
    expect((await page.locator('.message-list').boundingBox()).height).toBeGreaterThan(500)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth && document.documentElement.scrollHeight <= innerHeight)).toBe(true)
    if (width < 768) {
      await page.locator('.navigation-toggle').click()
      await expect(page.locator('.workspace-nav')).toBeVisible()
      await page.locator('.nav-scrim').click({ position: { x: width - 5, y: 20 } })
      await expect(page.locator('.workspace-nav')).toBeHidden()
    }
    await page.screenshot({ path: `../artifacts/web-adaptation-20261117/${width}.png`, animations: 'disabled' })
    expect(errors).toEqual([])
  })
}

test('网页缩窄后关闭的导航内容不遮挡对话，资料抽屉保持在视口内', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await fixture(page)
  await page.setViewportSize({ width: 715, height: 665 })
  await expect(page.locator('.workspace-nav')).toBeHidden()
  await page.getByRole('button', { name: '查看图鉴 · 烈咬陆鲨', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '资料与队伍侧栏' })
  await expect(drawer).toBeVisible()
  const box = await drawer.boundingBox()
  const header = await page.locator('.workspace-header').boundingBox()
  expect(box.y).toBeGreaterThanOrEqual(header.y + header.height - 1)
  expect(box.y + box.height).toBeLessThanOrEqual(665)
  await page.keyboard.press('Escape')
  await expect(drawer).toBeHidden()
  await expect(page.getByRole('textbox', { name: '输入消息' })).toBeEnabled()
})
