// Dedicated loopback account. Credentials and cookies stay in memory; never confirms writes.
import { readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync } from 'node:fs'
import { resolve, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomBytes } from 'node:crypto'
import { digest, localBase, frozenFileDigest } from './evaluation.mjs'
import { attemptsFrom } from './answer-quality.mjs'
const [baseArg,outputArg,...extra]=process.argv.slice(2)
if(!baseArg||!outputArg||extra.length)throw Error('Usage: node tools/campus/s2-tool-input-scenario.mjs LOCAL_API NEW_OUTPUT')
const base=localBase(baseArg),out=resolve(outputArg),planFile='evaluation/campus/tool-inputs/manifest.json',plan=JSON.parse(readFileSync(planFile))
if(existsSync(out))throw Error('Never overwrite evidence');mkdirSync(out,{recursive:true})
const report={schemaVersion:'tool-input-capture-v1',startedAt:new Date().toISOString(),plan,planSha256:frozenFileDigest(planFile),collectorSha256:digest(readFileSync(fileURLToPath(import.meta.url))),
 records:plan.cases.flatMap(c=>Array.from({length:plan.repetitions},(_,i)=>({caseId:c.id,repetition:i+1,status:'NOT_RUN',answer:''}))),humanAcceptance:'NOT_PERFORMED'}
const save=()=>writeFileSync(resolve(out,'capture.json'),JSON.stringify(report,null,2)+'\n'),cookies=new Map()
async function api(path,body,method=body===undefined?'GET':'POST',expected=200) {
 const response=await fetch(base+path,{method,redirect:'error',signal:AbortSignal.timeout(30000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',
 Cookie:[...cookies].map(([n,v])=>`${n}=${v}`).join('; '),...(cookies.get('CSRF-TOKEN')?{'X-CSRF-TOKEN':cookies.get('CSRF-TOKEN')}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 for(const line of response.headers.getSetCookie()){const[n,...v]=line.split(';')[0].split('=');cookies.set(n,v.join('='))}
 if(response.status!==expected)throw Error(`HTTP ${response.status}; expected ${expected}`)
 const text=await response.text(),value=text?JSON.parse(text):null
 return value&&Object.hasOwn(value,'code')&&Object.hasOwn(value,'data')?value.data:value
}
let active
try {
 if((await api('/auth/session')).mode!=='local')throw Error('Dedicated local account required')
 const username='input_'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url')
 await api('/auth/local/register',{username,password,nickname:'工具条件回归'},'POST',201);await api('/auth/local/login',{username,password})
 report.runtime=await api('/chat/runtime')
 if(report.runtime.toolInputEvidence?.schemaVersion!=='tool-input-evidence-v1')throw Error('Tool input instrumentation missing')
 report.sourceSha256={}
 function walk(dir){for(const entry of readdirSync(dir,{withFileTypes:true})){const f=resolve(dir,entry.name);if(entry.isDirectory())walk(f);else if(f.endsWith('.java'))report.sourceSha256[relative(process.cwd(),f).replaceAll('\\','/')]=digest(readFileSync(f))}}
 walk(resolve('src/main/java'));for(const file of ['pom.xml',planFile])report.sourceSha256[file]=digest(readFileSync(file))
 for(const[name,expected]of Object.entries(report.runtime.implementationSha256)) {
  const source=Object.keys(report.sourceSha256).find(f=>f.endsWith(`/${name}.java`))
  if(!source||digest(readFileSync(source.replace('src/main/java/','target/classes/').replace(/\.java$/,'.class')))!==expected)throw Error(`Unattested class: ${name}`)
 }
 // Separate dependency control, also warms the isolated public reference cache. Not a model call or a latency experiment.
 const calculation=plan.cases.find(c=>c.tool==='calculateBattleDamage')
 if(calculation)try{report.engineControl=await api('/pokemon/calculations/engine-damage',calculation.expectedArguments.request)}catch(error){report.engineControlError=error.message}
 for(const record of report.records) {
  const item=plan.cases.find(c=>c.id===record.caseId),events=[],seen=new Set();let cursor='0',history={}
  try {
   const conversationId='input-'+randomBytes(12).toString('hex'),run=await api('/chat/runs',{prompt:item.prompt,conversationId,gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',
    rulesetVersionId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',language:'zh-CN',timeoutSeconds:120,idempotencyKey:randomBytes(16).toString('hex')},'POST',202)
   active=run.runId;record.runId=active;const deadline=Date.now()+140000;let finished=false
   while(!finished&&Date.now()<deadline) {
    const page=await api(`/chat/runs/${active}/events?afterEventId=${cursor}`)
    for(const event of page.events||[]){const id=String(event.eventId);if(seen.has(id))continue;seen.add(id);events.push(event);cursor=id
     if(['run.completed','run.failed','run.cancelled','run.timed_out','approval.required'].includes(event.type)){record.status=event.type==='run.completed'?'COMPLETED':event.type;finished=true}}
    if(!finished)await new Promise(done=>setTimeout(done,350))
   }
   if(!finished||record.status!=='COMPLETED')throw Error('Run did not complete')
   const runId=active;active=null
   const messages=await api(`/chat/conversations/${conversationId}/messages`),user=messages.find(m=>m.role==='user'&&m.runId===runId),answer=messages.find(m=>m.role==='assistant'&&m.runId===runId)
   history={prompt:user?.content,answer:answer?.content,attempts:answer?.metadata?.modelUsageAttempts||[]}
  }catch(error){record.error=error.message;if(record.status==='NOT_RUN')record.status='COLLECTION_FAILED'}
  finally {
   if(active){try{await api(`/chat/runs/${active}/cancel`,{})}catch{record.cleanupFailed=true}active=null}
   record.answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join('');record.modelCalls=attemptsFrom(events).reduce((n,a)=>n+a.modelCallCount,0)
   for(const[key,value]of [['events',events],['history',history]]){const bytes=JSON.stringify(value,null,2)+'\n',file=`${record.caseId}-${record.repetition}-${key}.json`;record[key+'File']=file;record[key+'Sha256']=digest(bytes);writeFileSync(resolve(out,file),bytes)}
   save();console.log(`${record.caseId} #${record.repetition}: ${record.status}; calls=${record.modelCalls}`)
  }
 }
 report.pass=report.records.every(r=>r.status==='COMPLETED'&&!r.error&&!r.cleanupFailed)
}catch(error){report.setupError=error.message;report.pass=false}
finally{try{await api('/logout',undefined,'POST',204)}catch{report.cleanupFailed=true}report.finishedAt=new Date().toISOString();save()}
if(!report.pass||report.cleanupFailed)process.exitCode=1
