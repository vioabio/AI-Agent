import {test,expect} from '@playwright/test'
import {teamBuildCheckFixture} from './fixtures/team-build-check.js'
test.use({video:'off'})
for(const width of [1440,390])test(`整队核对、错误汇总、重试与配置切换 ${width}`,async({page})=>{
 await page.setViewportSize({width,height:width===390?844:1000})
 let mode='normal',release,delayed=false,writes=0,lastRequest
 const fact={formId:7,nationalNo:7,slug:'squirtle',speciesNameZh:'杰尼龟',formNameZh:'杰尼龟',formKey:'squirtle',type1:'水',type2:null,hp:44,attack:48,defense:65,spAttack:50,spDefense:64,speed:43,dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet',releaseStatus:'LOCAL_REFERENCE',sourceUrl:'https://pokeapi.co/api/v2/pokemon/7/',checksum:'sha256:'+'d'.repeat(64)}
 await page.route(url=>url.pathname.startsWith('/api/'),async route=>{
  const req=route.request(),p=new URL(req.url()).pathname
  if(p==='/api/auth/session')return route.fulfill({json:{mode:'disabled',authenticated:false,accessDenied:false,principalId:null,tenantId:null,roles:[],serverTime:'2026-10-27T00:00:00Z',absoluteExpiresAt:null,loginUrl:null,logoutUrl:null,csrf:{enabled:false,available:false,cookieName:'CSRF-TOKEN',headerName:'X-CSRF-TOKEN'}}})
  if(p==='/api/pokemon/reference/status')return route.fulfill({json:{enabled:true,dataReleaseId:'pokeapi-reference-v1',status:'LOCAL_REFERENCE'}})
  if(p==='/api/pokemon/facts')return route.fulfill({json:{items:[fact],nextCursor:null}})
  if(p.includes('/pokemon/facts/forms/'))return route.fulfill({json:fact})
  if(p==='/api/pokemon/reference/team-build-check'){
   expect(req.method()).toBe('POST');lastRequest=req.postDataJSON();const result=teamBuildCheckFixture(lastRequest)
   if(mode==='error')return route.fulfill({status:503,json:{message:'unavailable'}})
   if(mode==='counts')result.counts.REFERENCE_MATCHED=6
   if(mode==='joint')result.jointStatus='VERIFIED'
   if(mode==='delay'){delayed=true;await new Promise(r=>{release=r})}
   return route.fulfill({json:result}).catch(()=>{})
  }
  if((p==='/api/teams'||p.startsWith('/api/teams/'))&&req.method()!=='GET')writes++
  if(p==='/api/teams'||p.endsWith('/favorites'))return route.fulfill({json:{items:[],nextCursor:null}})
  return route.fulfill({json:[]})
 })
 await page.goto('/pokemon-workbench')
 const panel=page.locator('.team-build-check-panel:visible'),button=panel.getByRole('button',{name:'检查整队配置',exact:true})
 await page.getByRole('combobox',{name:'宝可梦名称或编号',exact:true}).fill('杰尼龟');await page.getByRole('button',{name:'查询',exact:true}).click();await page.getByRole('button',{name:'加入队伍',exact:true}).click()
 for(const [i,move]of ['water-pulse','ice-beam','protect','surf'].entries())await page.getByLabel(`第 1 位第 ${i+1} 个招式`,{exact:true}).fill(move)
 await page.getByLabel('第 1 位特性',{exact:true}).fill('torrent')
 await expect(button).toBeEnabled()
 mode='error';await button.click();await expect(panel.getByRole('alert')).toContainText('HTTP 503')
 for(const wrong of ['counts','joint']){mode=wrong;await button.click();await expect(panel.getByRole('alert')).toContainText('与当前成员配置或逐项结果不一致');await expect(panel.getByLabel('整队配置核对结果')).toHaveCount(0)}
 mode='normal';await button.click();const result=panel.getByLabel('整队配置核对结果');await expect(result).toContainText('1 位已核对');await expect(result).toContainText('联合可得性未验证');await expect(result).toContainText('逐项匹配 1 位');await expect(result).toContainText('water-pulse');expect(lastRequest.members[0].slot).toBe(1)
 await expect(panel.getByRole('button',{name:'让 AI 核对整队配置',exact:true})).toBeEnabled()
 await panel.scrollIntoViewIfNeeded();await page.screenshot({path:`../artifacts/team-agent-panel-${width}-2026-10-27.png`,animations:'disabled'})
 mode='delay';await button.click();await expect.poll(()=>delayed).toBe(true);await page.getByLabel('第 1 位等级',{exact:true}).fill('51');release();await expect(result).toHaveCount(0);await expect(panel).toContainText('队伍配置已改变')
 await expect(panel.getByRole('button',{name:'让 AI 核对整队配置',exact:true})).toHaveCount(0)
 mode='normal';await button.click();await expect(result).toBeVisible();expect(lastRequest.members[0].build.level).toBe(51)
 expect(writes).toBe(0);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
})
