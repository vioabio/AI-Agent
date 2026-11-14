import { test, expect } from '@playwright/test'
test.use({ video: 'off' })

test('typed fact cards survive replay and invalid blocks preserve readable text', async ({ page }) => {
  let finished = false
  const runId = 'run-u2-browser-fixture'
  let conversationId = ''
  const block = { schemaVersion: 'answer-block-v1', blockId: 'pokemon-39', kind: 'pokemon', formId: 39, name: '胖丁', types: ['一般', '妖精'], stats: [115, 45, 20, 45, 25, 20], gameReleaseId: 'pokemon-scarlet-violet', dataReleaseId: 'pokeapi-reference-v1', evidenceDigest: `sha256:${'a'.repeat(64)}` }
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const url = new URL(route.request().url())
    let body
    if (url.pathname === '/api/auth/session') body = { mode: 'disabled', authenticated: false, accessDenied: false, principalId: null, tenantId: null, roles: [], serverTime: '2026-11-13T00:00:00Z', absoluteExpiresAt: null, loginUrl: null, logoutUrl: null, csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' } }
    else if (url.pathname.endsWith('/messages')) body = finished ? [{ role: 'user', content: '介绍胖丁' }, { role: 'assistant', content: '已核验的图鉴正文。', runId }] : []
    else if (url.pathname === '/api/chat/runs' && route.request().method() === 'POST') {
      conversationId = route.request().postDataJSON().conversationId
      body = { runId, conversationId, status: 'QUEUED' }
    } else if (url.pathname.includes(`/chat/runs/${runId}/events`)) {
      const cursor = Number(route.request().headers()['last-event-id'] || url.searchParams.get('afterEventId') || 0)
      const events = [
        { id: '1', type: 'run.started', data: {} },
        { id: '2', type: 'context.confirmed', data: { gameReleaseId: block.gameReleaseId, dataReleaseId: block.dataReleaseId } },
        { id: '3', type: 'answer.delta', data: { text: '已核验的图鉴正文。' } },
        { id: '4', type: 'fact.card', data: { answerBlock: block } },
        { id: '5', type: 'fact.card', data: { answerBlock: block } },
        { id: '6', type: 'fact.card', data: { answerBlock: { ...block, blockId: 'pokemon-25', formId: 25, dataReleaseId: 'wrong' } } },
        ...(finished ? [{ id: '7', type: 'run.completed', data: { answer: '已核验的图鉴正文。' } }] : [])
      ].filter(e => Number(e.id) > cursor)
      body = { events }
    } else if (url.pathname === `/api/chat/runs/${runId}`) body = { runId, conversationId, status: finished ? 'COMPLETED' : 'RUNNING' }
    else return route.fulfill({ status: 404, json: {} })
    return route.fulfill({ json: body })
  })
  await page.goto('/game-master')
  await page.getByRole('textbox', { name: '输入消息' }).fill('介绍胖丁')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  const card = page.getByRole('region', { name: '已核验图鉴卡片' })
  await expect(card).toHaveCount(1)
  await expect(card).toContainText('一般 / 妖精')
  await expect(card).toContainText('115')
  await expect(card.getByRole('button', { name: '查看图鉴', exact: true })).toBeEnabled()
  finished = true
  await expect(page.getByRole('textbox', { name: '输入消息' })).toBeEnabled()
  await expect(card).toHaveCount(1)
  await expect(page.locator('.assistant-bubble').filter({ hasText: '已核验的图鉴正文。' })).toHaveCount(1)
})
