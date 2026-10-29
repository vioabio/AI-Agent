import {test,expect} from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import {randomBytes,createHash} from 'node:crypto'
const hash=file=>createHash('sha256').update(fs.readFileSync(file)).digest('hex')
async function api(page,route,body,expected=200){return page.evaluate(async({route,body,expected})=>{
 const cookie=document.cookie.split('; ').find(c=>c.startsWith('CSRF-TOKEN=')),csrf=cookie?decodeURIComponent(cookie.slice(11)):''
 const response=await fetch('/api'+route,{method:body===undefined?'GET':'POST',credentials:'include',headers:{'Content-Type':'application/json',...(csrf?{'X-CSRF-TOKEN':csrf}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(response.status!==expected)throw Error(`Unexpected HTTP ${response.status} at ${route}`)
 if(expected===204)return null
 const value=await response.json();return value&&Object.hasOwn(value,'code')&&Object.hasOwn(value,'data')?value.data:value
},{route,body,expected})}
for(const width of [1440,390])test(`公开构建真实浏览器登录、图鉴与保存回读 ${width}`,async({page},testInfo)=>{
 const record={schemaVersion:'public-workspace-browser-smoke-v1',width,mode:'REAL_BUILT_FRONTEND_HTTP_SQLITE_NO_MODEL',pass:false,modelRequests:0,pageErrors:[],apiTraffic:[],blockedExternal:0}
 const root=path.resolve('..'),workspace=path.join(root,'artifacts/campus/local-smoke/workspace-v1')
 record.jarSha256=hash(path.join(workspace,'target/vio-ai-agent-0.0.1-SNAPSHOT.jar'));record.frontendIndexSha256=hash(path.join(workspace,'vio-ai-agent-frontend/dist/index.html'))
 page.on('pageerror',()=>record.pageErrors.push('PAGE_SCRIPT_ERROR'))
 page.on('response',r=>{const u=new URL(r.url());if(u.pathname.startsWith('/api/'))record.apiTraffic.push({method:r.request().method(),path:u.pathname,status:r.status()})})
 await page.route('**/*',async route=>{const req=route.request(),u=new URL(req.url());if(!['data:','blob:'].includes(u.protocol)&&u.origin!=='http://127.0.0.1:13059'){record.blockedExternal++;return route.abort()}
  if(u.pathname==='/api/chat/runs'&&req.method()==='POST'){record.modelRequests++;return route.abort()}return route.continue()})
 try{
  await page.setViewportSize({width,height:width===390?844:1000});await page.goto('/pokemon-workbench')
  await expect(page.getByTestId('local-auth-portal')).toBeVisible()
  const username='browser_smoke_'+randomBytes(5).toString('hex'),password=randomBytes(24).toString('base64url')
  await api(page,'/auth/local/register',{username,password,nickname:'界面联调验证'},201)
  await page.getByLabel('用户名',{exact:true}).fill(username);await page.getByLabel('密码',{exact:true}).fill(password)
  await page.locator('.local-auth-form button[type=submit]').click()
  await expect(page.getByRole('heading',{name:'宝可梦助手',exact:true})).toBeVisible();record.login=true
  if(!await page.locator('.resource-sidebar').isVisible())await page.locator('button.resource-toggle:visible').first().click()
  await page.getByRole('textbox',{name:'查询目标',exact:true}).fill('杰尼龟');await page.getByRole('button',{name:'查询',exact:true}).click()
  await expect(page.getByRole('button',{name:'加入队伍',exact:true})).toBeVisible();await expect(page.locator('.fact-card:visible')).toContainText('杰尼龟');record.cachedFactVisible=true
  await page.getByRole('button',{name:'加入队伍',exact:true}).click()
  await page.locator('[aria-label="工作区视图"] button:visible').filter({hasText:'队伍配置'}).first().click()
  const name='公开构建联调队伍'+width;await page.getByRole('textbox',{name:'队伍名称',exact:true}).fill(name)
  const saving=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/teams'&&r.request().method()==='POST')
  await page.getByRole('button',{name:'保存队伍',exact:true}).click();const response=await saving;expect(response.status()).toBe(201)
  const raw=await response.json(),created=raw.data??raw;expect(created.name).toBe(name);expect(created.members[0].formId).toBe(7)
  const before=await api(page,`/teams/${created.id}`);expect(before).toEqual(created);record.savedAndReadBack=true
  await page.reload();await expect(page.getByRole('heading',{name:'宝可梦助手',exact:true})).toBeVisible();expect(await api(page,`/teams/${created.id}`)).toEqual(before);record.reloadReadBack=true
  if(width===390&&await page.getByRole('button',{name:'关闭资料侧栏',exact:true}).isVisible())await page.getByRole('button',{name:'关闭资料侧栏',exact:true}).click()
  await page.locator('[aria-label="工作区视图"] button:visible').filter({hasText:'队伍配置'}).first().click()
  await expect(page.locator('.team-card:visible').filter({hasText:name})).toBeVisible();record.savedTeamVisibleAfterReload=true
  const savedCard=page.locator('.team-card:visible').filter({hasText:name});await savedCard.scrollIntoViewIfNeeded();await expect(savedCard).toBeInViewport();record.savedTeamInViewport=true
  record.noHorizontalOverflow=await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth);expect(record.noHorizontalOverflow).toBe(true)
  expect(record.modelRequests).toBe(0);expect(record.pageErrors).toEqual([])
  await page.screenshot({path:testInfo.outputPath('workspace.png'),animations:'disabled'});record.screenshot='workspace.png'
  await api(page,'/auth/session');await api(page,'/logout',{},204);expect((await api(page,'/auth/session')).authenticated).toBe(false);record.logout=true
  record.pass=true
 }catch(error){record.error='BROWSER_FLOW_FAILED';throw error}
 finally{fs.mkdirSync(testInfo.outputDir,{recursive:true});fs.writeFileSync(testInfo.outputPath('observations.json'),JSON.stringify(record,null,2)+'\n')}
})
