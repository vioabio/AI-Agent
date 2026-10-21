import { test, expect } from '@playwright/test'

test.use({ video: 'off' })

const FACT = {
  formId: 6,
  slug: 'charizard',
  nationalNo: 6,
  speciesNameZh: '喷火龙',
  formNameZh: '普通形态',
  formKey: 'default',
  type1: '火',
  type2: '飞行',
  hp: 78,
  attack: 84,
  defense: 78,
  spAttack: 109,
  spDefense: 85,
  speed: 100,
  dataReleaseId: 'pokemon-scarlet-violet-data-v1',
  gameReleaseId: 'pokemon-scarlet-violet',
  releaseStatus: 'PUBLISHED',
  checksum: 'sha256:fixture-charizard',
  sourceUrl: 'https://example.invalid/pokemon/charizard',
  evidenceText: 'fixture evidence only'
}

function initialState() {
  return {
    teams: [],
    favorites: [],
    permission: false,
    emptyFacts: false,
    unsafeSource: false,
    conversations: [{ id: 'conversation-1', title: '朱紫对战建议', updatedAt: '2026-09-03T00:00:00Z' }],
    messages: [{ id: 'message-1', role: 'assistant', content: '请先确认规则版本。', createdAt: '2026-09-03T00:01:00Z', runId: 'run-1' }],
    runs: [], failMemberFacts: false, wrongMemberRelease: false, referenceEnabled: false, engineRequests: [], missingHistory: false
  }
}

