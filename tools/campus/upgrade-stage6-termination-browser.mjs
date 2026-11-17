import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {pathToFileURL} from 'node:url'
import {randomBytes} from 'node:crypto'
import {localBase,digest,sameJson} from './evaluation.mjs'
import {stage2Team} from './stage2-regression.mjs'
import {publicFingerprint} from './upgrade-baseline.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {combinationFeatures} from './upgrade-combination-verify.mjs'
import {verifyTerminatedApproval} from './stage6-termination-evidence.mjs'

const [directory,url,mode]=process.argv.slice(2),backend=new URL(localBase(url)),output=path.resolve(directory)
assert.notEqual(backend.port,'8123','ISOLATED_API_REQUIRED')
assert.ok(['enabled','disabled'].includes(mode),'EXPLICIT_COMBINATION_MODE_REQUIRED')
assert.equal(path.dirname(output),fs.realpathSync('artifacts'));assert.equal(fs.existsSync(output),false)
fs.mkdirSync(output)
const front=path.resolve('vio-ai-agent-frontend'),origin='http://127.0.0.1:4329'
const {chromium}=await import(pathToFileURL(path.join(front,'node_modules/playwright/index.mjs')))
const {createServer}=await import(pathToFileURL(path.join(front,'node_modules/vite/dist/node/index.js')))
const {default:config}=await import(pathToFileURL(path.join(front,'vite.config.js')))
const server=await createServer({...config,configFile:false,root:front,server:{...config.server,
  host:'127.0.0.1',port:4329,strictPort:true,proxy:{'/api':{target:backend.origin,changeOrigin:false}}}})
const report={schemaVersion:'stage6-termination-browser-v1',mode,status:'RUNNING',
  scope:'PENDING_APPROVAL_CANCEL_AND_DEADLINE',records:[],attempts:[],pageErrors:[],cleanup:[],
  requestPolicy:'BROWSER_REQUEST_DEADLINE_OVERRIDE_NO_RESPONSE_MOCKING',
  producerSha256:digest(fs.readFileSync(new URL(import.meta.url))),
  frontendSha256:Object.fromEntries(['api/run-contract.js','api/version-registry.js','composables/useRunStream.js','components/RunTimeline.vue','views/GameMaster.vue','views/SuperAgent.vue']
    .map(file=>[file,digest(fs.readFileSync(path.join(front,'src',file)))])),
  qualityReview:'REQUIRED',releaseDecision:'NOT_EVALUATED'}
