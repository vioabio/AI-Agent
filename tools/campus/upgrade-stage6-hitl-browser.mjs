import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {pathToFileURL} from 'node:url'
import {randomBytes} from 'node:crypto'
import {localBase, digest, sameJson} from './evaluation.mjs'
import {stage2Team} from './stage2-regression.mjs'
import {publicFingerprint} from './upgrade-baseline.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'

const [directory, url] = process.argv.slice(2), output = path.resolve(directory)
const backend = new URL(localBase(url)), origin = 'http://127.0.0.1:4329'
assert.equal(path.dirname(output), fs.realpathSync('artifacts')); assert.equal(fs.existsSync(output), false)
fs.mkdirSync(output)
const front = path.resolve('vio-ai-agent-frontend')
const {chromium} = await import(pathToFileURL(path.join(front,'node_modules/playwright/index.mjs')))
const {createServer} = await import(pathToFileURL(path.join(front,'node_modules/vite/dist/node/index.js')))
const {default:config} = await import(pathToFileURL(path.join(front,'vite.config.js')))
const server = await createServer({...config, configFile:false, root:front,
  server:{...config.server,host:'127.0.0.1',port:4329,strictPort:true,
    proxy:{'/api':{target:backend.origin,changeOrigin:false}}}})
const report = {schemaVersion:'stage6-hitl-browser-v1',caseId:'S6-05',status:'RUNNING',
  startedAt:new Date().toISOString(),records:[],pageErrors:[],cleanup:[],qualityReview:'REQUIRED',releaseDecision:'NOT_EVALUATED'}