async function installApiFixture(page, state) {
  // Match the API root, excluding Vite modules under /src/api/.
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const url = new URL(request.url())
    const pathname = url.pathname
    if(pathname === '/api/pokemon/reference/status') {
      await route.fulfill({status:state.referenceEnabled?200:404,contentType:'application/json',body:JSON.stringify({enabled:state.referenceEnabled,dataReleaseId:'pokeapi-reference-v1',status:'LOCAL_REFERENCE'})});return
    }
    if(pathname === '/api/pokemon/calculations/engine-damage') {
      const payload=request.postDataJSON();state.engineRequests.push(payload)
      await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({engine:'@smogon/calc',engineVersion:'0.12.0',dataReleaseId:payload.dataReleaseId,range:{minimum:80,maximum:96},percent:{minimum:50,maximum:60},description:'Fixture damage result',assumptions:payload,limitation:'Fixture only'})});return
    }
    if(pathname === '/api/pokemon/guides') {
      await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify([{document:{id:'training-configuration-v1',title:'培养配置说明',author:'VIO project',updatedAt:'2026-10-20',gameReleaseId:'pokemon-scarlet-violet',sourceUrl:'',content:'配置应注明努力值、个体值和性格。'},score:5,checksum:'sha256:guide-fixture'}])});return
    }
    if (pathname === '/api/auth/session') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        mode: 'disabled', authenticated: false, accessDenied: false,
        principalId: null, tenantId: null, roles: [], serverTime: new Date().toISOString(),
        absoluteExpiresAt: null, loginUrl: null, logoutUrl: null,
        csrf: { enabled: false, available: false, cookieName: 'CSRF-TOKEN', headerName: 'X-CSRF-TOKEN' }
      }) })
      return
    }
    if (state.permission && pathname.endsWith('/pokemon/facts')) {
      await route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ message: 'permission denied' }) })
      return
    }
    if (pathname.includes('/pokemon/facts/forms/')) {
      const referenceFact=state.factsByForm?.[pathname.split('/').at(-1)] || FACT
      await route.fulfill({ status: state.failMemberFacts ? 503 : 200, contentType: 'application/json',
        body: JSON.stringify(state.failMemberFacts ? { message: '资料服务暂不可用' }
          : { ...referenceFact, dataReleaseId: state.wrongMemberRelease ? 'other-version' : state.referenceEnabled ? 'pokeapi-reference-v1' : referenceFact.dataReleaseId }) })
      return
    }
    if (pathname === '/api/chat/runs' && request.method() === 'POST') {
      const requestBody = request.postDataJSON()
      const run = { ...requestBody, runId: `run-workspace-${state.runs.length + 1}`, status: 'COMPLETED' }
      state.runs.push(run)
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(run) })
      return
    }
    if (pathname.includes('/api/chat/runs/') && pathname.endsWith('/events')) {
      const cursor = Number(url.searchParams.get('afterEventId') || request.headers()['last-event-id'] || 0)
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ events: [
        { id: '1', type: 'run.started', data: {} },
        { id: '2', type: 'run.completed', data: { answer: '请补充配招和道具以便分析。' } }
      ].filter(event => Number(event.id) > cursor) }) })
      return
    }
    if (pathname.startsWith('/api/chat/runs/')) {
      const run = state.runs.find(run => pathname.endsWith(run.runId))
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(run || {
        runId: 'run-1', conversationId: 'conversation-1', status: 'COMPLETED',
        gameReleaseId: FACT.gameReleaseId, battleFormat: 'casual-doubles',
        rulesetVersionId: 'casual-v1', dataReleaseId: FACT.dataReleaseId, language: 'zh-CN'
      }) })
      return
    }
    if (pathname.endsWith('/pokemon/facts')) {
      if (state.referenceUnavailable) {
        await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:503,message:'PokéAPI 暂不可达',success:false})});return
      }
      if (state.missingRelease) {
        await route.fulfill({status:404,contentType:'application/json',body:JSON.stringify({code:404,message:'找不到 data_release: pokemon-scarlet-violet-data-v1',success:false})});return
      }
      const fact = { ...(state.fact || FACT), ...(state.referenceEnabled?{dataReleaseId:'pokeapi-reference-v1',releaseStatus:'LOCAL_REFERENCE'}:{}), ...(state.unsafeSource?{sourceUrl:'javascript:alert(1)'}:{}) }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.emptyFacts ? [] : [fact]) })
      return
    }
    if (pathname.endsWith('/teams') && request.method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: state.teams, nextCursor: null }) })
      return
    }
    if (pathname.endsWith('/teams') && request.method() === 'POST') {
      const body = JSON.parse(request.postData() || '{}')
      const team = { id: 'team-1', ...body, members: body.members || [] }
      state.teams = [team]
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(team) })
      return
    }
    if (/\/teams\/[^/]+$/.test(pathname) && request.method() === 'PATCH') {
      const body = JSON.parse(request.postData() || '{}')
      state.teams = [{ ...(state.teams[0] || { id: 'team-1' }), ...body }]
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.teams[0]) })
      return
    }
    if (pathname.endsWith('/favorites') && request.method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: state.favorites, nextCursor: null }) })
      return
    }
    if (pathname.endsWith('/favorites') && request.method() === 'POST') {
      const body = JSON.parse(request.postData() || '{}')
      const favorite = { id: `favorite-${state.favorites.length + 1}`, ...body, createdAt: '2026-09-04T00:00:00Z', updatedAt: '2026-09-04T00:00:00Z' }
      state.favorites = [favorite, ...state.favorites]
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(favorite) })
      return
    }
    if (/\/favorites\/[^/]+$/.test(pathname) && request.method() === 'DELETE') {
      const id = decodeURIComponent(pathname.split('/').pop())
      state.favorites = state.favorites.filter(item => String(item.id) !== id)
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', timestamp: 1 }) })
      return
    }
    if (pathname.endsWith('/pokemon/activities')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
      return
    }
    if (pathname.endsWith('/conversations') && request.method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.conversations) })
      return
    }
    if (/\/chat\/conversations\/[^/]+\/messages$/.test(pathname)) {
      await route.fulfill({ status: state.missingHistory?404:200, contentType: 'application/json', body: JSON.stringify(state.missingHistory?{}:state.messages) })
      return
    }
    await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ message: 'fixture route missing' }) })
  })
}

async function openWorkbench(page, state) {
  await installApiFixture(page, state)
  await page.goto('/pokemon-workbench')
  await expect(page.getByRole('heading', { name: '宝可梦助手' })).toBeVisible()
  await page.locator('.more-resources summary').click()
  await page.getByRole('button', { name: '全部', exact: true }).click()
}

