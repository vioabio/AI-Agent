import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {pathToFileURL} from 'node:url'
import {randomBytes} from 'node:crypto'
import {localBase, digest, sameJson} from './evaluation.mjs'
import {publicFingerprint} from './upgrade-baseline.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'

const [directory,url]=process.argv.slice(2), output=path.resolve(directory)
const backend=new URL(localBase(url)), origin='http://127.0.0.1:4329'
assert.equal(path.dirname(output),fs.realpathSync('artifacts'));assert.equal(fs.existsSync(output),false)
fs.mkdirSync(output)
const front=path.resolve('vio-ai-agent-frontend')
const {chromium}=await import(pathToFileURL(path.join(front,'node_modules/playwright/index.mjs')))
const {createServer}=await import(pathToFileURL(path.join(front,'node_modules/vite/dist/node/index.js')))
const {default:config}=await import(pathToFileURL(path.join(front,'vite.config.js')))
const server=await createServer({...config,configFile:false,root:front,
  server:{...config.server,host:'127.0.0.1',port:4329,strictPort:true,
    proxy:{'/api':{target:backend.origin,changeOrigin:false}}}})
const report={schemaVersion:'stage6-dex-transfer-browser-v1',status:'RUNNING',records:[],cleanup:[],
  pageErrors:[],dialogs:[],startedAt:new Date().toISOString(),qualityReview:'REQUIRED',releaseDecision:'NOT_EVALUATED'}
report.sourceSha256=Object.fromEntries([
  'tools/campus/upgrade-stage6-dex-transfer-browser.mjs',
  'vio-ai-agent-frontend/src/views/PokemonWorkbench.vue',
  'vio-ai-agent-frontend/src/api/team-transfer.js',
  'vio-ai-agent-frontend/src/api/team-document.js'
].map(file=>[file,digest(fs.readFileSync(file))]))
const stats=values=>Object.fromEntries(['hp','atk','def','spa','spd','spe'].map((key,index)=>[key,values[index]]))
const fixture={name:'U6 transfer '+randomBytes(5).toString('hex'),rulesetId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',members:[
  {formId:7,slot:1,build:{moves:['surf','ice-beam','protect','rain-dance'],ability:'torrent',item:'leftovers',nature:'modest',level:47,
    evs:stats([252,0,4,252,0,0]),ivs:stats([31,0,31,31,31,30]),teraType:'water'}},
  {formId:25,slot:2,build:{moves:['thunderbolt','quick-attack','protect','thunder-wave'],ability:'static',item:'light-ball',nature:'timid',level:42,
    evs:stats([4,0,0,252,0,252]),ivs:stats([31,0,31,31,31,31]),teraType:'flying'}}
]}
const portable=team=>({name:team.name,rulesetId:team.rulesetId,dataReleaseId:team.dataReleaseId,
  members:team.members.map(({formId,slot,build})=>({formId,slot,build}))})
