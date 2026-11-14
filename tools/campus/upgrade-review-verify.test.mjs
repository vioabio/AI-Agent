import test from 'node:test'
import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {verifyDevelopmentReview} from './upgrade-review-verify.mjs'
import {coreComponents,additionalComponents,componentManifestVersion,componentManifestSha256} from './runtime-component-manifest.mjs'

function fixture(){
 const events=JSON.stringify([{type:'answer.delta',payload:{text:'已核验事实'}}])
 const turn={runId:'run-test',prompt:'问题',answer:'已核验事实',status:'COMPLETED',eventsFile:'U0-FACTS-01-0-events.json',eventsSha256:digest(events)}
 const baseline={schemaVersion:'upgrade-baseline-v1',split:'dev',datasetSha256:'frozen',mode:'COLLECTED_NOT_REVIEWED',cleanupErrors:[],selectedCaseIds:['U0-FACTS-01'],records:[{caseId:'U0-FACTS-01',turns:[turn]}]}
 const baselineBytes=JSON.stringify(baseline)
 const review={schemaVersion:'u0-development-content-review-v1',scope:'DEVELOPMENT_SET_NOT_INDEPENDENT_HELDOUT_ACCEPTANCE',reviewMethod:'AI_ASSISTED_BODY_SOURCE_AND_TOOL_EVENT_REVIEW',sourceSha256:digest(baselineBytes),summary:{total:1,pass:1,fail:0,blocked:0},records:[{caseId:'U0-FACTS-01',verdict:'PASS',note:'逐项检查',evidence:[{runId:turn.runId,eventsFile:turn.eventsFile,eventsSha256:turn.eventsSha256,answerSha256:digest(turn.answer)}]}]}
 return {baseline,baselineBytes,review,readEvents:()=>events,expectedCases:[{id:'U0-FACTS-01',turns:['问题']}],datasetSha256:'frozen',comparison:{schemaVersion:'runtime-artifact-comparison-v1',status:'ARTIFACTS_MATCH',classes:['AccountScopeReply','DefaultRunExecutor','ToolCallAgent','GuideAnswerEvidence','VioManus'].map(name=>({name,matches:true,expected:'a'.repeat(64),actual:'a'.repeat(64)}))}}
}
test('valid existing review verifies only integrity, never release acceptance',()=>{
 const result=verifyDevelopmentReview(fixture());assert.equal(result.status,'VERIFIED');assert.equal(result.releaseDecision,'NOT_EVALUATED')
 assert.equal(result.buildCoverage,'LEGACY_ARCHIVE_ONLY')
})

