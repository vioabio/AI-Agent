import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
test.use({ video: 'off' })
// Usage and execution details are opt-in workspace developer features.
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('vio.workspace.developer-mode', 'true'))
})
function usage(base, budget, attemptId) {
  const fixture=JSON.parse(readFileSync(new URL('./fixtures/model-cost.json',import.meta.url)))
  fixture.attemptId=attemptId
  const estimate=count=>({system:0,history:0,toolDefinitions:0,toolResults:0,currentMessages:count,inputTokens:count})
  const context={schemaVersion:'context-budget-v1',estimator:'cl100k-base-proxy-v1',calibration:'PROXY_UNCALIBRATED',status:'CHECKED',
    contextTokens:32768,inputLimit:30720,outputReserve:2048,historyLimit:4096,availableHistoryTurns:0,omittedHistoryTurns:0,requestsChecked:1,
    safetyPercent:125,lastEstimate:estimate(budget),baseEstimate:estimate(base),estimateSchemaVersion:'context-estimate-pair-v1'}
  fixture.contextBudget=context
  for(const call of fixture.calls) {
    const native=call.tokens.inputTokens
    call.contextBudget=context
    call.tokenEstimateComparison={schemaVersion:'token-estimate-comparison-v1',status:'PAIR_RECORDED',baseInputTokens:base,budgetInputTokens:budget,
      providerInputTokens:native,baseErrorTokens:base-native,budgetErrorTokens:budget-native,budgetCovered:budget>=native}
  }
  return fixture
}
const auth={mode:'disabled',authenticated:false,accessDenied:false,principalId:null,tenantId:null,roles:[],serverTime:'2026-10-25T00:00:00Z',
  absoluteExpiresAt:null,loginUrl:null,logoutUrl:null,csrf:{enabled:false,available:false,cookieName:'CSRF-TOKEN',headerName:'X-CSRF-TOKEN'}}
for(const width of [1440,390])test(`Token输入估算对比与低估提示 ${width}`,async({page})=>{
  await page.setViewportSize({width,height:width===390?844:900})
  await page.route(url=>url.pathname.startsWith('/api/'),route=>{
    const path=new URL(route.request().url()).pathname
    if(path==='/api/auth/session')return route.fulfill({json:auth})
    if(path.endsWith('/messages'))return route.fulfill({json:[
      {role:'assistant',content:'预算覆盖示例',metadata:{modelUsageAttempts:[usage(1200,1500,'a1111111-1111-4111-a111-111111111111')]}},
      {role:'assistant',content:'预算低估示例',metadata:{modelUsageAttempts:[usage(800,900,'b1111111-1111-4111-a111-111111111111')]}}
    ]})
    return route.fulfill({status:404,json:{}})
  })
  await page.goto('/game-master')
  const details=page.getByLabel('模型用量与耗时')
  await expect(details).toHaveCount(2)
  await expect(page.locator('.token-estimate-comparison').first()).toBeHidden()
  for(const item of await details.all())await item.locator('summary').click()
  await expect(page.locator('.token-estimate-comparison').first()).toContainText('本次预算估算覆盖')
  await expect(page.locator('.token-estimate-comparison').last()).toContainText('本次低估 100 Token')
  await expect(page.locator('.token-estimate-comparison').last()).toContainText('不代表其他请求已校准')
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
  await details.last().scrollIntoViewIfNeeded()
  await page.screenshot({path:`../artifacts/s4-calibration-${width}.png`})
})
