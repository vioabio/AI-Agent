import fs from 'node:fs'
import path from 'node:path'
import {pathToFileURL} from 'node:url'
import assert from 'node:assert/strict'

const [traceFile, outputDirectory] = process.argv.slice(2)
if (!traceFile || !outputDirectory) throw Error('Usage: jaeger-browser-check.mjs TRACE_JSON OUTPUT_DIRECTORY')
if (fs.statSync(traceFile).size > 1000000) throw Error('TRACE_TOO_LARGE')
const trace = JSON.parse(fs.readFileSync(traceFile, 'utf8'))
assert.match(trace.traceId, /^[a-f0-9]{32}$/)
const names = ['agent.run.execute', 'agent.tool.dispatch', 'agent.tool.callback', 'agent.model.call']
assert.equal(trace.spans?.length, 4)
assert.deepEqual(trace.spans.map(span => span.name).sort(), [...names].sort())
const output = path.resolve(outputDirectory)
const artifacts = fs.realpathSync('artifacts')
const requested = path.relative(artifacts, output)
if (!requested || requested.startsWith('..') || path.isAbsolute(requested)) throw Error('ARTIFACT_DIRECTORY_REQUIRED')
fs.mkdirSync(output, {recursive:true})
const within = path.relative(artifacts, fs.realpathSync(output))
if (!within || within.startsWith('..') || path.isAbsolute(within)) throw Error('ARTIFACT_DIRECTORY_REQUIRED')
const {chromium} = await import(pathToFileURL(path.resolve('vio-ai-agent-frontend/node_modules/playwright/index.mjs')))
const browser = await chromium.launch({headless:true, channel:process.platform === 'win32' ? 'chrome' : undefined})
try {
  const page = await browser.newPage({viewport:{width:1440,height:1000}})
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('http://127.0.0.1:16686/trace/' + trace.traceId, {waitUntil:'networkidle'})
  await page.getByText('agent.run.execute', {exact:true}).first().waitFor({timeout:15000})
  const body = await page.locator('body').innerText()
  for (const name of names) assert.ok(body.includes(name), name)
  assert.deepEqual(errors, [])
  await page.screenshot({path:path.join(output,'trace-browser.png'),fullPage:true})
  const report = {status:'PASS',traceId:trace.traceId,spanNames:names,pageErrors:errors}
  fs.writeFileSync(path.join(output,'browser-result.json'), JSON.stringify(report,null,2) + '\n')
  console.log(JSON.stringify(report))
} finally {
  await browser.close()
}