test.describe('N5 宝可梦工作台关键交互', () => {
  test('图鉴收藏绑定版本，消息格式化不执行模型中的 HTML', async ({page}) => {
    const state=initialState();state.referenceEnabled=true
    state.messages=[{role:'assistant',content:'**图鉴说明**\n<script>window.injected=1</script>\n这是 **水属性** 资料。'}]
    await openWorkbench(page,state)
    await expect(page.getByRole('heading',{name:'图鉴说明',exact:true})).toBeVisible()
    await expect(page.locator('.assistant-bubble script')).toHaveCount(0)
    expect(await page.evaluate(()=>window.injected)).toBeUndefined()
    await page.locator('.facts-panel button[type=submit]').click()
    await page.getByRole('button',{name:'收藏该资料',exact:true}).click()
    await expect(page.getByRole('button',{name:'该资料已收藏',exact:true})).toBeDisabled()
    expect(state.favorites).toHaveLength(1)
    expect(state.favorites[0].resourceKey).toBe('pokeapi-reference-v1:6')
    expect(state.favorites[0].metadata.dataReleaseId).toBe('pokeapi-reference-v1')
  })

  test('保存后保留队伍编辑并显示已保存，草稿改动使旧计算失效', async ({page}) => {
    const state=initialState();state.referenceEnabled=true
    await openWorkbench(page,state)
    await page.locator('.facts-panel button[type=submit]').click()
    await page.getByRole('button',{name:'加入队伍',exact:true}).click()
    await page.getByLabel('队伍名称').fill('当前草稿计算队')
    await page.getByLabel('第 1 位第 1 个招式',{exact:true}).fill('flamethrower')
    await page.getByLabel('第 1 位 特攻 EV',{exact:true}).fill('252')
    await page.getByRole('button',{name:'保存队伍',exact:true}).click()
    await expect(page.getByLabel('队伍名称')).toHaveValue('当前草稿计算队')
    await expect(page.locator('.roster-save-state')).toHaveText('已保存')
    await page.getByLabel('攻击方队伍成员',{exact:true}).selectOption('draft:1')
    await page.getByLabel('伤害防御方',{exact:true}).fill('squirtle')
    await page.getByLabel('伤害招式',{exact:true}).fill('flamethrower')
    await page.getByRole('button',{name:'开始计算',exact:true}).click()
    await expect(page.locator('.engine-panel .calculation-card')).toBeVisible()
    expect(state.engineRequests[0].attacker.build.evs.spa).toBe(252)
    await page.getByLabel('第 1 位 特攻 EV',{exact:true}).fill('200')
    await expect(page.locator('.roster-save-state')).toHaveText('未保存草稿')
    await expect(page.getByLabel('攻击方队伍成员',{exact:true})).toHaveValue('')
    await expect(page.locator('.engine-panel .calculation-card')).toHaveCount(0)
  })

  for (const viewport of [{width:768,height:768},{width:1024,height:768},{width:1280,height:720},{width:1366,height:768},{width:1920,height:1080},{width:2560,height:1440}]) {
    test(`web viewport ${viewport.width}x${viewport.height}: inline resources and visible composer`, async ({page}) => {
      await page.setViewportSize(viewport)
      await installApiFixture(page,initialState())
      await page.goto('/game-master')
      const sidebar=page.locator('.resource-sidebar')
      await expect(sidebar).toBeVisible()
      await expect(sidebar).toHaveCSS('position','static')
      const chat=await page.locator('.conversation-pane').boundingBox()
      const resources=await sidebar.boundingBox()
      expect(chat.width).toBeGreaterThan(350)
      expect(resources.x).toBeGreaterThanOrEqual(chat.x+chat.width)
      await expect(page.getByRole('textbox',{name:'输入消息',exact:true})).toBeInViewport()
      await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width)
      if(viewport.width<1200) {
        await page.getByRole('button',{name:'打开历史菜单',exact:true}).click()
        await expect(page.getByRole('button',{name:'新对话',exact:true})).toBeInViewport()
        await page.getByRole('button',{name:'关闭历史菜单',exact:true}).click()
      }
      await page.getByRole('button',{name:'队伍配置',exact:true}).click()
      await expect(sidebar).toHaveCSS('position','static')
      await expect(page.getByRole('textbox',{name:'输入消息',exact:true})).toBeInViewport()
      await page.getByLabel('队伍名称').fill('网页适配草稿')
      await expect(page.getByRole('button',{name:'保存队伍',exact:true})).toBeInViewport()
      await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width)
      if(viewport.width===1366) await page.screenshot({path:'../artifacts/web-layout-team-1366.png',animations:'disabled'})
      await page.getByRole('button',{name:'对话',exact:true}).click()
      if(viewport.width===1366) await page.screenshot({path:'../artifacts/web-layout-chat-1366.png',animations:'disabled'})
    })
  }

  test('web resize restores inline resources and preserves drafts', async ({page}) => {
    await page.setViewportSize({width:390,height:844})
    await installApiFixture(page,initialState())
    await page.goto('/game-master')
    await expect(page.locator('.resource-sidebar')).toBeHidden()
    await page.getByRole('textbox',{name:'输入消息',exact:true}).fill('缩放后继续编辑')
    await page.setViewportSize({width:1024,height:768})
    await expect(page.locator('.resource-sidebar')).toBeVisible()
    await expect(page.locator('.resource-sidebar')).toHaveCSS('position','static')
    await page.getByRole('button',{name:'关闭资料侧栏',exact:true}).click()
    await page.setViewportSize({width:1100,height:800})
    await expect(page.locator('.resource-sidebar')).toBeHidden()
    await page.setViewportSize({width:390,height:844})
    await expect(page.locator('.resource-sidebar')).toBeHidden()
    await page.setViewportSize({width:1366,height:768})
    await expect(page.locator('.resource-sidebar')).toBeVisible()
    await expect(page.getByRole('textbox',{name:'输入消息',exact:true})).toHaveValue('缩放后继续编辑')
  })

  test('参考图三成员队伍：六槽横排，配置表与计算条件在同一首屏', async ({page}) => {
    await page.setViewportSize({width:1586,height:992})
    const state=initialState();state.referenceEnabled=true
    const squirtle={...FACT,formId:7,nationalNo:7,slug:'squirtle',speciesNameZh:'杰尼龟',type1:'水',type2:null,hp:44,attack:48,defense:65,spAttack:50,spDefense:64,speed:43,dataReleaseId:'pokeapi-reference-v1'}
    state.factsByForm={7:squirtle,25:{...squirtle,formId:25,nationalNo:25,slug:'pikachu',speciesNameZh:'皮卡丘',type1:'电'},1:{...squirtle,formId:1,nationalNo:1,slug:'bulbasaur',speciesNameZh:'妙蛙种子',type1:'草',type2:'毒'}}
    const build={moves:['water-pulse','protect'],ability:'torrent',item:'',nature:'modest',level:50,evs:{hp:0,atk:0,def:0,spa:252,spd:4,spe:252},ivs:{hp:31,atk:31,def:31,spa:31,spd:31,spe:31}}
    state.teams=[{id:'reference-team',name:'我的休闲队伍',rulesetId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',members:[{slot:1,formId:7,build},{slot:2,formId:25,build},{slot:3,formId:1,build}]}]
    state.messages=[{role:'user',content:'帮我检查这份配置'},{role:'assistant',content:'可以先补齐道具与其余招式，再根据实际伤害结果讨论搭配。'}]
    await installApiFixture(page,state)
    await page.goto('/game-master?dataReleaseId=pokeapi-reference-v1')
    await page.getByRole('button',{name:'队伍配置',exact:true}).click()
    await page.locator('.team-list').getByRole('button',{name:'编辑',exact:true}).click()
    await expect(page.locator('.team-roster .team-slot strong')).toHaveText(['杰尼龟','皮卡丘','妙蛙种子'])
    await page.getByLabel('攻击方队伍成员',{exact:true}).selectOption('draft:1')
    await page.getByLabel('防御方队伍成员',{exact:true}).selectOption('draft:2')
    await page.getByLabel('伤害招式',{exact:true}).fill('water-pulse')
    await expect(page.getByLabel('伤害防御方',{exact:true})).toHaveValue('pikachu')
    await page.locator('.resource-sidebar').evaluate(element=>{element.scrollTop=0})
    await page.screenshot({path:'../artifacts/reference-aligned-team-filled.png',animations:'disabled'})
    const table=await page.locator('.training-table:visible').boundingBox()
    expect(table.y+table.height).toBeLessThanOrEqual(980)
    const total=await page.locator('.ev-total:visible').boundingBox()
    expect(total.y+total.height).toBeLessThanOrEqual(980)
    await expect(page.getByRole('button',{name:'开始计算',exact:true})).toBeInViewport()
  })

  test('统一工作区保留聊天与队伍草稿，桌面与手机布局无横向溢出', async ({page}) => {
    const errors=[]
    page.on('pageerror',error=>errors.push(error.message))
    await page.setViewportSize({width:1586,height:992})
    const state=initialState();state.referenceEnabled=true
    state.fact={...FACT,formId:7,nationalNo:7,slug:'squirtle',speciesNameZh:'杰尼龟',type1:'水',type2:null,hp:44,attack:48,defense:65,spAttack:50,spDefense:64,speed:43}
    state.messages=[{role:'user',content:'帮我看看杰尼龟的特点，顺便考虑队伍搭配。',createdAt:'2026-10-21T06:23:00Z'},{role:'assistant',content:'**先认识杰尼龟**\n杰尼龟是水属性宝可梦。搭配队伍时，可以先考虑电、草属性弱点。具体配置还需结合赛制与招式。',createdAt:'2026-10-21T06:23:10Z'}]
    await installApiFixture(page,state)
    await page.goto('/game-master?dataReleaseId=pokeapi-reference-v1')
    await page.getByLabel('查询目标').fill('杰尼龟')
    await page.locator('.facts-panel button[type=submit]').click()
    await expect(page.getByRole('heading',{name:'杰尼龟',exact:true})).toBeVisible()
    await expect(page.locator('.fact-hero img')).toHaveJSProperty('complete',true)
    await page.getByRole('textbox',{name:'输入消息',exact:true}).fill('继续分析我的队伍')
    await expect(page.getByRole('heading',{name:'先认识杰尼龟',exact:true})).toBeVisible()
    const bodyFont=await page.locator('.assistant-bubble').last().evaluate(element=>Number.parseFloat(getComputedStyle(element).fontSize))
    expect(bodyFont).toBeGreaterThanOrEqual(16)
    await page.screenshot({path:'../artifacts/reference-aligned-conversation.png',animations:'disabled'})
    await page.getByRole('button',{name:'加入队伍',exact:true}).click()
    await expect(page.locator('.workspace-shell')).toHaveClass(/team-mode/)
    await page.getByLabel('队伍名称').fill('我的休闲队伍')
    await page.getByLabel('第 1 位第 1 个招式',{exact:true}).fill('water-pulse')
    await page.getByLabel('第 1 位第 2 个招式',{exact:true}).fill('protect')
    await page.getByLabel('第 1 位 特攻 EV',{exact:true}).fill('252')
    await page.getByLabel('第 1 位 特防 EV',{exact:true}).fill('4')
    await page.getByLabel('第 1 位 速度 EV',{exact:true}).fill('252')
    await page.locator('.resource-sidebar').evaluate(element=>{element.scrollTop=0})
    const roster=await page.locator('.team-workspace>.workbench-content>.team-roster').boundingBox()
    const editor=await page.locator('.team-panel').boundingBox()
    const engine=await page.locator('.engine-panel').boundingBox()
    expect(roster.width).toBeGreaterThan(editor.width+100)
    expect(editor.y).toBeGreaterThan(roster.y+roster.height)
    expect(Math.abs(editor.y-engine.y)).toBeLessThan(3)
    const tops=await page.locator('.team-roster .team-slot').evaluateAll(elements=>elements.map(element=>element.getBoundingClientRect().top))
    expect(new Set(tops).size).toBe(1)
    await page.screenshot({path:'../artifacts/reference-aligned-team.png',animations:'disabled'})
    await page.getByRole('button',{name:'对话',exact:true}).click()
    await expect(page.getByRole('textbox',{name:'输入消息',exact:true})).toHaveValue('继续分析我的队伍')
    await expect(page.locator('.fact-card h3').first()).toHaveText('杰尼龟')
    await page.getByRole('button',{name:'队伍配置',exact:true}).click()
    await expect(page.getByLabel('队伍名称')).toHaveValue('我的休闲队伍')
    await expect(page.getByLabel('第 1 位第 1 个招式',{exact:true})).toHaveValue('water-pulse')
    await expect(page.getByLabel('第 1 位第 2 个招式',{exact:true})).toHaveValue('protect')
    await page.setViewportSize({width:390,height:844})
    await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
    await page.locator('.resource-sidebar').evaluate(element=>{element.scrollTop=0})
    await page.screenshot({path:'../artifacts/reference-aligned-team-mobile.png',animations:'disabled'})
    await page.getByRole('button',{name:'关闭资料侧栏',exact:true}).click()
    await page.getByRole('button',{name:'对话',exact:true}).click()
    await expect(page.getByRole('textbox',{name:'输入消息',exact:true})).toHaveValue('继续分析我的队伍')
    await page.screenshot({path:'../artifacts/reference-aligned-chat-mobile.png',animations:'disabled'})
    expect(errors).toEqual([])
  })

  test('左侧历史入口恢复原会话，资料关闭后再打开仍保留查询', async ({page}) => {
    const state=initialState()
    await installApiFixture(page,state)
    await page.goto('/game-master')
    await page.getByLabel('查询目标').fill('charizard')
    await page.locator('.facts-panel button[type=submit]').click()
    await page.getByRole('button',{name:'关闭资料侧栏',exact:true}).click()
    await page.getByRole('button',{name:'资料与队伍',exact:true}).click()
    await expect(page.getByLabel('查询目标')).toHaveValue('charizard')
    await expect(page.locator('.fact-card h3').first()).toHaveText('喷火龙')
    await page.locator('.recent-conversations').getByRole('button',{name:/朱紫对战建议/}).click()
    await expect(page).toHaveURL(/conversation=conversation-1/)
    await expect(page.getByLabel('赛制',{exact:true})).toHaveValue('casual-doubles')
  })

  test('资料上游断网提示代理检查并支持恢复后重试', async ({page}) => {
    const state=initialState();state.referenceEnabled=true;state.referenceUnavailable=true
    await openWorkbench(page,state)
    await page.locator('.facts-panel button[type=submit]').click()
    await expect(page.getByText(/PokéAPI 资料服务暂不可达，请检查网络或资料代理设置后重试/)).toBeVisible()
    state.referenceUnavailable=false
    await page.locator('.facts-panel').getByRole('button',{name:'重试',exact:true}).click()
    await expect(page.locator('.fact-card').first()).toBeVisible()
  })

  test('旧会话保留版本并提供显式新建参考资料会话入口', async ({page}) => {
    const state=initialState();state.referenceEnabled=true
    state.messages=[{id:'old-user',role:'user',content:'旧会话问题'}]
    await openWorkbench(page,state)
    await expect(page.getByLabel('数据版本').first()).toHaveValue(FACT.dataReleaseId)
    state.messages=[]
    await page.getByRole('button',{name:'使用本地参考资料开启新对话'}).click()
    await expect(page.getByLabel('数据版本').first()).toHaveValue('pokeapi-reference-v1')
    await expect(page).toHaveURL(/dataReleaseId=pokeapi-reference-v1/)
  })

  test('未启用资料接口与未加载数据版本显示可操作提示', async ({page}) => {
    const state=initialState();state.missingRelease=true
    await openWorkbench(page,state)
    await expect(page.getByText(/当前后端未启用本地资料接口/)).toBeVisible()
    await page.locator('.facts-panel button[type=submit]').click()
    await expect(page.getByText(/当前数据版本 .* 未加载/)).toBeVisible()
  })

  test('新会话历史404仍可启用参考库，配置可保存回读并用于伤害计算', async ({ page }) => {
    const state=initialState();state.referenceEnabled=true;state.missingHistory=true
    await openWorkbench(page,state)
    await expect(page.getByLabel('数据版本').first()).toHaveValue('pokeapi-reference-v1')
    await page.locator('.facts-panel button[type=submit]').click()
    await page.getByRole('button',{name:'加入队伍',exact:true}).click()
    await page.getByLabel('队伍名称').fill('培养配置测试队')
    await page.getByLabel('第 1 位第 1 个招式',{exact:true}).fill('flamethrower')
    await page.getByLabel('第 1 位第 2 个招式',{exact:true}).fill('protect')
    await page.getByLabel('第 1 位特性',{exact:true}).fill('blaze')
    await page.getByLabel('第 1 位道具',{exact:true}).fill('leftovers')
    await page.getByLabel('第 1 位性格',{exact:true}).selectOption('modest')
    await page.getByLabel('第 1 位 特攻 EV',{exact:true}).fill('252')
    await page.getByRole('button',{name:'保存队伍',exact:true}).click()
    await expect(page.getByRole('heading',{name:'培养配置测试队',exact:true})).toBeVisible()
    expect(state.teams[0].members[0].build.moves).toEqual(['flamethrower','protect'])
    expect(state.teams[0].members[0].build.evs.spa).toBe(252)
    await page.getByRole('button',{name:'编辑',exact:true}).click()
    await expect(page.getByLabel('第 1 位第 1 个招式',{exact:true})).toHaveValue('flamethrower')
    await expect(page.getByLabel('第 1 位第 2 个招式',{exact:true})).toHaveValue('protect')
    await expect(page.getByLabel('第 1 位道具',{exact:true})).toHaveValue('leftovers')
    await page.getByLabel('攻击方队伍成员',{exact:true}).selectOption('team-1:1')
    await expect(page.getByLabel('伤害攻击方',{exact:true})).toHaveValue('charizard')
    await page.getByLabel('伤害防御方',{exact:true}).fill('squirtle')
    await page.getByLabel('伤害招式',{exact:true}).fill('flamethrower')
    await page.getByRole('button',{name:'开始计算',exact:true}).click()
    await expect(page.getByText('80–96 HP · 50.0%–60.0%',{exact:true})).toBeVisible()
    expect(state.engineRequests[0].attacker.build.nature).toBe('modest')
    expect(state.engineRequests[0].attacker.build.evs.spa).toBe(252)
    await page.getByRole('button',{name:'让 AI 解释结果',exact:true}).click()
    await expect(page.getByRole('textbox',{name:'输入消息',exact:true})).toHaveValue(/0.12.0/)
    expect(state.runs).toHaveLength(0)
  })

  test('攻略检索展示作者日期并可携带证据提问',async({page})=>{
    await openWorkbench(page,initialState())
    await page.getByRole('button',{name:'攻略',exact:true}).click()
    await page.getByRole('textbox',{name:'攻略问题',exact:true}).fill('努力值如何分配')
    await page.getByRole('button',{name:'检索攻略',exact:true}).click()
    await expect(page.getByRole('heading',{name:'培养配置说明',exact:true})).toBeVisible()
    await page.getByRole('button',{name:'结合攻略提问',exact:true}).click()
    await expect(page.getByRole('textbox',{name:'输入消息',exact:true})).toHaveValue(/sha256:guide-fixture/)
  })

  test('手机侧栏查询后可以把资料带回聊天，且不产生横向溢出', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    const state = initialState()
    await openWorkbench(page, state)
    await page.getByRole('button', { name: '图鉴', exact: true }).click()
    await page.locator('.facts-panel button[type=submit]').click()
    await page.getByRole('button', { name: '询问 AI', exact: true }).click()
    await expect(page.getByRole('complementary', { name: '资料与队伍侧栏' })).toHaveCount(0)
    await expect(page.getByRole('textbox', { name: '输入消息', exact: true })).toHaveValue(/喷火龙/)
    await page.getByRole('button', { name: '资料与队伍', exact: true }).click()
    await expect(page.getByRole('button', { name: '关闭资料侧栏', exact: true })).toBeVisible()
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
    await page.screenshot({ path: '../artifacts/workspace-mobile.png' })
    await page.getByRole('button', { name: '关闭资料侧栏', exact: true }).click()
    await expect(page.getByRole('textbox', { name: '输入消息', exact: true })).toHaveValue(/喷火龙/)
  })

  test('资料卡加入队伍、保存并携带真实成员资料交给 AI 分析', async ({ page }) => {
    const state = initialState()
    await openWorkbench(page, state)
    await page.locator('.facts-panel button[type=submit]').click()
    await page.getByRole('button', { name: '加入队伍', exact: true }).click()
    await page.locator('.team-version-settings summary').click()
    await expect(page.getByLabel('第 1 个成员 formId')).toHaveValue('6')
    await page.getByLabel('队伍名称').fill('喷火龙测试队')
    await page.getByRole('button', { name: '保存队伍', exact: true }).click()
    await expect(page.getByRole('heading', { name: '喷火龙测试队' })).toBeVisible()
    await page.getByRole('button', { name: '让 AI 分析', exact: true }).click()
    const input = page.getByRole('textbox', { name: '输入消息', exact: true })
    await expect(input).toHaveValue(/喷火龙测试队/)
    await expect(input).toHaveValue(/"speed":100/)
    expect(state.runs).toHaveLength(0) // A handoff is a reviewable draft, not an implicit paid model call.
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(page.locator('.assistant-bubble').filter({ hasText: '请补充配招和道具' })).toBeVisible()
    expect(state.runs[0].prompt).toContain('喷火龙')
    expect(state.runs[0].dataReleaseId).toBe(FACT.dataReleaseId)
  })

  test('历史继续对话沿用原会话 ID 和可恢复的赛制', async ({ page }) => {
    const state = initialState()
    await openWorkbench(page, state)
    await page.locator('.history-panel').getByRole('button', { name: /朱紫对战建议/ }).click()
    await page.getByRole('button', { name: '继续这段对话', exact: true }).click()
    await expect(page).toHaveURL(/conversation=conversation-1/)
    await expect(page.getByLabel('赛制', { exact: true })).toHaveValue('casual-doubles')
    await page.getByRole('textbox', { name: '输入消息', exact: true }).fill('继续分析')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await expect.poll(() => state.runs.length).toBe(1)
    expect(state.runs[0].conversationId).toBe('conversation-1')
    expect(state.runs[0].battleFormat).toBe('casual-doubles')
  })

  for (const failure of ['failMemberFacts', 'wrongMemberRelease']) {
    test(`队伍资料失败或跨版本时不生成分析草稿 ${failure}`, async ({ page }) => {
      const state = initialState()
      state.teams = [{ id: 'team-1', name: '异常队伍', rulesetId: 'casual-v1', dataReleaseId: FACT.dataReleaseId, members: [{ formId: 6, slot: 1 }] }]
      state[failure] = true
      await openWorkbench(page, state)
      await page.getByRole('button', { name: '让 AI 分析', exact: true }).click()
      await expect(page.locator('.resource-status')).toContainText('无法分析队伍')
      await expect(page.getByRole('textbox', { name: '输入消息', exact: true })).toHaveValue('')
      expect(state.runs).toHaveLength(0)
    })
  }
  test('创建上下文后展示版本化事实、来源和 release', async ({ page }) => {
    const state = initialState()
    await openWorkbench(page, state)
    await page.getByLabel('查询目标').fill('charizard')
    await page.locator('.facts-panel button[type=submit]').click()
    await expect(page.getByRole('heading', { name: '喷火龙' })).toBeVisible()
    await page.locator('.fact-provenance summary').click()
    await expect(page.getByText('pokemon-scarlet-violet-data-v1')).toBeVisible()
    await expect(page.getByText('fixture evidence only')).toBeVisible()
  })

  test('重复队伍成员在客户端被拒绝且不发送写请求', async ({ page }) => {
    const state = initialState()
    await openWorkbench(page, state)
    await page.getByLabel('队伍名称').fill('重复测试队伍')
    await page.locator('.team-version-settings summary').click()
    await page.getByLabel('规则键').fill('casual-v1')
    await page.getByLabel('数据版本').last().fill('pokemon-scarlet-violet-data-v1')
    await page.getByLabel('第 1 个成员 formId').fill('6')
    await page.getByLabel('第 2 个成员 formId').fill('6')
    await page.getByRole('button', { name: '保存队伍' }).click()
    await expect(page.getByText('队伍不能重复使用同一 formId', { exact: true })).toBeVisible()
    expect(state.teams).toHaveLength(0)
  })

  test('保存队伍携带幂等语义并可编辑回读', async ({ page }) => {
    const state = initialState()
    await openWorkbench(page, state)
    await page.getByLabel('队伍名称').fill('朱紫休闲单打')
    await page.locator('.team-version-settings summary').click()
    await page.getByLabel('规则键').fill('casual-v1')
    await page.getByLabel('数据版本').last().fill('pokemon-scarlet-violet-data-v1')
    await page.getByLabel('第 1 个成员 formId').fill('6')
    await page.getByRole('button', { name: '保存队伍' }).click()
    await expect(page.getByRole('heading', { name: '朱紫休闲单打' })).toBeVisible()
    await page.getByRole('button', { name: '编辑', exact: true }).click()
    await page.getByLabel('队伍名称').fill('朱紫休闲单打（更新）')
    await page.getByRole('button', { name: '更新队伍' }).click()
    await expect(page.getByRole('heading', { name: '朱紫休闲单打（更新）' })).toBeVisible()
  })

  test('历史会话点击后只展示服务端消息', async ({ page }) => {
    const state = initialState()
    await openWorkbench(page, state)
    await page.locator('.history-panel').getByRole('button', { name: /朱紫对战建议/ }).click()
    await expect(page.locator('.message-history').getByText('请先确认规则版本。')).toBeVisible()
    await expect(page.getByText('Run run-1')).toBeVisible()
  })

  test('权限失败显示权限态，不回退到示例事实', async ({ page }) => {
    const state = initialState()
    state.permission = true
    await openWorkbench(page, state)
    await page.locator('.facts-panel button[type=submit]').click()
    await expect(page.getByRole('alert')).toContainText('需要登录或资源权限')
    await expect(page.getByText('喷火龙')).toHaveCount(0)
  })

  test('危险来源协议不会变成可点击外链', async ({ page }) => {
    const state = initialState()
    state.unsafeSource = true
    await openWorkbench(page, state)
    await page.locator('.facts-panel button[type=submit]').click()
    await expect(page.getByRole('heading', { name: '喷火龙' })).toBeVisible()
    await expect(page.locator('a[href^="javascript:"]')).toHaveCount(0)
    await page.locator('.fact-provenance summary').click()
    await expect(page.getByText('服务端未返回可验证 HTTP(S) 来源')).toBeVisible()
  })

  test('空结果保持明确空态，不伪造样例内容', async ({ page }) => {
    const state = initialState()
    state.emptyFacts = true
    await openWorkbench(page, state)
    await page.locator('.facts-panel button[type=submit]').click()
    await expect(page.getByText('该 data release 没有可展示的事实')).toBeVisible()
    await expect(page.getByText('喷火龙')).toHaveCount(0)
  })

  test('收藏资源可创建、从服务端回读并删除', async ({ page }) => {
    const state = initialState()
    await openWorkbench(page, state)
    await page.getByLabel('收藏资源类型').selectOption('pokemon')
    await page.getByLabel('收藏资源键').fill('charizard')
    await page.getByLabel('收藏展示备注').fill('我的喷火龙')
    await page.getByRole('button', { name: '加入收藏' }).click()
    await expect(page.getByRole('heading', { name: '我的喷火龙' })).toBeVisible()
    expect(state.favorites).toHaveLength(1)

    page.once('dialog', dialog => dialog.accept())
    await page.getByRole('button', { name: '删除收藏 我的喷火龙' }).click()
    await expect(page.getByText('当前主体还没有收藏资源。')).toBeVisible()
    expect(state.favorites).toHaveLength(0)
  })
})
