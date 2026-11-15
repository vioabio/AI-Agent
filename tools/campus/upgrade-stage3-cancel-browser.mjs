import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath,pathToFileURL} from 'node:url'
import {randomBytes} from 'node:crypto'
import {localBase,digest,sameJson} from './evaluation.mjs'
import {publicFingerprint} from './upgrade-baseline.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {stage2Team} from './stage2-regression.mjs'
import {stage3Cases} from './stage3-regression.mjs'
import {createRunViewModel,reduceRunEvent} from '../../vio-ai-agent-frontend/src/api/run-contract.js'

export function verifyCancelledReplay(runId,events,tail,cursor) {
 assert.ok(events.length>0)
 let prior=0n
 for(const event of events) {
  assert.equal(event.runId,runId);assert.ok(BigInt(event.eventId)>prior);prior=BigInt(event.eventId)
 }
 assert.equal(events.filter(e=>e.type==='run.cancelled').length,1)
 assert.equal(events.filter(e=>['run.completed','run.failed','run.timed.out'].includes(e.type)).length,0)
 const terminal=events.findIndex(e=>e.type==='run.cancelled')
 assert.equal(terminal,events.length-1,'EVENT_AFTER_CANCEL_TERMINAL')
 assert.equal(events.slice(terminal+1).filter(e=>['answer.delta','answer.completed','run.completed'].includes(e.type)).length,0)
 assert.ok(sameJson(tail,events.filter(e=>BigInt(e.eventId)>BigInt(cursor))))
 const replay=list=>list.reduce((model,event)=>reduceRunEvent(model,event),createRunViewModel({runId,status:'RUNNING'}))
 const once=replay(events),twice=replay([...events,...events,...tail])
 assert.equal(once.status,'cancelled');assert.equal(twice.answer,once.answer)
 assert.equal(once.answer,events.filter(e=>e.type==='answer.delta').map(e=>e.payload.text).join(''))
 return {status:'VERIFIED',answerSha256:digest(once.answer),answerLength:once.answer.length,eventCount:events.length,duplicateReplayStable:true}
}

