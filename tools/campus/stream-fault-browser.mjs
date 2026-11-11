// Real browser -> Vite proxy -> isolated Spring Run API. No mocked API responses.
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
const root=process.cwd(),front=path.join(root,'vio-ai-agent-frontend')
const {chromium}=await import(pathToFileURL(path.join(front,'node_modules/playwright/index.mjs')))
const channel=process.env.N5_E2E_BROWSER_CHANNEL||(process.platform==='win32'?'chrome':undefined)
const browser=await chromium.launch({headless:true,channel})
if(process.argv.includes('--probe')){await browser.close();process.exit(0)}
const api=new URL(process.env.VIO_FAULT_TEST_API)
if(api.protocol!=='http:'||api.hostname!=='127.0.0.1'||Number(api.port)<1024||api.port==='8123')throw Error('Isolated API required')
const {createServer}=await import(pathToFileURL(path.join(front,'node_modules/vite/dist/node/index.js')))
const {default:base}=await import(pathToFileURL(path.join(front,'vite.config.js')))
const server=await createServer({...base,configFile:false,root:front,server:{...base.server,host:'127.0.0.1',port:4319,strictPort:true,proxy:{'/api':{target:api.origin,changeOrigin:false}}}})
const context=await browser.newContext({viewport:{width:1440,height:900},serviceWorkers:'block'})
const page=await context.newPage();let creations=0
page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname==='/api/chat/runs')creations++})
try{
 await server.listen();await page.goto('http://127.0.0.1:4319/game-master')
 const input=page.getByLabel('输入消息',{exact:true});await input.waitFor();await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
 await input.fill('请回复一段简短的宝可梦欢迎语。')
 await page.getByRole('button',{name:'发送',exact:true}).click()
 await page.locator('.assistant-bubble').filter({hasText:'部分测试'}).waitFor({timeout:30000})
 if(await page.locator('.run-live-error').count())throw Error('Provider already failed before browser disconnect')
 await context.setOffline(true);await new Promise(r=>setTimeout(r,350));await context.setOffline(false)
 await page.locator('.run-live-error').filter({hasText:'失败'}).waitFor({timeout:30000})
 const partial=page.locator('.assistant-bubble').filter({hasText:'部分测试回答'})
 const text=await partial.innerText()
 if((text.match(/部分测试回答/g)||[]).length!==1||creations!==1)throw Error('Duplicate text or generation restart')
 if(await page.locator('.assistant-row').last().getByText('已完成',{exact:true}).count())throw Error('Partial answer marked complete')
 fs.mkdirSync('artifacts/stream-fault-browser',{recursive:true})
 await page.screenshot({path:'artifacts/stream-fault-browser/failed-partial.png'})
 console.log(JSON.stringify({browserDisconnected:true,partialPreservedOnce:true,runCreations:creations,failedVisible:true}))
}finally{await context.setOffline(false).catch(()=>{});await context.close();await browser.close();await server.close()}
