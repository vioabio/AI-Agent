// New isolated accounts, public snapshots and read-only Runs. No credentials are written to evidence.
import fs from 'node:fs'
import path from 'node:path'
import {randomBytes} from 'node:crypto'
import {fileURLToPath} from 'node:url'
import {localBase,digest,sameJson} from './evaluation.mjs'
import {validateSnapshot} from '../reference-cache.mjs'
import {resolveRuntimeClasses} from './reference-case.mjs'
import {attemptsFrom} from './answer-quality.mjs'
import {normalizeModelUsage} from '../../vio-ai-agent-frontend/src/api/model-usage.js'
import {checkLearnsetProjection,auditLearnsetRecord} from './learnset-observations.mjs'

export async function runScenario(baseArg,bundle,output) {
 const base=localBase(baseArg),planFile='evaluation/campus/learnsets-v1/plan.json',plan=JSON.parse(fs.readFileSync(planFile,'utf8'))
 if(fs.existsSync(output))throw Error('Use a new output directory; evidence cannot be overwritten')
 if(plan.schemaVersion!=='learnset-development-plan-v1'||plan.repetitions!==2||plan.cases.length!==3)throw Error('Unexpected fixed plan')
 if(digest(fs.readFileSync(path.join(bundle,'manifest.json')))!==plan.sourceBundleManifestSha256)throw Error('Changed public bundle manifest')
 const sources={}
 for(const [file,expected] of Object.entries(plan.sources)) {
  if(!/^pokemon-(?:squirtle|froslass)\.json$/.test(file))throw Error('Unexpected public source file')
  const raw=fs.readFileSync(path.join(bundle,file));if(digest(raw)!==expected.fileSha256)throw Error('Changed public source file')
  sources[file]=validateSnapshot(raw,file)
 }
 const report={schemaVersion:'learnset-runtime-observations-v1',scope:plan.scope,startedAt:new Date().toISOString(),planSha256:digest(fs.readFileSync(planFile)),
  collectorSha256:Object.fromEntries(['learnset-scenario.mjs','learnset-observations.mjs','tool-inputs.mjs','answer-quality.mjs','evaluation.mjs','reference-case.mjs'].map(f=>[f,digest(fs.readFileSync('tools/campus/'+f))])),
  records:plan.cases.flatMap(c=>Array.from({length:plan.repetitions},(_,i)=>({caseId:c.id,repetition:i+1,status:'NOT_RUN',answer:'',checks:{collected:false}}))),
  httpBefore:[],httpAfter:[],humanReviewed:0,formalAdmissions:0},cookies=new Map()
 fs.mkdirSync(output,{recursive:true})
 let active=null
 const api=async(route,body,method=body===undefined?'GET':'POST',expected=200)=>{
  const r=await fetch(base+route,{method,redirect:'error',signal:AbortSignal.timeout(30000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; '),...(cookies.get('CSRF-TOKEN')?{'X-CSRF-TOKEN':cookies.get('CSRF-TOKEN')}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
  for(const line of r.headers.getSetCookie()){const[k,...v]=line.split(';')[0].split('=');cookies.set(k,v.join('='))}
  if(r.status!==expected)throw Error(`HTTP ${r.status} expected ${expected} at ${route}`)
  if(expected===204)return null
  const value=await r.json();return expected<400&&value&&Object.hasOwn(value,'code')&&Object.hasOwn(value,'data')?value.data:value
 }
 const sourceDigests=()=>{
  const files={};const walk=dir=>{for(const e of fs.readdirSync(dir,{withFileTypes:true})){const f=path.join(dir,e.name);if(e.isDirectory())walk(f);else if(f.endsWith('.java'))files[f.replaceAll('\\','/')]=digest(fs.readFileSync(f))}};walk('src/main/java')
  for(const file of [planFile,'pom.xml','src/main/resources/pokemon-mechanics/references-v1.json','src/main/resources/pokemon-mechanics/references-v1.meta.json','src/main/resources/pokemon-strategy/overview-v1.json','src/main/resources/pokemon-strategy/sources-v1.json','tools/pokemon-engine/calculate.cjs','tools/pokemon-engine/package-lock.json'])files[file]=digest(fs.readFileSync(file))
  return files
 }
 const save=()=>fs.writeFileSync(path.join(output,'observations.json'),JSON.stringify(report,null,2)+'\n')
 const read=async item=>{
  const actual=await api(`/pokemon/reference/learnsets/${item.pokemonId}/${item.moveSlug}?dataReleaseId=pokeapi-reference-v1&gameReleaseId=pokemon-scarlet-violet`)
  const checked=checkLearnsetProjection(actual,item,sources[item.sourceFile]);return {caseId:item.id,actual,...checked}
 }
 try {
  if((await api('/auth/session')).mode!=='local')throw Error('Isolated local account mode required')
  const username='learnset_'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url')
  await api('/auth/local/register',{username,password,nickname:'学习途径验证'},'POST',201);await api('/auth/local/login',{username,password})
  report.runtime=await api('/chat/runtime');report.sourceSha256=sourceDigests()
  if(report.runtime.evaluationPolicy?.mode!=='READ_ONLY_TOOLS'||report.runtime.learnsetReferencePolicy?.schemaVersion!=='pokemon-learnset-facts-v1'
   ||!report.runtime.tools?.includes('lookupPokemonLearnset'))throw Error('Wrong evaluation runtime or missing learnset tool')
  for(const cls of resolveRuntimeClasses(report.sourceSha256,report.runtime.implementationSha256))if(digest(fs.readFileSync(cls.file))!==cls.sha256)throw Error('Stale runtime '+cls.name)
  for(const item of [...plan.cases,...plan.httpOnly])report.httpBefore.push(await read(item))
  if(report.httpBefore.some(r=>!r.pass))throw Error('Source/HTTP preflight mismatch')
  const bad=await api('/pokemon/reference/learnsets/7/water-pulse?dataReleaseId=pokeapi-reference-v1&gameReleaseId=pokemon-sword-shield',undefined,'GET',400)
  report.invalidVersionRejected=bad.code===400
  save()
  for(const row of report.records) {
   const item=plan.cases.find(c=>c.id===row.caseId),conversationId='learnset-'+randomBytes(12).toString('hex'),events=[],seen=new Set()
   row.prompt=item.prompt;const started=Date.now();let cursor='0'
   try {
    const run=await api('/chat/runs',{prompt:item.prompt,conversationId,gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',rulesetVersionId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',language:'zh-CN',timeoutSeconds:120,idempotencyKey:randomBytes(16).toString('hex')},'POST',202)
    active=run.runId;row.runId=active;const deadline=Date.now()+140000;let done=false
    while(!done&&Date.now()<deadline){const page=await api(`/chat/runs/${active}/events?afterEventId=${cursor}`);for(const e of page.events||[]){const id=String(e.eventId);if(seen.has(id))continue;seen.add(id);events.push(e);cursor=id;if(['run.completed','run.failed','run.timed_out','run.cancelled','approval.required'].includes(e.type)){row.terminal=e.type;done=true}}if(!done)await new Promise(r=>setTimeout(r,350))}
    if(!done)throw Error('Local collection deadline exceeded')
    row.status=row.terminal==='run.completed'?'COMPLETED':row.terminal.toUpperCase();active=null
    row.answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join('')
    const history=await api(`/chat/conversations/${conversationId}/messages`),answer=history.find(m=>m.role==='assistant'&&m.runId===row.runId),attempts=attemptsFrom(events)
    row.modelUsageAttempts=attempts;row.historyMatches=answer?.content===row.answer&&sameJson(attempts.map(normalizeModelUsage),(answer?.metadata?.modelUsageAttempts||[]).map(normalizeModelUsage))
    row.actualModelCalls=attempts.reduce((n,a)=>n+a.modelCallCount,0)
    const expected=report.httpBefore.find(r=>r.caseId===item.id).actual;row.audit=auditLearnsetRecord(row,events,item,expected)
    row.checks={collected:true,...row.audit.checks,historyMatches:row.historyMatches};row.pass=Object.values(row.checks).every(v=>v===true)
   }catch(error){row.status='COLLECTION_FAILED';row.pass=false;row.error=error.message}
   finally{
    if(active){try{await api(`/chat/runs/${active}/cancel`,{});row.cancelledAfterCollectionFailure=true}catch{report.cleanupFailed=true}active=null}
    const file=`${item.id}-${row.repetition}-events.json`,raw=JSON.stringify(events,null,2)+'\n';fs.writeFileSync(path.join(output,file),raw,{flag:'wx'});row.eventsFile=file;row.eventsSha256=digest(raw);row.elapsedMs=Date.now()-started;save()
   }
   console.log(JSON.stringify({caseId:row.caseId,repetition:row.repetition,status:row.status,pass:row.pass,modelCalls:row.actualModelCalls,checks:row.checks}))
  }
  for(const item of [...plan.cases,...plan.httpOnly])report.httpAfter.push(await read(item))
  report.sourcesUnchanged=sameJson(report.sourceSha256,sourceDigests());report.runtimeAfter=await api('/chat/runtime');report.runtimeUnchanged=sameJson(report.runtime.implementationSha256,report.runtimeAfter.implementationSha256)
 }catch(error){report.error=error.message}
 finally{
  if(active)try{await api(`/chat/runs/${active}/cancel`,{})}catch{report.cleanupFailed=true}
  try{await api('/logout',undefined,'POST',204)}catch{report.cleanupFailed=true}
  report.finishedAt=new Date().toISOString();report.summary={planned:report.records.length,completed:report.records.filter(r=>r.status==='COMPLETED').length,engineeringPass:report.records.filter(r=>r.pass).length,
   httpBefore:report.httpBefore.filter(r=>r.pass).length,httpAfter:report.httpAfter.filter(r=>r.pass).length,actualModelCalls:report.records.reduce((n,r)=>n+(r.actualModelCalls||0),0),humanReviewed:0,formalAdmissions:0}
  report.pass=!report.error&&!report.cleanupFailed&&report.invalidVersionRejected&&report.sourcesUnchanged&&report.runtimeUnchanged&&report.summary.engineeringPass===report.summary.planned&&report.summary.httpAfter===4;save()
  fs.writeFileSync(path.join(output,'answers.md'),report.records.map(r=>`## ${r.caseId} / ${r.repetition}\n\n${r.prompt||''}\n\n${r.answer}\n`).join('\n'),{flag:'wx'})
 }
 console.log(JSON.stringify({...report.summary,pass:report.pass,error:report.error}));if(!report.pass)process.exitCode=1;return report
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){const [base,bundle,out,...extra]=process.argv.slice(2);if(!base||!bundle||!out||extra.length)throw Error('Usage: node tools/campus/learnset-scenario.mjs ISOLATED_LOCAL_API PUBLIC_BUNDLE NEW_OUTPUT_DIRECTORY');await runScenario(base,bundle,out)}
