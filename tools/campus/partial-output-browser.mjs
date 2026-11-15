import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {pathToFileURL} from 'node:url'
import {localBase,digest,sameJson} from './evaluation.mjs'
import {publicFingerprint} from './upgrade-baseline.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {combinationFeatures} from './upgrade-combination-verify.mjs'
import {verifyPartialTermination} from './partial-output-evidence.mjs'

const front=path.resolve('vio-ai-agent-frontend')
let api,control,output,mode
if(!process.argv.includes('--probe')) {
  api=new URL(localBase(process.env.VIO_PARTIAL_TEST_API))
  assert.notEqual(api.port,'8123','ISOLATED_API_REQUIRED')
  control=new URL(process.env.VIO_PARTIAL_CONTROL)
  assert.equal(control.hostname,'127.0.0.1');assert.equal(control.protocol,'http:');assert.equal(control.pathname,'/control')
  assert.notEqual(control.port,'8123');assert.equal(control.username,'');assert.equal(control.password,'');assert.equal(control.search,'');assert.equal(control.hash,'')
  output=fs.realpathSync(process.env.VIO_PARTIAL_OUTPUT)
  const relative=path.relative(fs.realpathSync('artifacts'),output)
  assert.ok(relative&&!relative.startsWith('..')&&!path.isAbsolute(relative))
  assert.equal(fs.existsSync(path.join(output,'partial-browser.json')),false)
  mode=process.env.VIO_PARTIAL_MODE;assert.ok(['enabled','disabled'].includes(mode))
}
const {chromium}=await import(pathToFileURL(path.join(front,'node_modules/playwright/index.mjs')))
const browser=await chromium.launch({headless:true,channel:process.platform==='win32'?'chrome':undefined})
if(process.argv.includes('--probe')){await browser.close();process.exit(0)}
const {createServer}=await import(pathToFileURL(path.join(front,'node_modules/vite/dist/node/index.js')))
const {default:config}=await import(pathToFileURL(path.join(front,'vite.config.js')))
const origin='http://127.0.0.1:4329'
const server=await createServer({...config,configFile:false,root:front,server:{...config.server,
  host:'127.0.0.1',port:4329,strictPort:true,proxy:{'/api':{target:api.origin,changeOrigin:false}}}})
const context=await browser.newContext({viewport:{width:1440,height:950},serviceWorkers:'block'})
const report={schemaVersion:'partial-output-browser-v1',mode,status:'RUNNING',records:[],pageErrors:[],
  provider:'CONTROLLED_LOOPBACK_SSE',qualityBenefit:'NOT_EVALUATED',releaseDecision:'NOT_EVALUATED',
  producerSha256:digest(fs.readFileSync(new URL(import.meta.url))),
  frontendSha256:Object.fromEntries(['api/run-contract.js','composables/useRunStream.js','views/GameMaster.vue']
    .map(file=>[file,digest(fs.readFileSync(path.join(front,'src',file)))]))}