async function main() {
 const [directory,url]=process.argv.slice(2),apiBase=new URL(localBase(url)),output=path.resolve(directory)
 assert.equal(path.dirname(output),fs.realpathSync('artifacts'));assert.equal(fs.existsSync(output),false);fs.mkdirSync(output)
 const front=path.resolve('vio-ai-agent-frontend'),origin='http://127.0.0.1:4329'
 const {chromium}=await import(pathToFileURL(path.join(front,'node_modules/playwright/index.mjs')))
 const {createServer}=await import(pathToFileURL(path.join(front,'node_modules/vite/dist/node/index.js')))
 const {default:base}=await import(pathToFileURL(path.join(front,'vite.config.js')))
 const server=await createServer({...base,configFile:false,root:front,server:{...base.server,host:'127.0.0.1',port:4329,strictPort:true,proxy:{'/api':{target:apiBase.origin,changeOrigin:false}}}})
 const report={schemaVersion:'stage3-cancel-browser-v1',scope:['S3-10','S3-13'],status:'RUNNING',records:[],cleanup:[],pageErrors:[],startedAt:new Date().toISOString(),releaseDecision:'NOT_EVALUATED',
  producerSha256:digest(fs.readFileSync(new URL(import.meta.url))),
  frontendSha256:Object.fromEntries(['api/run-contract.js','composables/useRunStream.js','views/GameMaster.vue']
    .map(file=>[file,digest(fs.readFileSync(path.join(front,'src',file)))]))}
 let browser,context,authenticated=false,runId
 async function api(route,body,expected=200) {
  const cookies=await context.cookies(origin),csrf=cookies.find(cookie=>cookie.name==='CSRF-TOKEN')?.value
  const response=await context.request.fetch(origin+'/api'+route,{method:body===undefined?'GET':'POST',headers:{Origin:origin,...(csrf?{'X-CSRF-TOKEN':decodeURIComponent(csrf)}:{})},...(body===undefined?{}:{data:body}),timeout:30000,maxRedirects:0})
  assert.equal(response.status(),expected,'HTTP_'+response.status());if(expected===204)return null
  const value=await response.json();return value&&Object.hasOwn(value,'data')&&Object.hasOwn(value,'code')?value.data:value
 }
 try {
  await server.listen();browser=await chromium.launch({headless:true,channel:process.platform==='win32'?'chrome':undefined})
  context=await browser.newContext({viewport:{width:1440,height:900},serviceWorkers:'block'})
  assert.equal((await api('/auth/session')).mode,'local')
  const credentials={username:'cancel_'+randomBytes(8).toString('hex'),password:randomBytes(24).toString('base64url'),nickname:'取消重连隔离测试'}
  await api('/auth/local/register',credentials,201);await api('/auth/local/login',credentials);authenticated=true
  const runtime=await api('/chat/runtime');report.runtime=publicFingerprint(runtime);report.runtimeBuild=runtimeBuild(runtime)
  report.guideCatalog=await api('/pokemon/guides/catalog');report.semanticState=await api('/pokemon/guides/semantic')
  const team=await api('/pokemon/teams',stage2Team(),201);report.teamBefore=await api('/pokemon/teams/'+team.id)
  const page=await context.newPage();page.on('pageerror',e=>report.pageErrors.push(e.name))
  await page.goto(origin+'/game-master',{waitUntil:'domcontentloaded'})
  const input=page.getByLabel('输入消息',{exact:true});await input.waitFor({timeout:30000})
  await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
  const created=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/chat/runs')
  const prompt=stage3Cases().find(row=>row.id==='S3-03').turns[0]
  await input.fill(prompt);await page.getByRole('button',{name:'发送',exact:true}).click()
  const response=await created;assert.equal(response.status(),202);runId=(await response.json()).runId
  report.runId=runId;report.prompt=prompt
  let before=[],state,ready=false
  const deadline=Date.now()+120000
  while(Date.now()<deadline) {
   before=(await api('/chat/runs/'+runId+'/events?afterEventId=0')).events;state=await api('/chat/runs/'+runId)
   if(state.status==='RUNNING'){ready=true;break}
   if(['COMPLETED','FAILED','CANCELLED','TIMED_OUT'].includes(state.status))break
   await new Promise(resolve=>setTimeout(resolve,100))
  }
  report.beforeCancelStatus=state?.status;report.beforeEvents=before
  assert.equal(ready,true,'RUNNING_WINDOW_REQUIRED')
  const cursor=String(before.at(-1).eventId);report.beforeCancelStatus=state.status;report.beforeEvents=before
  await context.setOffline(true)
  report.cancel=await api('/chat/runs/'+runId+'/cancel',{reason:'stage3_browser_cancel'})
  await new Promise(resolve=>setTimeout(resolve,350));await context.setOffline(false)
  assert.equal(report.cancel.changed,true,'CANCEL_RACE_NOT_ACCEPTED');assert.equal(report.cancel.status,'CANCELLED')
  await page.getByText('已取消',{exact:true}).first().waitFor({timeout:30000})
  await new Promise(resolve=>setTimeout(resolve,1500))
  const events=(await api('/chat/runs/'+runId+'/events?afterEventId=0')).events
  const again=(await api('/chat/runs/'+runId+'/events?afterEventId=0')).events
  assert.ok(sameJson(events,again),'TERMINAL_EVENTS_CHANGED')
  const tail=(await api('/chat/runs/'+runId+'/events?afterEventId='+cursor)).events
  report.replay=verifyCancelledReplay(runId,events,tail,cursor)
  report.partialAnswerObserved=events.some(e=>e.type==='answer.delta'&&e.payload.text)
  if(report.partialAnswerObserved) {
   const text=await page.locator('.assistant-bubble').last().innerText()
   assert.ok(text.trim().length>0)
   assert.ok((text.match(/伤害结果（计算事实）/g)||[]).length<=1)
  }
  assert.equal(await page.locator('.assistant-row').last().getByText('已完成',{exact:true}).count(),0)
  const repeat=await api('/chat/runs/'+runId+'/cancel',{reason:'stage3_repeat_cancel'})
  assert.equal(repeat.changed,false);assert.equal(repeat.terminalEventId,report.cancel.terminalEventId)
  report.eventsFile='cancel-events.json';const bytes=JSON.stringify(events,null,2)+'\n';fs.writeFileSync(path.join(output,report.eventsFile),bytes,{flag:'wx'});report.eventsSha256=digest(bytes)
  report.answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload.text).join('')
  report.teamAfter=await api('/pokemon/teams/'+team.id);assert.ok(sameJson(report.teamBefore,report.teamAfter))
  await page.screenshot({path:path.join(output,'cancelled-reconnected.png')})
  const after=await api('/chat/runtime');assert.equal(publicFingerprint(after).sha256,report.runtime.sha256);assert.equal(runtimeBuild(after).sha256,report.runtimeBuild.sha256)
  report.runtimeAfter=publicFingerprint(after);report.runtimeBuildAfter=runtimeBuild(after)
  assert.deepEqual(report.pageErrors,[]);report.status='PASS';report.records=[{caseId:'S3-10',verdict:'PASS'},{caseId:'S3-13',verdict:'PASS'}]
 } catch(error) {report.status='FAIL';report.failure=error.code??error.name;report.failureCheck=String(error.message).slice(0,250);process.exitCode=1}
 finally {
  if(context)await context.setOffline(false).catch(()=>{})
  if(runId)try{await api('/chat/runs/'+runId+'/cancel',{});report.cleanup.push('RUN_TERMINAL')}catch{report.cleanup.push('CANCEL_UNVERIFIED');report.status='FAIL';process.exitCode=1}
  if(authenticated)try{await api('/logout',{},204);report.cleanup.push('LOGGED_OUT')}catch{report.status='FAIL';process.exitCode=1}
  if(browser)await browser.close();await server.close();report.finishedAt=new Date().toISOString()
  fs.writeFileSync(path.join(output,'browser-result.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:report.status,records:report.records,failure:report.failure,failureCheck:report.failureCheck}))
 }
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await main()
