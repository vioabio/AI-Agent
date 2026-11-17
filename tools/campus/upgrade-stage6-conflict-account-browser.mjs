import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {pathToFileURL} from 'node:url'
import {randomBytes} from 'node:crypto'
import {localBase, digest, sameJson} from './evaluation.mjs'
import {stage2Team} from './stage2-regression.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {publicFingerprint} from './upgrade-baseline.mjs'

const [directory, url, scope='full'] = process.argv.slice(2), output = path.resolve(directory)
assert.ok(['full','governance'].includes(scope),'SUPPORTED_BROWSER_SCOPE_REQUIRED')
const backend = new URL(localBase(url)), origin = 'http://127.0.0.1:4329'
assert.equal(path.dirname(output), fs.realpathSync('artifacts'))
assert.equal(fs.existsSync(output), false)
fs.mkdirSync(output)
const front = path.resolve('vio-ai-agent-frontend')
const {chromium} = await import(pathToFileURL(path.join(front,'node_modules/playwright/index.mjs')))
const {createServer} = await import(pathToFileURL(path.join(front,'node_modules/vite/dist/node/index.js')))
const {default:config} = await import(pathToFileURL(path.join(front,'vite.config.js')))
const server = await createServer({...config, configFile:false, root:front,
  server:{...config.server,host:'127.0.0.1',port:4329,strictPort:true,
    proxy:{'/api':{target:backend.origin,changeOrigin:false}}}})
const report = {schemaVersion:'stage6-conflict-account-browser-v1', status:'RUNNING',
  startedAt:new Date().toISOString(),records:[],cleanup:[],pageErrors:[],qualityReview:'REQUIRED',releaseDecision:'NOT_EVALUATED'}