async function request(route,body) {
  const r=await fetch(api.origin+'/api'+route,{method:body===undefined?'GET':'POST',
    headers:{'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(15000)})
  assert.equal(r.status,body!==undefined&&route==='/chat/runs'?202:200)
  const v=await r.json();return v&&Object.hasOwn(v,'code')&&Object.hasOwn(v,'data')?v.data:v
}
async function providerStatus(){const r=await fetch(control+'/status',{redirect:'error',signal:AbortSignal.timeout(10000)});assert.equal(r.status,200);const v=await r.json();assert.equal(v.fixture,'u6-partial-output-v1');return v}
let currentRun
try {
  await providerStatus();await server.listen()
  const runtime=await request('/chat/runtime');report.runtime=publicFingerprint(runtime);report.runtimeBuild=runtimeBuild(runtime)
  for(const key of combinationFeatures)assert.equal(runtime.upgradeFeatures[key],mode==='enabled')
  for(const status of ['cancelled','timed_out']) {
    const beforeProvider=await providerStatus(),page=await context.newPage();let creations=0
    page.on('pageerror',e=>report.pageErrors.push(e.name))
    await page.route('**/api/chat/runs',async route=>{
      if(route.request().method()!=='POST')return route.continue()
      creations++;await route.continue({postData:JSON.stringify({...route.request().postDataJSON(),timeoutSeconds:status==='timed_out'?8:30})})
    })
    await page.goto(origin+'/game-master',{waitUntil:'domcontentloaded'})
    const input=page.getByLabel('输入消息',{exact:true});await input.waitFor({timeout:30000})
    await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
    const accepted=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/chat/runs')
    await input.fill('请回复一段简短的宝可梦欢迎语。');await page.getByRole('button',{name:'发送',exact:true}).click()
    const response=await accepted;assert.equal(response.status(),202);currentRun=(await response.json()).runId
    await page.locator('.assistant-bubble').filter({hasText:'部分测试回答'}).waitFor({timeout:5000})
    const before=(await request('/chat/runs/'+currentRun+'/events?afterEventId=0')).events,cursor=String(before.at(-1).eventId)
    assert.equal((await request('/chat/runs/'+currentRun)).status,'RUNNING')
    await context.setOffline(true)
    if(status==='cancelled')assert.equal((await request('/chat/runs/'+currentRun+'/cancel',{})).changed,true)
    const end=Date.now()+15000
    while((await request('/chat/runs/'+currentRun)).status!==status.toUpperCase()){
      assert.ok(Date.now()<end,'TERMINAL_NOT_REACHED');await new Promise(done=>setTimeout(done,100))
    }
    const frozen=(await request('/chat/runs/'+currentRun+'/events?afterEventId=0')).events
    const release=await fetch(control+'/release',{method:'POST',redirect:'error',signal:AbortSignal.timeout(10000)});assert.equal(release.status,204)
    let afterProvider=await providerStatus()
    const tailEnd=Date.now()+3000
    while(afterProvider.tailAttempts===beforeProvider.tailAttempts&&Date.now()<tailEnd){await new Promise(done=>setTimeout(done,50));afterProvider=await providerStatus()}
    await context.setOffline(false)
    await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled,{},{timeout:15000})
    const text=await page.locator('.assistant-bubble').last().innerText();assert.equal((text.match(/部分测试回答/g)||[]).length,1)
    assert.equal(text.includes('不应发布的尾部'),false)
    assert.equal(await page.locator('.assistant-row').last().getByText('已完成',{exact:true}).count(),0)
    const events=(await request('/chat/runs/'+currentRun+'/events?afterEventId=0')).events
    assert.ok(sameJson(frozen,events));assert.equal(creations,1)
    const file=status+'-partial-events.json',bytes=JSON.stringify(events,null,2)+'\n';fs.writeFileSync(path.join(output,file),bytes,{flag:'wx'})
    const row={runId:currentRun,status,cursor,eventsFile:file,eventsSha256:digest(bytes),providerBefore:beforeProvider,providerAfter:afterProvider,
      uiPartialOnce:true,uiNotCompleted:true,runCreations:creations}
    row.replay=verifyPartialTermination(bytes,row);report.records.push(row)
    await page.screenshot({path:path.join(output,status+'-partial.png')});currentRun=null;await page.close()
  }
  report.runtimeAfter=publicFingerprint(await request('/chat/runtime'));report.runtimeBuildAfter=runtimeBuild(await request('/chat/runtime'))
  assert.deepEqual(report.pageErrors,[]);assert.deepEqual(report.runtimeAfter,report.runtime);assert.deepEqual(report.runtimeBuildAfter,report.runtimeBuild)
  report.status='PASS'
}catch(error){report.status='FAIL';report.failure=error.name;report.failureCheck=String(error.message).slice(0,250);process.exitCode=1}
finally {
  await context.setOffline(false).catch(()=>{})
  if(currentRun)try{await request('/chat/runs/'+currentRun+'/cancel',{})}catch{report.cleanup='CANCEL_FAILED';process.exitCode=1}
  await context.close();await browser.close();await server.close()
  fs.writeFileSync(path.join(output,'partial-browser.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify({status:report.status,runs:report.records.length,output,failure:report.failure,failureCheck:report.failureCheck}))
}
