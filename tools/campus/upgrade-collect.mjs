import {randomBytes} from 'node:crypto'
import {writeFileSync,readFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {loadFrozenSuite,prepare,publicFingerprint,collectionFailure,root} from './upgrade-baseline.mjs'
import {localBase,digest,eventBoundaryReached,sameJson,frozenFileDigest} from './evaluation.mjs'
import {collectionStatus,runtimeBuild} from './upgrade-collection-status.mjs'
import {localEvaluationSession} from './local-evaluation-session.mjs'
import {longHistoryCases} from './upgrade-long-history.mjs'
import {stage2Cases,stage2Team} from './stage2-regression.mjs'
import {stage3Cases,stage3DamageInputs} from './stage3-regression.mjs'
import {stage4Cases} from './stage4-regression.mjs'
import {stage5Cases} from './stage5-regression.mjs'
import {stage6RoutingCases, stage6InjectionCases} from './stage6-routing.mjs'
import {loadIndependentSuite} from './independent-suite.mjs'
const [output,url,limitText='20',caseText='',suiteName='frozen',split='dev']=process.argv.slice(2)
if(!output||!url)throw Error('Usage: upgrade-collect.mjs artifacts/NEW http://127.0.0.1:8123/api [limit] [case-ids] [frozen|frozen-regression|long-history|s2-regression|s3-regression|s4-regression|s5-regression|s6-routing|s6-injection|independent-v2|independent-v2-regression|independent-v3|independent-v3-regression] [dev|heldout]')
const base=localBase(url),limit=Number(limitText)
const requested=caseText?caseText.split(','):null
if(!Number.isInteger(limit)||limit<1||limit>20)throw Error('limit 1..20')
if(!['frozen','frozen-regression','long-history','s2-regression','s3-regression','s4-regression','s5-regression','s6-routing','s6-injection','independent-v2','independent-v2-regression','independent-v3','independent-v3-regression'].includes(suiteName))throw Error('SUPPORTED_SUITE_REQUIRED')
if(!['dev','heldout'].includes(split)||split==='heldout'&&suiteName!=='frozen')throw Error('HELDOUT_REQUIRES_FROZEN_SUITE')
const suite=loadFrozenSuite(),report=prepare(output,split),opened=new Set()
const independentCandidate=['independent-v2','independent-v2-regression','independent-v3','independent-v3-regression'].includes(suiteName)
if(independentCandidate) {
 const candidate=loadIndependentSuite(suiteName.startsWith('independent-v3')?resolve(root,'evaluation/upgrade-v1.1/u6/independent-v3'):undefined)
 suite.cases=candidate.cases;suite.manifest=candidate.manifest
 report.suiteKind=suiteName.endsWith('-regression')?'KNOWN_INDEPENDENT_CANDIDATE_REGRESSION':'NEW_FIRST_ANSWER_CANDIDATE'
 report.datasetSha256=candidate.datasetSha256;report.corpusSha256=candidate.manifest.corpus.sha256
 report.rubricSha256=candidate.manifest.files['rubric.json']
 report.records=candidate.cases.map(item=>({caseId:item.id,verdict:'NOT_RUN'}))
}
if(suiteName==='frozen-regression') {
 suite.cases=suite.cases.filter(item=>item.split==='heldout').map(item=>({...item,split:'dev'}))
 report.suiteKind='KNOWN_HELDOUT_REGRESSION'
 report.records=suite.cases.map(item=>({caseId:item.id,verdict:'NOT_RUN'}))
}
if(suiteName==='long-history') {
 suite.cases=longHistoryCases()
 report.suiteKind='LONG_HISTORY_DEVELOPMENT'
 report.datasetSha256=digest(suite.cases)
 report.records=suite.cases.map(item=>({caseId:item.id,verdict:'NOT_RUN'}))
}
if(suiteName==='s2-regression') {
 suite.cases=stage2Cases()
 report.suiteKind='STAGE2_BUSINESS_REGRESSION'
 report.testSpecification={file:'document/测试文档/测试文档_阶段2_资料与队伍问答.md',
  sha256:frozenFileDigest(resolve(root,'document/测试文档/测试文档_阶段2_资料与队伍问答.md'))}
 report.datasetSha256=digest(suite.cases)
 report.records=suite.cases.map(item=>({caseId:item.id,verdict:'NOT_RUN'}))
}
if(suiteName==='s3-regression') {
 suite.cases=stage3Cases()
 report.suiteKind='STAGE3_STANDARD_REGRESSION'
 report.testSpecification={file:'document/测试文档/测试文档_阶段3_伤害计算与Agent协作.md',
  sha256:frozenFileDigest(resolve(root,'document/测试文档/测试文档_阶段3_伤害计算与Agent协作.md'))}
 report.damageReferenceSha256=frozenFileDigest(resolve(root,'evaluation/campus/s01/damage-reference.json'))
 report.notSelectedProceduralCases=['S3-10','S3-11','S3-12','S3-13']
 report.datasetSha256=digest(suite.cases)
 report.records=suite.cases.map(item=>({caseId:item.id,verdict:'NOT_RUN'}))
}
if(suiteName==='s4-regression') {
 suite.cases=stage4Cases()
 report.suiteKind='STAGE4_CONVERSATION_REGRESSION'
 report.testSpecification={file:'document/测试文档/测试文档_阶段4_连续对话与历史状态.md',
  sha256:frozenFileDigest(resolve(root,'document/测试文档/测试文档_阶段4_连续对话与历史状态.md'))}
 report.semanticCases=16;report.fillerTurns=9
 report.datasetSha256=digest(suite.cases)
 report.records=suite.cases.map(item=>({caseId:item.id,verdict:'NOT_RUN'}))
}
if(suiteName==='s5-regression') {
 suite.cases=stage5Cases()
 report.suiteKind='STAGE5_GUIDE_ANSWER_REGRESSION'
 report.testSpecification={file:'document/测试文档/测试文档_阶段5_Embedding与混合检索.md',
  sha256:frozenFileDigest(resolve(root,'document/测试文档/测试文档_阶段5_Embedding与混合检索.md'))}
 report.datasetSha256=digest(suite.cases)
 report.records=suite.cases.map(item=>({caseId:item.id,verdict:'NOT_RUN'}))
}
const session=localEvaluationSession(base),api=session.api
if(['s6-routing','s6-injection'].includes(suiteName)) {
 suite.cases=suiteName==='s6-routing'?stage6RoutingCases():stage6InjectionCases()
 report.suiteKind=suiteName==='s6-routing'?'STAGE6_ROLE_ROUTING':'STAGE6_DOCUMENT_INJECTION'
 report.testSpecification={file:'document/测试文档/测试文档_阶段6_治理与端到端运行.md',
  sha256:frozenFileDigest(resolve(root,'document/测试文档/测试文档_阶段6_治理与端到端运行.md'))}
 report.datasetSha256=digest(suite.cases)
 report.records=suite.cases.map(item=>({caseId:item.id,verdict:'NOT_RUN'}))
}
report.producerSha256=digest(readFileSync(new URL(import.meta.url)))
report.producerDependenciesSha256=Object.fromEntries(['local-evaluation-session.mjs','upgrade-long-history.mjs','stage2-regression.mjs','stage3-regression.mjs','stage4-regression.mjs','stage5-regression.mjs','stage6-routing.mjs','independent-suite.mjs']
 .map(name=>[name,digest(readFileSync(new URL(name,import.meta.url)))]))
report.mode='COLLECTING';report.cleanupErrors=[]
let authenticated=false
const save=()=>writeFileSync(resolve(output,'baseline.json'),JSON.stringify(report,null,2)+'\n')
try{
 await session.login('U0隔离评测');authenticated=true
 const runtime=await api('/chat/runtime');report.runtime=publicFingerprint(runtime);report.runtimeBuild=runtimeBuild(runtime)
 if(split==='heldout'||suiteName==='frozen-regression'||independentCandidate) {
  if(split==='heldout')report.suiteKind='FROZEN_HELDOUT_FIRST_ANSWERS'
  report.candidateGuideCatalog=await api('/pokemon/guides/catalog')
  report.candidateGuideIndex=await api('/pokemon/guides/index')
  report.candidateSemanticState=await api('/pokemon/guides/semantic')
  report.rubricSha256=suite.manifest.files['rubric.json']
  if(independentCandidate&&report.candidateGuideCatalog.corpusSha256!==suite.manifest.corpus.sha256)throw Error('CANDIDATE_CORPUS_MISMATCH')
 }
 if(['s6-routing','s6-injection'].includes(suiteName)) {
  report.availableTools=runtime.tools
  report.rolePolicySourceSha256=digest(readFileSync(resolve(root,'src/main/java/com/vio/vioaiagent/agent/SpecialistRole.java')))
  report.guideCatalog=await api('/pokemon/guides/catalog')
 }
 if(['s2-regression','s3-regression','s4-regression','s6-routing','s6-injection'].includes(suiteName)) {
  const fixture=stage2Team(),created=await api('/pokemon/teams',fixture,201)
  const before=await api('/pokemon/teams/'+created.id)
  const actual={name:before.name,rulesetId:before.rulesetId,dataReleaseId:before.dataReleaseId,
   members:before.members.map(({formId,slot,build})=>({formId,slot,build}))}
  if(!sameJson(actual,fixture))throw Error('TEST_TEAM_FIXTURE_MISMATCH')
  report.testTeam={before,after:null,retainedInIsolatedAccount:true}
 }
 if(['s3-regression','s6-routing'].includes(suiteName)) {
  report.damageOracles={}
  for(const [name,input] of Object.entries(stage3DamageInputs())) {
   const result=await api('/pokemon/calculations/engine-damage',input)
   report.damageOracles[name]={input,result,resultSha256:digest(result)}
  }
 }
 if(requested&&requested.some(id=>!report.records.some(r=>r.caseId===id)))throw Error('DEV_CASE_REQUIRED')
 report.selectedCaseIds=requested||report.records.slice(0,limit).map(r=>r.caseId)
 for(const record of report.records.filter(r=>report.selectedCaseIds.includes(r.caseId))){
  const item=suite.cases.find(c=>c.id===record.caseId);record.turns=[];const conversationId='u0-'+randomBytes(12).toString('hex')
  try{for(const prompt of item.turns){
   const start=Date.now(),run=await api('/chat/runs',{...item.scope,dataReleaseId:"pokeapi-reference-v1",prompt,conversationId,timeoutSeconds:120,idempotencyKey:randomBytes(16).toString('hex')},202)
   opened.add(run.runId);const events=[],seen=new Set();let cursor='0',status='RUNNING',ttft=null,runView
   while(Date.now()-start<140000){
    const page=await api('/chat/runs/'+run.runId+'/events?afterEventId='+cursor)
    for(const event of page.events||[]){const id=String(event.eventId??event.id);if(seen.has(id))continue;seen.add(id);cursor=id;events.push(event);if(event.type==='answer.delta'&&event.payload?.text&&ttft===null)ttft=Date.now()-start}
    runView=await api('/chat/runs/'+run.runId);status=runView.status
    if(eventBoundaryReached(status,events)||status==='WAITING_APPROVAL')break
    await new Promise(r=>setTimeout(r,250))
   }
   const file=record.caseId+'-'+record.turns.length+'-events.json',bytes=JSON.stringify(events,null,2)+'\n'
   writeFileSync(resolve(output,file),bytes)
   const semanticCaseId=item.turnCaseIds?.[record.turns.length]
   record.turns.push({runId:run.runId,prompt,status,...(semanticCaseId?{semanticCaseId,conversationId:runView?.conversationId}:{}),ttftMs:ttft,totalMs:Date.now()-start,answer:events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join(''),eventsFile:file,eventsSha256:digest(bytes)})
   if(suiteName==='s5-regression')record.turns.at(-1).semanticStatus=await api('/pokemon/guides/semantic')
   if(suiteName==='s6-injection') {
    const probeQuery={query:prompt,gameReleaseId:item.scope.gameReleaseId,language:item.scope.language,
     battleFormatId:item.scope.battleFormat,rulesetVersionId:item.scope.rulesetVersionId}
    const hits=await api('/pokemon/guides/retrieve?'+new URLSearchParams(probeQuery))
    record.turns.at(-1).sourceProbe={query:probeQuery,hits,canonical:[]}
    for(const hit of hits)record.turns.at(-1).sourceProbe.canonical.push(await api('/pokemon/guides/chunks/'+hit.view.chunk.id))
   }
   if(!eventBoundaryReached(status,events)||status==='WAITING_APPROVAL'){await api('/chat/runs/'+run.runId+'/cancel',{});record.verdict='BLOCKED';break}
   opened.delete(run.runId)
  }
  if(record.verdict!=='BLOCKED')record.verdict='PENDING_REVIEW'
  if(report.testTeam) {
   const after=await api('/pokemon/teams/'+report.testTeam.before.id)
   record.teamSnapshotSha256=digest(after)
   record.teamUnchanged=sameJson(after,report.testTeam.before)
   if(!record.teamUnchanged)report.cleanupErrors.push('TEST_TEAM_CHANGED')
  }
  }catch(error){record.verdict='BLOCKED';record.failure=collectionFailure(error)}
  save();console.log(record.caseId+': '+record.verdict)
 }
 report.mode='COLLECTED_NOT_REVIEWED'
}catch(error){report.mode='SETUP_BLOCKED';report.setupError=collectionFailure(error)}
finally{
 for(const id of opened)try{await api('/chat/runs/'+id+'/cancel',{})}catch{report.cleanupErrors.push('CANCEL_FAILED')}
 if(authenticated&&report.testTeam)try {
  report.testTeam.after=await api('/pokemon/teams/'+report.testTeam.before.id)
  report.testTeam.unchanged=sameJson(report.testTeam.before,report.testTeam.after)
  if(!report.testTeam.unchanged)report.cleanupErrors.push('TEST_TEAM_CHANGED')
 }catch{report.cleanupErrors.push('TEST_TEAM_RECHECK_UNAVAILABLE')}
 if(authenticated)try{
  const runtime=await api('/chat/runtime')
  report.runtimeAfter=publicFingerprint(runtime);report.runtimeBuildAfter=runtimeBuild(runtime)
  if(['s6-routing','s6-injection'].includes(suiteName)) {
   if(!sameJson(report.availableTools,runtime.tools))report.cleanupErrors.push('AVAILABLE_TOOLS_CHANGED')
   report.guideCatalogAfter=await api('/pokemon/guides/catalog')
   if(!sameJson(report.guideCatalog,report.guideCatalogAfter))report.cleanupErrors.push('GUIDE_CATALOG_CHANGED')
  }
 }catch{report.cleanupErrors.push('RUNTIME_RECHECK_UNAVAILABLE')}
 if(authenticated)try{await api('/logout',{},204)}catch{report.cleanupErrors.push('LOGOUT_UNVERIFIED')}
 report.finishedAt=new Date().toISOString();
 report.collection=collectionStatus(report,suite.cases.filter(item=>item.split===split));save()
 process.exitCode=report.collection.exitCode
 console.log(JSON.stringify(report.collection))
}
