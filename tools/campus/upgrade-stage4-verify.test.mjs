import test from 'node:test'
import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {coreComponents,additionalComponents} from './runtime-component-manifest.mjs'
import {stage2Team} from './stage2-regression.mjs'
import {stage4Cases} from './stage4-regression.mjs'
import {verifyStage4} from './upgrade-stage4-verify.mjs'
function fixture(){
 const cases=stage4Cases(),files=new Map(),before={...stage2Team(),id:1}
 let count=0
 const records=cases.map(item=>({caseId:item.id,verdict:'PENDING_REVIEW',teamUnchanged:true,teamSnapshotSha256:digest(before),turns:item.turns.map((prompt,index)=>{
  const runId='run-stage4-'+(++count),eventsFile=`${item.id}-${index}-events.json`
  const events=[{eventId:1,runId,type:'answer.delta',payload:{text:'结构测试'}},{eventId:2,runId,type:'run.completed',payload:{}}]
  const bytes=JSON.stringify(events);files.set(eventsFile,bytes)
  return {runId,prompt,status:'COMPLETED',answer:'结构测试',semanticCaseId:item.turnCaseIds[index],conversationId:'conversation-'+item.id,eventsFile,eventsSha256:digest(bytes)}
 })}))
 const configuration={provider:'fixture'},runtime={configuration,sha256:digest(configuration)}
 const build=runtimeBuild({implementationSha256:Object.fromEntries([...coreComponents,...additionalComponents].map(([name])=>[name,'a'.repeat(64)]))})
 const report={schemaVersion:'upgrade-baseline-v1',mode:'COLLECTED_NOT_REVIEWED',split:'dev',suiteKind:'STAGE4_CONVERSATION_REGRESSION',datasetSha256:digest(cases),testSpecification:{file:'document/测试文档/测试文档_阶段4_连续对话与历史状态.md',sha256:'b'.repeat(64)},semanticCases:16,fillerTurns:9,cleanupErrors:[],records,selectedCaseIds:cases.map(row=>row.id),runtime,runtimeAfter:runtime,runtimeBuild:build,runtimeBuildAfter:build,testTeam:{before,after:structuredClone(before),unchanged:true}}
 return {report,files,verify:()=>verifyStage4(JSON.stringify(report),name=>files.get(name),'b'.repeat(64))}
}
test('stage4 verifies four conversation boundaries and separates semantic coverage from filler',()=>{
 const result=fixture().verify()
 assert.equal(result.status,'VERIFIED');assert.equal(result.integrity.turns,25)
 assert.equal(result.semanticCases,16);assert.equal(result.fillerTurns,9);assert.equal(result.qualityReview,'REQUIRED')
})
test('missing or exchanged conversation identities and case labels are rejected',()=>{
 for(const change of [r=>delete r.records[0].turns[0].conversationId,r=>r.records[0].turns[1].conversationId='other',r=>r.records[1].turns.forEach(t=>t.conversationId='conversation-S4-A'),r=>r.records[0].turns[0].semanticCaseId='S4-B01']){
  const sample=fixture();change(sample.report);assert.throws(sample.verify)
 }
})
test('team mutation and wrong event identity cannot pass stage4 evidence verification',()=>{
 const sample=fixture();sample.report.testTeam.after.members[0].build.level=99
 assert.throws(sample.verify,/TEST_TEAM_SNAPSHOT_MISMATCH/)
 const swapped=fixture(),turn=swapped.report.records[0].turns[0],events=JSON.parse(swapped.files.get(turn.eventsFile))
 events[0].runId='run-different';const bytes=JSON.stringify(events);swapped.files.set(turn.eventsFile,bytes);turn.eventsSha256=digest(bytes)
 assert.throws(swapped.verify,/EVENT_RUN_MISMATCH/)
})
