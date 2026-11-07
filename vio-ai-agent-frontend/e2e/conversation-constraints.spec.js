import { test, expect } from '@playwright/test'
test.use({ video: 'off' })
const initial = () => ({ revision: 0, responseStyle: 'STANDARD', trainingBudget: 'UNRESTRICTED', avoidLegendary: false, notes: '' })
const auth = { mode: 'disabled', authenticated: false, accessDenied: false, principalId: null, tenantId: null, roles: [],
  serverTime: '2026-10-25T00:00:00Z', absoluteExpiresAt: null, loginUrl: null, logoutUrl: null,
  csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' } }
for (const width of [1440, 390]) {
  test(`对话偏好保存回读与冲突提示 ${width}`, async ({ page }) => {
    let value = initial(), conflict = false
    await page.setViewportSize({ width, height: width === 390 ? 844 : 900 })
    await page.route(url => url.pathname.startsWith('/api/'), async route => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/auth/session') return route.fulfill({ json: auth })
      if (path.endsWith('/messages')) return route.fulfill({ json: [] })
      if (path.endsWith('/constraints')) {
        if (route.request().method() === 'PUT') {
          if (conflict) return route.fulfill({ status: 409 })
          value = { ...route.request().postDataJSON(), revision: value.revision + 1 }
        }
        return route.fulfill({ json: value })
      }
      return route.fulfill({ status: 404, json: {} })
    })
    await page.goto('/game-master')
    await page.getByRole('button', { name: '对话偏好', exact: true }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('回答详略').selectOption('CONCISE')
    await dialog.getByLabel('培养成本').selectOption('LOW')
    await dialog.getByLabel('建议避开传说宝可梦').check()
    await dialog.getByLabel('补充要求').fill('优先解释水属性成员的培养。')
    await dialog.getByRole('button', { name: '保存偏好', exact: true }).click()
    await expect(dialog.getByRole('status')).toContainText('已保存')
    expect(value.revision).toBe(1)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: `../artifacts/s4-constraints-${width}.png` })
    await dialog.getByRole('button', { name: '关闭对话偏好' }).click()
    await page.getByRole('button', { name: '对话偏好', exact: true }).click()
    await expect(dialog.getByLabel('补充要求')).toHaveValue(value.notes)
    conflict = true
    await dialog.getByLabel('补充要求').fill('未覆盖的草稿')
    await dialog.getByRole('button', { name: '保存偏好', exact: true }).click()
    await expect(dialog.getByRole('alert')).toContainText('其他页面更新')
    expect(value.notes).toBe('优先解释水属性成员的培养。')
    await dialog.getByRole('button', { name: '重新读取' }).click()
    await expect(dialog.getByLabel('补充要求')).toHaveValue(value.notes)
    await dialog.getByRole('button', { name: '关闭对话偏好' }).click()
    await expect(page.getByRole('button', { name: '对话偏好', exact: true })).toBeFocused()
  })
}
