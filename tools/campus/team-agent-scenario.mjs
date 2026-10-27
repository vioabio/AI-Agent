import fs from 'node:fs'
import {randomBytes} from 'node:crypto'
import {localBase,digest,sameJson,canonical} from './evaluation.mjs'
import {attemptsFrom} from './answer-quality.mjs'
import {resolveRuntimeClasses} from './reference-case.mjs'
import {collectToolInputs,checkToolInput} from './tool-inputs.mjs'
const [baseArg,output,...extra]=process.argv.slice(2)
if(!baseArg||!output||extra.length||fs.existsSync(output))throw Error('Use a fresh output directory and dedicated loopback backend')
const base=localBase(baseArg);if(new URL(base).port!=='18219')throw Error('Only dedicated port 18219 is supported')
const plan=JSON.parse(fs.readFileSync('evaluation/campus/team-agent-v1/plan.json','utf8'))
if(plan.cases.length!==2||plan.repetitions!==1||digest(fs.readFileSync('artifacts/campus/user-reports/public-bundle-2026-10-26/manifest.json'))!==plan.sourceBundleManifestSha256)throw Error('Changed fixed plan or source bundle')
fs.mkdirSync(output,{recursive:true});const cookies=new Map(),report={schemaVersion:'team-agent-observations-v1',startedAt:new Date().toISOString(),planSha256:digest(fs.readFileSync('evaluation/campus/team-agent-v1/plan.json')),collectorSha256:digest(fs.readFileSync('tools/campus/team-agent-scenario.mjs')),records:[],humanReviewed:0,formalAdmissions:0};let active=null
const save=()=>fs.writeFileSync(output+'/observations.json',JSON.stringify(report,null,2)+'\n')
async function api(route,body,method=body===undefined?'GET':'POST',expected=200){
 const r=await fetch(base+route,{method,redirect:'error',signal:AbortSignal.timeout(30000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; '),...(cookies.get('CSRF-TOKEN')?{'X-CSRF-TOKEN':cookies.get('CSRF-TOKEN')}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 for(const line of r.headers.getSetCookie()){const[k,...v]=line.split(';')[0].split('=');cookies.set(k,v.join('='))}
 if(r.status!==expected)throw Error(`HTTP ${r.status} expected ${expected} at ${route}`);if(expected===204)return null
 const v=await r.json();return expected<400&&v&&Object.hasOwn(v,'code')&&Object.hasOwn(v,'data')?v.data:v
}
function sourceHashes(){const result={};function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const path=dir+'/'+e.name;if(e.isDirectory())walk(path);else if(path.endsWith('.java'))result[path]=digest(fs.readFileSync(path))}}walk('src/main/java');return result}
const build={moves:['water-pulse','ice-beam','protect','surf'],ability:'torrent',item:'',nature:'modest',level:50,evs:{hp:0,atk:0,def:0,spa:252,spd:4,spe:252},ivs:{hp:31,atk:31,def:31,spa:31,spd:31,spe:31}}
try{
 if((await api('/auth/session')).mode!=='local')throw Error('Dedicated local account mode required')
 const username='team_agent_'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url');await api('/auth/local/register',{username,password,nickname:'整队只读验证'},'POST',201);await api('/auth/local/login',{username,password})
 report.runtime=await api('/chat/runtime');report.sourceSha256=sourceHashes()
 if(!report.runtime.tools.includes('checkPokemonTeamBuild')||!report.runtime.tools.includes('readCurrentTeam')||report.runtime.teamBuildCheckPolicy?.answerVersion!=='bound-team-build-answer-v1')throw Error('Wrong runtime tools or answer version')
 for(const cls of resolveRuntimeClasses(report.sourceSha256,report.runtime.implementationSha256))if(digest(fs.readFileSync(cls.file))!==cls.sha256)throw Error('Stale class '+cls.name)
 const created=await api('/teams',{name:'整队只读验证队',rulesetId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',members:[{slot:1,formId:7,build},{slot:2,formId:478}]},'POST',201);report.teamBefore=await api('/teams/'+created.id)
 const draft={dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet',members:[{slot:1,pokemonId:7,build},{slot:2,pokemonId:478,build:null}]}
 const saved={...draft,members:report.teamBefore.members.map(m=>({slot:m.slot,pokemonId:m.formId,build:m.build??null}))}
 for(const item of plan.cases){
  const request=item.mode==='saved'?saved:draft,row={caseId:item.id,request,status:'NOT_RUN'},events=[],seen=new Set();let cursor='0';report.records.push(row)
  try{
   row.httpBefore=await api('/pokemon/reference/team-build-check',request)
   row.prompt=(item.mode==='saved'?`请先调用readCurrentTeam读取当前账号已保存队伍编号 ${created.id}，再检查整队配置。以下是该队伍当前实际配置。\n`:'请检查整队配置。\n')+'保留原位置和未填写字段，只核对，不保存、不生成修改预览；逐项匹配不证明联合可得或赛制合法。\n'+JSON.stringify(request)
   const conversationId='team-agent-'+randomBytes(12).toString('hex'),run=await api('/chat/runs',{prompt:row.prompt,conversationId,gameReleaseId:request.gameReleaseId,battleFormat:'casual-singles',rulesetVersionId:'casual-v1',dataReleaseId:request.dataReleaseId,language:'zh-CN',timeoutSeconds:plan.timeoutSeconds,idempotencyKey:randomBytes(16).toString('hex')},'POST',202)
   active=run.runId;row.runId=active;const deadline=Date.now()+140000
   while(!row.terminal&&Date.now()<deadline){const page=await api(`/chat/runs/${active}/events?afterEventId=${cursor}`);for(const event of page.events||[]){const id=String(event.eventId);if(seen.has(id))continue;seen.add(id);events.push(event);cursor=id;if(['run.completed','run.failed','run.timed_out','run.cancelled','approval.required'].includes(event.type))row.terminal=event.type}if(!row.terminal)await new Promise(r=>setTimeout(r,350))}
   if(!row.terminal||row.terminal==='approval.required')throw Error('Read-only task did not reach a safe terminal')
   active=null;row.status=row.terminal==='run.completed'?'COMPLETED':row.terminal;row.answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join('');row.modelUsageAttempts=attemptsFrom(events);row.actualModelCalls=row.modelUsageAttempts.reduce((sum,a)=>sum+a.modelCallCount,0)
   const finished=events.filter(e=>e.type==='tool.finished'),proofs=finished.filter(e=>e.payload?.toolName==='checkPokemonTeamBuild').map(e=>e.payload)
   const history=await api(`/chat/conversations/${conversationId}/messages`),answer=history.find(m=>m.role==='assistant'&&m.runId===row.runId);row.httpAfter=await api('/pokemon/reference/team-build-check',request)
   row.checks={completed:row.status==='COMPLETED',requiredTools:item.requiredTools.every(tool=>finished.some(e=>e.payload?.toolName===tool)),boundOutput:proofs.length>0&&proofs.every(p=>p.outputEvidence?.schemaVersion==='team-build-output-evidence-v1'&&p.outputEvidence.status==='VERIFIED'&&p.outputEvidence.reportSha256===row.httpBefore.reportSha256&&p.outputEvidence.parameterDigest===p.parameterDigest&&p.outputEvidence.jointStatus==='NOT_VERIFIED'),requestPreserved:sameJson(row.httpBefore.request,request),httpStable:sameJson(row.httpBefore,row.httpAfter),memberCount:row.answer.includes('已核对 2 位'),jointLimit:row.answer.includes('联合可得性未验证'),noInternalIds:!/(?:sha256:|reportSha256|dataReleaseId)/.test(row.answer),historyMatches:answer?.content===row.answer,noApproval:!events.some(e=>e.type==='approval.required'),noWriteTools:!finished.some(e=>/preview|applyConfirmed|fileWrite|terminal/i.test(e.payload?.toolName||'')),realModelObserved:row.actualModelCalls>0}
   row.checks.correctSavedRead=item.mode!=='saved'||checkToolInput(collectToolInputs(events),{tool:'readCurrentTeam',fields:{'/teamId':created.id}}).pass
   row.pass=Object.values(row.checks).every(Boolean)
  }catch(error){row.error=error.message;row.pass=false;row.status='COLLECTION_FAILED'}
  finally{if(active){try{await api(`/chat/runs/${active}/cancel`,{});row.cancelled=true}catch{report.cleanupFailed=true}active=null}const raw=JSON.stringify(events,null,2)+'\n';row.eventsFile=item.id+'-events.json';row.eventsSha256=digest(raw);fs.writeFileSync(output+'/'+row.eventsFile,raw,{flag:'wx'});save()}
  console.log(JSON.stringify({caseId:row.caseId,status:row.status,pass:row.pass,modelCalls:row.actualModelCalls,error:row.error,checks:row.checks}))
 }
 report.teamAfter=await api('/teams/'+created.id);report.savedTeamUnchanged=sameJson(report.teamBefore,report.teamAfter);report.sourcesUnchanged=sameJson(report.sourceSha256,sourceHashes());report.runtimeAfter=await api('/chat/runtime');report.runtimeUnchanged=sameJson(report.runtime.implementationSha256,report.runtimeAfter.implementationSha256)
}catch(error){report.error=error.message}
finally{try{await api('/logout',undefined,'POST',204)}catch{report.cleanupFailed=true}report.finishedAt=new Date().toISOString();report.pass=!report.error&&!report.cleanupFailed&&report.records.length===2&&report.records.every(r=>r.pass)&&report.savedTeamUnchanged&&report.sourcesUnchanged&&report.runtimeUnchanged;save();console.log(JSON.stringify({pass:report.pass,completed:report.records.filter(r=>r.pass).length,planned:2,error:report.error}));if(!report.pass)process.exitCode=1}
