import {test,expect} from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import {randomBytes,createHash} from 'node:crypto'
import {attemptsFrom} from '../../tools/campus/answer-quality.mjs'
import {auditTeamPlanningWithReferences} from '../../tools/campus/team-planning-reference.mjs'
const sha=file=>createHash('sha256').update(fs.readFileSync(file)).digest('hex')
async function api(page,route,body,method=body===undefined?'GET':'POST',expected=200){return page.evaluate(async({route,body,method,expected})=>{
 const token=document.cookie.split('; ').find(c=>c.startsWith('CSRF-TOKEN=')),csrf=token?decodeURIComponent(token.slice(11)):''
 const response=await fetch('/api'+route,{method,credentials:'include',headers:{'Content-Type':'application/json',...(csrf?{'X-CSRF-TOKEN':csrf}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(response.status!==expected)throw Error(`Unexpected HTTP ${response.status} at ${route}`);if(expected===204)return null
 const value=await response.json();return value&&Object.hasOwn(value,'code')&&Object.hasOwn(value,'data')?value.data:value
},{route,body,method,expected})}
test('真实模型核对、单字段预览与确认保存录屏',async({browser},testInfo)=>{
 const plan=JSON.parse(fs.readFileSync('../evaluation/campus/demo-recording-v1/plan.json')),record={schemaVersion:'campus-model-demo-observation-v1',pass:false,approvalActor:'AUTOMATED_DEDICATED_TEST_ACCOUNT',humanStrategyAcceptance:false,startedAt:new Date().toISOString(),planSha256:sha('../evaluation/campus/demo-recording-v1/plan.json'),tasks:[]}
 const authContext=await browser.newContext({viewport:{width:1440,height:900}}),authPage=await authContext.newPage();let context,page,active,video
 async function submit(prompt){const created=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/chat/runs'&&r.request().method()==='POST');await page.getByRole('textbox',{name:'输入消息',exact:true}).fill(prompt);await page.getByRole('textbox',{name:'输入消息',exact:true}).press('Enter');const response=await created;expect(response.status()).toBe(202);const run=await response.json();active=run.runId;return run}
 async function finishTask(run){const state=await api(page,`/chat/runs/${run.runId}`);const events=(await api(page,`/chat/runs/${run.runId}/events`)).events;const answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join('');const attempts=attemptsFrom(events);const history=await api(page,`/chat/conversations/${run.conversationId}/messages`);expect(history.find(m=>m.runId===run.runId&&m.role==='assistant')?.content).toBe(answer);const row={runId:run.runId,status:state.status,answer,answerDeltaCount:events.filter(e=>e.type==='answer.delta').length,modelCalls:attempts.reduce((n,a)=>n+a.modelCallCount,0),events};record.tasks.push(row);active=null;return row}
 try{
  await authPage.goto('http://127.0.0.1:13059/game-master');await expect(authPage.getByTestId('local-auth-portal')).toBeVisible()
  const username='demo_'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url')
  await api(authPage,'/auth/local/register',{username,password,nickname:'模型演示验证'},'POST',201)
  await authPage.getByLabel('用户名',{exact:true}).fill(username);await authPage.getByLabel('密码',{exact:true}).fill(password);await authPage.locator('.local-auth-form button[type=submit]').click();await expect(authPage.getByRole('heading',{name:'宝可梦助手',exact:true})).toBeVisible()
  record.runtime=await api(authPage,'/chat/runtime');expect(record.runtime.teamBuildCheckPolicy.answerVersion).toBe('bound-team-build-answer-v3')
  record.publicFrontendIndexSha256=sha('../artifacts/campus/local-smoke/workspace-v1/vio-ai-agent-frontend/dist/index.html')
  const build={level:50,nature:'modest',ability:'torrent',item:'leftovers',moves:['surf'],evs:{hp:4,atk:0,def:0,spa:252,spd:0,spe:252},ivs:{hp:31,atk:31,def:31,spa:31,spd:31,spe:31}}
  const created=await api(authPage,'/teams',{name:'录屏专用队伍',rulesetId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',members:[{formId:7,slot:1,build}]},'POST',201);record.before=await api(authPage,`/teams/${created.id}`)
  const storageState=await authContext.storageState();context=await browser.newContext({storageState,viewport:{width:1440,height:900},recordVideo:{dir:testInfo.outputPath('video'),size:{width:1440,height:900}},serviceWorkers:'block'});page=await context.newPage();video=page.video();record.recordingStartedAt=new Date().toISOString()
  await page.goto('http://127.0.0.1:13059/game-master?dataReleaseId=pokeapi-reference-v1');await expect(page.getByRole('heading',{name:'宝可梦助手',exact:true})).toBeVisible()
  const request={dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet',members:[{slot:1,pokemonId:7,build:null},{slot:2,pokemonId:478,build:null}]}
  const reference=await api(page,'/pokemon/reference/team-build-check',request)
  const first=await submit(plan.readOnlyPrompt);await expect.poll(async()=> (await api(page,`/chat/runs/${first.runId}`)).status,{timeout:140000}).toBe('COMPLETED')
  const firstResult=await finishTask(first);firstResult.audit=auditTeamPlanningWithReferences(firstResult.events,{request,reportSha256:reference.reportSha256,mode:'MODEL_TOOL_SELECTION'},{7:['7','squirtle','杰尼龟'],478:['478','froslass','雪妖女']});expect(firstResult.audit.pass).toBe(true);expect(firstResult.modelCalls).toBeGreaterThan(0);expect(firstResult.answerDeltaCount).toBeGreaterThan(0);await page.screenshot({path:testInfo.outputPath('answer.png')})
  const second=await submit(plan.changePromptTemplate.replace('{teamId}',String(created.id)));await expect(page.getByText('队伍修改预览',{exact:true})).toBeVisible({timeout:140000})
  record.preview=(await api(page,`/chat/runs/${second.runId}/team-change`)).preview;expect(record.preview.changes).toHaveLength(1);expect(record.preview.changes[0]).toMatchObject(plan.expectedChange)
  expect(await api(page,`/teams/${created.id}`)).toEqual(record.before);record.noWriteBeforeConfirmation=true
  const confirm=page.getByRole('button',{name:'确认保存',exact:true});await expect(confirm).toBeInViewport();await page.screenshot({path:testInfo.outputPath('preview.png')});await confirm.click()
  await expect.poll(async()=> (await api(page,`/chat/runs/${second.runId}`)).status,{timeout:140000}).toBe('COMPLETED')
  const secondResult=await finishTask(second);expect(secondResult.modelCalls).toBeGreaterThan(0)
  record.outcome=await api(page,`/chat/runs/${second.runId}/team-change`);expect(record.outcome.status).toBe('APPLIED');expect(secondResult.answer).toBe(record.outcome.message)
  record.after=await api(page,`/teams/${created.id}`);const expected=structuredClone(record.before);expected.members[0].build.level=51;expect(record.after).toEqual(expected)
  const tools=secondResult.events.filter(e=>e.type==='tool.finished').map(e=>e.payload?.toolName);for(const name of plan.requiredChangeTools)expect(tools).toContain(name)
  await page.reload();await expect(page.getByRole('heading',{name:'宝可梦助手',exact:true})).toBeVisible();expect(await api(page,`/teams/${created.id}`)).toEqual(expected);record.reloadReadBack=true
  await page.screenshot({path:testInfo.outputPath('result.png')});record.pass=true
 }catch(error){record.error='MODEL_DEMO_FLOW_FAILED';throw error}
 finally{
  if(active&&page)try{await api(page,`/chat/runs/${active}/cancel`,{})}catch{record.cancelFailed=true}
  if(context){await context.close();if(video)record.videoFile=await video.path();record.recordingFinishedAt=new Date().toISOString()}
  try{await api(authPage,'/auth/session');await api(authPage,'/logout',{},'POST',204)}catch{record.logoutFailed=true}
  await authContext.close();record.finishedAt=new Date().toISOString();fs.mkdirSync(testInfo.outputDir,{recursive:true});fs.writeFileSync(testInfo.outputPath('observations.json'),JSON.stringify(record,null,2)+'\n')
 }
})
