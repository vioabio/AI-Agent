// Fixed isolated development capture; passwords and cookies never leave memory.
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { resolve, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomBytes, createHash } from 'node:crypto'
import { localBase } from './evaluation.mjs'
import { attemptsFrom } from './answer-quality.mjs'
const [baseArg,outputArg,...extra]=process.argv.slice(2)
if(!baseArg || !outputArg || extra.length)throw Error('Usage: node tools/campus/s4-quality-scenario.mjs LOCAL_API NEW_OUTPUT')
const base=localBase(baseArg),output=resolve(outputArg),planFile='evaluation/campus/s4-quality/manifest.json',plan=JSON.parse(readFileSync(planFile))
if(existsSync(output))throw Error('Never overwrite evidence')
mkdirSync(output,{recursive:true})
const hash=value=>createHash('sha256').update(value).digest('hex')
const report={schemaVersion:'answer-quality-capture-v1',startedAt:new Date().toISOString(),plan,planSha256:hash(readFileSync(planFile)),
 collectorSha256:hash(readFileSync(fileURLToPath(import.meta.url))),records:plan.cases.flatMap(c=>Array.from({length:plan.repetitions},(_,i)=>({caseId:c.id,repetition:i+1,status:'NOT_RUN',answer:''}))),humanAcceptance:'NOT_PERFORMED'}
const save=()=>writeFileSync(resolve(output,'capture.json'),JSON.stringify(report,null,2)+'\n')
const cookies=new Map()
async function api(path,body,method=body===undefined?'GET':'POST',expected=200) {
 const response=await fetch(base+path,{method,redirect:'error',signal:AbortSignal.timeout(30000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',
 Cookie:[...cookies].map(([n,v])=>`${n}=${v}`).join('; '),...(cookies.get('CSRF-TOKEN')?{'X-CSRF-TOKEN':cookies.get('CSRF-TOKEN')}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 for(const line of response.headers.getSetCookie()){const [n,...v]=line.split(';')[0].split('=');cookies.set(n,v.join('='))}
 if(response.status!==expected)throw Error(`HTTP ${response.status}; expected ${expected}`)
 const text=await response.text(),value=text?JSON.parse(text):null
 return value && Object.hasOwn(value,'code') && Object.hasOwn(value,'data')?value.data:value
}
let active
try {
 if((await api('/auth/session')).mode!=='local')throw Error('Requires dedicated local account mode')
 const username='quality_'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url')
 await api('/auth/local/register',{username,password,nickname:'回答意图回归'},'POST',201);await api('/auth/local/login',{username,password})
 report.runtime=await api('/chat/runtime')
 if(report.runtime.promptVersion!=='campus-s4-intent-v2')throw Error('Requires fixed intent prompt version')
 report.sourceSha256={}
 function walk(directory) {
  for(const entry of readdirSync(directory,{withFileTypes:true})) {
   const file=resolve(directory,entry.name)
   if(entry.isDirectory())walk(file)
   else if(file.endsWith('.java'))report.sourceSha256[relative(process.cwd(),file).replaceAll('\\','/')]=hash(readFileSync(file))
  }
 }
 walk(resolve('src/main/java'));for(const file of ['pom.xml',planFile])report.sourceSha256[file]=hash(readFileSync(file))
 for(const [name,expected]of Object.entries(report.runtime.implementationSha256)) {
  const source=Object.keys(report.sourceSha256).find(p=>p.endsWith(`/${name}.java`))
  if(!source || hash(readFileSync(source.replace('src/main/java/','target/classes/').replace(/\.java$/,'.class')))!==expected)throw Error(`Unattested class: ${name}`)
 }
 const prefix='qa-'+randomBytes(8).toString('hex')
 for(let repetition=1;repetition<=plan.repetitions;repetition++)for(const item of plan.cases) {
  const record=report.records.find(r=>r.caseId===item.id&&r.repetition===repetition),events=[],seen=new Set();let cursor='0',history={}
  try {
   const conversationId=`${prefix}-${repetition}-${item.conversation||item.id}`,path=`/chat/conversations/${conversationId}/constraints`
   let revision=0
   try{revision=(await api(path)).revision}catch(error){if(!error.message.startsWith('HTTP 404'))throw error}
   record.preferences=await api(path,{revision,...item.preferences},'PUT')
   const run=await api('/chat/runs',{prompt:item.prompt,conversationId,gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',
    rulesetVersionId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',language:'zh-CN',timeoutSeconds:120,idempotencyKey:randomBytes(16).toString('hex')},'POST',202)
   active=run.runId;record.runId=run.runId;const deadline=Date.now()+140000;let finished=false
   while(!finished && Date.now()<deadline) {
    const page=await api(`/chat/runs/${run.runId}/events?afterEventId=${cursor}`)
    for(const event of page.events||[]) {
     const id=String(event.eventId);if(seen.has(id))continue;seen.add(id);events.push(event);cursor=id
     if(['run.completed','run.failed','run.cancelled','run.timed_out','approval.required'].includes(event.type)){record.status=event.type==='run.completed'?'COMPLETED':event.type;finished=true}
    }
    if(!finished)await new Promise(done=>setTimeout(done,350))
   }
   if(!finished)throw Error('Missing terminal boundary')
   if(record.status!=='COMPLETED')throw Error('Run did not complete')
   active=null
   const messages=await api(`/chat/conversations/${conversationId}/messages`),user=messages.find(m=>m.role==='user'&&m.runId===run.runId),answer=messages.find(m=>m.role==='assistant'&&m.runId===run.runId)
   history={prompt:user?.content,answer:answer?.content,userPreferences:user?.metadata?.conversationConstraints,
    answerPreferences:answer?.metadata?.conversationConstraints,attempts:answer?.metadata?.modelUsageAttempts||[]}
  }catch(error){record.error=error.message;if(record.status==='NOT_RUN')record.status='COLLECTION_FAILED'}
  finally {
   if(active){try{await api(`/chat/runs/${active}/cancel`,{})}catch{record.cleanupFailed=true}active=null}
   record.answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join('')
   record.modelCalls=attemptsFrom(events).reduce((sum,a)=>sum+a.modelCallCount,0)
   for(const [key,value]of [['events',events],['history',history]]) {
    const bytes=JSON.stringify(value,null,2)+'\n',file=`${item.id}-${repetition}-${key}.json`
    record[`${key}File`]=file;record[`${key}Sha256`]=hash(bytes);writeFileSync(resolve(output,file),bytes)
   }
   save();console.log(`${item.id} #${repetition}: ${record.status}; chars=${[...record.answer.trim()].length}; calls=${record.modelCalls}`)
  }
 }
 report.pass=report.records.every(r=>r.status==='COMPLETED'&&!r.error&&!r.cleanupFailed)
}catch(error){report.setupError=error.message;report.pass=false}
finally{try{await api('/logout',undefined,'POST',204)}catch{report.cleanupFailed=true}report.finishedAt=new Date().toISOString();save()}
if(!report.pass || report.cleanupFailed)process.exitCode=1