report.sourceSha256=Object.fromEntries([
  'tools/campus/upgrade-stage6-conflict-account-browser.mjs',
  'vio-ai-agent-frontend/src/auth/session-state.js',
  'vio-ai-agent-frontend/src/composables/useAuthSession.js'
].map(file=>[file,digest(fs.readFileSync(file))]))
report.scope=scope
const actors = [], runs = [], tag = randomBytes(5).toString('hex')
let browser, actorA, actorB, teamA, baselineA
function apiFor(context) {
  return async function api(route,body,expected=200,method=body===undefined?'GET':'POST') {
    const csrf = (await context.cookies(origin)).find(cookie=>cookie.name==='CSRF-TOKEN')?.value
    const response = await context.request.fetch(origin+'/api'+route,{method,
      headers:{Origin:origin,...(csrf?{'X-CSRF-TOKEN':decodeURIComponent(csrf)}:{})},
      ...(body===undefined?{}:{data:body}),timeout:30000,maxRedirects:0})
    assert.equal(response.status(),expected,'HTTP_'+response.status())
    if(expected===204)return null
    const text = await response.text()
    if(!text)return null
    const value = JSON.parse(text)
    if(expected<300 && value && Object.hasOwn(value,'code'))assert.equal(value.code,200,'BUSINESS_RESPONSE_FAILED')
    return value && Object.hasOwn(value,'data') && Object.hasOwn(value,'code') ? value.data : value
  }
}
async function actor(label) {
  const context = await browser.newContext({viewport:{width:1440,height:1000},serviceWorkers:'block'})
  const api = apiFor(context)
  const credentials = {username:'s6_'+label+'_'+randomBytes(7).toString('hex'),
    password:randomBytes(24).toString('base64url'),nickname:'隔离账号'+label}
  assert.equal((await api('/auth/session')).mode,'local')
  await api('/auth/local/register',credentials,201);await api('/auth/local/login',credentials)
  const value = {context,api,credentials}
  actors.push(value)
  return value
}
async function waitState(api,runId,wanted) {
  const deadline = Date.now()+140000
  do {
    const view = await api('/chat/runs/'+runId)
    if(view.status===wanted)return view
    assert.equal(['FAILED','CANCELLED','TIMED_OUT','COMPLETED'].includes(view.status),false,'RUN_ENDED_BEFORE_'+wanted)
    await new Promise(done=>setTimeout(done,250))
  }while(Date.now()<deadline)
  throw Error('RUN_STATE_TIMEOUT_'+wanted)
}
async function pageFor(context,conversation) {
  const page = await context.newPage()
  page.on('pageerror',error=>report.pageErrors.push(error.name))
  await page.goto(origin+'/game-master?conversation='+conversation,{waitUntil:'domcontentloaded'})
  await page.getByLabel('输入消息',{exact:true}).waitFor({timeout:30000})
  await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
  return page
}
async function preview(page,level,conversation) {
  const created = page.waitForResponse(response=>response.request().method()==='POST'
    && new URL(response.url()).pathname==='/api/chat/runs')
  const prompt = `读取我的${teamA.name}队伍，把槽位1的等级从51改为${level}，其余字段保持当前值。生成预览并等待页面确认。`
  await page.getByLabel('输入消息',{exact:true}).fill(prompt)
  await page.getByRole('button',{name:'发送',exact:true}).click()
  const response = await created;assert.equal(response.status(),202)
  const runId = (await response.json()).runId
  runs.push({owner:'A',runId})
  const view = await waitState(actorA.api,runId,'WAITING_APPROVAL')
  assert.equal(view.conversationId,conversation)
  const outcome = await actorA.api('/chat/runs/'+runId+'/team-change')
  assert.equal(outcome.status,'PENDING');assert.equal(outcome.preview.teamId,teamA.id)
  assert.equal(outcome.preview.changes.length,1)
  const change = outcome.preview.changes[0]
  assert.equal(change.slot,1);assert.equal(String(change.before),'51');assert.equal(String(change.after),String(level))
  assert.ok(['等级','level'].includes(change.field))
  return {runId,conversation,prompt,preview:outcome.preview}
}
async function confirm(page,run) {
  const decided = page.waitForResponse(response=>response.request().method()==='POST'
    && new URL(response.url()).pathname==='/api/chat/runs/'+run.runId+'/team-change/decision')
  await page.getByRole('button',{name:'确认保存',exact:true}).last().click()
  const response = await decided;assert.equal(response.status(),200)
  assert.deepEqual(response.request().postDataJSON(),{approvalId:run.preview.proposalId,approved:true})
  await waitState(actorA.api,run.runId,'COMPLETED')
  return actorA.api('/chat/runs/'+run.runId+'/team-change')
}
try {
  await server.listen();browser = await chromium.launch({headless:true,channel:process.platform==='win32'?'chrome':undefined})
  actorA = await actor('A');actorB = await actor('B')
  const runtime = await actorA.api('/chat/runtime')
  report.runtime=publicFingerprint(runtime);report.runtimeBuild=runtimeBuild(runtime)
  report.guideCatalog=await actorA.api('/pokemon/guides/catalog');report.semanticState=await actorA.api('/pokemon/guides/semantic')
  const fixtureA = {...stage2Team(),name:'U6_PRIVATE_A_'+tag}
  teamA = await actorA.api('/pokemon/teams',fixtureA,201)
  baselineA = await actorA.api('/pokemon/teams/'+teamA.id);report.teamBefore=baselineA
  const conversation1 = 's6-a-first-'+tag, conversation2 = 's6-a-stale-'+tag
  const page1 = await pageFor(actorA.context,conversation1), page2 = await pageFor(actorA.context,conversation2)
  const first = await preview(page1,52,conversation1), stale = await preview(page2,53,conversation2)
  assert.ok(sameJson(await actorA.api('/pokemon/teams/'+teamA.id),baselineA),'PENDING_PREVIEWS_WROTE_TEAM')
  const applied = await confirm(page1,first)
  assert.equal(applied.status,'APPLIED')
  const expected = structuredClone(baselineA);expected.members[0].build.level=52
  assert.ok(sameJson(await actorA.api('/pokemon/teams/'+teamA.id),expected))
  const conflict = await confirm(page2,stale)
  assert.equal(conflict.status,'CONFLICT')
  assert.ok(sameJson(await actorA.api('/pokemon/teams/'+teamA.id),expected),'STALE_PREVIEW_OVERWROTE_TEAM')
  await page2.locator('.assistant-bubble').getByText('队伍已被修改或删除，本次预览未保存。请重新读取队伍并生成新预览。',{exact:true}).first().waitFor({timeout:30000})
  await actorA.api('/chat/runs/'+stale.runId+'/team-change/decision',
    {approvalId:stale.preview.proposalId,approved:true},409)
  for(const run of [first,stale]) {
    const events=(await actorA.api('/chat/runs/'+run.runId+'/events?afterEventId=0')).events
    const bytes=JSON.stringify(events,null,2)+'\n', file=run===first?'applied-events.json':'conflict-events.json'
    fs.writeFileSync(path.join(output,file),bytes,{flag:'wx'})
    run.eventsFile=file;run.eventsSha256=digest(bytes)
  }
  report.records.push({caseId:'S6-06',first,stale,applied,conflict,teamAfter:await actorA.api('/pokemon/teams/'+teamA.id)})
  await page2.screenshot({path:path.join(output,'conflict.png')})
  await page2.close()

  const markerB='U6_PRIVATE_B_'+tag, fixtureB={...stage2Team(),name:markerB}
  const teamB=await actorB.api('/pokemon/teams',fixtureB,201)
  const seedB=await actorB.api('/chat/runs',{prompt:markerB+'。这一轮仅回复“收到”。',conversationId:'s6-b-seed-'+tag,
    gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',rulesetVersionId:'casual-v1',
    dataReleaseId:'pokeapi-reference-v1',language:'zh-CN',timeoutSeconds:120},202)
  runs.push({owner:'B',runId:seedB.runId});await waitState(actorB.api,seedB.runId,'COMPLETED')
  await actorB.api('/pokemon/teams/'+teamA.id,undefined,404)
  await actorB.api('/chat/runs/'+first.runId+'/events?afterEventId=0',undefined,404)
  await page1.getByText('队伍修改已保存',{exact:true}).last().waitFor({timeout:30000})
  assert.ok((await page1.locator('body').innerText()).includes(teamA.name))
  await page1.locator('.account-trigger').click()
  await page1.getByRole('button',{name:'切换账号',exact:true}).click()
  await page1.getByTestId('local-auth-portal').waitFor({timeout:30000})
  assert.equal((await page1.locator('body').innerText()).includes(teamA.name),false)
  await page1.getByLabel('用户名',{exact:true}).fill(actorB.credentials.username)
  await page1.getByLabel('密码',{exact:true}).fill(actorB.credentials.password)
  await page1.locator('form').getByRole('button',{name:'登录',exact:true}).click()
  await page1.getByLabel('输入消息',{exact:true}).waitFor({timeout:30000})
  await page1.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
  assert.equal(new URL(page1.url()).searchParams.has('conversation'),false,'OLD_CONVERSATION_URL_NOT_CLEARED')
  const switchedApi=apiFor(actorA.context), switched=await switchedApi('/auth/session'), originalB=await actorB.api('/auth/session')
  assert.equal(switched.principalId,originalB.principalId)
  const ownTeams=await switchedApi('/pokemon/teams')
  assert.ok(ownTeams.some(team=>team.id===teamB.id));assert.equal(ownTeams.some(team=>team.id===teamA.id),false)
  assert.equal((await page1.locator('body').innerText()).includes(teamA.name),false)
  const nextRun=page1.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname==='/api/chat/runs')
  await page1.getByLabel('输入消息',{exact:true}).fill(markerB+'。这一轮仅回复“收到”。')
  await page1.getByRole('button',{name:'发送',exact:true}).click()
  const nextResponse=await nextRun;assert.equal(nextResponse.status(),202,'SWITCHED_ACCOUNT_CANNOT_START_OWN_RUN')
  const next=(await nextResponse.json()).runId
  runs.push({owner:'B',runId:next})
  const nextView=await waitState(switchedApi,next,'COMPLETED')
  assert.notEqual(nextView.conversationId,conversation1,'OLD_ACCOUNT_CONVERSATION_REUSED')
  await page1.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
  const visible=await page1.locator('body').innerText()
  assert.equal(visible.includes(teamA.name),false);assert.ok(visible.includes(markerB))
  await switchedApi('/chat/runs/'+first.runId+'/events?afterEventId=0',undefined,404)
  const accountEventFiles=[]
  for(const [label,runId,read]of [['seed',seedB.runId,actorB.api],['next',next,switchedApi]]) {
    const events=(await read('/chat/runs/'+runId+'/events?afterEventId=0')).events
    const bytes=JSON.stringify(events,null,2)+'\n',file='account-'+label+'-events.json'
    fs.writeFileSync(path.join(output,file),bytes,{flag:'wx'})
    accountEventFiles.push({runId,file,sha256:digest(bytes)})
  }
  report.records.push({caseId:'S6-04',browserSwitch:true,privateContentHidden:true,ownTeamsOnly:true,
    nextRunId:next,nextConversationId:nextView.conversationId,oldConversationId:conversation1,accountEventFiles})
  await page1.screenshot({path:path.join(output,'switched-account.png')})

  // Exercise normal model output and the actual copy/edit controls after the switch.
  if(scope==='full') {
  const normalPrompt='你好，请用中文介绍你能提供的宝可梦助手功能和使用方法。只需公开回答。'
  const normalCreated=page1.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname==='/api/chat/runs')
  await page1.getByLabel('输入消息',{exact:true}).fill(normalPrompt)
  await page1.getByRole('button',{name:'发送',exact:true}).click()
  const normalResponse=await normalCreated;assert.equal(normalResponse.status(),202)
  const normalId=(await normalResponse.json()).runId;runs.push({owner:'B',runId:normalId})
  await page1.waitForFunction(()=>{
    const bubbles=document.querySelectorAll('.assistant-row .assistant-bubble:not(.thinking-bubble)')
    return bubbles.length && bubbles[bubbles.length-1].textContent.trim().length>0
      && document.querySelector('textarea[aria-label="输入消息"]').disabled
  },{},{timeout:60000})
  const interimText=await page1.locator('.assistant-row .assistant-bubble:not(.thinking-bubble)').last().innerText()
  const interimStatus=(await switchedApi('/chat/runs/'+normalId)).status
  await waitState(switchedApi,normalId,'COMPLETED')
  await page1.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
  const normalEvents=(await switchedApi('/chat/runs/'+normalId+'/events?afterEventId=0')).events
  const answer=normalEvents.filter(event=>event.type==='answer.delta').map(event=>event.payload.text).join('')
  fs.writeFileSync(path.join(output,'normal-events.json'),JSON.stringify(normalEvents,null,2)+'\n',{flag:'wx'})
  report.streamProbe={normalId,normalPrompt,interimStatus,interimTextLength:interimText.length,answer}
  assert.ok(answer.length>0);assert.equal(/^[\s]*[\[{]/.test(answer),false)
  assert.ok(normalEvents.filter(event=>event.type==='answer.delta').length>1,'MULTIPLE_PUBLIC_DELTAS_REQUIRED')
  const usage=normalEvents.filter(event=>event.type==='usage').at(-1)?.payload
  assert.ok(usage?.modelCallCount>0,'REAL_MODEL_CALL_REQUIRED')
  await actorA.context.grantPermissions(['clipboard-read','clipboard-write'],{origin})
  const assistant=page1.locator('.assistant-row').last()
  await assistant.hover();await assistant.getByRole('button',{name:'复制消息',exact:true}).click()
  const copied=await page1.evaluate(()=>navigator.clipboard.readText())
  const normalizedClipboard=copied.replaceAll('\r\n','\n'), normalizedAnswer=answer.replaceAll('\r\n','\n')
  assert.equal(normalizedClipboard,normalizedAnswer,'COPY_MUST_INCLUDE_THE_COMPLETE_PUBLIC_ANSWER')
  const userRow=page1.locator('.user-row').last()
  const bubbleBox=await userRow.locator('.user-bubble').boundingBox(), actionsBox=await userRow.locator('.message-actions').boundingBox()
  assert.ok(bubbleBox && actionsBox && actionsBox.y>=bubbleBox.y+bubbleBox.height-1,'MESSAGE_ACTIONS_MUST_BE_BELOW_BUBBLE')
  await userRow.hover();await userRow.getByRole('button',{name:'编辑消息',exact:true}).click()
  const input=page1.getByLabel('输入消息',{exact:true})
  assert.equal(await input.inputValue(),normalPrompt)
  const editedPrompt='你好，请只用一句中文介绍你能提供的资料查询功能。'
  await input.fill(editedPrompt)
  const editedCreated=page1.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname==='/api/chat/runs')
  await page1.getByRole('button',{name:'发送',exact:true}).click()
  const editedResponse=await editedCreated;assert.equal(editedResponse.status(),202)
  const editedRequest=editedResponse.request().postDataJSON()
  assert.equal(editedRequest.prompt,editedPrompt)
  assert.notEqual(editedRequest.conversationId,conversation1)
  const editedId=(await editedResponse.json()).runId;runs.push({owner:'B',runId:editedId})
  await waitState(switchedApi,editedId,'COMPLETED')
  await page1.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
  const editedEvents=(await switchedApi('/chat/runs/'+editedId+'/events?afterEventId=0')).events
  fs.writeFileSync(path.join(output,'edited-events.json'),JSON.stringify(editedEvents,null,2)+'\n',{flag:'wx'})
  assert.ok(sameJson(await switchedApi('/pokemon/teams/'+teamB.id),teamB))
  assert.equal((await page1.locator('body').innerText()).includes(teamA.name),false)
  report.records.push({caseId:'S6-07',normalId,editedId,normalPrompt,editedPrompt,editedRequest,
    interimStatus,interimTextLength:interimText.length,answer,answerSha256:digest(answer),
    clipboardSha256:digest(copied),actionsBelowBubble:true,teamUnchanged:true,
    normalizedClipboardSha256:digest(normalizedClipboard),normalizedAnswerSha256:digest(normalizedAnswer),
    modelCallCount:usage.modelCallCount,
    eventFiles:['normal-events.json','edited-events.json']})
  await page1.screenshot({path:path.join(output,'edited-and-copied.png')})
  }
  const afterRuntime=await actorB.api('/chat/runtime')
  report.runtimeAfter=publicFingerprint(afterRuntime);report.runtimeBuildAfter=runtimeBuild(afterRuntime)
  assert.equal(publicFingerprint(afterRuntime).sha256,report.runtime.sha256)
  assert.equal(runtimeBuild(afterRuntime).sha256,report.runtimeBuild.sha256)
  assert.deepEqual(report.pageErrors,[]);report.status='COLLECTED_PENDING_CONTENT_REVIEW'
} catch(error) {
  report.status='FAIL';report.failure=String(error.message).slice(0,250);process.exitCode=1
} finally {
  // Account A's first context may now hold B's session. Use a fresh A session for cleanup.
  if(actorA && browser)try {
    const context=await browser.newContext(), api=apiFor(context)
    await api('/auth/session');await api('/auth/local/login',actorA.credentials)
    for(const run of runs.filter(run=>run.owner==='A'))await api('/chat/runs/'+run.runId+'/cancel',{reason:'s6_cleanup'})
    if(teamA) {
      await api('/pokemon/teams/'+teamA.id,{...stage2Team(),name:baselineA.name},200,'PATCH')
      report.restoredTeam=await api('/pokemon/teams/'+teamA.id)
      assert.ok(sameJson(report.restoredTeam,baselineA));report.cleanup.push('TEST_BASELINE_RESTORED')
    }
    await api('/logout',{},204);await context.close()
  }catch{report.cleanup.push('RESTORE_FAILED');report.status='FAIL';process.exitCode=1}
  for(const actor of actors)try {
    if(actor===actorB)for(const run of runs.filter(run=>run.owner==='B'))await actor.api('/chat/runs/'+run.runId+'/cancel',{reason:'s6_cleanup'})
    await actor.api('/logout',{},204);report.cleanup.push('LOGGED_OUT')
  }catch{report.cleanup.push('LOGOUT_FAILED');report.status='FAIL';process.exitCode=1}
  if(browser)await browser.close()
  await server.close();report.finishedAt=new Date().toISOString()
  fs.writeFileSync(path.join(output,'browser-result.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify({status:report.status,cases:report.records.map(row=>row.caseId),failure:report.failure}))
}
