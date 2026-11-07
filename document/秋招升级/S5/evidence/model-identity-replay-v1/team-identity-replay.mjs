import {sameJson,digest} from './evaluation.mjs'
import {collectToolInputs,checkToolInput} from './tool-inputs.mjs'
import {auditTeamPlanning} from './team-planning.mjs'
import {auditTeamPlanningWithReferences} from './team-planning-reference.mjs'
import {attemptsFrom} from './answer-quality.mjs'

// Offline audit of one captured session. No model call, mutation or semantic approval.
export function auditIdentityReplay(record,plan,{historyBinding,observationSha256}={}){
 if(!plan?.request?.members?.length||!Array.isArray(plan.literalQueries)||!plan.literalQueries.length
   ||new Set(plan.literalQueries).size!==plan.literalQueries.length||typeof plan.readOnlyPrompt!=='string'
   ||typeof plan.changePromptTemplate!=='string')throw Error('Explicit request and literal member names required')
 const [first,second]=record.tasks||[]
 if(record.tasks?.length!==2||!Array.isArray(first?.events)||!Array.isArray(second?.events))throw Error('Two captured tasks required')
 const reference=record.readOnlyReferenceBefore
 if(!reference?.reportSha256||!sameJson(record.readOnlyRequest,plan.request)||!sameJson(reference.request,plan.request))throw Error('Actual pre-run HTTP reference must bind the original request')
 const expected={request:plan.request,reportSha256:reference.reportSha256,mode:'MODEL_TOOL_SELECTION'}
 const v1=auditTeamPlanning(first.events,expected),v2=auditTeamPlanningWithReferences(first.events,expected,plan.aliases)
 const firstAggregate=first.events.findIndex(e=>e.type==='tool.started'&&e.payload?.toolName==='checkPokemonTeamBuild')
 const earlier=collectToolInputs(firstAggregate<0?[]:first.events.slice(0,firstAggregate))
 const literalReads=plan.literalQueries.map(query=>{
  const expectedRead={tool:'lookupPokemonReference',fields:{'/query':query,'/dataReleaseId':plan.request.dataReleaseId},exactArguments:{query,dataReleaseId:plan.request.dataReleaseId}}
  const matching=earlier.filter(c=>c.tool==='lookupPokemonReference'&&checkToolInput([c],expectedRead).pass)
  return {query,pass:matching.length>0,matchingCallIds:matching.map(c=>c.callId)}
 })
 const calls=record.tasks.map(t=>collectToolInputs(t.events)),usage=record.tasks.map(t=>attemptsFrom(t.events).reduce((n,a)=>n+a.modelCallCount,0))
 const runIds=record.tasks.map(t=>[...new Set(t.events.map(e=>e.runId).filter(Boolean))])
 const expectedAfter=structuredClone(record.before)
 if(!expectedAfter?.members?.[0]?.build)throw Error('Original saved team required')
 expectedAfter.members[0].build.level=51
 const changes=record.preview?.changes
 const firstPreview=second.events.findIndex(e=>e.type==='tool.started'&&e.payload?.toolName==='previewTeamChanges')
 const savedRead=checkToolInput(collectToolInputs(firstPreview<0?[]:second.events.slice(0,firstPreview)),
  {tool:'readCurrentTeam',fields:{'/teamId':record.before.id},exactArguments:{teamId:record.before.id}})
 const bindings=historyBinding?.bindings
 const boundHistory=/^[a-f0-9]{64}$/.test(observationSha256||'')&&historyBinding?.observationSha256===observationSha256
  &&Array.isArray(bindings)&&bindings.length===2&&bindings.every((b,i)=>b.runId===record.tasks[i].runId
   &&typeof b.conversationId==='string'&&b.conversationId.length>0&&/^[a-f0-9]{64}$/.test(b.ownerSha256||'')
   &&b.answerSha256===digest(record.tasks[i].answer)
   &&b.userPromptSha256===digest(i===0?plan.readOnlyPrompt:plan.changePromptTemplate.replace('{teamId}',String(record.before.id))))
 const checks={capturedPass:record.pass===true,completed:record.tasks.every(t=>t.status==='COMPLETED'),
  allAggregateCallsBound:v2.pass,literalNamesBeforeAggregate:firstAggregate>=0&&literalReads.every(r=>r.pass),
  noWriteDuringReadOnly:sameJson(record.before,record.afterReadOnly),
  boundStoredHistory:boundHistory,sameConversation:boundHistory&&bindings[0].conversationId===bindings[1].conversationId,
  sameAccount:boundHistory&&bindings[0].ownerSha256===bindings[1].ownerSha256,
  ownRunEvidence:runIds.every((ids,i)=>ids.length===1&&ids[0]===record.tasks[i].runId)&&first.runId!==second.runId,
  realUsage:usage.every((n,i)=>Number.isSafeInteger(n)&&n>0&&n===record.tasks[i].modelCalls),
  runtimeStable:/^[a-f0-9]{64}$/.test(record.runtime?.implementationSha256?.ToolCallAgent||'')
   &&/^[a-f0-9]{64}$/.test(record.runtime?.implementationSha256?.ToolExecutionGateway||'')
   &&sameJson(record.runtime.implementationSha256,record.runtimeAfter?.implementationSha256),
  boundSavedTeamRead:savedRead.pass,previewTeam:record.preview?.teamId===record.before.id,
  previewScope:Array.isArray(changes)&&changes.length===1&&changes[0].slot===1&&changes[0].field==='等级'&&changes[0].before==='50'&&changes[0].after==='51',
  originalLevel:record.before.members[0].build.level===50,noWriteBeforeConfirmation:record.noWriteBeforeConfirmation===true,
  exactSavedTeam:sameJson(record.after,expectedAfter),applied:record.outcome?.status==='APPLIED',receipt:second.answer===record.outcome?.message,
  savedReadBack:record.reloadReadBack===true,requiredChangeToolsObserved:(plan.requiredChangeTools||[]).every(name=>calls[1].some(c=>c.tool===name&&['SUCCEEDED','EXECUTED','DEDUPLICATED'].includes(c.status))),
  cleanup:!record.cancelFailed&&!record.logoutFailed,automatedActor:record.approvalActor==='AUTOMATED_DEDICATED_TEST_ACCOUNT'&&record.humanStrategyAcceptance===false}
 return {schemaVersion:'team-identity-replay-audit-v1',pass:Object.values(checks).every(Boolean),checks,literalReads,
  protocolResults:{originalV1Pass:v1.pass,scopedReferencesV2Pass:v2.pass},actualModelCalls:usage.reduce((a,b)=>a+b,0),
  distinctToolCalls:calls.map(c=>c.length),answerDeltaCounts:record.tasks.map(t=>t.answerDeltaCount),
  previewInputEvidence:calls[1].filter(c=>c.tool==='previewTeamChanges').map(c=>({callId:c.callId,coverage:c.evidence?.coverage||'MISSING',bound:c.bound,failures:c.failures})),
  strictPreviewInputCertified:calls[1].some(c=>c.tool==='previewTeamChanges')&&calls[1].filter(c=>c.tool==='previewTeamChanges').every(c=>c.bound),
  newHumanReviews:0,formalAdmissions:0,tokenByTokenStreamingVerified:false,fullRecruitingVideoComplete:false,
  scope:'One continuous dedicated test conversation. Read-only input binding and preview/display/storage effects checked separately; OMITTED preview arguments are not certified. Historical failure retained; no general planning or semantic quality score.'}
}
