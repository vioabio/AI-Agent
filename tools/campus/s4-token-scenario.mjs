// Fixed local development/validation capture. No credential or cookie is persisted.
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { resolve, relative } from 'node:path'
import { randomBytes, createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { localBase } from './evaluation.mjs'
const [baseArg,outputArg,...extra]=process.argv.slice(2)
if(!baseArg || !outputArg || extra.length)throw Error('Usage: node tools/campus/s4-token-scenario.mjs LOCAL_API NEW_OUTPUT')
const base=localBase(baseArg),output=resolve(outputArg),planFile='evaluation/campus/s4-token/manifest.json',plan=JSON.parse(readFileSync(planFile))
if(existsSync(output))throw Error('Never overwrite evidence')
mkdirSync(output,{recursive:true})
const hash=value=>createHash('sha256').update(value).digest('hex')
const report={schemaVersion:'token-calibration-capture-v1',startedAt:new Date().toISOString(),plan,planSha256:hash(readFileSync(planFile)),
  collectorSha256:hash(readFileSync(fileURLToPath(import.meta.url))),
  records:plan.cases.map(c=>({caseId:c.id,status:'NOT_RUN',attempts:[],historyAttempts:[]})),qualityAcceptance:'NOT_PERFORMED'}
const save=()=>writeFileSync(resolve(output,'capture.json'),JSON.stringify(report,null,2)+'\n')
const cookies=new Map()
async function api(path,body,method=body===undefined?'GET':'POST',expected=200) {
  const response=await fetch(base+path,{method,redirect:'error',signal:AbortSignal.timeout(30000),
    headers:{Origin:'http://localhost:3000','Content-Type':'application/json',Cookie:[...cookies].map(([n,v])=>`${n}=${v}`).join('; '),
      ...(cookies.get('CSRF-TOKEN')?{'X-CSRF-TOKEN':cookies.get('CSRF-TOKEN')}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
  for(const line of response.headers.getSetCookie()){const [n,...v]=line.split(';')[0].split('=');cookies.set(n,v.join('='))}
  if(response.status!==expected)throw Error(`HTTP ${response.status}; expected ${expected}`)
  const text=await response.text(),value=text?JSON.parse(text):null
  return value && Object.hasOwn(value,'code') && Object.hasOwn(value,'data')?value.data:value
}
let active
try {
  if((await api('/auth/session')).mode!=='local')throw Error('Requires dedicated local account mode')
  const username='token_'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url')
  await api('/auth/local/register',{username,password,nickname:'Token观测验证'},'POST',201);await api('/auth/local/login',{username,password})
  report.runtime=await api('/chat/runtime')
  if(report.runtime.tokenEstimateObservation?.schemaVersion!=='token-estimate-comparison-v1' || report.runtime.contextBudgetPolicy.safetyPercent!==125)throw Error('Requires unchanged 125% observation policy')
  report.sourceSha256={}
  function walk(directory) {
    for(const entry of readdirSync(directory,{withFileTypes:true})) {
      const file=resolve(directory,entry.name)
      if(entry.isDirectory())walk(file)
      else if(file.endsWith('.java'))report.sourceSha256[relative(process.cwd(),file).replaceAll('\\','/')]=hash(readFileSync(file))
    }
  }
  walk(resolve('src/main/java'))
  for(const file of ['pom.xml',planFile])report.sourceSha256[file]=hash(readFileSync(file))
  for(const [name,expected] of Object.entries(report.runtime.implementationSha256)) {
    const source=Object.keys(report.sourceSha256).find(p=>p.endsWith(`/${name}.java`))
    if(!source || hash(readFileSync(source.replace('src/main/java/','target/classes/').replace(/\.java$/,'.class')))!==expected)throw Error(`Unattested class: ${name}`)
  }
  const prefix='cal-'+randomBytes(10).toString('hex')
  for(const item of plan.cases) {
    const record=report.records.find(r=>r.caseId===item.id),events=[],seen=new Set();let cursor='0'
    try {
      const run=await api('/chat/runs',{prompt:item.prompt+(item.text||'').repeat(item.repeat||0),conversationId:`${prefix}-${item.conversation||item.id}`,
        gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',rulesetVersionId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',
        language:item.language,timeoutSeconds:120,idempotencyKey:randomBytes(16).toString('hex')},'POST',202)
      active=run.runId;record.runId=run.runId;const deadline=Date.now()+140000;let finished=false
      while(!finished && Date.now()<deadline) {
        const page=await api(`/chat/runs/${run.runId}/events?afterEventId=${cursor}`)
        for(const event of page.events||[]) {
          const id=String(event.eventId);if(seen.has(id))continue;seen.add(id);events.push(event);cursor=id
          if(['run.completed','run.failed','run.cancelled','run.timed_out','approval.required'].includes(event.type)) {record.status=event.type==='run.completed'?'COMPLETED':event.type;finished=true}
        }
        if(!finished)await new Promise(done=>setTimeout(done,350))
      }
      if(!finished)throw Error('Missing terminal boundary')
      if(record.status!=='COMPLETED') {await api(`/chat/runs/${run.runId}/cancel`,{});throw Error('Run did not complete')}
      active=null
      const history=await api(`/chat/conversations/${run.conversationId}/messages`)
      record.historyAttempts=history.find(m=>m.role==='assistant' && m.runId===run.runId)?.metadata?.modelUsageAttempts||[]
    } catch(error) {record.error=error.message;if(record.status==='NOT_RUN')record.status='COLLECTION_FAILED'}
    finally {
      if(active){try{await api(`/chat/runs/${active}/cancel`,{})}catch{record.cleanupFailed=true}active=null}
      record.attempts=[...new Map(events.flatMap(e=>{const u=e.type==='usage'?e.payload:e.payload?.modelUsage;return u?[[u.attemptId,u]]:[]})).values()]
      record.eventsFile=`${item.id}-events.json`;const bytes=JSON.stringify(events,null,2)+'\n';writeFileSync(resolve(output,record.eventsFile),bytes);record.eventsSha256=hash(bytes)
      save();console.log(`${item.id}: ${record.status}; calls=${record.attempts.reduce((sum,a)=>sum+a.modelCallCount,0)}`)
    }
  }
  report.pass=report.records.every(r=>r.status==='COMPLETED' && !r.error && !r.cleanupFailed)
} catch(error) {report.setupError=error.message;report.pass=false}
finally {try{await api('/logout',undefined,'POST',204)}catch{report.cleanupFailed=true}report.finishedAt=new Date().toISOString();save()}
if(!report.pass || report.cleanupFailed)process.exitCode=1
