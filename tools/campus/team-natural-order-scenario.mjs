import fs from 'node:fs'
import {randomBytes} from 'node:crypto'
import {localBase,digest,sameJson,canonical} from './evaluation.mjs'
import {attemptsFrom} from './answer-quality.mjs'
import {resolveRuntimeClasses} from './reference-case.mjs'
import {collectToolInputs,checkToolInput} from './tool-inputs.mjs'
import {auditTeamPlanning} from './team-planning.mjs'
import {auditTeamPlanningWithReferences} from './team-planning-reference.mjs'
import {fixedNaturalInputs,waitForNaturalSession,requireNaturalRuntime} from './team-natural-preflight.mjs'
const [baseArg,output,...extra]=process.argv.slice(2)
if(!baseArg||!output||extra.length||fs.existsSync(output))throw Error('Use a fresh output directory and dedicated loopback backend')
const base=localBase(baseArg);if(new URL(base).port!=='18249')throw Error('Only dedicated port 18249 is supported')
const {plan,protocol}=fixedNaturalInputs(fs.readFileSync('evaluation/campus/team-natural-v1/cases.json','utf8'),fs.readFileSync('evaluation/campus/team-planning-reference-v2/protocol.json','utf8'))
if(plan.cases.length!==3||plan.repetitions!==1||digest(fs.readFileSync('artifacts/campus/user-reports/public-bundle-2026-10-26/manifest.json'))!=='46e8d9080f671d371b5ae31584b8cca5e548542bfc9bf7f5ae18fe8e3caf5a72')throw Error('Changed fixed plan or source bundle')
fs.mkdirSync(output,{recursive:true});const cookies=new Map(),report={schemaVersion:'team-agent-observations-v1',startedAt:new Date().toISOString(),planSha256:digest(fs.readFileSync('evaluation/campus/team-natural-v1/cases.json')),collectorSha256:digest(fs.readFileSync('tools/campus/team-natural-order-scenario.mjs')),records:[],humanReviewed:0,formalAdmissions:0};let active=null,authenticated=false
const save=()=>fs.writeFileSync(output+'/observations.json',JSON.stringify(report,null,2)+'\n')
async function api(route,body,method=body===undefined?'GET':'POST',expected=200,timeoutMs=30000){
 const r=await fetch(base+route,{method,redirect:'error',signal:AbortSignal.timeout(timeoutMs),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; '),...(cookies.get('CSRF-TOKEN')?{'X-CSRF-TOKEN':cookies.get('CSRF-TOKEN')}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 for(const line of r.headers.getSetCookie()){const[k,...v]=line.split(';')[0].split('=');cookies.set(k,v.join('='))}
 if(r.status!==expected)throw Error(`HTTP ${r.status} expected ${expected} at ${route}`);if(expected===204)return null
 const v=await r.json();return expected<400&&v&&Object.hasOwn(v,'code')&&Object.hasOwn(v,'data')?v.data:v
}
function sourceHashes(){const result={};function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const path=dir+'/'+e.name;if(e.isDirectory())walk(path);else if(path.endsWith('.java'))result[path]=digest(fs.readFileSync(path))}}walk('src/main/java');return result}
const build={moves:['water-pulse','ice-beam','protect','surf'],ability:'torrent',item:'',nature:'modest',level:50,evs:{hp:0,atk:0,def:0,spa:252,spd:4,spe:252},ivs:{hp:31,atk:31,def:31,spa:31,spd:31,spe:31}}
try{
 report.readiness=await waitForNaturalSession(base,()=>api('/auth/session',undefined,'GET',200,2000))
 report.referenceProtocolSha256=digest(fs.readFileSync('evaluation/campus/team-planning-reference-v2/protocol.json'))
 report.preflightSha256=digest(fs.readFileSync('tools/campus/team-natural-preflight.mjs'))
 const username='team_agent_'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url');await api('/auth/local/register',{username,password,nickname:'整队只读验证'},'POST',201);await api('/auth/local/login',{username,password});authenticated=true
 report.runtime=await api('/chat/runtime');report.sourceSha256=sourceHashes()
 requireNaturalRuntime(report.runtime)
 for(const cls of resolveRuntimeClasses(report.sourceSha256,report.runtime.implementationSha256))if(digest(fs.readFileSync(cls.file))!==cls.sha256)throw Error('Stale class '+cls.name)
 const created=await api('/teams',{name:'整队只读验证队',rulesetId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',members:[{slot:1,formId:7,build},{slot:2,formId:478}]},'POST',201);report.teamBefore=await api('/teams/'+created.id)
 for(const item of plan.cases){
  const request=item.expectedRequest,row={caseId:item.id,request,status:'NOT_RUN'},events=[],seen=new Set();let cursor='0';report.records.push(row)
  try{
   row.httpBefore=await api('/pokemon/reference/team-build-check',request)
   row.prompt=item.prompt
   const conversationId='team-agent-'+randomBytes(12).toString('hex'),run=await api('/chat/runs',{prompt:row.prompt,conversationId,gameReleaseId:request.gameReleaseId,battleFormat:'casual-singles',rulesetVersionId:'casual-v1',dataReleaseId:request.dataReleaseId,language:'zh-CN',timeoutSeconds:120,idempotencyKey:randomBytes(16).toString('hex')},'POST',202)
   active=run.runId;row.runId=active;const deadline=Date.now()+140000
   while(!row.terminal&&Date.now()<deadline){const page=await api(`/chat/runs/${active}/events?afterEventId=${cursor}`);for(const event of page.events||[]){const id=String(event.eventId);if(seen.has(id))continue;seen.add(id);events.push(event);cursor=id;if(['run.completed','run.failed','run.timed_out','run.cancelled','approval.required'].includes(event.type))row.terminal=event.type}if(!row.terminal)await new Promise(r=>setTimeout(r,350))}
   if(!row.terminal||row.terminal==='approval.required')throw Error('Read-only task did not reach a safe terminal')
   active=null;row.status=row.terminal==='run.completed'?'COMPLETED':row.terminal;row.answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join('');row.modelUsageAttempts=attemptsFrom(events);row.actualModelCalls=row.modelUsageAttempts.reduce((sum,a)=>sum+a.modelCallCount,0)
   const finished=events.filter(e=>e.type==='tool.finished'),proofs=finished.filter(e=>e.payload?.toolName==='checkPokemonTeamBuild').map(e=>e.payload)
   const history=await api(`/chat/conversations/${conversationId}/messages`),answer=history.find(m=>m.role==='assistant'&&m.runId===row.runId);row.httpAfter=await api('/pokemon/reference/team-build-check',request)
   row.checks={completed:row.status==='COMPLETED',requiredTools:['checkPokemonTeamBuild'].every(tool=>finished.some(e=>e.payload?.toolName===tool)),boundOutput:proofs.length>0&&proofs.every(p=>p.outputEvidence?.schemaVersion==='team-build-output-evidence-v1'&&p.outputEvidence.status==='VERIFIED'&&p.outputEvidence.reportSha256===row.httpBefore.reportSha256&&p.outputEvidence.parameterDigest===p.parameterDigest&&p.outputEvidence.jointStatus==='NOT_VERIFIED'),requestPreserved:sameJson(row.httpBefore.request,request),httpStable:sameJson(row.httpBefore,row.httpAfter),memberCount:row.answer.includes('已核对 '+request.members.length+' 位'),jointLimit:row.answer.includes('联合可得性未验证'),noInternalIds:!/(?:sha256:|reportSha256|dataReleaseId)/.test(row.answer),historyMatches:answer?.content===row.answer,noApproval:!events.some(e=>e.type==='approval.required'),noWriteTools:!finished.some(e=>/preview|applyConfirmed|fileWrite|terminal/i.test(e.payload?.toolName||'')),executionObserved:item.mode==='draft'?row.actualModelCalls===0:row.actualModelCalls>0}
   row.checks.correctSavedRead=item.mode!=='saved'||checkToolInput(collectToolInputs(events),{tool:'readCurrentTeam',fields:{'/teamId':created.id}}).pass
   row.executionMode=item.executionMode
   row.selectionOrigins=[...new Set(proofs.map(p=>p.selectionOrigin||'MODEL_TOOL_SELECTION'))]
   row.checks.selectionOrigin=item.mode==='draft'?row.selectionOrigins.length===1&&row.selectionOrigins[0]==='PROGRAM_SUPPLIED_TEAM_REQUEST':row.selectionOrigins.every(origin=>origin==='MODEL_TOOL_SELECTION')
   row.planningAudit=auditTeamPlanning(events,{request,reportSha256:row.httpBefore.reportSha256,mode:item.executionMode})
   row.referencePlanningAudit=auditTeamPlanningWithReferences(events,{request,reportSha256:row.httpBefore.reportSha256,mode:item.executionMode},protocol.queriesByPokemonId)
   row.checks.strictPlanning=row.planningAudit.pass
   row.pass=Object.values(row.checks).every(Boolean)
  }catch(error){row.error=error.message;row.pass=false;row.status='COLLECTION_FAILED'}
  finally{if(active){try{await api(`/chat/runs/${active}/cancel`,{});row.cancelled=true}catch{report.cleanupFailed=true}active=null}const raw=JSON.stringify(events,null,2)+'\n';row.eventsFile=item.id+'-events.json';row.eventsSha256=digest(raw);fs.writeFileSync(output+'/'+row.eventsFile,raw,{flag:'wx'});save()}
  console.log(JSON.stringify({caseId:row.caseId,status:row.status,pass:row.pass,modelCalls:row.actualModelCalls,error:row.error,checks:row.checks}))
 }
 report.teamAfter=await api('/teams/'+created.id);report.savedTeamUnchanged=sameJson(report.teamBefore,report.teamAfter);report.sourcesUnchanged=sameJson(report.sourceSha256,sourceHashes());report.runtimeAfter=await api('/chat/runtime');report.runtimeUnchanged=sameJson(report.runtime.implementationSha256,report.runtimeAfter.implementationSha256)
}catch(error){report.error=error.message}
finally{if(authenticated){try{await api('/logout',undefined,'POST',204)}catch{report.cleanupFailed=true}}report.finishedAt=new Date().toISOString();report.protocolComparison={v1Passed:report.records.filter(r=>r.planningAudit?.pass).length,v2Passed:report.records.filter(r=>r.referencePlanningAudit?.pass).length,planned:plan.cases.length,scope:'Separate protocols, not model quality improvement'};report.pass=!report.error&&!report.cleanupFailed&&report.records.length===3&&report.records.every(r=>r.pass)&&report.savedTeamUnchanged&&report.sourcesUnchanged&&report.runtimeUnchanged;save();console.log(JSON.stringify({pass:report.pass,completed:report.records.filter(r=>r.pass).length,planned:3,error:report.error}));if(!report.pass)process.exitCode=1}
