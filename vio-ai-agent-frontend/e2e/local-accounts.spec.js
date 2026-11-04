import { test, expect } from '@playwright/test'
test.use({ video: 'off' })

async function fixture(page, { authenticated = false } = {}) {
  const state={authenticated,profile:{id:'local-fixture',username:'trainer',nickname:'小智',avatar:'squirtle',version:1},writes:[]}
  await page.context().addCookies([{name:'CSRF-TOKEN',value:'fixture-csrf',url:'http://127.0.0.1:3013'}])
  await page.route(url=>url.pathname.startsWith('/api/'),async route=>{
    const req=route.request(),path=new URL(req.url()).pathname,method=req.method()
    const json=(body,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)})
    if(path==='/api/auth/session')return json({mode:'local',authenticated:state.authenticated,accessDenied:false,principalId:state.authenticated?state.profile.id:null,tenantId:state.authenticated?'local':null,roles:state.authenticated?['trainer']:[],serverTime:new Date().toISOString(),absoluteExpiresAt:state.authenticated?new Date(Date.now()+3600000).toISOString():null,loginUrl:'/api/auth/local/login',logoutUrl:'/api/logout',csrf:{enabled:true,available:true,cookieName:'CSRF-TOKEN',headerName:'X-CSRF-TOKEN'}})
    if(path.startsWith('/api/auth/local/') && method!=='GET') {
      state.writes.push({path,body:req.postDataJSON(),csrf:req.headers()['x-csrf-token']})
      if(req.headers()['x-csrf-token']!=='fixture-csrf')return json({message:'CSRF missing'},403)
    }
    if(path==='/api/auth/local/register')return json({account:state.profile,recoveryCode:'a'.repeat(64)},201)
    if(path==='/api/auth/local/login') {
      if(req.postDataJSON().password!=='fixture-password')return json({message:'用户名或密码错误'},401)
      state.authenticated=true;return json(state.profile)
    }
    if(path==='/api/auth/local/recover')return json({recoveryCode:'b'.repeat(64)})
    if(path==='/api/auth/local/profile') {
      if(method==='PATCH')Object.assign(state.profile,req.postDataJSON())
      return json(state.profile)
    }
    if(path==='/api/auth/local/password')return json({recoveryCode:'c'.repeat(64)})
    if(path==='/api/auth/local/legacy-data')return json({eligible:false,conversations:0,teams:0})
    if(path==='/api/logout'){state.authenticated=false;return route.fulfill({status:204})}
    if(path==='/api/pokemon/reference/status')return json({enabled:true,status:'LOCAL_REFERENCE',dataReleaseId:'pokeapi-reference-v1'})
    if(path.endsWith('/messages'))return json([])
    if(path==='/api/conversations' || path==='/api/teams' || path==='/api/favorites')return json([])
    return json({message:'fixture empty'},404)
  })
  return state
}

for(const viewport of [{width:1366,height:768},{width:1280,height:720},{width:1024,height:640},{width:390,height:844},{width:390,height:667}]) {
  test(`登录与注册完整展示 ${viewport.width}x${viewport.height}`,async({page})=>{
    await fixture(page);await page.setViewportSize(viewport);await page.goto('/game-master')
    await expect(page.locator('.login-reference-art')).toHaveAttribute('data-loaded','true')
    for(const mode of ['登录','创建账户']) {
      await page.locator('.account-tabs').getByRole('button',{name:mode,exact:true}).click()
      await expect(page.locator('.local-auth-form footer')).toBeInViewport({ratio:1})
      await expect(page.locator('.local-auth-form button[type=submit]')).toBeInViewport({ratio:1})
      await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollHeight)).toBeLessThanOrEqual(viewport.height)
      const size=await page.locator('.local-auth-form').evaluate(el=>({content:el.scrollHeight,height:el.clientHeight}))
      expect(size.content).toBeLessThanOrEqual(size.height+1)
    }
    await page.locator('.account-tabs').getByRole('button',{name:'登录',exact:true}).click()
    if(viewport.width===1366)await page.screenshot({path:'../artifacts/login-fit-window-desktop.png',animations:'disabled'})
    if(viewport.height===667)await page.screenshot({path:'../artifacts/login-fit-window-mobile.png',animations:'disabled'})
  })
}

