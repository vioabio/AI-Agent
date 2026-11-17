import test from 'node:test'
import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {coreComponents,additionalComponents} from './runtime-component-manifest.mjs'
import {stage2Team} from './stage2-regression.mjs'
import {stage3Cases,stage3DamageInputs} from './stage3-regression.mjs'
import {verifyStage3} from './upgrade-stage3-verify.mjs'

function fixture() {
 const cases=stage3Cases(),before={...stage2Team(),id:1},files=new Map()
 const damageOracles=Object.fromEntries(Object.entries(stage3DamageInputs()).map(([key,input])=>{
  const result={range:{minimum:1,maximum:2},conditionValidation:{status:'VERIFIED',typedRequestDigest:digest(input)}}
  return [key,{input,result,resultSha256:digest(result)}]
 }))
 let number=0
 const records=cases.map(item=>({caseId:item.id,verdict:'PENDING_REVIEW',teamUnchanged:true,teamSnapshotSha256:digest(before),turns:item.turns.map((prompt,index)=>{
  const runId='run-fixture-'+(++number),eventsFile=`${item.id}-${index}-events.json`
  const events=[{eventId:1,runId,type:'answer.delta',payload:{text:'仅作结构测试'}}]
  if(index===0&&['S3-01','S3-02','S3-03','S3-08','S3-14'].includes(item.id)) {
   const oracle=damageOracles[item.id==='S3-08'?'changed':'baseline']
   events.push({eventId:2,runId,type:'tool.finished',payload:{toolName:'calculateBattleDamage',status:'SUCCEEDED',governanceEventId:'fixture-call',outputEvidence:{status:'VERIFIED',typedRequestDigest:oracle.result.conditionValidation.typedRequestDigest,outputDigest:oracle.resultSha256}}})
  }
  events.push({eventId:events.length+1,runId,type:'run.completed',payload:{}})
  const bytes=JSON.stringify(events);files.set(eventsFile,bytes)
  return {runId,prompt,status:'COMPLETED',answer:'仅作结构测试',eventsFile,eventsSha256:digest(bytes)}
 })}))
 const configuration={provider:'fixture'},runtime={configuration,sha256:digest(configuration)}
 const build=runtimeBuild({implementationSha256:Object.fromEntries([...coreComponents,...additionalComponents].map(([name])=>[name,'a'.repeat(64)]))})
 const report={schemaVersion:'upgrade-baseline-v1',mode:'COLLECTED_NOT_REVIEWED',split:'dev',suiteKind:'STAGE3_STANDARD_REGRESSION',datasetSha256:digest(cases),testSpecification:{file:'document/测试文档/测试文档_阶段3_伤害计算与Agent协作.md',sha256:'b'.repeat(64)},damageReferenceSha256:'c'.repeat(64),damageOracles,records,selectedCaseIds:cases.map(row=>row.id),cleanupErrors:[],runtime,runtimeAfter:runtime,runtimeBuild:build,runtimeBuildAfter:build,testTeam:{before,after:structuredClone(before),unchanged:true}}
 return {report,files,verify:()=>verifyStage3(JSON.stringify(report),name=>files.get(name),'b'.repeat(64),'c'.repeat(64))}
}

test('stage3 evidence binds exact engine inputs and outputs while requiring content review',()=>{
 const result=fixture().verify()
 assert.equal(result.status,'VERIFIED');assert.equal(result.integrity.turns,12)
 assert.equal(result.calculations.length,5);assert.equal(result.qualityReview,'REQUIRED')
})

test('changed or missing direct-engine oracles and team snapshots fail verification',()=>{
 for(const change of [r=>r.damageOracles.changed.input.attacker.build.evs.spa=252,r=>delete r.damageOracles.baseline,r=>r.testTeam.after.members[0].build.level=52]){
  const sample=fixture();change(sample.report);assert.throws(sample.verify)
 }
})

test('a model calculation using a different input digest cannot pass',()=>{
 const sample=fixture(),turn=sample.report.records[0].turns[0]
 const events=JSON.parse(sample.files.get(turn.eventsFile))
 events[1].payload.outputEvidence.typedRequestDigest='d'.repeat(64)
 const bytes=JSON.stringify(events);sample.files.set(turn.eventsFile,bytes);turn.eventsSha256=digest(bytes)
 assert.throws(sample.verify,/CALCULATION_ORACLE_MISMATCH/)
})
