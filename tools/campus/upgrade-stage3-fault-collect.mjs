import assert from 'node:assert/strict'
import {readFileSync,writeFileSync,existsSync,mkdirSync,realpathSync} from 'node:fs'
import {resolve,dirname} from 'node:path'
import {fileURLToPath} from 'node:url'
import {randomBytes} from 'node:crypto'
import {localEvaluationSession} from './local-evaluation-session.mjs'
import {localBase,digest,eventBoundaryReached,sameJson} from './evaluation.mjs'
import {publicFingerprint} from './upgrade-baseline.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {stage2Team} from './stage2-regression.mjs'
import {stage3Cases,stage3DamageInputs} from './stage3-regression.mjs'

export function verifyFaultEvents(mode,runId,events,policy={maxModelCalls:1,tokenLimit:32768}) {
 assert.ok(['EngineUnavailable','LowBudget'].includes(mode))
 assert.ok(events.length>0)
 let cursor=0n
 for(const event of events){assert.equal(event.runId,runId);assert.ok(BigInt(event.eventId)>cursor);cursor=BigInt(event.eventId)}
 assert.equal(events.filter(e=>e.type==='run.failed').length,1)
 assert.equal(events.filter(e=>['run.completed','run.cancelled','answer.completed'].includes(e.type)).length,0)
 const error=events.find(e=>e.type==='run.failed').payload.errorType
 if(mode==='EngineUnavailable') {
  assert.equal(error,'DAMAGE_CALCULATION_FAILED')
  assert.ok(events.some(e=>e.type==='tool.finished'&&e.payload.toolName==='calculateBattleDamage'&&e.payload.status==='FAILED'))
  assert.equal(events.some(e=>e.type==='tool.finished'&&e.payload.toolName==='calculateBattleDamage'&&e.payload.status==='SUCCEEDED'),false)
  const attempts=events.filter(e=>e.type==='tool.started'&&e.payload.toolName==='calculateBattleDamage').length
  assert.ok(attempts>=1&&attempts<=2)
 } else {
  assert.equal(error,'RUN_TASK_BUDGET_EXCEEDED')
  const usage=events.flatMap(e=>e.type==='usage'?[e.payload.taskBudget]:e.payload?.modelUsage?[e.payload.modelUsage.taskBudget]:[])
  assert.ok(usage.length>0)
  assert.equal(new Set(usage.map(b=>b.budgetId)).size,1)
  assert.ok([1,2].includes(policy.maxModelCalls));assert.equal(policy.tokenLimit,32768)
  for(const budget of usage){assert.equal(typeof budget.budgetId,'string');assert.ok(budget.budgetId.length>0);assert.equal(budget.maxModelCalls,policy.maxModelCalls);assert.equal(budget.tokenLimit,policy.tokenLimit);assert.equal(budget.stateKnown,true);assert.ok(budget.modelCalls<=policy.maxModelCalls)}
  assert.equal(usage.at(-1).status,'BLOCKED');assert.ok(['CALL_LIMIT','TOKEN_LIMIT'].includes(usage.at(-1).blockReason))
  if(usage.at(-1).blockReason==='CALL_LIMIT')assert.equal(usage.at(-1).modelCalls,policy.maxModelCalls)
  if(policy.maxModelCalls===2) {
   const roles=new Set(events.filter(e=>e.type==='route.selected'&&e.payload.delegationEvent==='agent.delegation.started').map(e=>e.payload.agentRole))
   assert.ok(roles.has('TEAM_PLANNING')&&roles.has('DAMAGE_CALCULATION'),'CROSS_ROLE_BUDGET_CHECK_REQUIRED')
  }
 }
 return {status:'VERIFIED',errorType:error,events:events.length,qualityReview:'REQUIRED'}
}
async function main(){
 const [directory,url,mode,metadataFile]=process.argv.slice(2),output=resolve(directory),base=localBase(url)
 assert.ok(['EngineUnavailable','LowBudget'].includes(mode));assert.equal(dirname(output),realpathSync('artifacts'));assert.equal(existsSync(output),false)
 const metadata=JSON.parse(readFileSync(metadataFile,'utf8').replace(/^\uFEFF/,''))
 assert.equal(metadata.testFault,mode);assert.equal(metadata.status,'READY');assert.equal(metadata.port,Number(new URL(base).port));assert.notEqual(metadata.port,8123)
 mkdirSync(output)
 const report={schemaVersion:'stage3-fault-collection-v1',caseId:mode==='EngineUnavailable'?'S3-11':'S3-12',mode,status:'RUNNING',startedAt:new Date().toISOString(),cleanup:[],qualityReview:'REQUIRED',releaseDecision:'NOT_EVALUATED'}
 const session=localEvaluationSession(base),api=session.api
 let authenticated=false,runId
 try {
  await session.login('故障预算隔离验收');authenticated=true
  const runtime=await api('/chat/runtime');report.runtime=publicFingerprint(runtime);report.runtimeBuild=runtimeBuild(runtime)
  if(mode==='LowBudget'){assert.ok([1,2].includes(runtime.taskBudgetPolicy.maxModelCalls));assert.equal(runtime.taskBudgetPolicy.tokenLimit,32768)}
  const created=await api('/pokemon/teams',stage2Team(),201);report.teamBefore=await api('/pokemon/teams/'+created.id)
  if(mode==='EngineUnavailable'){await api('/pokemon/calculations/engine-damage',stage3DamageInputs().baseline,500);report.directProbe='HTTP_500'}
  const prompt=stage3Cases().find(c=>c.id===(mode==='EngineUnavailable'?'S3-01':'S3-03')).turns[0]
  report.prompt=prompt
  const run=await api('/chat/runs',{gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',rulesetVersionId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',language:'zh-CN',prompt,conversationId:'fault-'+randomBytes(12).toString('hex'),timeoutSeconds:120},202)
  runId=run.runId;report.runId=runId
  let events=[],view
  const deadline=Date.now()+140000
  do {
   events=(await api('/chat/runs/'+runId+'/events?afterEventId=0')).events;view=await api('/chat/runs/'+runId)
   if(eventBoundaryReached(view.status,events))break
   await new Promise(done=>setTimeout(done,250))
  }while(Date.now()<deadline)
  const bytes=JSON.stringify(events,null,2)+'\n';writeFileSync(resolve(output,'events.json'),bytes,{flag:'wx'});report.eventsSha256=digest(bytes)
  report.answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload.text).join('');report.runStatus=view.status
  report.integrity=verifyFaultEvents(mode,runId,events,runtime.taskBudgetPolicy)
  report.teamAfter=await api('/pokemon/teams/'+created.id);assert.ok(sameJson(report.teamBefore,report.teamAfter))
  const after=await api('/chat/runtime');assert.equal(publicFingerprint(after).sha256,report.runtime.sha256);assert.equal(runtimeBuild(after).sha256,report.runtimeBuild.sha256)
  report.status='COLLECTED_PENDING_CONTENT_REVIEW'
 } catch(error){report.status='FAIL';report.failure=error.code??error.name;report.failureCheck=String(error.message).slice(0,250);process.exitCode=1}
 finally {
  if(runId)try{await api('/chat/runs/'+runId+'/cancel',{});report.cleanup.push('RUN_TERMINAL')}catch{report.status='FAIL';process.exitCode=1}
  if(authenticated)try{await session.logout();report.cleanup.push('LOGGED_OUT')}catch{report.status='FAIL';process.exitCode=1}
  report.finishedAt=new Date().toISOString();writeFileSync(resolve(output,'fault-result.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:report.status,caseId:report.caseId,integrity:report.integrity,failure:report.failure,failureCheck:report.failureCheck}))
 }
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))await main()