let browser, context, authenticated = false, team
const runs = []
async function api(route, body, expected=200, method=body===undefined?'GET':'POST') {
  const cookies = await context.cookies(origin), csrf = cookies.find(cookie => cookie.name==='CSRF-TOKEN')?.value
  const response = await context.request.fetch(origin+'/api'+route,{method,
    headers:{Origin:origin,...(csrf?{'X-CSRF-TOKEN':decodeURIComponent(csrf)}:{})},
    ...(body===undefined?{}:{data:body}),timeout:30000,maxRedirects:0})
  assert.equal(response.status(),expected,'HTTP_'+response.status())
  if(expected===204)return null
  const value = await response.json()
  if(value && Object.hasOwn(value,'code'))assert.equal(value.code,200,'BUSINESS_RESPONSE_FAILED')
  return value && Object.hasOwn(value,'data') && Object.hasOwn(value,'code') ? value.data : value
}
async function waitState(runId, wanted) {
  const deadline = Date.now()+140000
  do {
    const view = await api('/chat/runs/'+runId)
    if(view.status===wanted)return view
    assert.equal(['FAILED','CANCELLED','TIMED_OUT'].includes(view.status),false,'UNEXPECTED_RUN_TERMINAL')
    await new Promise(done=>setTimeout(done,250))
  }while(Date.now()<deadline)
  throw Error('EXPECTED_RUN_STATE_NOT_REACHED')
}
try {
  await server.listen()
  browser = await chromium.launch({headless:true,channel:process.platform==='win32'?'chrome':undefined})
  context = await browser.newContext({viewport:{width:1440,height:1000},serviceWorkers:'block'})
  assert.equal((await api('/auth/session')).mode,'local')
  const credentials = {username:'hitl_'+randomBytes(8).toString('hex'),
    password:randomBytes(24).toString('base64url'),nickname:'页面确认隔离测试'}
  await api('/auth/local/register',credentials,201);await api('/auth/local/login',credentials);authenticated=true
  const runtime = await api('/chat/runtime')
  report.runtime=publicFingerprint(runtime);report.runtimeBuild=runtimeBuild(runtime)
  report.guideCatalog=await api('/pokemon/guides/catalog');report.semanticState=await api('/pokemon/guides/semantic')
  report.producerSha256=digest(fs.readFileSync(new URL(import.meta.url)))
  team = await api('/pokemon/teams',stage2Team(),201)
  report.teamBefore = await api('/pokemon/teams/'+team.id)
  const page = await context.newPage()
  page.on('pageerror',error=>report.pageErrors.push(error.name))
  await page.goto(origin+'/game-master',{waitUntil:'domcontentloaded'})
  const input = page.getByLabel('输入消息',{exact:true})
  await input.waitFor({timeout:30000})
  for(const approved of [false,true]) {
    const viewport=approved?{width:1440,height:1000}:{width:430,height:932}
    await page.setViewportSize(viewport)
    await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
    const created = page.waitForResponse(response=>response.request().method()==='POST'
      && new URL(response.url()).pathname==='/api/chat/runs')
    const prompt = '读取我的test1队伍，把槽位1的等级从51改为52，其余字段保持当前值。只生成修改预览，等待我在页面确认。'
    await input.fill(prompt);await page.getByRole('button',{name:'发送',exact:true}).click()
    const response = await created;assert.equal(response.status(),202)
    const runId = (await response.json()).runId;runs.push(runId)
    await waitState(runId,'WAITING_APPROVAL')
    const before = await api('/pokemon/teams/'+team.id)
    assert.ok(sameJson(before,report.teamBefore),'PREVIEW_MUST_NOT_WRITE')
    const preview = await api('/chat/runs/'+runId+'/team-change')
    assert.equal(preview.status,'PENDING');assert.equal(preview.preview.teamId,team.id)
    assert.equal(preview.preview.changes.length,1)
    const change = preview.preview.changes[0]
    assert.equal(change.slot,1);assert.equal(String(change.before),'51');assert.equal(String(change.after),'52')
    assert.ok(['level','等级'].includes(change.field),'UNEXPECTED_PREVIEW_FIELD')
    const decided = page.waitForResponse(result=>result.request().method()==='POST'
      && new URL(result.url()).pathname==='/api/chat/runs/'+runId+'/team-change/decision')
    await page.getByRole('button',{name:approved?'确认保存':'取消修改',exact:true}).last().click()
    const decision = await decided;assert.equal(decision.status(),200)
    const decisionBody = decision.request().postDataJSON()
    assert.equal(decisionBody.approved,approved);assert.equal(decisionBody.approvalId,preview.preview.proposalId)
    await waitState(runId,approved?'COMPLETED':'FAILED')
    const outcome = await api('/chat/runs/'+runId+'/team-change'), after = await api('/pokemon/teams/'+team.id)
    const expected = structuredClone(report.teamBefore)
    if(approved)expected.members[0].build.level=52
    assert.ok(sameJson(after,expected),'ONLY_PREVIEWED_FIELD_MAY_CHANGE')
    if(approved)assert.equal(outcome.status,'APPLIED')
    else assert.equal(outcome.status,'DENIED')
    await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
    if(approved)await page.getByText('队伍修改已保存',{exact:true}).last().waitFor({timeout:30000})
    else await page.getByText('已取消待确认的操作。',{exact:true}).last().waitFor({timeout:30000})
    const events = (await api('/chat/runs/'+runId+'/events?afterEventId=0')).events
    const eventBytes = JSON.stringify(events,null,2)+'\n', file = approved?'accepted-events.json':'rejected-events.json'
    fs.writeFileSync(path.join(output,file),eventBytes,{flag:'wx'})
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true)
    report.records.push({approved,runId,prompt,preview,decision:decisionBody,outcome,after,viewport,
      eventsFile:file,eventsSha256:digest(eventBytes),uiInputEnabled:true,uiSavedReceiptShown:approved})
    await page.screenshot({path:path.join(output,approved?'confirmed.png':'rejected.png')})
  }
  const afterRuntime = await api('/chat/runtime')
  report.runtimeAfter=publicFingerprint(afterRuntime);report.runtimeBuildAfter=runtimeBuild(afterRuntime)
  assert.equal(publicFingerprint(afterRuntime).sha256,report.runtime.sha256)
  assert.equal(runtimeBuild(afterRuntime).sha256,report.runtimeBuild.sha256)
  assert.deepEqual(report.pageErrors,[])
  report.status='COLLECTED_PENDING_CONTENT_REVIEW'
} catch(error) {
  report.status='FAIL';report.failure=String(error.message).slice(0,250);process.exitCode=1
} finally {
  for(const runId of runs)try{await api('/chat/runs/'+runId+'/cancel',{reason:'s6_hitl_cleanup'})}
  catch{report.cleanup.push('RUN_CLEANUP_FAILED');report.status='FAIL';process.exitCode=1}
  if(authenticated && team)try {
    await api('/pokemon/teams/'+team.id,stage2Team(),200,'PATCH')
    report.restoredTeam=await api('/pokemon/teams/'+team.id)
    assert.ok(sameJson(report.restoredTeam,report.teamBefore),'TEST_BASELINE_RESTORE_REQUIRED')
    report.cleanup.push('TEST_BASELINE_RESTORED')
  }catch{report.cleanup.push('RESTORE_FAILED');report.status='FAIL';process.exitCode=1}
  if(authenticated)try{await api('/logout',{},204);report.cleanup.push('LOGGED_OUT')}
  catch{report.cleanup.push('LOGOUT_FAILED');report.status='FAIL';process.exitCode=1}
  if(browser)await browser.close()
  await server.close()
  report.finishedAt=new Date().toISOString()
  fs.writeFileSync(path.join(output,'browser-result.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify({status:report.status,records:report.records.length,failure:report.failure}))
}
