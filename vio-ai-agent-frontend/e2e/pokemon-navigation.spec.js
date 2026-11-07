import { test, expect } from '@playwright/test'

test.use({ video: 'off' })
const names = [
  { nationalNo: 7, nameZh: '杰尼龟', slug: 'squirtle', aliases: ['杰尼龟', '傑尼龜'] },
  { nationalNo: 25, nameZh: '皮卡丘', slug: 'pikachu', aliases: ['皮卡丘'] },
  { nationalNo: 478, nameZh: '雪妖女', slug: 'froslass', aliases: ['雪妖女'] }
]
for (const width of [1440, 1080, 390]) {
  test(`回答定位图鉴、历史入口与名称查询 ${width}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const queries = []
    await page.route(url => url.pathname.startsWith('/api/'), async route => {
      const url = new URL(route.request().url())
      if (url.pathname === '/api/auth/session') return route.fulfill({ json: { mode: 'disabled', authenticated: false, accessDenied: false,
        principalId: null, tenantId: null, roles: [], serverTime: new Date().toISOString(), absoluteExpiresAt: null, loginUrl: null, logoutUrl: null,
        csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' } } })
      if (url.pathname === '/api/pokemon/reference/status') return route.fulfill({ json: { enabled: true, status: 'LOCAL_REFERENCE', dataReleaseId: 'pokeapi-reference-v1' } })
      if (url.pathname === '/api/pokemon/reference/names') return route.fulfill({ json: names })
      if (url.pathname.endsWith('/messages')) return route.fulfill({ json: [
        { role: 'user', content: '介绍皮卡丘' }, { role: 'assistant', content: '皮卡丘是电属性宝可梦。' },
        { role: 'user', content: '再介绍雪妖女' }, { role: 'assistant', content: '雪妖女是冰与幽灵属性，也可以比较杰尼龟。' }
      ] })
      if (url.pathname === '/api/pokemon/facts') {
        queries.push(Object.fromEntries(url.searchParams))
        const id = Number(url.searchParams.get('nationalNo') || url.searchParams.get('slug'))
        const name = names.find(n => n.nationalNo === id)
        const stats = id === 25 ? { type1: '电', hp: 35, attack: 55, defense: 40, spAttack: 50, spDefense: 50, speed: 90 }
          : { type1: '水', hp: 44, attack: 48, defense: 65, spAttack: 50, spDefense: 64, speed: 43 }
        return route.fulfill({ json: { items: name ? [{ formId: id, nationalNo: id, speciesNameZh: name.nameZh, slug: name.slug, dataReleaseId: 'pokeapi-reference-v1', ...stats }] : [] } })
      }
      if (url.pathname === '/api/chat/conversations') return route.fulfill({ json: { items: [] } })
      return route.fulfill({ status: 404, json: {} })
    })
    await page.goto('/game-master')
    await page.getByRole('button', { name: '查看图鉴 · 雪妖女', exact: true }).click()
    const facts = page.locator('.facts-panel')
    await expect(facts.locator('.fact-card')).toContainText('雪妖女')
    expect(queries.at(-1).nationalNo).toBe('478')
    // A second species in the same answer has its own target.
    if (width < 1280) await page.getByRole('button', { name: '关闭资料侧栏' }).click()
    await page.getByRole('button', { name: '查看图鉴 · 杰尼龟', exact: true }).click()
    await expect(facts.locator('.fact-card')).toContainText('杰尼龟')
    const input = page.getByRole('combobox', { name: '宝可梦名称或编号', exact: true })
    await expect(facts.locator('.fact-search-options')).toHaveCount(0)
    await input.fill('杰尼')
    await expect(page.locator('#pokemon-name-options option')).toHaveCount(1)
    await input.fill('杰尼龟')
    await facts.getByRole('button', { name: '查询', exact: true }).click()
    await expect.poll(() => queries.at(-1)?.nationalNo).toBe('7')
    expect(queries.at(-1).slug).toBeUndefined()
    await input.fill('SQUIRTLE')
    await input.press('Enter')
    await expect(facts.locator('.fact-card')).toContainText('杰尼龟')
    await input.fill('#025')
    await input.press('Enter')
    await expect(facts.locator('.fact-card')).toContainText('皮卡丘')
    expect(queries.at(-1).nationalNo).toBe('25')
    await facts.getByRole('button', { name: '清空图鉴搜索', exact: true }).click()
    await expect(input).toHaveValue('')
    await expect(input).toBeFocused()
    await expect(input).toHaveCSS('border-top-width', '0px')
    await expect(input).toHaveCSS('outline-style', 'none')
    await page.getByRole('search', { name: '图鉴搜索' }).screenshot({ path: testInfo.outputPath('search-focused.png') })
    // Clearing the search field keeps the reference being read in view.
    await expect(facts.locator('.fact-card')).toContainText('皮卡丘')
    // Historical answers still expose their own species; mobile overlay can be closed.
    if (width < 1280) await page.getByRole('button', { name: '关闭资料侧栏' }).click()
    await page.getByRole('button', { name: '查看图鉴 · 皮卡丘', exact: true }).click()
    await expect(facts.locator('.fact-card')).toContainText('皮卡丘')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const form = page.getByRole('search', { name: '图鉴搜索' })
    expect((await form.getByRole('combobox').boundingBox()).width).toBeGreaterThan(110)
    await expect(form.getByRole('button', { name: '查询', exact: true })).toHaveText('搜索')
    await form.screenshot({ path: testInfo.outputPath('search.png') })
    await facts.screenshot({ path: testInfo.outputPath('pokedex.png'), animations: 'disabled' })
  })
}