let browser,context,authenticated=false,team,activeRun,deadlineSeconds=60
async function response(route,body) {
  const cookies=await context.cookies(origin),csrf=cookies.find(cookie=>cookie.name==='CSRF-TOKEN')?.value
  return context.request.fetch(origin+'/api'+route,{method:body===undefined?'GET':'POST',
    headers:{Origin:origin,...(csrf?{'X-CSRF-TOKEN':decodeURIComponent(csrf)}:{})},
    ...(body===undefined?{}:{data:body}),timeout:30000,maxRedirects:0})
}
async function api(route,body,expected=200) {
  const result=await response(route,body);assert.equal(result.status(),expected,'HTTP_'+result.status())
  if(expected===204)return null
  const value=await result.json()
  if(value&&Object.hasOwn(value,'code'))assert.equal(value.code,200)
  return value&&Object.hasOwn(value,'data')&&Object.hasOwn(value,'code')?value.data:value
}
async function waitState(runId,wanted) {
  const end=Date.now()+45000
  do {
    const view=await api('/chat/runs/'+runId)
    if(view.status===wanted)return view
    assert.ok(!['COMPLETED','FAILED','CANCELLED','TIMED_OUT'].includes(view.status),'UNEXPECTED_TERMINAL_'+view.status)
    await new Promise(done=>setTimeout(done,100))
  }while(Date.now()<end)
  throw Error('RUN_STATE_DEADLINE')
}
try {
  await server.listen();browser=await chromium.launch({headless:true,channel:process.platform==='win32'?'chrome':undefined})
  context=await browser.newContext({viewport:{width:1440,height:1000},serviceWorkers:'block'})
  const credentials={username:'term_'+randomBytes(8).toString('hex'),password:randomBytes(24).toString('base64url'),nickname:'终止审批组合测试'}
  assert.equal((await api('/auth/session')).mode,'local')
  await api('/auth/local/register',credentials,201);await api('/auth/local/login',credentials);authenticated=true
  const runtime=await api('/chat/runtime')
  for(const feature of combinationFeatures)assert.equal(runtime.upgradeFeatures[feature],mode==='enabled')
  report.runtime=publicFingerprint(runtime);report.runtimeBuild=runtimeBuild(runtime)
  report.guideCatalog=await api('/pokemon/guides/catalog');report.semanticState=await api('/pokemon/guides/semantic')
  team=await api('/pokemon/teams',stage2Team(),201);report.teamBefore=await api('/pokemon/teams/'+team.id)
  const page=await context.newPage();page.on('pageerror',error=>report.pageErrors.push(error.name))
  await page.route('**/api/chat/runs',async route=>{
    if(route.request().method()!=='POST')return route.continue()
    const body={...route.request().postDataJSON(),timeoutSeconds:deadlineSeconds}
    await route.continue({postData:JSON.stringify(body)})
  })
  await page.goto(origin+'/game-master',{waitUntil:'domcontentloaded'})
  const input=page.getByLabel('输入消息',{exact:true});await input.waitFor({timeout:30000})
  for(const status of ['cancelled','timed_out']) {
    deadlineSeconds=status==='timed_out'?10:60
    await page.setViewportSize(status==='timed_out'?{width:430,height:932}:{width:1440,height:1000})
    await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
    const created=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/chat/runs')
    const prompt='读取我的test1队伍，把槽位1等级从51改为52，其余字段保持当前值。只生成修改预览，等待页面确认。'
    await input.fill(prompt);await page.getByRole('button',{name:'发送',exact:true}).click()
    const accepted=await created;assert.equal(accepted.status(),202);activeRun=(await accepted.json()).runId
    const admitted=await api('/chat/runs/'+activeRun)
    report.attempts.push({runId:activeRun,status,dataReleaseId:admitted.dataReleaseId})
    assert.equal(admitted.dataReleaseId,report.teamBefore.dataReleaseId,'CONVERSATION_VERSION_DRIFT')
    await waitState(activeRun,'WAITING_APPROVAL')
    const preview=await api('/chat/runs/'+activeRun+'/team-change')
    assert.equal(preview.status,'PENDING');assert.equal(preview.preview.teamId,team.id)
    assert.ok(sameJson(await api('/pokemon/teams/'+team.id),report.teamBefore))
    await page.getByRole('button',{name:'确认保存',exact:true}).waitFor({timeout:5000})
    const before=(await api('/chat/runs/'+activeRun+'/events?afterEventId=0')).events
    const cursor=String(before.at(-1).eventId)
    if(status==='cancelled')await page.getByRole('button',{name:'停止任务',exact:true}).click()
    else await context.setOffline(true)
    await waitState(activeRun,status.toUpperCase())
    await context.setOffline(false)
    await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled,{},{timeout:30000})
    assert.equal(await page.locator('.approval-card').count(),0,'STALE_APPROVAL_CARD')
    assert.equal(await page.getByRole('button',{name:'确认保存',exact:true}).count(),0)
    assert.equal(await page.locator('.assistant-row').last().getByText('已完成',{exact:true}).count(),0)
    const events=(await api('/chat/runs/'+activeRun+'/events?afterEventId=0')).events
    const tail=(await api('/chat/runs/'+activeRun+'/events?afterEventId='+cursor)).events
    assert.ok(sameJson(tail,events.filter(event=>BigInt(event.eventId)>BigInt(cursor))))
    const file=status+'-events.json',bytes=JSON.stringify(events,null,2)+'\n'
    fs.writeFileSync(path.join(output,file),bytes,{flag:'wx'})
    const row={runId:activeRun,prompt,status,proposalId:preview.preview.proposalId,deadlineSeconds,
      preview,eventsFile:file,eventsSha256:digest(bytes),cursor,approvalButtonsHidden:true}
    row.replay=verifyTerminatedApproval(bytes,row)
    const late=await response('/chat/runs/'+activeRun+'/team-change/decision',{approvalId:row.proposalId,approved:true})
    assert.equal(late.status(),status==='timed_out'?410:409);row.lateDecisionStatus=late.status()
    row.outcome=await api('/chat/runs/'+activeRun+'/team-change')
    assert.equal(row.outcome.status,status==='timed_out'?'EXPIRED':'CANCELLED')
    row.teamAfter=await api('/pokemon/teams/'+team.id);assert.ok(sameJson(row.teamAfter,report.teamBefore))
    assert.ok(sameJson((await api('/chat/runs/'+activeRun+'/events?afterEventId=0')).events,events),'LATE_DECISION_CHANGED_EVENTS')
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true)
    await page.screenshot({path:path.join(output,status+'.png')})
    await page.reload({waitUntil:'domcontentloaded'});await input.waitFor({timeout:30000})
    await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
    assert.equal(await page.locator('.approval-card').count(),0,'RESTORED_STALE_APPROVAL')
    row.reloadApprovalHidden=true;report.records.push(row);activeRun=null
    console.log(mode+' '+status+': PASS')
  }
  const after=await api('/chat/runtime');report.runtimeAfter=publicFingerprint(after);report.runtimeBuildAfter=runtimeBuild(after)
  assert.equal(report.runtimeAfter.sha256,report.runtime.sha256);assert.deepEqual(report.runtimeBuildAfter,report.runtimeBuild)
  assert.deepEqual(report.pageErrors,[]);report.status='PASS'
}catch(error){report.status='FAIL';report.failure=error.code||error.name;report.failureCheck=String(error.message).slice(0,200);process.exitCode=1}
finally {
  if(context)await context.setOffline(false).catch(()=>{})
  if(activeRun)try{
    const events=(await api('/chat/runs/'+activeRun+'/events?afterEventId=0')).events
    const bytes=JSON.stringify(events,null,2)+'\n';fs.writeFileSync(path.join(output,'failed-run-events.json'),bytes,{flag:'wx'})
    report.failedRun={runId:activeRun,eventsFile:'failed-run-events.json',eventsSha256:digest(bytes)}
    await api('/chat/runs/'+activeRun+'/cancel',{});report.cleanup.push('RUN_TERMINATED')
  }catch{report.cleanup.push('CANCEL_FAILED');report.status='FAIL';process.exitCode=1}
  if(authenticated)try{report.teamAfter=await api('/pokemon/teams/'+team.id);assert.ok(sameJson(report.teamAfter,report.teamBefore));await api('/logout',{},204);report.cleanup.push('LOGGED_OUT')}catch{report.cleanup.push('CLEANUP_FAILED');report.status='FAIL';process.exitCode=1}
  if(browser)await browser.close();await server.close()
  fs.writeFileSync(path.join(output,'browser-result.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify({mode,status:report.status,records:report.records.length,failure:report.failure,failureCheck:report.failureCheck}))
}
