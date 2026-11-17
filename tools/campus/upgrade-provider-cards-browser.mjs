// Real browser, authenticated isolated Spring server and the configured model provider.
// Usage: node ... API_URL artifacts/NEW_DIRECTORY [enabled|disabled]
import fs from 'node:fs'
import path from 'node:path'
import {pathToFileURL} from 'node:url'
import {randomBytes} from 'node:crypto'
import assert from 'node:assert/strict'
import {digest, localBase} from './evaluation.mjs'
import {publicFingerprint} from './upgrade-baseline.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {verifyTurnEvents} from './upgrade-event-evidence.mjs'

const [apiUrl, outputName, mode='enabled',portText='4328'] = process.argv.slice(2)
if (!outputName || !['enabled','disabled'].includes(mode)) throw Error('API, new artifact directory and enabled/disabled mode required')
const frontendPort=Number(portText)
if(!Number.isInteger(frontendPort)||frontendPort<1024||frontendPort>65535)throw Error('VALID_FRONTEND_PORT_REQUIRED')
const apiBase = new URL(localBase(apiUrl))
if (apiBase.port === '8123') throw Error('ISOLATED_API_REQUIRED')
const output = path.resolve(outputName)
const within = path.relative(fs.realpathSync('artifacts'), fs.realpathSync(path.dirname(output)))
if (within.startsWith('..') || path.isAbsolute(within) || fs.existsSync(output)) throw Error('NEW_ARTIFACT_DIRECTORY_REQUIRED')
fs.mkdirSync(output)
const front = path.resolve('vio-ai-agent-frontend')
const {chromium} = await import(pathToFileURL(path.join(front,'node_modules/playwright/index.mjs')))
const {createServer} = await import(pathToFileURL(path.join(front,'node_modules/vite/dist/node/index.js')))
const {default:base} = await import(pathToFileURL(path.join(front,'vite.config.js')))
const origin = 'http://127.0.0.1:'+frontendPort
const server = await createServer({...base,configFile:false,root:front,
  server:{...base.server,host:'127.0.0.1',port:frontendPort,strictPort:true,
    proxy:{'/api':{target:apiBase.origin,changeOrigin:false}}}})
const cases = [
  {id:'U2-LIVE-FACT',prompt:'查询胖丁的属性和种族值，只列图鉴已核验字段。',type:'fact.card',region:'已核验图鉴卡片'},
  {id:'U2-LIVE-DAMAGE',prompt:'按默认50级、Hardy、0 EV和31 IV，计算杰尼龟用撞击攻击杰尼龟，无道具、无天气及场地。',type:'calculation.card',region:'已核验伤害卡片'},
  {id:'U2-LIVE-TEAM',prompt:'分析队伍，只看这两位参考成员的资料，不保存，不生成修改预览。\n参考成员：7,39',type:'team.matrix',region:'队伍参考成员卡片'},
  {id:'U2-LIVE-MISSING',prompt:'查询不存在的宝可梦「量子泡泡龙XYZ」的属性和种族值，只列已核验事实。',type:null}
]
const report = {schemaVersion:'provider-cards-browser-v1',scope:'FOUR_DEVELOPMENT_CASES_REAL_PROVIDER_BROWSER',
  mode,qualityReview:'REQUIRED',releaseDecision:'NOT_EVALUATED',casesSha256:digest(cases),
  producerSha256:digest(fs.readFileSync(new URL(import.meta.url))),
  frontendComponentsSha256:Object.fromEntries(['components/ChatRoom.vue','components/PokemonAnswerBlock.vue',
    'components/DamageAnswerBlock.vue','components/TeamSuggestionAnswerBlock.vue','api/answer-block.js','api/evidence-answer-block.js']
    .map(name=>[name,digest(fs.readFileSync(path.join(front,'src',name)))])),
  startedAt:new Date().toISOString(),status:'RUNNING',records:[],pageErrors:[],teamMutationRequests:[],cleanup:[]}
