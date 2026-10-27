// Isolated local account; writes only a new test fixture before read-only checks. No credentials in evidence.
import fs from 'node:fs'
import path from 'node:path'
import {randomBytes} from 'node:crypto'
import {fileURLToPath} from 'node:url'
import {localBase,digest,canonical,sameJson} from './evaluation.mjs'
import {resolveRuntimeClasses} from './reference-case.mjs'
import {attemptsFrom} from './answer-quality.mjs'
import {normalizeModelUsage} from '../../vio-ai-agent-frontend/src/api/model-usage.js'
import {auditBuildCheck} from './build-check-observations.mjs'
import {auditLearningNotes} from './learning-review-observations.mjs'
import {validateSnapshot} from '../reference-cache.mjs'

export async function runScenario(baseArg,output,planFile='evaluation/campus/build-check-v1/plan.json') {
 const base=localBase(baseArg),plan=JSON.parse(fs.readFileSync(planFile,'utf8')),learningPlan=plan.schemaVersion==='learning-review-development-plan-v1'
 if(fs.existsSync(output))throw Error('Use a new output directory; never overwrite evidence')
 if((!learningPlan&&plan.schemaVersion!=='build-check-development-plan-v1')||plan.cases.length!==(learningPlan?2:3)||plan.repetitions!==2)throw Error('Unexpected fixed plan')
 let learningSource=null
 if(learningPlan){const dir='artifacts/campus/user-reports/public-bundle-2026-10-26',raw=fs.readFileSync(dir+'/pokemon-squirtle.json');if(digest(raw)!==plan.sourceFileSha256||digest(fs.readFileSync(dir+'/manifest.json'))!==plan.sourceBundleManifestSha256)throw Error('Changed fixed public source');learningSource=validateSnapshot(raw,'pokemon-squirtle.json')}
 fs.mkdirSync(output,{recursive:true});const cookies=new Map(),report={schemaVersion:'build-check-runtime-v1',scope:plan.scope,startedAt:new Date().toISOString(),planSha256:digest(fs.readFileSync(planFile)),
  collectorSha256:Object.fromEntries(['build-check-scenario.mjs','build-check-observations.mjs','learning-review-observations.mjs','tool-inputs.mjs','answer-quality.mjs','evaluation.mjs','reference-case.mjs'].map(f=>[f,digest(fs.readFileSync('tools/campus/'+f))])),
  records:plan.cases.flatMap(c=>Array.from({length:2},(_,i)=>({caseId:c.id,repetition:i+1,status:'NOT_RUN',answer:'',checks:{collected:false}}))),httpBefore:[],httpAfter:[],humanReviewed:0,formalAdmissions:0}
 let active=null
 if(learningPlan)report.collectorSha256['learning-review-scenario.mjs']=digest(fs.readFileSync('tools/campus/learning-review-scenario.mjs'))
 const api=async(route,body,method=body===undefined?'GET':'POST',expected=200)=>{
  const r=await fetch(base+route,{method,redirect:'error',signal:AbortSignal.timeout(30000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; '),...(cookies.get('CSRF-TOKEN')?{'X-CSRF-TOKEN':cookies.get('CSRF-TOKEN')}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
  for(const line of r.headers.getSetCookie()){const[k,...v]=line.split(';')[0].split('=');cookies.set(k,v.join('='))}
  if(r.status!==expected)throw Error(`HTTP ${r.status} expected ${expected} at ${route}`)
  if(expected===204)return null
  const value=await r.json();return expected<400&&value&&Object.hasOwn(value,'code')&&Object.hasOwn(value,'data')?value.data:value
 }
 const sourceDigests=()=>{const files={};const walk=(dir,select)=>{for(const e of fs.readdirSync(dir,{withFileTypes:true})){const f=path.join(dir,e.name);if(e.isDirectory())walk(f,select);else if(select(f))files[f.replaceAll('\\','/')]=digest(fs.readFileSync(f))}};walk('src/main/java',f=>f.endsWith('.java'));for(const dir of ['pokemon-mechanics-independent','pokemon-mechanics','pokemon-strategy','pokemon-guides'])walk('src/main/resources/'+dir,()=>true);for(const f of [planFile,'pom.xml','tools/pokemon-engine/calculate.cjs','tools/pokemon-engine/package-lock.json'])files[f]=digest(fs.readFileSync(f));return files}
 const save=()=>fs.writeFileSync(path.join(output,'observations.json'),JSON.stringify(report,null,2)+'\n')
 const read=async item=>{
  const actual=await api('/pokemon/reference/build-check',item.request),copy={...actual};delete copy.reportSha256
  const sourceRowsMatched=!learningPlan||actual.source?.pokemonChecksum===learningSource.envelope.sha256&&actual.moves.every(m=>{
   const raw=JSON.parse(learningSource.envelope.payload),source=raw.moves.find(r=>r.move.name===m.normalized),rows=(source?.version_group_details||[]).filter(v=>v.version_group.name==='scarlet-violet').map(v=>({methodKey:v.move_learn_method.name,levelLearnedAt:v.level_learned_at,order:v.order??null})),sort=a=>a.map(canonical).sort((a,b)=>JSON.stringify(a).localeCompare(JSON.stringify(b)))
   return sameJson(sort(m.learning?.entries||[]),sort(rows))
  })
  return {caseId:item.id,sourceRowsMatched,pass:sourceRowsMatched&&actual.schemaVersion==='pokemon-build-check-v1'&&actual.scope==='SUPPLIED_FIELDS_AND_REFERENCE_MEMBERSHIP_NOT_JOINT_LEGALITY'&&sameJson(actual.request,item.request)&&actual.status===item.expectedStatus&&actual.abilityStatus===item.expectedAbility&&sameJson(actual.moves.map(m=>m.status),item.expectedRows)&&actual.reportSha256==='sha256:'+digest(JSON.stringify(canonical(copy)))&&actual.source?.pokemonId===item.request.pokemonId&&actual.moves.every(m=>m.learning?.sourceChecksum===actual.source.pokemonChecksum),actual}
 }
 try {
  if((await api('/auth/session')).mode!=='local')throw Error('New isolated local accounts required')
  const username='build_'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url')
  await api('/auth/local/register',{username,password,nickname:'配招核对验证'},'POST',201);await api('/auth/local/login',{username,password})
  report.runtime=await api('/chat/runtime');report.sourceSha256=sourceDigests()
  report.sourceSha256['vio-ai-agent-frontend/src/api/build-learning.js']=digest(fs.readFileSync('vio-ai-agent-frontend/src/api/build-learning.js'))
  if(report.runtime.evaluationPolicy?.mode!=='READ_ONLY_TOOLS'||report.runtime.buildCheckPolicy?.schemaVersion!=='pokemon-build-check-v1'||report.runtime.buildCheckPolicy.writesTeams!==false||!report.runtime.tools?.includes('checkPokemonBuild'))throw Error('Wrong runtime policy')
  if(learningPlan&&report.runtime.buildCheckPolicy.learningNotesVersion!=='recorded-learning-level-notes-v1')throw Error('Wrong learning notes runtime')
  report.executionMode=report.runtime.buildCheckPolicy.requestRouteVersion==='explicit-build-request-route-v1'?'GOVERNED_SUPPLIED_BUILD_PROGRAM_ROUTE':'MODEL_TOOL_PLANNING'
  for(const cls of resolveRuntimeClasses(report.sourceSha256,report.runtime.implementationSha256))if(digest(fs.readFileSync(cls.file))!==cls.sha256)throw Error('Stale runtime '+cls.name)
  const team=await api('/teams',{name:'只读核对专用练习队',rulesetId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',members:[{slot:1,formId:7,build:plan.cases[0].request.build}]},'POST',201)
  report.teamBefore=await api(`/teams/${team.id}`)
  for(const item of plan.cases)report.httpBefore.push(await read(item))
  if(report.httpBefore.some(r=>!r.pass))throw Error('HTTP/source preflight mismatch')
  report.invalidVersionRejected=(await api('/pokemon/reference/build-check',{...plan.cases[0].request,gameReleaseId:'pokemon-sword-shield'},'POST',400)).code===400
  report.numericCoercionRejected=(await api('/pokemon/reference/build-check',{...plan.cases[0].request,pokemonId:'7'},'POST',400)).code===400
  save()
  for(const row of report.records){const item=plan.cases.find(c=>c.id===row.caseId),expected=report.httpBefore.find(r=>r.caseId===item.id).actual,conversationId='build-'+randomBytes(12).toString('hex'),events=[],seen=new Set();let cursor='0';row.prompt=item.prompt;const started=Date.now()
   try {
    const run=await api('/chat/runs',{prompt:item.prompt,conversationId,gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',rulesetVersionId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',language:'zh-CN',timeoutSeconds:120,idempotencyKey:randomBytes(16).toString('hex')},'POST',202)
    active=run.runId;row.runId=active;let done=false;const deadline=Date.now()+140000
    while(!done&&Date.now()<deadline){const page=await api(`/chat/runs/${active}/events?afterEventId=${cursor}`);for(const e of page.events||[]){const id=String(e.eventId);if(seen.has(id))continue;seen.add(id);events.push(e);cursor=id;if(['run.completed','run.failed','run.timed_out','run.cancelled','approval.required'].includes(e.type)){row.terminal=e.type;done=true}}if(!done)await new Promise(r=>setTimeout(r,350))}
    if(!done||row.terminal==='approval.required')throw Error('Read-only task did not reach an allowed terminal')
    row.status=row.terminal==='run.completed'?'COMPLETED':row.terminal.toUpperCase();active=null;row.answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join('')
    const history=await api(`/chat/conversations/${conversationId}/messages`),answer=history.find(m=>m.role==='assistant'&&m.runId===row.runId),attempts=attemptsFrom(events)
    row.modelUsageAttempts=attempts;row.actualModelCalls=attempts.reduce((n,a)=>n+a.modelCallCount,0);row.audit=auditBuildCheck(row,events,expected)
    row.checks={collected:true,...row.audit.checks,historyMatches:answer?.content===row.answer&&sameJson(attempts.map(normalizeModelUsage),(answer?.metadata?.modelUsageAttempts||[]).map(normalizeModelUsage))};row.pass=Object.values(row.checks).every(v=>v===true)
    if(learningPlan){row.learningAudit=auditLearningNotes(row.answer,expected,item.expected);Object.assign(row.checks,row.learningAudit.checks);row.pass=Object.values(row.checks).every(v=>v===true)}
    row.selectionOrigins=[...new Set(events.filter(e=>e.type==='tool.finished'&&e.payload?.toolName==='checkPokemonBuild').map(e=>e.payload.selectionOrigin||'MODEL_TOOL_SELECTION'))]
    if(report.executionMode==='GOVERNED_SUPPLIED_BUILD_PROGRAM_ROUTE'){row.checks.programOriginAndNoModel=row.actualModelCalls===0&&row.selectionOrigins.length===1&&row.selectionOrigins[0]==='PROGRAM_SUPPLIED_BUILD_REQUEST';row.pass=Object.values(row.checks).every(v=>v===true)}
   }catch(error){row.status='COLLECTION_FAILED';row.pass=false;row.error=error.message}
   finally{if(active){try{await api(`/chat/runs/${active}/cancel`,{});row.cancelledAfterCollectionFailure=true}catch{report.cleanupFailed=true}active=null}const file=`${item.id}-${row.repetition}-events.json`,raw=JSON.stringify(events,null,2)+'\n';fs.writeFileSync(path.join(output,file),raw,{flag:'wx'});row.eventsFile=file;row.eventsSha256=digest(raw);row.elapsedMs=Date.now()-started;save()}
   console.log(JSON.stringify({caseId:row.caseId,repetition:row.repetition,status:row.status,pass:row.pass,modelCalls:row.actualModelCalls,checks:row.checks}))
  }
  for(const item of plan.cases)report.httpAfter.push(await read(item))
  report.teamAfter=await api(`/teams/${team.id}`);report.savedTeamUnchanged=sameJson(report.teamBefore,report.teamAfter)
  const afterSources=sourceDigests();afterSources['vio-ai-agent-frontend/src/api/build-learning.js']=digest(fs.readFileSync('vio-ai-agent-frontend/src/api/build-learning.js'));report.sourcesUnchanged=sameJson(report.sourceSha256,afterSources);report.runtimeAfter=await api('/chat/runtime');report.runtimeUnchanged=sameJson(report.runtime.implementationSha256,report.runtimeAfter.implementationSha256)
 }catch(error){report.error=error.message}
 finally{if(active)try{await api(`/chat/runs/${active}/cancel`,{})}catch{report.cleanupFailed=true}try{await api('/logout',undefined,'POST',204)}catch{report.cleanupFailed=true}
  report.finishedAt=new Date().toISOString();report.summary={planned:report.records.length,completed:report.records.filter(r=>r.status==='COMPLETED').length,engineeringPass:report.records.filter(r=>r.pass).length,httpBefore:report.httpBefore.filter(r=>r.pass).length,httpAfter:report.httpAfter.filter(r=>r.pass).length,
   uniqueBuildChecks:report.records.reduce((n,r)=>n+(r.audit?.outputs.length||0),0),finishedNotifications:report.records.reduce((n,r)=>n+(r.audit?.outputs.reduce((s,c)=>s+c.notifications,0)||0),0),actualModelCalls:report.records.reduce((n,r)=>n+(r.actualModelCalls||0),0),humanReviewed:0,formalAdmissions:0}
  report.pass=!report.error&&!report.cleanupFailed&&report.savedTeamUnchanged&&report.sourcesUnchanged&&report.runtimeUnchanged&&report.invalidVersionRejected&&report.numericCoercionRejected&&report.summary.engineeringPass===report.records.length&&report.summary.httpBefore===plan.cases.length&&report.summary.httpAfter===plan.cases.length;save();fs.writeFileSync(path.join(output,'answers.md'),report.records.map(r=>`## ${r.caseId} / ${r.repetition}\n\n${r.prompt||''}\n\n${r.answer}\n`).join('\n'),{flag:'wx'})
 }
 console.log(JSON.stringify({...report.summary,pass:report.pass,error:report.error}));if(!report.pass)process.exitCode=1;return report
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){const [base,out,...extra]=process.argv.slice(2);if(!base||!out||extra.length)throw Error('Usage: node tools/campus/build-check-scenario.mjs ISOLATED_LOCAL_API NEW_OUTPUT_DIRECTORY');await runScenario(base,out)}
