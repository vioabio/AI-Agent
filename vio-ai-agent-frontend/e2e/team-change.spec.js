import { test, expect } from '@playwright/test'

test.use({ video: 'off' })

async function fixture(page, mode = 'save') {
  const state = { decision: null, submissions: 0, teamReads: 0 }
  const team = { id: 1, name: '练习队', rulesetId: 'casual-v1', dataReleaseId: 'pokeapi-reference-v1',
    members: [{ formId: 7, slot: 1, build: { level: 50, nature: 'modest', ability: 'torrent', item: 'leftovers', moves: ['surf'],
      evs: { hp: 4, atk: 0, def: 0, spa: 252, spd: 0, spe: 252 }, ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } } }] }
  const preview = { proposalId: 'approval-team', teamId: 1, teamName: team.name, expiresAt: '2099-01-01T00:00:00Z',
    rationale: '<script>window.previewInjected=true</script>按训练目标调整等级',
    changes: [{ slot: 1, member: '杰尼龟', field: '等级', before: '50', after: '51' }],
    calculationEvidence: '未获得本 Run 的有效伤害计算，不推断胜率。', limitations: ['仅保存确认的配置'] }
  let conversationId = 'conversation-team'
  const terminal = () => state.decision !== null
  const answer = () => mode === 'conflict' ? '队伍已被修改，本次预览未保存。' : '已保存并回读队伍「练习队」。'
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const url = new URL(route.request().url()), method = route.request().method()
    let body = {}
    if (url.pathname === '/api/auth/session') body = { mode: 'disabled', authenticated: false, accessDenied: false,
      principalId: null, tenantId: null, roles: [], serverTime: new Date().toISOString(), absoluteExpiresAt: null,
      loginUrl: null, logoutUrl: null, csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' } }
    else if (url.pathname === '/api/pokemon/reference/status') body = { enabled: true, dataReleaseId: 'pokeapi-reference-v1' }
    else if (url.pathname === '/api/chat/runs' && method === 'POST') {
      conversationId = route.request().postDataJSON().conversationId
      body = { runId: 'run-team', conversationId, status: 'RUNNING' }
    } else if (url.pathname === '/api/chat/runs/run-team/events') {
      const cursor = Number(url.searchParams.get('afterEventId') || 0)
      const events = [{ id: '1', type: 'run.started', data: {} },
        { id: '2', type: 'approval.required', data: { approvalId: preview.proposalId, teamChange: preview } }]
      if (terminal()) events.push({ id: '3', type: 'approval.resolved', data: { approved: state.decision } },
        { id: '4', type: state.decision ? 'run.completed' : 'run.failed', data: state.decision ? { answer: answer() } : { errorType: 'APPROVAL_DENIED', reason: '已取消修改' } })
      body = { events: events.filter(event => Number(event.id) > cursor), terminal: terminal() }
    } else if (url.pathname === '/api/chat/runs/run-team') body = { runId: 'run-team', conversationId, status: terminal() ? state.decision ? 'COMPLETED' : 'FAILED' : 'RUNNING' }
    else if (url.pathname === '/api/chat/runs/run-team/team-change/decision') {
      state.submissions++
      await new Promise(resolve => setTimeout(resolve, 250))
      if (mode === 'expired') { await route.fulfill({ status: 410, json: { message: '审批已过期，请重新分析' } }); return }
      state.decision = route.request().postDataJSON().approved
      if (state.decision && mode === 'save') team.members[0].build.level = 51
      body = { allowed: state.decision, reason: state.decision ? '' : '已取消修改' }
    } else if (url.pathname === '/api/chat/runs/run-team/team-change') body = { preview,
      status: !state.decision ? 'DENIED' : mode === 'conflict' ? 'CONFLICT' : 'APPLIED', savedTeam: team, message: mode === 'conflict' ? answer() : null }
    else if (url.pathname === '/api/teams') { state.teamReads++; body = { items: [team] } }
    else if (url.pathname === '/api/teams/1') body = team
    else if (url.pathname.includes('/pokemon/facts/forms/')) body = { formId: 7, slug: 'squirtle', speciesNameZh: '杰尼龟', gameReleaseId: 'pokemon-scarlet-violet', dataReleaseId: 'pokeapi-reference-v1', type1: '水' }
    else if (url.pathname.includes('/messages')) body = []
    else body = { items: [] }
    await route.fulfill({ status: 200, json: body })
  })
  await page.goto('/game-master')
  const input = page.getByRole('textbox', { name: '输入消息' })
  await input.fill('请读取队伍编号1，将第一名等级改为51，先生成预览。')
  await input.press('Enter')
  await expect(page.getByText('队伍修改预览', { exact: true })).toBeVisible()
  return { state, team, input }
}

for (const width of [1440, 390]) {
  test(`确认队伍差异后才保存，阻止重复点击并回读列表 ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const { state, team, input } = await fixture(page)
    expect(state.submissions).toBe(0)
    expect(team.members[0].build.level).toBe(50)
    await expect(page.locator('.proposal-table')).toContainText('杰尼龟等级5051')
    await expect(page.getByRole('button', { name: '确认保存', exact: true })).toBeInViewport()
    await page.screenshot({ path: `../artifacts/campus/s1/preview-${width}.png` })
    expect(await page.evaluate(() => window.previewInjected)).toBeUndefined()
    const beforeReads = state.teamReads
    await page.getByRole('button', { name: '确认保存', exact: true }).click()
    await expect(page.getByRole('button', { name: '处理中…', exact: true })).toBeDisabled()
    await expect(page.locator('.assistant-bubble').filter({ hasText: '已保存并回读' })).toBeVisible()
    await expect.poll(() => state.teamReads).toBeGreaterThan(beforeReads)
    expect(state.submissions).toBe(1)
    expect(team.members[0].build.level).toBe(51)
    await expect(input).toBeEnabled()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  })
}

for (const mode of ['deny', 'expired', 'conflict']) {
  test(`队伍确认异常保留明确状态且不写入 ${mode}`, async ({ page }) => {
    const { state, team } = await fixture(page, mode)
    await page.getByRole('button', { name: mode === 'deny' ? '取消修改' : '确认保存', exact: true }).click()
    if (mode === 'expired') {
      await expect(page.getByText('队伍修改预览', { exact: true })).toBeVisible()
      await expect(page.getByRole('button', { name: '确认保存', exact: true })).toBeEnabled()
      await expect(page.locator('.run-error')).toContainText('过期')
    } else if (mode === 'conflict') await expect(page.locator('.assistant-bubble').filter({ hasText: '本次预览未保存' })).toBeVisible()
    else await expect(page.getByText('队伍修改预览', { exact: true })).toHaveCount(0)
    expect(state.submissions).toBe(1)
    expect(team.members[0].build.level).toBe(50)
  })
}
