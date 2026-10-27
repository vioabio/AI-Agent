import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import assert from 'node:assert/strict'
import { bundleHash } from './ablation-review.mjs'
import { renderReviewHtml } from './review-workbench-html.mjs'
const require = createRequire(path.resolve('vio-ai-agent-frontend/package.json'))
const { chromium } = require('@playwright/test')
const [root, out, ...extra] = process.argv.slice(2)
if (!root || !out || extra.length) throw Error('Usage: node tools/campus/review-workbench-browser.mjs WORKBENCH_DIRECTORY NEW_OUTPUT')
const expectedBundle = JSON.parse(fs.readFileSync(path.join(root, 'bundle.json')))
if (fs.existsSync(out)) throw Error('Never overwrite browser QA')
fs.mkdirSync(out, { recursive: true })
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ acceptDownloads: true }), page = await context.newPage()
const errors = [], external = []; page.on('pageerror', e => errors.push(e.message)); page.on('request', r => { if (/^https?:/.test(r.url())) external.push(r.url()) })
try {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(pathToFileURL(path.resolve(root, 'review.html')).href)
  await page.locator('#cases button').first().waitFor()
  assert.equal(await page.locator('#cases button').count(), 6)
  assert.ok((await page.locator('#question').textContent()).includes('种族值'))
  const firstExcerpts = expectedBundle.rows[0].excerpts
  assert.ok((await page.locator('#literal').textContent()).includes(`${firstExcerpts.filter(e => e.documentIds.length).length}/${firstExcerpts.length}`))
  await page.screenshot({ path: `${out}/desktop.png` })
  await page.locator('#export').click(); assert.ok((await page.locator('#error').textContent()).includes('请先'))
  await page.locator('#cases button').nth(3).click()
  assert.ok((await page.locator('#source').textContent()).includes('预览'))
  assert.equal(await page.locator('#citations .decision').count(), expectedBundle.rows[3].excerpts.length)
  assert.equal(await page.locator('#criteria .decision').count(), expectedBundle.rows[3].criteria.length)
  await page.locator('#reviewer').fill('BROWSER_FIXTURE_AI_ASSISTED_NOT_HUMAN')
  await page.locator('#reviewer-type').selectOption('ai-assisted')
  await page.locator('#note').fill('Synthetic UI interaction only; not a real semantic review')
  for (const area of await page.locator('#criteria textarea, #citations textarea').all()) await area.fill('Synthetic interaction; semantic judgement deliberately remains unknown')
  await page.locator('#submit-row').click()
  assert.equal(await page.locator('#error').textContent(), '')
  assert.ok((await page.locator('#progress').textContent()).includes('已加入导出 1 条'))
  const downloadPromise = page.waitForEvent('download'); await page.locator('#export').click()
  await (await downloadPromise).saveAs(`${out}/submitted-ai-fixture.json`)
  const exported = JSON.parse(fs.readFileSync(`${out}/submitted-ai-fixture.json`))
  assert.equal(exported.reviews.length, 1); assert.equal(exported.reviews[0].reviewerType, 'ai-assisted')
  assert.ok(exported.reviews[0].decisions.every(d => d.verdict === 'unknown'))
  await page.locator('#note').fill('Edited fixture; previous acknowledgement must be invalidated')
  assert.ok((await page.locator('#progress').textContent()).includes('已加入导出 0 条'))
  const draftPromise = page.waitForEvent('download'); await page.locator('#save-draft').click()
  await (await draftPromise).saveAs(`${out}/draft-fixture.json`)
  await page.locator('#draft-file').setInputFiles(`${out}/draft-fixture.json`)
  await page.waitForFunction(() => document.getElementById('error').textContent.includes('草稿已恢复'))
  assert.ok((await page.locator('#progress').textContent()).includes('已加入导出 0 条'))
  assert.equal(await page.locator('#attested').isChecked(), false)
  const wrongDraft = JSON.parse(fs.readFileSync(`${out}/draft-fixture.json`)); wrongDraft.bundleSha256 = 'wrong'
  fs.writeFileSync(`${out}/wrong-draft.json`, JSON.stringify(wrongDraft))
  await page.locator('#draft-file').setInputFiles(`${out}/wrong-draft.json`)
  await page.waitForFunction(() => document.getElementById('error').textContent.includes('不匹配'))
  await page.locator('#reviewer-type').selectOption('human'); await page.locator('#submit-row').click()
  assert.ok((await page.locator('#error').textContent()).includes('确认已实际核对'))
  await page.setViewportSize({ width: 390, height: 844 }); await page.reload()
  await page.locator('#cases button').first().waitFor()
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
  await page.screenshot({ path: `${out}/mobile.png`, fullPage: true })
  const injection = JSON.parse(fs.readFileSync(`${root}/bundle.json`))
  injection.rows[0].answer = '</script><img src="https://invalid.example/attack" onerror="window.hacked=1">'
  injection.rows[0].excerpts = []; injection.bundleSha256 = bundleHash(injection)
  fs.writeFileSync(`${out}/injection-fixture.html`, renderReviewHtml(injection))
  await page.goto(pathToFileURL(path.resolve(`${out}/injection-fixture.html`)).href)
  assert.equal(await page.locator('img').count(), 0); assert.equal(await page.evaluate(() => window.hacked), undefined)
  assert.ok((await page.locator('#answer').textContent()).includes('<img'))
  assert.deepEqual(errors, []); assert.deepEqual(external, [])
  const result = { schemaVersion: 'review-workbench-browser-qa-v1', passed: true, desktop: '1440x1000', mobile: '390x844',
    checks: ['six actual observations visible', 'raw source navigation', 'unreviewed export rejected', 'partial AI-only export', 'edits invalidate acknowledgement', 'draft restore clears acknowledgement', 'wrong bundle draft rejected', 'human attestation required', 'mobile has no horizontal overflow', 'injection remains text', 'no external requests or JS errors'],
    reviewerFixture: 'AI_ASSISTED_SYNTHETIC_NOT_HUMAN_ACCEPTANCE', screenshots: ['desktop.png', 'mobile.png'] }
  fs.writeFileSync(`${out}/result.json`, JSON.stringify(result, null, 2) + '\n'); console.log(JSON.stringify(result))
} finally { await browser.close() }
