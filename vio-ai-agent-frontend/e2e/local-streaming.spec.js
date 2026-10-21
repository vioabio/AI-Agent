import { test, expect } from '@playwright/test'

// These deterministic fixture tests retain traces/screenshots; no video codec required.
test.use({ video: 'off' })

for (const view of ['/game-master', '/super-agent']) {
  test(`本地聊天逐段渲染且完成后不重复正文 ${view}`, async ({ page }) => {
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
        writeText: async text => {
          if (window.failCopy) throw new Error('Clipboard permission denied')
          window.copiedMessage = text
        }
      } })
    })
    let finished = false
    const runId = 'run-local-stream-fixture'
    let conversationId = ''
    await page.route(url => url.pathname.startsWith('/api/'), async route => {
      const request = route.request()
      const url = new URL(request.url())
      let body
      if (url.pathname === '/api/auth/session') {
        body = { mode: 'disabled', authenticated: false, accessDenied: false,
          principalId: null, tenantId: null, roles: [], serverTime: new Date().toISOString(),
          absoluteExpiresAt: null, loginUrl: null, logoutUrl: null,
          csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' } }
      } else if (url.pathname.endsWith('/messages')) {
        body = finished ? [
          { role: 'user', content: '介绍杰尼龟' },
          { role: 'assistant', content: '杰尼龟是水属性。阵容需要确认赛制。' }
        ] : []
      } else if (url.pathname === '/api/chat/runs' && request.method() === 'POST') {
        conversationId = request.postDataJSON().conversationId
        body = { runId, conversationId, status: 'QUEUED' }
      } else if (url.pathname.startsWith(`/api/chat/runs/${runId}/events`)) {
        const cursor = Number(request.headers()['last-event-id'] || url.searchParams.get('afterEventId') || 0)
        const events = [
          { id: '1', type: 'run.started', data: {} },
          { id: '2', type: 'answer.delta', data: { text: '杰尼龟是水属性。' } },
          ...(finished ? [
            { id: '3', type: 'answer.delta', data: { text: '阵容需要确认赛制。' } },
            { id: '4', type: 'run.completed', data: { answer: '杰尼龟是水属性。阵容需要确认赛制。' } }
          ] : [])
        ].filter(event => Number(event.id) > cursor)
        body = { events }
      } else if (url.pathname === `/api/chat/runs/${runId}`) {
        body = { runId, conversationId, status: finished ? 'COMPLETED' : 'RUNNING' }
      } else {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
        return
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
    })
    await page.goto(view)
    const input = page.getByRole('textbox', { name: '输入消息' })
    await input.fill('介绍杰尼龟')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    const answer = page.locator('.assistant-bubble').filter({ hasText: '杰尼龟是水属性。' })
    await expect(answer).toHaveText('杰尼龟是水属性。')
    await expect(page.getByRole('button', { name: '停止任务' })).toBeVisible()
    await expect(input).toBeDisabled()
    await expect(page.getByRole('button', { name: '编辑消息', exact: true })).toBeDisabled()
    await expect(page.locator('.user-row').getByRole('button', { name: '复制消息', exact: true })).toBeEnabled()
    finished = true
    await expect(answer).toHaveText('杰尼龟是水属性。阵容需要确认赛制。')
    await expect(input).toBeEnabled()
    await expect(page.locator('.audit-details')).not.toHaveAttribute('open', '')
    await page.reload()
    await expect(page.locator('.assistant-bubble').filter({ hasText: '杰尼龟是水属性。' }))
      .toHaveText('杰尼龟是水属性。阵容需要确认赛制。')

    // Reloaded history supports copying and editing without mutating the original.
    const original = page.locator('.user-bubble').filter({ hasText: '介绍杰尼龟' })
    await page.locator('.user-row').getByRole('button', { name: '复制消息', exact: true }).click()
    await expect.poll(() => page.evaluate(() => window.copiedMessage)).toBe('介绍杰尼龟')
    await expect(page.locator('.action-status')).toHaveText('消息已复制')
    await page.evaluate(() => { window.failCopy = true })
    await page.locator('.user-row').getByRole('button', { name: '复制消息', exact: true }).click()
    await expect(page.locator('.action-status')).toContainText('复制失败')

    await input.fill('尚未发送的草稿')
    await page.getByRole('button', { name: '编辑消息', exact: true }).click()
    await expect(input).toHaveValue('介绍杰尼龟')
    await expect(input).toBeFocused()
    await input.fill('改动后取消')
    await page.getByRole('button', { name: '取消编辑', exact: true }).click()
    await expect(input).toHaveValue('尚未发送的草稿')
    await expect(original).toHaveText('介绍杰尼龟')

    await page.getByRole('button', { name: '编辑消息', exact: true }).click()
    await input.fill('介绍杰尼龟的特性')
    const sent = page.waitForRequest(request => request.method() === 'POST'
      && new URL(request.url()).pathname === '/api/chat/runs')
    await input.press('Enter')
    expect((await sent).postDataJSON().prompt).toBe('介绍杰尼龟的特性')
    await expect(page.locator('.user-bubble')).toHaveText(['介绍杰尼龟', '介绍杰尼龟的特性'])
    await expect(page.getByRole('button', { name: '取消编辑', exact: true })).toHaveCount(0)
  })
}
