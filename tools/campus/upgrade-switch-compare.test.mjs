import test from 'node:test'
import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {collectionStatus,runtimeBuild} from './upgrade-collection-status.mjs'
import {compareSwitch} from './upgrade-switch-compare.mjs'
import {coreComponents,additionalComponents} from './runtime-component-manifest.mjs'

const expected = [{id:'U0-FACTS-01',turns:['事实问题']},{id:'U0-FACTS-02',turns:['后续问题']}]

test('domain tracing comparisons require equal recorded configuration sampling policies',()=>{
  const before=fixture(),after=fixture(true)
  for(const [report,enabled] of [[before,false],[after,true]]) {
    report.runtime.configuration.upgradeFeatures={domainTracing:enabled}
    report.runtimeAfter.configuration=structuredClone(report.runtime.configuration)
    report.runtime.sha256=report.runtimeAfter.sha256=digest(report.runtime.configuration)
  }
  assert.throws(()=>compareSwitch(before,after,'domainTracing',expected),/DOMAIN_SAMPLING_POLICY_REQUIRED/)
  for(const report of [before,after]) {
    report.runtime.configuration.domainTracingPolicy={sampleRate:1,automaticTracing:false}
    report.runtimeAfter.configuration=structuredClone(report.runtime.configuration)
    report.runtime.sha256=report.runtimeAfter.sha256=digest(report.runtime.configuration)
  }
  assert.equal(compareSwitch(before,after,'domainTracing',expected).turns,2)
  after.runtime.configuration.domainTracingPolicy.sampleRate=0.25
  after.runtimeAfter.configuration=structuredClone(after.runtime.configuration)
  after.runtime.sha256=after.runtimeAfter.sha256=digest(after.runtime.configuration)
  assert.throws(()=>compareSwitch(before,after,'domainTracing',expected),/OTHER_CONFIGURATION_CHANGED/)
})
function fixture(enabled=false) {
  const configuration = {configuredProvider:'deepseek',configuredModelId:'fixture-model',upgradeFeatures:{skills:enabled,contextAssembly:false},features:{answerStreaming:true}}
  const build = runtimeBuild({implementationSha256:Object.fromEntries([...coreComponents,...additionalComponents].map(([name])=>[name,'a'.repeat(64)]))})
  return {
    schemaVersion:'upgrade-baseline-v1',mode:'COLLECTED_NOT_REVIEWED',cleanupErrors:[],runtime:{configuration,sha256:digest(configuration)},
    runtimeAfter:{configuration:structuredClone(configuration),sha256:digest(configuration)},runtimeBuild:build,runtimeBuildAfter:structuredClone(build),
    datasetSha256:'b'.repeat(64),corpusSha256:'c'.repeat(64),sourceStateSha256:'d'.repeat(64),candidateCommit:'e'.repeat(40),
    selectedCaseIds:expected.map(item=>item.id),records:expected.map((item,index)=>({caseId:item.id,verdict:'PENDING_REVIEW',turns:[{
      runId:'run-'+(enabled?'candidate':'baseline')+index,prompt:item.turns[0],status:'COMPLETED',answer:'已采集回答',ttftMs:100+index*100+(enabled?10:0),totalMs:500+index*100+(enabled?20:0)
    }]}))
  }
}

