import { test, expect } from '@playwright/test'
import fixture from './fixtures/model-cost.json' with { type: 'json' }

test.use({ video: 'off' })
for (const width of [1440, 390]) {
  test(`参考费用与缺失明细适配网页 ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 900 })
    const unknown = structuredClone(fixture)
    unknown.cost = unknown.calls[0].cost = { schemaVersion: 'model-cost-v1', kind: 'REFERENCE_ESTIMATE_NOT_INVOICE', status: 'UNKNOWN',
      reason: 'BILLING_DETAILS_MISSING', amount: null, currency: null }
    unknown.calls[0].billingUsage = { schemaVersion: 'billing-usage-v1', status: 'UNKNOWN', cachedInputTokens: null,
      uncachedInputTokens: null, reasoningOutputTokens: null, reasoningIncludedInOutput: true }
    await page.route(url => url.pathname.startsWith('/api/'), async route => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/auth/session') return route.fulfill({ json: { mode: 'disabled', authenticated: false, accessDenied: false,
        principalId: null, tenantId: null, roles: [], serverTime: new Date().toISOString(), absoluteExpiresAt: null, loginUrl: null, logoutUrl: null,
        csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' } } })
      if (path.endsWith('/messages')) return route.fulfill({ json: [
        { role: 'assistant', content: '受控价格测试的回答。', metadata: { modelUsageAttempts: [fixture] } },
        { role: 'assistant', content: '供应商用量可读，缓存明细未取得。', metadata: { modelUsageAttempts: [unknown] } }
      ] })
      return route.fulfill({ status: 404, json: {} })
    })
    await page.goto('/game-master')
    const details = page.getByLabel('模型用量与耗时')
    await expect(details).toHaveCount(2)
    await details.first().locator('summary').click()
    await expect(details.first().locator('dd')).toHaveText(['1 次', '1000', '100', '0.30 秒', '约 USD 0.00094'])
    await expect(details.first()).toContainText('测试价格')
    await expect(details.first()).toContainText('fixture-v1')
    await expect(details.first()).toContainText('缓存命中 400 Token')
    await expect(details.first()).toContainText('推理 30 Token（已包含在输出中）')
    await expect(details.first()).toContainText('不等于账单')
    await expect(details.first().getByRole('link', { name: '价格来源' })).toHaveAttribute('href', 'https://example.invalid/prices')
    await details.last().locator('summary').click()
    await expect(details.last()).toContainText('供应商或 SDK 未提供所需缓存明细')
    await expect(details.last().locator('dd')).toHaveText(['1 次', '1000', '100', '0.30 秒', '未知'])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await details.first().scrollIntoViewIfNeeded()
    await page.screenshot({ path: `../artifacts/s4-cost-${width}.png` })
  })
}
