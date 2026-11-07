import { test, expect } from '@playwright/test'

test.use({ video: 'off' })

for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
  test(`宝可梦特色回答展示与复制 ${viewport.width}`, async ({ page }) => {
    await page.setViewportSize(viewport)
    const answer = '雪妖女可以从速度与干扰的角度考虑定位。\n\n### 图鉴速览（资料事实）\n属性：冰 / 幽灵\n速度种族值为 **110**。\n\n### 培养方案（策略建议）\n- 先明确队伍需要的角色，再决定招式。\n- 当前配置尚未验证赛制合法性。\n\n| 培养项目 | 建议 |\n| --- | --- |\n| 优先方向 | 速度与角色配合 |\n| 招式选择 | 结合资料与队伍确认 |\n\n### 队伍搭配（策略建议）\n先检查队伍弱点覆盖，再讨论具体队友。\n\n### 训练家提醒\n当前没有热门阵容数据，不能据此判断环境排名。\n[自行访问来源](https://www.smogon.com/forums/threads/example.3712513/)'
    await page.addInitScript(() => Object.defineProperty(navigator, 'clipboard', { configurable: true,
      value: { writeText: async value => { window.copiedAnswer = value } } }))
    await page.route(url => url.pathname.startsWith('/api/'), async route => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/auth/session') {
        await route.fulfill({ json: { mode: 'disabled', authenticated: false, accessDenied: false,
          principalId: null, tenantId: null, roles: [], serverTime: new Date().toISOString(),
          absoluteExpiresAt: null, loginUrl: null, logoutUrl: null,
          csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' } } })
      } else if (path.endsWith('/messages')) {
        await route.fulfill({ json: [{ role: 'user', content: '介绍雪妖女' }, { role: 'assistant', content: answer }] })
      } else await route.fulfill({ status: 404, json: {} })
    })
    await page.goto('/game-master')
    const body = page.locator('.assistant-bubble').filter({ hasText: '速度种族值' })
    await expect(body.locator('.answer-type-badge')).toHaveText(['冰', '幽灵'])
    await expect(body.locator('table th')).toHaveText(['培养项目', '建议'])
    await expect(body.locator('.answer-section.note')).toContainText('没有热门阵容数据')
    await expect(body.locator('ul li')).toHaveCount(2)
    const link=body.getByRole('link',{name:'自行访问来源'})
    await expect(link).toHaveAttribute('href','https://www.smogon.com/forums/threads/example.3712513/')
    await expect(link).toHaveAttribute('target','_blank')
    for(const row of await page.locator('.message-row').all()) {
      const bubble=await row.locator('.message-bubble').boundingBox()
      const actions=await row.locator('.message-actions').boundingBox()
      expect(actions.y).toBeGreaterThanOrEqual(bubble.y+bubble.height)
    }
    await body.locator('..').getByRole('button', { name: '复制消息', exact: true }).click()
    await expect.poll(() => page.evaluate(() => window.copiedAnswer)).toBe(answer)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await body.scrollIntoViewIfNeeded()
    await page.screenshot({ path: `../artifacts/pokemon-answer-${viewport.width}-2026-10-23.png` })
  })
}