test('complete collection remains pending quality review and compares paired polling timings',()=>{
  const before=fixture(),after=fixture(true),original=JSON.stringify(before)
  assert.equal(collectionStatus(before,expected).exitCode,0)
  const report=compareSwitch(before,after,'skills',expected)
  assert.equal(report.turns,2);assert.equal(report.summary.ttftMs.delta.median,10)
  assert.equal(report.summary.totalMs.delta.p95,20);assert.equal(report.qualityReview,'REQUIRED')
  assert.equal(report.releaseDecision,'NOT_EVALUATED');assert.equal(JSON.stringify(before),original)
})
test('setup failure, incomplete turn and cleanup failure return failing collection codes',()=>{
  assert.equal(collectionStatus({mode:'SETUP_BLOCKED'},expected).exitCode,2)
  for(const change of [
    r=>r.cleanupErrors.push('LOGOUT_UNVERIFIED'),r=>r.records[0].turns.pop(),
    r=>delete r.schemaVersion,
    r=>r.records[0].turns[0].status='FAILED',r=>r.records[0].turns[0].answer='',
    r=>r.records[0].turns[0].prompt='changed',r=>r.records[0].turns[0].runId=r.records[1].turns[0].runId,
    r=>r.selectedCaseIds=[],r=>r.selectedCaseIds.push(r.selectedCaseIds[0])
  ]){
    const report=fixture();change(report);assert.equal(collectionStatus(report,expected).exitCode,1)
  }
})
test('configuration or loaded code drift during collection is rejected',()=>{
  for(const change of [
    r=>delete r.runtimeAfter,r=>delete r.runtimeBuildAfter,
    r=>r.runtimeBuildAfter.components.TaskSkillRegistry='b'.repeat(64),
    r=>{r.runtimeAfter.configuration.upgradeFeatures.skills=true;r.runtimeAfter.sha256=digest(r.runtimeAfter.configuration)}
  ]){
    const report=fixture();change(report);assert.equal(collectionStatus(report,expected).exitCode,1)
  }
})
test('different data, commit, source or loaded code prevents a paired comparison',()=>{
  for(const change of [
    r=>r.datasetSha256='f'.repeat(64),r=>r.corpusSha256='f'.repeat(64),r=>r.sourceStateSha256='f'.repeat(64),
    r=>r.candidateCommit='f'.repeat(40),
    r=>{r.runtimeBuild=runtimeBuild({implementationSha256:Object.fromEntries([...coreComponents,...additionalComponents].map(([name])=>[name,'f'.repeat(64)]))});r.runtimeBuildAfter=structuredClone(r.runtimeBuild)}
  ]){
    const after=fixture(true);change(after);assert.throws(()=>compareSwitch(fixture(),after,'skills',expected))
  }
})
test('only one configured switch may change',()=>{
  assert.throws(()=>compareSwitch(fixture(),fixture(),'skills',expected),/ONE_CHANGED_SWITCH_REQUIRED/)
  const after=fixture(true)
  after.runtime.configuration.upgradeFeatures.contextAssembly=true
  after.runtime.sha256=digest(after.runtime.configuration);after.runtimeAfter=structuredClone(after.runtime)
  assert.throws(()=>compareSwitch(fixture(),after,'skills',expected),/OTHER_CONFIGURATION_CHANGED/)
  assert.throws(()=>compareSwitch(fixture(),fixture(true),'unknown',expected),/SUPPORTED_SWITCH_REQUIRED/)
})
test('repeated Runs, different selection and invalid latency cannot create improvement claims',()=>{
  const repeated=fixture(true);repeated.records[0].turns[0].runId=fixture().records[0].turns[0].runId
  assert.throws(()=>compareSwitch(fixture(),repeated,'skills',expected),/INDEPENDENT_PAIRED_RUNS_REQUIRED/)
  const partial=fixture(true);partial.selectedCaseIds.pop()
  assert.throws(()=>compareSwitch(fixture(),partial,'skills',expected),/PAIRED_CASES_REQUIRED/)
  for(const value of [null,-1,NaN,Infinity,90000000]){
    const invalid=fixture(true);invalid.records[0].turns[0].ttftMs=value
    assert.throws(()=>compareSwitch(fixture(),invalid,'skills',expected),/TIMING_UNAVAILABLE/)
  }
  const invalid=fixture(true);invalid.records[0].turns[0].ttftMs=999
  assert.throws(()=>compareSwitch(fixture(),invalid,'skills',expected),/TIMING_INCONSISTENT/)
})
test('configuration field order and case collection order do not change pairing',()=>{
  const after=fixture(true)
  after.selectedCaseIds.reverse();after.records.reverse()
  const config=after.runtime.configuration
  after.runtime.configuration=Object.fromEntries(Object.entries(config).reverse());after.runtime.sha256=digest(after.runtime.configuration);after.runtimeAfter=structuredClone(after.runtime)
  assert.equal(compareSwitch(fixture(),after,'skills',expected).turns,2)
})
test('runtime component snapshots select only manifest hashes',()=>{
  assert.throws(()=>runtimeBuild({implementationSha256:{}}),/RUNTIME_COMPONENTS_UNAVAILABLE/)
  const hashes=Object.fromEntries([...coreComponents,...additionalComponents].map(([name])=>[name,'a'.repeat(64)]))
  hashes.apiKey='private';assert.equal(JSON.stringify(runtimeBuild({implementationSha256:hashes})).includes('private'),false)
})
