import {test,expect} from '@playwright/test'
import {buildCheckFixture} from './fixtures/build-check.js'
import {machineFixture,machineLearning} from './fixtures/machine-reference.js'
import {machineRecipeFixture} from './fixtures/machine-recipe.js'
test.use({video:'off'})
for(const width of [1440,390])test(`配方数量、来源差异、重试与配置切换 ${width}`,async({page})=>{
 await page.setViewportSize({width,height:width===390?844:1000})
 let mode='normal',release,delayed=false,writes=0
 const learnings=new Map(),fact={formId:7,nationalNo:7,slug:'squirtle',speciesNameZh:'杰尼龟',formNameZh:'杰尼龟',formKey:'squirtle',type1:'水',type2:null,hp:44,attack:48,defense:65,spAttack:50,spDefense:64,speed:43,dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet',releaseStatus:'LOCAL_REFERENCE',sourceUrl:'https://pokeapi.co/api/v2/pokemon/7/',checksum:'sha256:'+'d'.repeat(64)}
 await page.route(url=>url.pathname.startsWith('/api/'),async route=>{
  const req=route.request(),p=new URL(req.url()).pathname
  if(p==='/api/auth/session')return route.fulfill({json:{mode:'disabled',authenticated:false,accessDenied:false,principalId:null,tenantId:null,roles:[],serverTime:'2026-10-27T00:00:00Z',absoluteExpiresAt:null,loginUrl:null,logoutUrl:null,csrf:{enabled:false,available:false,cookieName:'CSRF-TOKEN',headerName:'X-CSRF-TOKEN'}}})
  if(p==='/api/pokemon/reference/status')return route.fulfill({json:{enabled:true,dataReleaseId:'pokeapi-reference-v1',status:'LOCAL_REFERENCE'}})
  if(p==='/api/pokemon/facts')return route.fulfill({json:{items:[fact],nextCursor:null}})
  if(p.includes('/pokemon/facts/forms/'))return route.fulfill({json:fact})
  if(p.startsWith('/api/pokemon/reference/machines/recipes/')){
   expect(req.method()).toBe('GET');expect(new URL(req.url()).searchParams.get('gameReleaseId')).toBe('pokemon-scarlet-violet')
   if(mode==='error')return route.fulfill({status:503,json:{message:'unavailable'}})
   const result=machineRecipeFixture(p.split('/').at(-1))
   if(mode==='wrongMove')result.moveSlug='hydro-pump'
   if(mode==='wrongSource')result.sources[0].url='https://untrusted.example/recipe'
   if(mode==='delay'){delayed=true;await new Promise(r=>{release=r})}
   return route.fulfill({json:result}).catch(()=>{})
  }
  if(p.startsWith('/api/pokemon/reference/machines/'))return route.fulfill({json:machineFixture(learnings.get(p.split('/').at(-1)))})
  if(p==='/api/pokemon/reference/build-check'){
   const result=buildCheckFixture(req.postDataJSON());for(const m of result.moves){m.learning={...machineLearning(m.normalized),...m.learning};learnings.set(m.normalized,structuredClone(m.learning))}return route.fulfill({json:result})
  }
  if((p==='/api/teams'||p.startsWith('/api/teams/'))&&req.method()!=='GET')writes++
  if(p==='/api/teams'||p.endsWith('/favorites'))return route.fulfill({json:{items:[],nextCursor:null}})
  return route.fulfill({json:[]})
 })
 await page.goto('/pokemon-workbench')
 await page.getByRole('textbox',{name:'查询目标',exact:true}).fill('杰尼龟');await page.getByRole('button',{name:'查询',exact:true}).click();await page.getByRole('button',{name:'加入队伍',exact:true}).click()
 for(const [i,move]of ['water-pulse','ice-beam','protect','surf'].entries())await page.getByLabel(`第 1 位第 ${i+1} 个招式`,{exact:true}).fill(move)
 await page.getByLabel('第 1 位特性',{exact:true}).fill('torrent')
 const panel=page.locator('.build-check-panel:visible'),check=panel.getByRole('button',{name:'检查当前配招',exact:true})
 await check.click()
 const group=slug=>page.getByRole('group',{name:`${slug} 学习器查询`,exact:true}),protect=()=>group('protect'),water=()=>group('water-pulse'),ice=()=>group('ice-beam')
 await protect().getByRole('button',{name:'查看学习器编号',exact:true}).click()
 mode='error';await protect().getByRole('button',{name:'查看配方参考',exact:true}).click();await expect(protect().getByRole('alert')).toContainText('HTTP 503');await expect(protect()).toContainText('TM007')
 for(const invalid of ['wrongMove','wrongSource']){mode=invalid;await protect().getByRole('button',{name:'查看配方参考',exact:true}).click();await expect(protect().getByRole('alert')).toContainText('与当前招式、编号或来源不一致');await expect(protect().getByLabel('学习器配方参考')).toHaveCount(0)}
 mode='normal';await protect().getByRole('button',{name:'查看配方参考',exact:true}).click()
 const result=protect().getByLabel('学习器配方参考');await expect(result).toContainText('400 LP');await expect(result).toContainText('Lechonk Hair × 3');await expect(result).toContainText('Scatterbug Powder × 3');await expect(result).toContainText('待人工及游戏内核验');await expect(result.getByRole('link')).toHaveCount(2)
 await result.scrollIntoViewIfNeeded();await page.screenshot({path:`../artifacts/machine-recipes-panel-${width}-2026-10-27.png`,animations:'disabled'})
 await protect().getByRole('button',{name:'收起配方参考',exact:true}).click();await expect(result).toHaveCount(0)
 await water().getByRole('button',{name:'查看学习器编号',exact:true}).click();await water().getByRole('button',{name:'查看配方参考',exact:true}).click();const conflict=water().getByLabel('学习器配方参考');await expect(conflict).toContainText('来源差异待核对');await expect(conflict).not.toContainText('1500');await expect(conflict.locator('li')).toHaveCount(0)
 await ice().getByRole('button',{name:'查看学习器编号',exact:true}).click();mode='delay';await ice().getByRole('button',{name:'查看配方参考',exact:true}).click();await expect.poll(()=>delayed).toBe(true)
 await page.getByLabel('第 1 位等级',{exact:true}).fill('51');release();await expect(ice()).toHaveCount(0);await expect(page.getByLabel('学习器配方参考')).toHaveCount(0)
 mode='normal';await check.click();await ice().getByRole('button',{name:'查看学习器编号',exact:true}).click();await ice().getByRole('button',{name:'查看配方参考',exact:true}).click();await expect(ice().getByLabel('学习器配方参考')).toContainText('10,000 LP')
 expect(writes).toBe(0);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
})
