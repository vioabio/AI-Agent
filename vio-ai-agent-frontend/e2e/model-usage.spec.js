import { test, expect } from '@playwright/test'
test.use({ video: 'off' })
// Usage and execution details are opt-in workspace developer features.
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('vio.workspace.developer-mode', 'true'))
})
for (const width of [1440, 390]) {
  test(`历史消息中的用量默认折叠并适配网页 ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 900 })
    const attempt = { schemaVersion: 'model-usage-v1', attemptId: 'a1111111-1111-4111-a111-111111111111', scope: 'EXECUTION_ATTEMPT',
      coverage: 'INSTRUMENTED', modelCallCount: 1, callsWithUsage: 1, usageStatus: 'COMPLETE',
      totals: { inputTokens: 11, outputTokens: 3, totalTokens: 14 }, modelDurationMs: 300, recordsTruncated: false,
      taskBudget: { schemaVersion: 'task-budget-v1', scope: 'RUN', budgetId: 'b2222222-2222-4222-a222-222222222222',
        accounting: 'MAX_RESERVATION_OR_PROVIDER_V1', maxModelCalls: 16, tokenLimit: 10000, stateKnown: true,
        modelCalls: 1, reservedTokens: 478, chargedTokens: 478, remainingModelCalls: 15, remainingTokens: 9522,
        status: 'ACTIVE', closed: false, blockReason: null },
      contextBudget: { schemaVersion: 'context-budget-v1', estimator: 'cl100k-base-proxy-v1', calibration: 'PROXY_UNCALIBRATED', status: 'CHECKED',
        contextTokens: 1024, inputLimit: 896, outputReserve: 128, historyLimit: 256, availableHistoryTurns: 3, omittedHistoryTurns: 1,
        requestsChecked: 1, safetyPercent: 125, lastEstimate: { system: 100, history: 50, toolDefinitions: 100, toolResults: 20, currentMessages: 80, inputTokens: 350 } },
      cost: { status: 'UNKNOWN' }, secret: 'private-provider-body', calls: [{ callNo: 1, mode: 'STREAM', outcome: 'RETURNED',
        configuredModelId: 'fixture-model', durationMs: 300, firstTextDeltaMs: 100, usageStatus: 'PROVIDER_REPORTED',
        tokens: { inputTokens: 11, outputTokens: 3, totalTokens: 14 } }] }
    await page.route(url => url.pathname.startsWith('/api/'), async route => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/auth/session') await route.fulfill({ json: { mode: 'disabled', authenticated: false,
        accessDenied: false, principalId: null, tenantId: null, roles: [], serverTime: new Date().toISOString(),
        absoluteExpiresAt: null, loginUrl: null, logoutUrl: null,
        csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' } } })
      else if (path.endsWith('/messages')) await route.fulfill({ json: [{ role: 'user', content: '介绍杰尼龟' },
        { role: 'assistant', content: '杰尼龟是水属性宝可梦。', metadata: { modelUsageAttempts: [attempt] } },
        { role: 'assistant', content: '用量未取得的回答。', metadata: { modelUsageAttempts: [{ ...attempt, usageStatus: 'UNKNOWN', callsWithUsage: 0,
          totals: null, calls: [{ ...attempt.calls[0], usageStatus: 'UNKNOWN', tokens: null }] }] } }] })
      else await route.fulfill({ status: 404, json: {} })
    })
    await page.goto('/game-master')
    const details = page.getByLabel('模型用量与耗时')
    await expect(details).toHaveCount(2)
    await expect(details.first()).not.toHaveAttribute('open')
    await expect(page.locator('.context-history-notice').first()).toBeVisible()
    await details.first().locator('summary').click()
    await expect(details.first().locator('dd')).toHaveText(['1 次', '11', '3', '0.30 秒', '未知'])
    await expect(details.first()).toContainText('本地估算，非计费')
    await expect(details.first()).toContainText('350 / 896')
    await expect(details.first()).toContainText('模型 1 / 16 次')
    await expect(details.first()).toContainText('478 / 10000')
    await expect(details.first()).toContainText('不等于真实用量或费用')
    await details.last().locator('summary').click()
    await expect(details.last()).toContainText('未把缺失值记为 0')
    await expect(details.last().locator('dd')).toHaveText(['1 次', '未知', '未知', '0.30 秒', '未知'])
    await expect(page.locator('body')).not.toContainText('private-provider-body')
    await expect(page.locator('body')).not.toContainText(attempt.attemptId)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await details.first().scrollIntoViewIfNeeded()
    await page.screenshot({ path: `../artifacts/s4-task-budget-${width}.png` })
  })
}
test('连续发送后上一条回答保留用量，当前任务不重复展示', async ({ page }) => {
  let count = 0
  const runs = new Map()
  const zero = { schemaVersion: 'model-usage-v1', scope: 'EXECUTION_ATTEMPT', coverage: 'INSTRUMENTED', modelCallCount: 0,
    callsWithUsage: 0, usageStatus: 'COMPLETE', totals: { inputTokens: 0, outputTokens: 0, totalTokens: 0 },
    calls: [], modelDurationMs: 0, recordsTruncated: false, cost: { status: 'UNKNOWN' } }
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/auth/session') return route.fulfill({ json: { mode: 'disabled', authenticated: false, accessDenied: false,
      principalId: null, tenantId: null, roles: [], serverTime: new Date().toISOString(), absoluteExpiresAt: null, loginUrl: null, logoutUrl: null,
      csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' } } })
    if (path.endsWith('/messages')) return route.fulfill({ json: [] })
    if (path === '/api/chat/runs') {
      count++
      const run = { ...route.request().postDataJSON(), runId: `run-usage-${count}`, status: 'RUNNING' }
      runs.set(run.runId, run)
      return route.fulfill({ status: 202, json: run })
    }
    const runId = path.split('/')[4]
    if (runs.has(runId)) {
      if (path.includes('/events')) return route.fulfill({ json: { events: [
        { eventId: 1, type: 'run.started', payload: {} },
        { eventId: 2, type: 'tool.finished', payload: { toolName: 'searchPokemonGuides', governanceEventId: 'cache-hit', status: 'SUCCEEDED', cached: true, durationMs: 1 } },
        { eventId: 3, type: 'tool.finished', payload: { toolName: 'searchPokemonGuides', governanceEventId: 'denied', status: 'REJECTED' } },
        { eventId: 4, type: 'answer.delta', payload: { text: `已回答 ${runId}` } },
        { eventId: 5, type: 'run.completed', payload: { modelUsage: { ...zero,
          attemptId: runId.endsWith('1') ? 'a1111111-1111-4111-a111-111111111111' : 'b2222222-2222-4222-a222-222222222222' } } }
      ] } })
      return route.fulfill({ json: runs.get(runId) })
    }
    return route.fulfill({ status: 404, json: {} })
  })
  await page.goto('/game-master')
  const input = page.locator('textarea')
  await expect(input).toBeEnabled()
  for (const number of [1, 2]) {
    await input.fill(`问题 ${number}`)
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(page.getByLabel('模型用量与耗时')).toHaveCount(number)
    await expect(input).toBeEnabled()
  }
  await expect(page.locator('.message-content .model-usage-details')).toHaveCount(2)
  await page.locator('.process-details summary').click()
  await expect(page.locator('.tool-timeline li')).toHaveCount(2)
  await expect(page.locator('.tool-timeline li').first()).toContainText('复用本次任务结果')
  await expect(page.locator('.tool-timeline .tool-state')).toHaveText(['完成', '失败'])
})
