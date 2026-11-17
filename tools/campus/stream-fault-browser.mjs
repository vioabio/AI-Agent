// Real browser -> Vite proxy -> isolated Spring Run API. No mocked API responses.
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {verifyFailureReconnect} from './stage6-stream-evidence.mjs'
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
const output=fs.mkdtempSync(path.join(root,'artifacts','u6-stage6-stream-reconnect-'))
const report={schemaVersion:'stage6-stream-reconnect-v1',caseId:'S6-08',status:'RUNNING',startedAt:new Date().toISOString(),
 provider:'CONTROLLED_LOOPBACK_TRUNCATED_SSE',qualityReview:'REQUIRED',releaseDecision:'NOT_EVALUATED'}
async function read(route){const response=await fetch(api.origin+'/api'+route);assert.equal(response.status,200);return response.json()}
page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname==='/api/chat/runs')creations++})
try{
 await server.listen();await page.goto('http://127.0.0.1:4319/game-master')
 const input=page.getByLabel('输入消息',{exact:true});await input.waitFor();await page.waitForFunction(()=>!document.querySelector('textarea[aria-label="输入消息"]').disabled)
 await input.fill('请回复一段简短的宝可梦欢迎语。')
 const created=page.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname==='/api/chat/runs')
 await page.getByRole('button',{name:'发送',exact:true}).click()
 const response=await created;assert.equal(response.status(),202);const runId=(await response.json()).runId;report.runId=runId
 await page.locator('.assistant-bubble').filter({hasText:'部分测试'}).waitFor({timeout:30000})
 if(await page.locator('.run-live-error').count())throw Error('Provider already failed before browser disconnect')
 const before=(await read('/chat/runs/'+runId+'/events?afterEventId=0')).events,cursor=String(before.at(-1).eventId)
 report.before=before;report.cursor=cursor
 await context.setOffline(true);await new Promise(r=>setTimeout(r,2000));await context.setOffline(false)
 await page.locator('.run-live-error').filter({hasText:'失败'}).waitFor({timeout:30000})
 const partial=page.locator('.assistant-bubble').filter({hasText:'部分测试回答'})
 const text=await partial.innerText()
 if((text.match(/部分测试回答/g)||[]).length!==1||creations!==1)throw Error('Duplicate text or generation restart')
 if(await page.locator('.assistant-row').last().getByText('已完成',{exact:true}).count())throw Error('Partial answer marked complete')
 const events=(await read('/chat/runs/'+runId+'/events?afterEventId=0')).events
 const tail=(await read('/chat/runs/'+runId+'/events?afterEventId='+cursor)).events
 assert.equal((await read('/chat/runs/'+runId)).status,'FAILED')
 report.replay=verifyFailureReconnect(runId,before,events,tail,cursor)
 report.runCreations=creations;report.answer=text;report.tail=tail
 const bytes=JSON.stringify(events,null,2)+'\n';fs.writeFileSync(path.join(output,'events.json'),bytes,{flag:'wx'});report.eventsSha256=digest(bytes)
 await page.screenshot({path:path.join(output,'failed-partial.png')})
 report.status='COLLECTED_PENDING_CONTENT_REVIEW'
 console.log(JSON.stringify({browserDisconnected:true,partialPreservedOnce:true,runCreations:creations,failedVisible:true,output}))
}catch(error){report.status='FAIL';report.failure=String(error.message).slice(0,250);throw error}
finally{
 await context.setOffline(false).catch(()=>{});await context.close();await browser.close();await server.close()
 report.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(output,'browser-result.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
}