let browser, context, authenticated=false
const opened=new Set()
async function api(route, body, expected=200) {
  const cookies = await context.cookies(origin)
  const csrf = cookies.find(cookie=>cookie.name==='CSRF-TOKEN')?.value
  const response = await context.request.fetch(origin+'/api'+route,{method:body===undefined?'GET':'POST',
    headers:{Origin:origin,...(csrf?{'X-CSRF-TOKEN':decodeURIComponent(csrf)}:{})},
    ...(body===undefined?{}:{data:body}),timeout:30000,maxRedirects:0})
  assert.equal(response.status(),expected,'HTTP_'+response.status())
  if (expected===204) return null
  const value = await response.json()
  return value && Object.hasOwn(value,'data') && Object.hasOwn(value,'code') ? value.data : value
}
async function mobileCheck(page,element,file) {
  await page.setViewportSize({width:430,height:932})
  await page.getByRole('button',{name:'关闭资料侧栏',exact:true}).waitFor({state:'hidden'})
  await page.waitForFunction(()=>document.querySelector('.workspace-nav').getBoundingClientRect().right<=1)
  await element.scrollIntoViewIfNeeded()
  const bounds=await element.boundingBox()
  assert.ok(bounds.x>=0&&bounds.x+bounds.width<=431)
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true)
  assert.equal(await page.locator('.message-list').evaluate(element=>element.scrollLeft),0)
  await page.screenshot({path:path.join(output,file)})
  await page.setViewportSize({width:1440,height:900})
}
try {
  await server.listen()
  browser = await chromium.launch({headless:true,channel:process.platform==='win32'?'chrome':undefined})
  context = await browser.newContext({viewport:{width:1440,height:900},serviceWorkers:'block'})
  assert.equal((await api('/auth/session')).mode,'local')
  const credentials = {username:'cards_'+randomBytes(8).toString('hex'),password:randomBytes(24).toString('base64url'),nickname:'卡片隔离验收'}
  await api('/auth/local/register',credentials,201)
  await api('/auth/local/login',credentials); authenticated=true
  const runtime = await api('/chat/runtime')
  report.runtime=publicFingerprint(runtime); report.runtimeBuild=runtimeBuild(runtime)
  report.guideCatalog=await api('/pokemon/guides/catalog');report.semanticState=await api('/pokemon/guides/semantic')
  assert.equal(runtime.configuredProvider,'deepseek')
  assert.equal(runtime.upgradeFeatures.answerBlocks,mode==='enabled')
  const page = await context.newPage()
  page.on('pageerror',error=>report.pageErrors.push(error.name))
  page.on('request',request=>{
    if (['POST','PUT','PATCH','DELETE'].includes(request.method())
        && /\/(?:teams(?:\/|$)|team-change\/decision)/.test(new URL(request.url()).pathname))
      report.teamMutationRequests.push(request.method())
  })
  await page.goto(origin+'/game-master',{waitUntil:'domcontentloaded'})
  const input = page.getByLabel('输入消息',{exact:true})
  await input.waitFor({timeout:30000})
  for (const item of cases) {
    await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
    const created = page.waitForResponse(response=>new URL(response.url()).pathname==='/api/chat/runs' && response.request().method()==='POST')
    await input.fill(item.prompt); await page.getByRole('button',{name:'发送',exact:true}).click()
    const creation = await created; assert.equal(creation.status(),202)
    const run = await creation.json()
    opened.add(run.runId)
    const end = Date.now()+140000
    let events=[],state
    do {
      events=(await api('/chat/runs/'+run.runId+'/events?afterEventId=0')).events
      state=await api('/chat/runs/'+run.runId)
      if (events.some(event=>['run.completed','run.failed','run.cancelled','run.timed.out'].includes(event.type))) break
      await new Promise(done=>setTimeout(done,250))
    } while (Date.now()<end)
    const answer = events.filter(event=>event.type==='answer.delta').map(event=>event.payload.text).join('')
    const eventsFile=item.id+'-0-events.json',bytes=JSON.stringify(events,null,2)+'\n'
    fs.writeFileSync(path.join(output,eventsFile),bytes,{flag:'wx'})
    const turn={runId:run.runId,prompt:item.prompt,status:state.status,answer,eventsFile,eventsSha256:digest(bytes)}
    const modelCallCount=events.findLast(event=>event.type==='run.completed')?.payload?.modelUsage?.modelCallCount
    report.records.push({caseId:item.id,turn,modelCallCount,checks:'PENDING'})
    if (['COMPLETED','FAILED','CANCELLED','TIMED_OUT'].includes(state.status)) opened.delete(run.runId)
    verifyTurnEvents(turn,item.id,0,bytes)
    assert.ok(answer.trim())
    if(item.id==='U2-LIVE-MISSING') {
      assert.match(answer,/未取得|未能读取|当前资料源未找到/,'MISSING_REFERENCE_MUST_STAY_EVIDENCE_BOUND')
      assert.doesNotMatch(answer,/不存在|查无此|不是可核验的宝可梦|查询没有返回/,'UNVERIFIED_ABSENCE_CLAIM')
    }
    if (item.id==='U2-LIVE-TEAM') assert.doesNotMatch(answer,/培养方案|队伍搭配|训练家提醒/,'FACTS_ONLY_TEAM_SCOPE_REQUIRED')
    await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled,{},{timeout:10000})
    const row=page.locator('.assistant-row').filter({has:page.locator('.message-content')}).last()
    const bubble=row.locator('.assistant-bubble')
    const blocks=events.filter(event=>['fact.card','calculation.card','team.matrix','training.plan'].includes(event.type))
    if (mode==='disabled' || !item.type) {
      assert.equal(blocks.length,0)
      assert.equal(await row.locator('.verified-pokemon,.verified-damage,.team-reference').count(),0)
    } else {
      const matching=blocks.filter(event=>event.type===item.type)
      assert.ok(matching.length>0,'EXPECTED_TYPED_CARD_MISSING')
      const block=matching.at(-1).payload.answerBlock
      assert.equal(block.schemaVersion,'answer-block-v1')
      assert.equal(block.gameReleaseId,'pokemon-scarlet-violet')
      assert.equal(block.dataReleaseId,'pokeapi-reference-v1')
      const card=row.getByRole('region',{name:item.region,exact:true})
      assert.equal(await card.count(),1)
      if (item.type==='fact.card') {
        assert.equal(block.formId,39);assert.deepEqual(block.stats,[115,45,20,45,25,20])
        assert.deepEqual(block.types,['一般','妖精']);assert.match(await card.innerText(),/胖丁/)
        await card.getByRole('button',{name:'查看图鉴',exact:true}).click()
        await page.waitForFunction(()=>[...document.querySelectorAll('.fact-card h3')].some(el=>el.textContent.includes('胖丁')))
      }
      if (item.type==='calculation.card') {
        assert.ok(block.minimum>0 && block.maximum>=block.minimum)
        assert.equal(block.attacker.level,50);assert.equal(block.defender.level,50)
        assert.equal(block.evidence.targetBlockId,block.blockId)
        assert.equal(block.evidence.projectionDigest,block.evidenceDigest)
        assert.match(await card.innerText(),new RegExp(block.minimum+'–'+block.maximum))
        await card.getByText('查看核验依据',{exact:true}).click()
        for (const source of block.evidence.sources) {
          const link=card.getByRole('link',{name:source.role==='attacker'?'攻击方图鉴参考':'防御方图鉴参考'})
          assert.equal(await link.getAttribute('href'),source.url)
          assert.equal(await link.getAttribute('rel'),'noopener noreferrer')
        }
      }
      if (item.type==='team.matrix') {
        assert.deepEqual(block.members.map(member=>member.formId),[7,39])
        assert.match(await card.innerText(),/2 位参考成员 · 未保存/)
      }
      await mobileCheck(page,card,item.id+'-mobile.png')
    }
    if(mode==='disabled'||!item.type)await mobileCheck(page,bubble,item.id+'-mobile.png')
    await bubble.scrollIntoViewIfNeeded()
    await page.screenshot({path:path.join(output,item.id+'-desktop.png')})
    report.records.at(-1).checks='PASS'
    report.records.at(-1).narrowViewportChecked=true
    console.log(item.id+': PASS')
  }
  const after=await api('/chat/runtime')
  report.runtimeAfter=publicFingerprint(after);report.runtimeBuildAfter=runtimeBuild(after)
  assert.equal(publicFingerprint(after).sha256,report.runtime.sha256)
  assert.equal(runtimeBuild(after).sha256,report.runtimeBuild.sha256)
  assert.deepEqual(report.pageErrors,[]);assert.deepEqual(report.teamMutationRequests,[])
  assert.ok(report.records.some(row=>Number.isInteger(row.modelCallCount)&&row.modelCallCount>0),'REAL_MODEL_CALL_EVIDENCE_REQUIRED')
  report.status='PASS'
} catch (error) {
  report.status='FAIL';report.failure=error.code || error.name
  report.failureCheck=error.code==='ERR_ASSERTION'?String(error.message).slice(0,300)
    :/^[A-Z][A-Z0-9_]{0,80}$/.test(error.message || '')?error.message:'BROWSER_OR_API_CHECK_FAILED'
  process.exitCode=1
} finally {
  for (const runId of opened) try {await api('/chat/runs/'+runId+'/cancel',{});report.cleanup.push('RUN_CANCELLED')}
    catch {report.cleanup.push('RUN_CANCEL_UNVERIFIED');report.status='FAIL';process.exitCode=1}
  if (authenticated) try {await api('/logout',{},204);report.cleanup.push('LOGGED_OUT')} catch {report.cleanup.push('LOGOUT_UNVERIFIED');report.status='FAIL';process.exitCode=1}
  if (browser) await browser.close()
  await server.close()
  report.finishedAt=new Date().toISOString()
  fs.writeFileSync(path.join(output,'browser-result.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify({status:report.status,turns:report.records.length,checks:report.records.map(row=>row.checks),failure:report.failure,failureCheck:report.failureCheck}))
}
