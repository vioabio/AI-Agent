import {test,expect} from '@playwright/test'
import {buildCheckFixture} from './fixtures/build-check.js'
test.use({video:'off'})
for(const width of [1440,390])test(`学习途径等级展示、改动失效与迟到响应隔离 ${width}`,async({page})=>{
 await page.setViewportSize({width,height:width===390?844:1000})
 const fact={formId:7,nationalNo:7,slug:'squirtle',speciesNameZh:'杰尼龟',formNameZh:'杰尼龟',formKey:'squirtle',type1:'水',type2:null,hp:44,attack:48,defense:65,spAttack:50,spDefense:64,speed:43,dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet',releaseStatus:'LOCAL_REFERENCE',sourceUrl:'https://pokeapi.co/api/v2/pokemon/7/',checksum:'sha256:'+'d'.repeat(64)}
 let mode='normal',delayed,resolveDelayed,writes=0
 await page.route(url=>url.pathname.startsWith('/api/'),async route=>{
  const req=route.request(),p=new URL(req.url()).pathname
  if(p==='/api/auth/session')return route.fulfill({json:{mode:'disabled',authenticated:false,accessDenied:false,principalId:null,tenantId:null,roles:[],serverTime:'2026-10-27T00:00:00Z',absoluteExpiresAt:null,loginUrl:null,logoutUrl:null,csrf:{enabled:false,available:false,cookieName:'CSRF-TOKEN',headerName:'X-CSRF-TOKEN'}}})
  if(p==='/api/pokemon/reference/status')return route.fulfill({json:{enabled:true,dataReleaseId:'pokeapi-reference-v1',status:'LOCAL_REFERENCE'}})
  if(p==='/api/pokemon/facts')return route.fulfill({json:{items:[fact],nextCursor:null}})
  if(p.includes('/pokemon/facts/forms/'))return route.fulfill({json:fact})
  if(p==='/api/pokemon/reference/build-check'){
   const input=req.postDataJSON();if(mode==='error')return route.fulfill({status:503,json:{message:'资料暂不可用'}})
   if(mode==='delay'){delayed=true;await new Promise(r=>{resolveDelayed=r})}
   const result=buildCheckFixture(input);if(input.build.level===null){result.status='INCOMPLETE';result.missingFields=['level']}for(const m of result.moves){const e=(methodKey,levelLearnedAt)=>({methodKey,levelLearnedAt,order:null});m.learning.entries=m.supplied==='water-gun'?[e('level-up',3)]:m.supplied==='hydro-pump'?[e('level-up',33),e('machine',0)]:m.supplied==='aqua-jet'?[e('egg',0)]:[e('machine',0)]}return route.fulfill({json:result}).catch(()=>{})
  }
  if((p==='/api/teams'||p.startsWith('/api/teams/'))&&req.method()!=='GET')writes++
  if(p==='/api/teams'||p.endsWith('/favorites'))return route.fulfill({json:{items:[],nextCursor:null}})
  return route.fulfill({json:[]})
 })
 await page.goto('/pokemon-workbench')
 await page.getByRole('textbox',{name:'查询目标',exact:true}).fill('杰尼龟')
 await page.getByRole('button',{name:'查询',exact:true}).click()
 await page.getByRole('button',{name:'加入队伍',exact:true}).click()
 for(const [index,move]of ['water-gun','hydro-pump','aqua-jet','ice-beam'].entries())await page.getByLabel(`第 1 位第 ${index+1} 个招式`,{exact:true}).fill(move)
 await page.getByLabel('第 1 位特性',{exact:true}).fill('torrent');await page.getByLabel('第 1 位等级',{exact:true}).fill('5')
 const panel=page.locator('.build-check-panel:visible'),button=panel.getByRole('button',{name:'检查当前配招',exact:true})
 await button.click();await expect(panel.getByLabel('配招核对结果')).toContainText('组合仍待审核');await expect(panel).toContainText('water-gun：有朱紫学习记录');await expect(panel.getByLabel('water-gun 学习记录',{exact:true})).toContainText('记录等级 3；不高于配置等级 5');await expect(panel.getByLabel('hydro-pump 学习记录',{exact:true})).toContainText('记录等级 33；高于配置等级 5');await expect(panel.getByLabel('hydro-pump 学习记录',{exact:true})).toContainText('招式学习器');await expect(panel.getByLabel('aqua-jet 学习记录',{exact:true})).toContainText('蛋招式');expect(writes).toBe(0)
 await panel.scrollIntoViewIfNeeded();await page.screenshot({path:`../artifacts/learning-review-result-${width}-2026-10-27.png`,animations:'disabled'})
 await page.getByLabel('第 1 位等级',{exact:true}).fill('');await expect(panel.getByLabel('配招核对结果')).toHaveCount(0);await button.click();await expect(panel).toContainText('待补：等级');await expect(panel).toContainText('未提供有效配置等级，暂不比较');await page.getByLabel('第 1 位等级',{exact:true}).fill('5')
 await page.getByLabel('第 1 位第 1 个招式',{exact:true}).fill('tackle');await expect(panel.getByLabel('配招核对结果')).toHaveCount(0);await expect(panel).toContainText('配置已改变')
 mode='delay';await button.click();await expect.poll(()=>delayed).toBe(true);await page.getByLabel('第 1 位第 1 个招式',{exact:true}).fill('surf');resolveDelayed();await expect(panel.getByLabel('配招核对结果')).toHaveCount(0)
 mode='error';await button.click();await expect(panel.getByRole('alert')).toContainText('配招核对失败（HTTP 503）');expect(writes).toBe(0)
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
 await page.screenshot({path:`../artifacts/learning-review-error-${width}-2026-10-27.png`,animations:'disabled'})
})