test('本地注册保存恢复码后登录，修改资料并退出',async({page})=>{
  const errors=[];page.on('pageerror',e=>errors.push(e.message))
  const state=await fixture(page)
  await page.setViewportSize({width:1440,height:960});await page.goto('/')
  await expect(page).toHaveURL(/\/game-master$/)
  await expect(page.getByTestId('local-auth-portal')).toBeVisible()
  await expect(page.getByRole('img',{name:'杰尼龟与妙蛙种子在花园里的欢迎插画'})).toBeVisible()
  const artResponse=await page.request.get(await page.locator('.login-reference-art image').getAttribute('href'))
  expect(artResponse.ok()).toBeTruthy()
  await page.screenshot({path:'../artifacts/local-account-login-desktop.png',animations:'disabled'})
  await page.getByRole('button',{name:'创建账户',exact:true}).click()
  await page.getByLabel('用户名',{exact:true}).fill('trainer')
  await page.getByLabel('昵称',{exact:true}).fill('小智')
  await page.getByLabel('密码',{exact:true}).fill('fixture-password')
  await page.getByLabel('确认密码',{exact:true}).fill('fixture-password')
  await page.locator('button[type=submit]').click()
  await expect(page.getByRole('heading',{name:'请保存你的恢复码'})).toBeVisible()
  await expect(page.getByRole('button',{name:'返回登录',exact:true})).toBeDisabled()
  await page.getByLabel('我已妥善保存恢复码').check();await page.getByRole('button',{name:'返回登录',exact:true}).click()
  await page.getByLabel('密码',{exact:true}).fill('wrong-password');await page.locator('button[type=submit]').click()
  await expect(page.getByRole('alert')).toHaveText('用户名或密码错误')
  await page.getByLabel('密码',{exact:true}).fill('fixture-password');await page.locator('button[type=submit]').click()
  await expect(page.locator('.workspace-header h1')).toBeVisible()
  await expect(page).toHaveURL(/\/game-master$/)
  await page.locator('.workspace-brand').click()
  await expect(page).toHaveURL(/\/game-master$/)
  await expect(page.locator('.workspace-header h1')).toBeVisible()
  await page.locator('.account-trigger').click();await page.getByRole('button',{name:'账户设置',exact:true}).click()
  await page.getByLabel('昵称',{exact:true}).fill('小智的新队伍');await page.getByLabel('预设头像').selectOption('pikachu')
  await page.getByRole('button',{name:'保存修改',exact:true}).click();await expect(page.getByRole('status')).toContainText('个人资料已保存')
  await page.screenshot({path:'../artifacts/local-account-settings-desktop.png',animations:'disabled'})
  await page.getByRole('button',{name:'关闭账户设置',exact:true}).click()
  await expect(page.locator('.account-trigger')).toContainText('小智的新队伍')
  await page.locator('.account-trigger').click();await page.getByRole('button',{name:'退出登录',exact:true}).click()
  await expect(page.getByTestId('local-auth-portal')).toBeVisible()
  expect(state.writes.every(write=>write.csrf==='fixture-csrf')).toBeTruthy()
  expect(errors).toEqual([])
})

test('手机恢复密码页面与账户安全表单可用，恢复码不写入浏览器存储',async({page})=>{
  await fixture(page);await page.setViewportSize({width:390,height:844});await page.goto('/game-master')
  await page.getByRole('button',{name:'忘记密码？',exact:true}).click()
  await page.getByLabel('用户名',{exact:true}).fill('trainer');await page.getByLabel('恢复码',{exact:true}).fill('a'.repeat(64))
  await page.getByLabel('新密码',{exact:true}).fill('replacement-password');await page.getByLabel('确认密码',{exact:true}).fill('replacement-password')
  await page.getByRole('button',{name:'重设密码',exact:true}).click()
  await expect(page.locator('.recovery-code')).toHaveText('b'.repeat(64))
  await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  const stored=await page.evaluate(()=>JSON.stringify([Object.entries(localStorage),Object.entries(sessionStorage)]))
  expect(stored).not.toContain('replacement-password');expect(stored).not.toContain('b'.repeat(64))
  await page.screenshot({path:'../artifacts/local-account-mobile-recovery.png',animations:'disabled'})
})

test('修改密码后需保存新恢复码并重新登录，关闭弹窗保留聊天草稿',async({page})=>{
  await fixture(page,{authenticated:true});await page.goto('/game-master')
  await page.getByRole('textbox',{name:'输入消息',exact:true}).fill('未发送的队伍问题')
  await page.locator('.account-trigger').click();await page.getByRole('button',{name:'账户设置',exact:true}).click()
  await page.getByRole('button',{name:'关闭账户设置',exact:true}).click()
  await expect(page.getByRole('textbox',{name:'输入消息',exact:true})).toHaveValue('未发送的队伍问题')
  await page.locator('.account-trigger').click();await page.getByRole('button',{name:'账户设置',exact:true}).click()
  await page.getByRole('button',{name:'账号安全',exact:true}).click()
  await page.getByLabel('当前密码',{exact:true}).fill('fixture-password');await page.getByLabel('新密码',{exact:true}).fill('replacement-password');await page.getByLabel('确认新密码',{exact:true}).fill('replacement-password')
  await page.getByRole('button',{name:'修改密码',exact:true}).click()
  await expect(page.getByRole('heading',{name:'请保存新的恢复码',exact:true})).toBeVisible()
  await page.evaluate(()=>window.dispatchEvent(new Event('focus')))
  await expect(page.getByRole('heading',{name:'请保存新的恢复码',exact:true})).toBeVisible()
  await expect(page.getByRole('button',{name:'关闭账户设置',exact:true})).toBeDisabled()
  await page.getByLabel('我已保存新的恢复码').check();await page.getByRole('button',{name:'返回登录',exact:true}).click()
  await expect(page.getByTestId('local-auth-portal')).toBeVisible()
})
