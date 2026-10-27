import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { randomBytes, createHash } from 'node:crypto'
import { teamDigest } from '../../tools/campus/team-confirmation.mjs'
import { attemptsFrom } from '../../tools/campus/answer-quality.mjs'
import { normalizeModelUsage } from '../src/api/model-usage.js'

const hash = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex')
function sources() {
  const root = path.resolve('..'), result = {}
  for (const dir of ['src/main/java', 'vio-ai-agent-frontend/src']) {
    const walk = current => {
      for (const item of fs.readdirSync(current, { withFileTypes: true })) {
        const file = path.join(current, item.name)
        if (item.isDirectory()) walk(file)
        else if (/\.(java|js|vue|css)$/.test(item.name)) result[path.relative(root, file).replaceAll('\\', '/')] = hash(file)
      }
    }
    walk(path.join(root, dir))
  }
  for (const file of ['pom.xml', 'vio-ai-agent-frontend/e2e-live/team-workflow.spec.js',
    'vio-ai-agent-frontend/vite.workflow-live.config.js', 'vio-ai-agent-frontend/playwright.workflow-live.config.js',
    'vio-ai-agent-frontend/scripts/live-workflow-target.mjs']) result[file] = hash(path.join(root, file))
  return result
}
async function api(page, route, body, method = body === undefined ? 'GET' : 'POST', expected = 200, discard = false) {
  return page.evaluate(async ({ route, body, method, expected, discard }) => {
    const cookie = document.cookie.split('; ').find(value => value.startsWith('CSRF-TOKEN='))
    const csrf = cookie ? decodeURIComponent(cookie.slice('CSRF-TOKEN='.length)) : ''
    const response = await fetch('/api' + route, { method, credentials: 'include', redirect: 'error',
      headers: { 'Content-Type': 'application/json', ...(csrf ? { 'X-CSRF-TOKEN': csrf } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    if (response.status !== expected) throw new Error(`HTTP ${response.status}; expected ${expected}`)
    if (discard || response.status === 204) return null
    const value = await response.json()
    return value && Object.hasOwn(value, 'code') && Object.hasOwn(value, 'data') ? value.data : value
  }, { route, body, method, expected, discard })
}

for (const scenario of [{ width: 1440, action: 'confirm' }, { width: 390, action: 'deny' }]) {
  test(`真实本地账号、多成员预览与${scenario.action} ${scenario.width}`, async ({ page }, testInfo) => {
    const record = { schemaVersion: 'team-workflow-browser-live-v1', caseId: `${scenario.action}-${scenario.width}`,
      mode: 'REAL_BROWSER_MODEL_HTTP_SQLITE', approvalActor: 'automated dedicated test account',
      humanStrategyAcceptance: 'NOT_PERFORMED', startedAt: new Date().toISOString(), pass: false, sourceSha256: sources() }
    let active, terminal = false
    const apiTraffic = []
    page.on('response', response => {
      const url = new URL(response.url())
      if (url.pathname.startsWith('/api/')) apiTraffic.push({ method: response.request().method(), path: url.pathname, status: response.status() })
    })
    try {
      await page.setViewportSize({ width: scenario.width, height: 900 })
      await page.goto('/game-master')
      await expect(page.getByTestId('local-auth-portal')).toBeVisible()
      const username = 'live_' + randomBytes(6).toString('hex'), password = randomBytes(24).toString('base64url')
      // Registration response, including its recovery code, is discarded rather than saved as evidence.
      await api(page, '/auth/local/register', { username, password, nickname: '浏览器闭环验证' }, 'POST', 201, true)
      await page.getByLabel('用户名', { exact: true }).fill(username)
      await page.getByLabel('密码', { exact: true }).fill(password)
      await page.locator('.local-auth-form button[type=submit]').click()
      await expect(page.getByRole('heading', { name: '宝可梦助手', exact: true })).toBeVisible()
      expect((await api(page, '/auth/session')).authenticated).toBe(true)
      record.runtime = await api(page, '/chat/runtime')
      for (const [name, expected] of Object.entries(record.runtime.implementationSha256)) {
        const source = Object.keys(record.sourceSha256).find(file => file.endsWith('/' + name + '.java'))
        expect(Boolean(source)).toBe(true)
        expect(hash(path.resolve('..', source.replace('src/main/java/', 'target/classes/').replace(/\.java$/, '.class')))).toBe(expected)
      }
      const build = { level: 50, nature: 'modest', ability: 'torrent', item: 'leftovers', moves: ['surf'],
        evs: { hp: 4, atk: 0, def: 0, spa: 252, spd: 0, spe: 252 }, ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } }
      const created = await api(page, '/teams', { name: '浏览器多成员练习队', rulesetId: 'casual-v1', dataReleaseId: 'pokeapi-reference-v1',
        members: [{ formId: 7, slot: 1, build }, { formId: 4, slot: 2, build: { ...build, ability: 'blaze', moves: ['ember'] } }] }, 'POST', 201)
      record.before = await api(page, `/teams/${created.id}`)
      const expected = structuredClone(record.before)
      expected.members[0].build = { ...expected.members[0].build, level: 51, nature: 'timid',
        evs: { hp: 0, atk: 0, def: 4, spa: 252, spd: 0, spe: 252 }, ivs: { hp: 31, atk: 0, def: 31, spa: 31, spd: 31, spe: 31 } }
      expected.members[1].build = { ...expected.members[1].build, moves: ['ember', 'protect'], item: '' }
      record.expectedAfterConfirmation = expected
      const input = page.getByRole('textbox', { name: '输入消息', exact: true })
      const creation = page.waitForResponse(response => new URL(response.url()).pathname === '/api/chat/runs' && response.request().method() === 'POST')
      await input.fill(`请先单独调用 readCurrentTeam 读取我的队伍编号 ${created.id}，再用 previewTeamChanges 生成预览等待我点击确认。明确只改以下内容：第1位杰尼龟等级51、性格timid，EV改为hp=0 atk=0 def=4 spa=252 spd=0 spe=252，IV仅atk改为0，其余IV保持31；第2位小火龙的招式改为["ember","protect"]、清空道具为无（item传空字符串）。所有未提及字段保持已保存值，包括两侧特性和第2位等级、性格、EV/IV。不要计算伤害、搜索攻略或推断配置最优性。`)
      await input.press('Enter')
      const response = await creation; expect(response.status()).toBe(202)
      active = await response.json(); record.runId = active.runId; record.conversationId = active.conversationId
      await expect(page.getByText('队伍修改预览', { exact: true })).toBeVisible({ timeout: 120000 })
      await expect.poll(async () => (await api(page, `/chat/runs/${active.runId}/events`)).events.some(e => e.type === 'usage')).toBe(true)
      record.preview = (await api(page, `/chat/runs/${active.runId}/team-change`)).preview
      expect(record.preview.changes).toHaveLength(6)
      expect(record.preview.changes.filter(line => line.slot === 1).map(line => line.field).sort()).toEqual(['等级', '性格', '努力值', '个体值'].sort())
      expect(record.preview.changes.filter(line => line.slot === 2).map(line => line.field).sort()).toEqual(['招式', '道具'].sort())
      const rows = page.locator('.proposal-table tbody tr')
      await expect(rows).toHaveCount(6)
      await expect(rows.filter({ hasText: '第1位 · 杰尼龟' })).toHaveCount(4)
      await expect(rows.filter({ hasText: '第2位 · 小火龙' })).toHaveCount(2)
      record.beforeDecision = await api(page, `/teams/${created.id}`); expect(record.beforeDecision).toEqual(record.before)
      const planning = attemptsFrom((await api(page, `/chat/runs/${active.runId}/events`)).events)
      record.planningModelCalls = planning.reduce((sum, attempt) => sum + attempt.modelCallCount, 0)
      const button = page.getByRole('button', { name: scenario.action === 'confirm' ? '确认保存' : '取消修改', exact: true })
      await expect(button).toBeInViewport()
      await page.screenshot({ path: testInfo.outputPath('preview.png'), animations: 'disabled' })
      await button.click()
      const targetStatus = scenario.action === 'confirm' ? 'COMPLETED' : 'FAILED'
      await expect.poll(async () => (await api(page, `/chat/runs/${active.runId}`)).status).toBe(targetStatus)
      terminal = true
      record.outcome = await api(page, `/chat/runs/${active.runId}/team-change`)
      expect(record.outcome.status).toBe(scenario.action === 'confirm' ? 'APPLIED' : 'DENIED')
      record.after = await api(page, `/teams/${created.id}`)
      expect(record.after).toEqual(scenario.action === 'confirm' ? expected : record.before)
      record.events = (await api(page, `/chat/runs/${active.runId}/events`)).events
      const terminals = record.events.filter(e => ['run.completed', 'run.failed', 'run.cancelled', 'run.timed_out'].includes(e.type))
      expect(terminals).toHaveLength(1); expect(terminals[0].type).toBe(scenario.action === 'confirm' ? 'run.completed' : 'run.failed')
      const history = await api(page, `/chat/conversations/${active.conversationId}/messages`)
      const answer = history.find(message => message.role === 'assistant' && message.runId === active.runId)
      const attempts = attemptsFrom(record.events)
      record.modelCalls = attempts.reduce((sum, attempt) => sum + attempt.modelCallCount, 0)
      expect(record.modelCalls).toBe(record.planningModelCalls)
      if (scenario.action === 'confirm') {
        expect(answer?.content).toBe(record.outcome.message)
        expect(teamDigest((answer.metadata?.modelUsageAttempts || []).map(normalizeModelUsage))).toBe(teamDigest(attempts.map(normalizeModelUsage)))
        await expect(page.locator('.assistant-bubble').filter({ hasText: '第2位 · 小火龙' })).toBeVisible()
        await page.reload()
        await expect(page.locator('.assistant-bubble').filter({ hasText: '第1位 · 杰尼龟' })).toBeVisible()
        await expect(page.locator('.assistant-bubble').filter({ hasText: '第2位 · 小火龙' })).toBeVisible()
        expect(await api(page, `/teams/${created.id}`)).toEqual(expected)
      } else {
        expect(answer).toBeUndefined()
        await expect(page.getByText('队伍修改预览', { exact: true })).toHaveCount(0)
        await expect(page.locator('.assistant-bubble').filter({ hasText: '修改已保存' })).toHaveCount(0)
      }
      record.decisionRequests = apiTraffic.filter(t => t.method === 'POST' && t.path === `/api/chat/runs/${active.runId}/team-change/decision`)
      expect(record.decisionRequests).toHaveLength(1); expect(record.decisionRequests[0].status).toBe(200)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
      await page.screenshot({ path: testInfo.outputPath('result.png'), animations: 'disabled' })
      expect(sources()).toEqual(record.sourceSha256)
      record.pass = true
    } finally {
      if (active && !terminal) try { await api(page, `/chat/runs/${active.runId}/cancel`, {}) } catch { record.cancelCleanupFailed = true }
      try { await api(page, '/logout', undefined, 'POST', 204) } catch { record.logoutFailed = true }
      record.apiTraffic = apiTraffic; record.finishedAt = new Date().toISOString()
      fs.writeFileSync(testInfo.outputPath('observations.json'), JSON.stringify(record, null, 2) + '\n')
    }
  })
}
