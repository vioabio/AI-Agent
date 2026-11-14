import {randomBytes} from 'node:crypto'
import {writeFileSync,readFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {loadFrozenSuite,prepare,publicFingerprint,collectionFailure,root} from './upgrade-baseline.mjs'
import {localBase,digest,eventBoundaryReached} from './evaluation.mjs'
const [output,url,limitText='20',caseText='']=process.argv.slice(2)
if(!output||!url)throw Error('Usage: upgrade-collect.mjs artifacts/NEW http://127.0.0.1:8123/api [dev-limit]')
const base=localBase(url),limit=Number(limitText)
const requested=caseText?caseText.split(','):null
if(!Number.isInteger(limit)||limit<1||limit>20)throw Error('limit 1..20')
const suite=loadFrozenSuite(),report=prepare(output,'dev'),cookies=new Map(),opened=new Set()
report.producerSha256=digest(readFileSync(new URL(import.meta.url)))
report.mode='COLLECTING';report.cleanupErrors=[]
let authenticated=false
const save=()=>writeFileSync(resolve(output,'baseline.json'),JSON.stringify(report,null,2)+'\n')
async function api(path,body,expected=200){
 const r=await fetch(base+path,{method:body===undefined?'GET':'POST',redirect:'error',signal:AbortSignal.timeout(30000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; '),...(cookies.has('CSRF-TOKEN')?{'X-CSRF-TOKEN':decodeURIComponent(cookies.get('CSRF-TOKEN'))}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 for(const line of r.headers.getSetCookie()){const [k,...v]=line.split(';')[0].split('=');cookies.set(k,v.join('='))}
 if(r.status!==expected)throw Error('HTTP_'+r.status)
 const j=r.status===204?null:await r.json();return j&&Object.hasOwn(j,'data')&&Object.hasOwn(j,'code')?j.data:j
}
try{
 if((await api('/auth/session')).mode!=='local')throw Error('LOCAL_ACCOUNT_REQUIRED')
 const credentials={username:'u0_'+randomBytes(8).toString('hex'),password:randomBytes(24).toString('base64url'),nickname:'U0隔离评测'}
 await api('/auth/local/register',credentials,201);await api('/auth/local/login',credentials);authenticated=true
 const runtime=await api('/chat/runtime');report.runtime=publicFingerprint(runtime)
 if(requested&&requested.some(id=>!report.records.some(r=>r.caseId===id)))throw Error('DEV_CASE_REQUIRED')
 report.selectedCaseIds=requested||report.records.slice(0,limit).map(r=>r.caseId)
 for(const record of report.records.filter(r=>report.selectedCaseIds.includes(r.caseId))){
  const item=suite.cases.find(c=>c.id===record.caseId);record.turns=[];const conversationId='u0-'+randomBytes(12).toString('hex')
  try{for(const prompt of item.turns){
   const start=Date.now(),run=await api('/chat/runs',{...item.scope,dataReleaseId:"pokeapi-reference-v1",prompt,conversationId,timeoutSeconds:120,idempotencyKey:randomBytes(16).toString('hex')},202)
   opened.add(run.runId);const events=[],seen=new Set();let cursor='0',status='RUNNING',ttft=null
   while(Date.now()-start<140000){
    const page=await api('/chat/runs/'+run.runId+'/events?afterEventId='+cursor)
    for(const event of page.events||[]){const id=String(event.eventId??event.id);if(seen.has(id))continue;seen.add(id);cursor=id;events.push(event);if(event.type==='answer.delta'&&event.payload?.text&&ttft===null)ttft=Date.now()-start}
    status=(await api('/chat/runs/'+run.runId)).status
    if(eventBoundaryReached(status,events)||status==='WAITING_APPROVAL')break
    await new Promise(r=>setTimeout(r,250))
   }
   const file=record.caseId+'-'+record.turns.length+'-events.json',bytes=JSON.stringify(events,null,2)+'\n'
   writeFileSync(resolve(output,file),bytes)
   record.turns.push({runId:run.runId,prompt,status,ttftMs:ttft,totalMs:Date.now()-start,answer:events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join(''),eventsFile:file,eventsSha256:digest(bytes)})
   if(!eventBoundaryReached(status,events)||status==='WAITING_APPROVAL'){await api('/chat/runs/'+run.runId+'/cancel',{});record.verdict='BLOCKED';break}
   opened.delete(run.runId)
  }
  if(record.verdict!=='BLOCKED')record.verdict='PENDING_REVIEW'
  }catch(error){record.verdict='BLOCKED';record.failure=collectionFailure(error)}
  save();console.log(record.caseId+': '+record.verdict)
 }
 report.mode='COLLECTED_NOT_REVIEWED'
}catch(error){report.mode='SETUP_BLOCKED';report.setupError=collectionFailure(error)}
finally{
 for(const id of opened)try{await api('/chat/runs/'+id+'/cancel',{})}catch{report.cleanupErrors.push('CANCEL_FAILED')}
 if(authenticated)try{await api('/logout',{},204)}catch{report.cleanupErrors.push('LOGOUT_UNVERIFIED')}
 report.finishedAt=new Date().toISOString();save()
}
