import { test, expect } from '@playwright/test'
test.use({ video: 'off' })
const timing = { schemaVersion: 'response-timing-v1', scope: 'EXECUTION_ATTEMPT', origin: 'TASK_REGISTERED',
  dispatchWaitMs: 20, firstAnswerReadyMs: 50, observedMs: 60, answerPath: 'BUFFERED', deliveryMeasured: false }
const tool = { schemaVersion: 'tool-timing-v1', scope: 'CALLBACK_ATTEMPTS', complete: true, queueWaitMs: 12,
  authorizationMs: 2, executionMs: 20, observedMs: 35, attempts: [{ accepted: true, callbackStarted: true, callbackCompleted: true,
    queueWaitMs: 12, authorizationMs: 2, executionMs: 20, observedMs: 35 }] }
const usage = { schemaVersion: 'model-usage-v1', scope: 'EXECUTION_ATTEMPT', attemptId: 'a1111111-1111-4111-a111-111111111111',
  coverage: 'INSTRUMENTED', modelCallCount: 0, callsWithUsage: 0, usageStatus: 'COMPLETE', modelDurationMs: 0,
  calls: [], totals: { inputTokens: 0, outputTokens: 0, totalTokens: 0 }, responseTiming: timing,
  toolTimings: [{ eventId: 'fixture', toolName: 'lookup', timing: tool }] }
const auth = { mode: 'disabled', authenticated: false, accessDenied: false, principalId: null, tenantId: null, roles: [],
  serverTime: '2026-10-25T00:00:00Z', absoluteExpiresAt: null, loginUrl: null, logoutUrl: null,
  csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' } }
for (const width of [1440, 390]) {
  test(`历史调度与工具耗时适配网页 ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 900 })
    await page.route(url => url.pathname.startsWith('/api/'), route => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/auth/session') return route.fulfill({ json: auth })
      if (path.endsWith('/messages')) return route.fulfill({ json: [{ role: 'assistant', content: '历史回答', metadata: { modelUsageAttempts: [usage] } }] })
      return route.fulfill({ status: 404, json: {} })
    })
    await page.goto('/game-master')
    const details = page.getByLabel('模型用量与耗时')
    await details.locator('summary').click()
    await expect(details).toContainText('调度等待 0.02 秒')
    await expect(details).toContainText('服务端首段就绪 0.05 秒')
    await expect(details).toContainText('本次为完整答案就绪')
    await expect(page.getByLabel('工具耗时明细')).toContainText('排队 12 ms · 校验 2 ms · 执行 20 ms')
    await expect(page.locator('.client-response-timing')).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await details.scrollIntoViewIfNeeded()
    await page.screenshot({ path: `../artifacts/s4-timing-${width}.png` })
  })
}
test('新请求观测浏览器首段，重新加载历史不会伪造本机接收计时', async ({ page }) => {
  let completed = false
  let current
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/auth/session') return route.fulfill({ json: auth })
    if (path.endsWith('/messages')) return route.fulfill({ json: completed
      ? [{ role: 'assistant', content: '公开回答', metadata: { modelUsageAttempts: [usage] } }] : [] })
    if (path === '/api/chat/runs' && route.request().method() === 'POST') {
      current = { ...route.request().postDataJSON(), runId: 'run-timing-fixture', status: 'RUNNING' }
      return route.fulfill({ status: 202, json: current })
    }
    if (path.includes('/run-timing-fixture/events')) {
      completed = true
      return route.fulfill({ json: { events: [{ eventId: 1, type: 'run.started', payload: {} },
        { eventId: 2, type: 'answer.delta', payload: { text: '公开回答' } },
        { eventId: 3, type: 'run.completed', payload: { modelUsage: usage } }] } })
    }
    if (path.endsWith('/run-timing-fixture')) return route.fulfill({ json: current })
    return route.fulfill({ status: 404, json: {} })
  })
  await page.goto('/game-master')
  await page.locator('textarea').fill('你好')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  const details = page.getByLabel('模型用量与耗时')
  await expect(page.locator('.client-response-timing')).toBeHidden()
  await details.locator('summary').click()
  await expect(page.locator('.client-response-timing')).toBeVisible()
  await expect(page.locator('.client-response-timing')).toContainText('本机收到首段回答')
  await expect(page.locator('.client-response-timing')).toContainText('不代表模型首 Token')
  await page.reload()
  await expect(page.getByLabel('模型用量与耗时')).toBeVisible()
  await expect(page.locator('.client-response-timing')).toHaveCount(0)
})