function currentFixture(){
 const f=fixture(),rows=items=>items.map(([name])=>({name,matches:true,expected:'a'.repeat(64),actual:'a'.repeat(64)}))
 f.comparison={schemaVersion:'runtime-artifact-comparison-v3',componentManifestVersion,componentManifestSha256,status:'ARTIFACTS_MATCH',cleanup:'LOGGED_OUT',classes:rows(coreComponents),additionalComponents:rows(additionalComponents)}
 return f
}
test('current build evidence requires the complete versioned component manifest',()=>{
 const result=verifyDevelopmentReview(currentFixture())
 assert.equal(result.status,'VERIFIED');assert.equal(result.buildCoverage,'CURRENT_COMPONENT_MANIFEST')
 assert.equal(result.releaseDecision,'NOT_EVALUATED')
})
test('missing, duplicate, substituted and unknown helper identities cannot pass current coverage',()=>{
 for(const mutate of [
  f=>delete f.comparison.additionalComponents,
  f=>f.comparison.additionalComponents.pop(),
  f=>f.comparison.additionalComponents[1]={...f.comparison.additionalComponents[0]},
  f=>f.comparison.additionalComponents[0].name='UnrelatedHelper',
  f=>f.comparison.additionalComponents.push({...f.comparison.additionalComponents[0]}),
  f=>f.comparison.classes[0]=null,
  f=>f.comparison.additionalComponents[0]=null,
  f=>f.comparison.componentManifestSha256='b'.repeat(64),
  f=>f.comparison.componentManifestVersion='unknown',
  f=>delete f.comparison.cleanup,
  f=>f.comparison.cleanup='LOGOUT_UNVERIFIED'
 ]){
  const f=currentFixture();mutate(f)
  const result=verifyDevelopmentReview(f)
  assert.equal(result.status,'INVALID');assert.equal(result.buildCoverage,'CURRENT_MANIFEST_UNVERIFIED')
  assert.ok(result.errors.includes('BUILD_COMPARISON_UNVERIFIED'))
 }
})
test('a legacy report remains archival and cannot claim current component coverage',()=>{
 const f=currentFixture();f.comparison.schemaVersion='runtime-artifact-comparison-v2'
 delete f.comparison.additionalComponents
 assert.equal(verifyDevelopmentReview(f).buildCoverage,'LEGACY_ARCHIVE_ONLY')
})
test('edited baseline cannot reuse an old review',()=>{
 const f=fixture();f.baseline.records[0].turns[0].answer='被替换';f.baselineBytes=JSON.stringify(f.baseline)
 assert.ok(verifyDevelopmentReview(f).errors.includes('BASELINE_HASH_MISMATCH'))
})
test('even rehashed answer cannot diverge from actual event text',()=>{
 const f=fixture();f.baseline.records[0].turns[0].answer='被替换';f.baselineBytes=JSON.stringify(f.baseline);f.review.sourceSha256=digest(f.baselineBytes);f.review.records[0].evidence[0].answerSha256=digest('被替换')
 assert.ok(verifyDevelopmentReview(f).errors.includes('ANSWER_EVENT_MISMATCH'))
})
test('tampered event bytes or an escaping filename are rejected',()=>{
 let f=fixture();f.readEvents=()=> '[]';assert.ok(verifyDevelopmentReview(f).errors.includes('EVENT_HASH_MISMATCH'))
 f=fixture();f.baseline.records[0].turns[0].eventsFile='../private.json';f.baselineBytes=JSON.stringify(f.baseline)
 assert.ok(verifyDevelopmentReview(f).errors.includes('EVENT_FILE_INVALID_OR_UNAVAILABLE'))
})
test('cleanup failure, changed prompt, duplicate reviews and false summary are rejected',()=>{
 for(const mutate of [f=>f.baseline.cleanupErrors.push('LOGOUT_UNVERIFIED'),f=>f.baseline.records[0].turns[0].prompt='different',f=>f.review.records.push(f.review.records[0]),f=>f.review.summary.pass=2]){
  const f=fixture();mutate(f);f.baselineBytes=JSON.stringify(f.baseline);f.review.sourceSha256=digest(f.baselineBytes)
  assert.equal(verifyDevelopmentReview(f).status,'INVALID')
 }
})
test('five copies of one class cannot replace required build identities',()=>{
 const f=fixture();f.comparison.classes=f.comparison.classes.map(c=>({...c,name:'AccountScopeReply'}))
 assert.ok(verifyDevelopmentReview(f).errors.includes('BUILD_COMPARISON_UNVERIFIED'))
})
test('an optional component mismatch cannot be hidden behind the core class result',()=>{
 const f=fixture();f.comparison.additionalComponents=[{name:'LocalGuideSearch',expected:'a'.repeat(64),actual:'b'.repeat(64),matches:false}]
 assert.ok(verifyDevelopmentReview(f).errors.includes('ADDITIONAL_BUILD_COMPONENT_MISMATCH'))
})
test('v2 comparison must include the actual Run service and validate its resource hash',()=>{
 const f=fixture();f.comparison.schemaVersion='runtime-artifact-comparison-v2'
 assert.ok(verifyDevelopmentReview(f).errors.includes('BUILD_COMPARISON_UNVERIFIED'))
 f.comparison.classes.push({name:'PokemonAgentRunService',matches:true,expected:'b'.repeat(64),actual:'b'.repeat(64)})
 assert.equal(verifyDevelopmentReview(f).status,'VERIFIED')
 f.comparison.classes.at(-1).actual='c'.repeat(64)
 assert.ok(verifyDevelopmentReview(f).errors.includes('BUILD_COMPARISON_UNVERIFIED'))
})
