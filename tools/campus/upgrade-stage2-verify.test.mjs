import test from 'node:test'
import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {coreComponents,additionalComponents} from './runtime-component-manifest.mjs'
import {stage2Cases,stage2Team} from './stage2-regression.mjs'
import {verifyStage2} from './upgrade-stage2-verify.mjs'

function fixture(){
  const cases=stage2Cases(),files=new Map(),before={...stage2Team(),id:1,ownerId:'fixture'}
  let count=0
  const records=cases.map(item=>({caseId:item.id,verdict:'PENDING_REVIEW',teamUnchanged:true,teamSnapshotSha256:digest(before),
    turns:item.turns.map((prompt,index)=>{
      const runId='run-stage2-'+(++count),eventsFile=item.id+'-'+index+'-events.json'
      const events=[{eventId:1,runId,type:'answer.delta',payload:{text:'仅作结构测试'}},{eventId:2,runId,type:'run.completed',payload:{}}]
      const bytes=JSON.stringify(events);files.set(eventsFile,bytes)
      return {runId,prompt,status:'COMPLETED',answer:'仅作结构测试',eventsFile,eventsSha256:digest(bytes)}
    })}))
  const configuration={configuredProvider:'fixture',configuredModelId:'fixture'},runtime={configuration,sha256:digest(configuration)}
  const build=runtimeBuild({implementationSha256:Object.fromEntries([...coreComponents,...additionalComponents].map(([name])=>[name,'a'.repeat(64)]))})
  const report={schemaVersion:'upgrade-baseline-v1',mode:'COLLECTED_NOT_REVIEWED',split:'dev',suiteKind:'STAGE2_BUSINESS_REGRESSION',
    datasetSha256:digest(cases),testSpecification:{file:'document/测试文档/测试文档_阶段2_资料与队伍问答.md',sha256:'b'.repeat(64)},
    cleanupErrors:[],records,selectedCaseIds:cases.map(item=>item.id),runtime,runtimeAfter:structuredClone(runtime),
    runtimeBuild:build,runtimeBuildAfter:build,testTeam:{before,after:structuredClone(before),unchanged:true}}
  return {report,files,verify:()=>verifyStage2(JSON.stringify(report),name=>files.get(name),'b'.repeat(64))}
}

test('fourteen-case evidence validation requires separate semantic review',()=>{
  const report=fixture().verify()
  assert.equal(report.status,'VERIFIED');assert.equal(report.cases,14);assert.equal(report.integrity.turns,15)
  assert.equal(report.qualityReview,'REQUIRED');assert.equal(report.releaseDecision,'NOT_EVALUATED')
})

test('changed team builds, missing snapshots and false unchanged flags are rejected',()=>{
  for(const change of [
    r=>r.testTeam.before.members[0].build.level=52,
    r=>r.testTeam.after.members[0].build.moves.push('tackle'),
    r=>r.records[3].teamUnchanged=false,r=>delete r.records[0].teamSnapshotSha256
  ]){const sample=fixture();change(sample.report);assert.throws(sample.verify,/TEST_TEAM_/)}
})

test('edited questions or specifications and substituted event identities cannot pass',()=>{
  const question=fixture();question.report.records[0].turns[0].prompt='changed'
  assert.throws(question.verify,/COLLECTION_INCOMPLETE/)
  const document=fixture();document.report.testSpecification.sha256='c'.repeat(64)
  assert.throws(document.verify,/STAGE2_SPECIFICATION_MISMATCH/)
  const sample=fixture(),turn=sample.report.records[0].turns[0]
  const events=JSON.parse(sample.files.get(turn.eventsFile));events[0].runId='run-other'
  const bytes=JSON.stringify(events);sample.files.set(turn.eventsFile,bytes);turn.eventsSha256=digest(bytes)
  assert.throws(sample.verify,/EVENT_RUN_MISMATCH/)
})

test('a known write tool execution cannot be concealed by an unchanged final snapshot',()=>{
  const sample=fixture(),turn=sample.report.records[0].turns[0]
  const events=JSON.parse(sample.files.get(turn.eventsFile));events[1].eventId=3
  events.splice(1,0,{eventId:2,runId:turn.runId,type:'tool.started',payload:{toolName:'previewTeamChanges'}})
  const bytes=JSON.stringify(events);sample.files.set(turn.eventsFile,bytes);turn.eventsSha256=digest(bytes)
  assert.throws(sample.verify,/READ_ONLY_STAGE_EXECUTION_VIOLATION/)
})