let browser,context,authenticated=false,runId,original
async function api(route,body,expected=200,method=body===undefined?'GET':'POST') {
  const csrf=(await context.cookies(origin)).find(cookie=>cookie.name==='CSRF-TOKEN')?.value
  const response=await context.request.fetch(origin+'/api'+route,{method,
    headers:{Origin:origin,...(csrf?{'X-CSRF-TOKEN':decodeURIComponent(csrf)}:{})},
    ...(body===undefined?{}:{data:body}),timeout:30000,maxRedirects:0})
  assert.equal(response.status(),expected,'HTTP_'+response.status())
  if(expected===204)return null
  const value=await response.json()
  if(Object.hasOwn(value,'code'))assert.equal(value.code,200,'BUSINESS_RESPONSE_FAILED')
  return Object.hasOwn(value,'data')&&Object.hasOwn(value,'code')?value.data:value
}
try {
  await server.listen();browser=await chromium.launch({headless:true,channel:process.platform==='win32'?'chrome':undefined})
  context=await browser.newContext({viewport:{width:1440,height:1000},serviceWorkers:'block',acceptDownloads:true})
  assert.equal((await api('/auth/session')).mode,'local')
  const credentials={username:'dex_'+randomBytes(8).toString('hex'),password:randomBytes(24).toString('base64url'),nickname:'图鉴与文件隔离验收'}
  await api('/auth/local/register',credentials,201);await api('/auth/local/login',credentials);authenticated=true
  const runtime=await api('/chat/runtime');report.runtime=publicFingerprint(runtime);report.runtimeBuild=runtimeBuild(runtime)
  original=await api('/pokemon/teams',fixture,201);assert.deepEqual(portable(original),fixture)
  report.teamBefore=original
  const page=await context.newPage();page.on('pageerror',error=>report.pageErrors.push(error.name))
  page.on('dialog',async dialog=>{
    report.dialogs.push({type:dialog.type(),message:dialog.message()})
    if(dialog.type()==='confirm'&&dialog.message().includes('确认导入'))await dialog.accept()
    else {report.unexpectedDialog=true;await dialog.dismiss()}
  })
  await page.goto(origin+'/game-master',{waitUntil:'domcontentloaded'})
  await page.getByLabel('输入消息',{exact:true}).waitFor({timeout:30000})
  await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
  const dex=page.getByRole('search',{name:'图鉴搜索'}), searches=[]
  for(const query of ['杰尼龟','squirtle','7']) {
    await dex.getByLabel('宝可梦名称或编号').fill(query)
    const loaded=page.waitForResponse(result=>result.request().method()==='GET'&&new URL(result.url()).pathname==='/api/pokemon/facts')
    await dex.getByRole('button',{name:'查询',exact:true}).click()
    const loadedResponse=await loaded;assert.equal(loadedResponse.status(),200)
    const facts=(await loadedResponse.json()).data
    assert.equal(facts.items.length,1);assert.equal(facts.items[0].nationalNo,7)
    await page.locator('.fact-card h3').filter({hasText:'杰尼龟'}).waitFor({timeout:30000})
    assert.equal(await page.locator('.fact-card').count(),1)
    assert.match(await page.locator('.fact-card').innerText(),/#007/)
    searches.push({query,nationalNo:7,name:'杰尼龟',request:new URL(loadedResponse.url()).search})
  }
  await dex.getByLabel('宝可梦名称或编号').fill('qa-no-such-mon')
  await dex.getByRole('button',{name:'查询',exact:true}).click()
  await page.locator('.facts-panel .state-box.error').waitFor({timeout:30000})
  assert.equal(await page.locator('.fact-card').count(),0,'FAILED_SEARCH_MUST_NOT_DISPLAY_THE_OLD_FACT')
  const invalidNotice=await page.locator('.facts-panel .state-box.error').innerText()
  const created=page.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname==='/api/chat/runs')
  await page.getByLabel('输入消息',{exact:true}).fill('查询胖丁的属性和种族值，只列图鉴已核验字段。')
  await page.getByRole('button',{name:'发送',exact:true}).click()
  const response=await created;assert.equal(response.status(),202);runId=(await response.json()).runId
  const card=page.getByRole('region',{name:'已核验图鉴卡片',exact:true})
  await card.waitFor({timeout:120000});assert.match(await card.innerText(),/胖丁/)
  await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
  await card.getByRole('button',{name:'查看图鉴',exact:true}).click()
  await page.locator('.fact-card h3').filter({hasText:'胖丁'}).waitFor({timeout:30000})
  assert.match(await page.locator('.fact-card').innerText(),/#039/)
  const events=(await api('/chat/runs/'+runId+'/events?afterEventId=0')).events
  const bytes=JSON.stringify(events,null,2)+'\n';fs.writeFileSync(path.join(output,'dex-events.json'),bytes,{flag:'wx'})
  report.records.push({caseId:'S6-09',searches,invalidNotice,staleCardHidden:true,linkedNationalNo:39,
    runId,eventsFile:'dex-events.json',eventsSha256:digest(bytes)})
  await page.screenshot({path:path.join(output,'linked-dex.png')})

  await page.getByRole('button',{name:'队伍配置',exact:true}).click()
  const teamRow=page.locator('.team-list > .team-card').filter({hasText:fixture.name})
  await teamRow.getByRole('button',{name:'编辑',exact:true}).click()
  await page.getByLabel('第 1 位等级',{exact:true}).fill('48')
  const saved=page.waitForResponse(result=>result.request().method()==='PATCH'&&new URL(result.url()).pathname==='/api/teams/'+original.id)
  await page.getByRole('button',{name:'更新队伍',exact:true}).click()
  assert.equal((await saved).status(),200)
  const sourceTeam=await api('/pokemon/teams/'+original.id), expected=structuredClone(fixture)
  expected.members[0].build.level=48
  assert.deepEqual(portable(sourceTeam),expected)
  async function download(name) {
    const event=page.waitForEvent('download')
    await page.getByRole('button',{name:'导出',exact:true}).click()
    const file=await event;await file.saveAs(path.join(output,name))
    return fs.readFileSync(path.join(output,name))
  }
  const exportBytes=await download('exported-team.json'), exported=JSON.parse(exportBytes)
  assert.equal(exported.schemaVersion,2);assert.equal(exported.gameReleaseId,'pokemon-scarlet-violet')
  assert.deepEqual(exported.team,expected)
  assert.equal(Object.hasOwn(exported.team,'id'),false);assert.equal(Object.hasOwn(exported.team,'ownerId'),false)
  async function chooseFile(name,buffer) {
    await page.getByRole('button',{name:'导入',exact:true}).click()
    const dialog=page.getByRole('dialog',{name:'导入队伍',exact:true})
    await dialog.waitFor()
    const chooser=page.waitForEvent('filechooser')
    await dialog.getByRole('button',{name:'选择文件',exact:true}).click()
    await (await chooser).setFiles({name,mimeType:'application/octet-stream',buffer})
    return dialog
  }
  const badFiles=[]
  for(const [name,contents,notice] of [['broken.json','{bad','无法解析 JSON'],['unsupported.yaml','not-a-team','请选择 JSON']]) {
    const dialog=await chooseFile(name,Buffer.from(contents))
    await dialog.getByRole('alert').filter({hasText:notice}).waitFor({timeout:30000})
    assert.equal(await page.getByLabel('队伍名称',{exact:true}).inputValue(),expected.name)
    assert.ok(sameJson(await api('/pokemon/teams/'+original.id),sourceTeam))
    badFiles.push({name,notice:await dialog.getByRole('alert').innerText(),originalUnchanged:true})
    await dialog.getByRole('button',{name:'取消',exact:true}).click()
  }
  const dialog=await chooseFile('roundtrip.json',exportBytes)
  await dialog.waitFor({state:'hidden',timeout:30000})
  await page.locator('.roster-transfer-notice').filter({hasText:'已导入'}).waitFor({timeout:30000})
  assert.equal((await api('/pokemon/teams')).length,1,'IMPORT_DRAFT_MUST_NOT_SAVE_AUTOMATICALLY')
  const importedName=expected.name+' imported'
  await page.getByLabel('队伍名称',{exact:true}).fill(importedName)
  const importedSave=page.waitForResponse(result=>result.request().method()==='POST'&&new URL(result.url()).pathname==='/api/teams')
  await page.getByRole('button',{name:'保存队伍',exact:true}).click()
  const importedResponse=await importedSave;assert.equal(importedResponse.status(),201)
  const imported=(await importedResponse.json()).data
  const importedExpected={...expected,name:importedName}
  assert.deepEqual(portable(await api('/pokemon/teams/'+imported.id)),importedExpected)
  assert.ok(sameJson(await api('/pokemon/teams/'+original.id),sourceTeam),'IMPORT_OVERWROTE_SOURCE_TEAM')
  const again=JSON.parse(await download('roundtrip-team.json'))
  assert.deepEqual(again.team,importedExpected)
  assert.equal(report.unexpectedDialog,undefined)
  report.records.push({caseId:'S6-10',sourceTeam,exportSha256:digest(exportBytes),exported,imported,
    finalExport:again,badFiles,importWasDraft:true,sourcePreserved:true,allBuildFieldsVerified:true})
  await page.screenshot({path:path.join(output,'roundtrip-team.png')})
  const after=await api('/chat/runtime')
  assert.equal(publicFingerprint(after).sha256,report.runtime.sha256);assert.equal(runtimeBuild(after).sha256,report.runtimeBuild.sha256)
  assert.deepEqual(report.pageErrors,[]);report.status='COLLECTED_PENDING_CONTENT_REVIEW'
} catch(error) {
  report.status='FAIL';report.failure=String(error.message).slice(0,300);process.exitCode=1
} finally {
  if(runId)try{await api('/chat/runs/'+runId+'/cancel',{reason:'s6_dex_cleanup'})}catch{report.cleanup.push('RUN_CLEANUP_FAILED');report.status='FAIL';process.exitCode=1}
  if(authenticated&&original)try{
    await api('/pokemon/teams/'+original.id,fixture,200,'PATCH')
    report.restoredTeam=await api('/pokemon/teams/'+original.id)
    assert.deepEqual(portable(report.restoredTeam),fixture);report.cleanup.push('SOURCE_BASELINE_RESTORED')
  }catch{report.cleanup.push('RESTORE_FAILED');report.status='FAIL';process.exitCode=1}
  if(authenticated)try{await api('/logout',{},204);report.cleanup.push('LOGGED_OUT')}
  catch{report.cleanup.push('LOGOUT_FAILED');report.status='FAIL';process.exitCode=1}
  if(browser)await browser.close();await server.close()
  report.finishedAt=new Date().toISOString()
  fs.writeFileSync(path.join(output,'browser-result.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify({status:report.status,cases:report.records.map(row=>row.caseId),failure:report.failure}))
}
