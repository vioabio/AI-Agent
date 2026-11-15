import test from 'node:test'
import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {coreComponents,additionalComponents} from './runtime-component-manifest.mjs'
import {compareCombination,combinationFeatures} from './upgrade-combination-verify.mjs'

const expected=[{id:'U0-FACTS-05',split:'dev',turns:['问题']}]
function fixture(enabled) {
  const configuration={configuredProvider:'fixture',configuredModelId:'fixture',upgradeFeatures:Object.fromEntries(combinationFeatures.map(key=>[key,enabled]))}
  const build=runtimeBuild({implementationSha256:Object.fromEntries([...coreComponents,...additionalComponents].map(([key])=>[key,'a'.repeat(64)]))})
  return {schemaVersion:'upgrade-baseline-v1',suiteKind:'KNOWN_HELDOUT_REGRESSION',mode:'COLLECTED_NOT_REVIEWED',cleanupErrors:[],
    candidateCommit:'e'.repeat(40),datasetSha256:'b'.repeat(64),corpusSha256:'c'.repeat(64),sourceStateSha256:'d'.repeat(64),
    runtime:{configuration,sha256:digest(configuration)},runtimeAfter:{configuration:structuredClone(configuration),sha256:digest(configuration)},
    runtimeBuild:build,runtimeBuildAfter:structuredClone(build),candidateGuideCatalog:{corpusSha256:'f'.repeat(64)},
    candidateSemanticState:{enabled:true,configured:true,ready:true,mode:'HYBRID',model:'fixture',dimensions:1024,indexedChunks:30},
    selectedCaseIds:['U0-FACTS-05'],records:[{caseId:'U0-FACTS-05',verdict:'PENDING_REVIEW',turns:[{runId:enabled?'run-enabled':'run-disabled',prompt:'问题',status:'COMPLETED',answer:'正文'}]}]}
}
test('four-feature rollback preserves original records and requires separate content review',()=>{
  const a=fixture(true),b=fixture(false),original=JSON.stringify([a,b])
  const result=compareCombination(a,b,expected)
  assert.equal(result.cases,1);assert.equal(result.turnsPerGroup,1)
  assert.equal(result.contentReview,'REQUIRED');assert.equal(result.singleFeatureBenefit,'NOT_EVALUATED')
  assert.equal(JSON.stringify([a,b]),original)
})
test('unrelated settings, semantic scope, actual corpus or source drift cannot be paired',()=>{
  for(const change of [r=>r.sourceStateSha256='0'.repeat(64),r=>r.candidateGuideCatalog.corpusSha256='0'.repeat(64),
    r=>r.candidateSemanticState.model='other',r=>{r.runtime.configuration.configuredModelId='other';r.runtime.sha256=digest(r.runtime.configuration);r.runtimeAfter=structuredClone(r.runtime)}]) {
    const disabled=fixture(false);change(disabled);assert.throws(()=>compareCombination(fixture(true),disabled,expected))
  }
})
test('incomplete, reused or partially disabled Runs cannot pass rollback evidence checks',()=>{
  for(const change of [r=>r.cleanupErrors.push('failed'),r=>r.records[0].turns[0].runId='run-enabled',r=>r.records[0].turns.pop(),
    r=>{r.runtime.configuration.upgradeFeatures.skills=true;r.runtime.sha256=digest(r.runtime.configuration);r.runtimeAfter=structuredClone(r.runtime)}]) {
    const disabled=fixture(false);change(disabled);assert.throws(()=>compareCombination(fixture(true),disabled,expected))
  }
})
