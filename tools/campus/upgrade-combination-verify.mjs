import assert from 'node:assert/strict'
import {readFileSync,writeFileSync,realpathSync} from 'node:fs'
import {resolve,relative,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest,sameJson} from './evaluation.mjs'
import {collectionStatus} from './upgrade-collection-status.mjs'
import {verifySelectedEvents} from './upgrade-event-evidence.mjs'
import {loadFrozenSuite} from './upgrade-baseline.mjs'

export const combinationFeatures=Object.freeze(['skills','contextAssembly','answerBlocks','domainTracing'])

export function compareCombinationConfiguration(enabled,disabled) {
  for(const runtime of [enabled,disabled])assert.equal(runtime.sha256,digest(runtime.configuration),'Public configuration digest mismatch')
  const before=structuredClone(enabled.configuration),after=structuredClone(disabled.configuration)
  for(const feature of combinationFeatures) {
    assert.equal(before.upgradeFeatures?.[feature],true)
    assert.equal(after.upgradeFeatures?.[feature],false)
    delete before.upgradeFeatures[feature];delete after.upgradeFeatures[feature]
  }
  assert.ok(sameJson(before,after),'Other public configuration changed')
  return combinationFeatures
}

/** A combined rollback check has its own scope and does not attribute gains to one feature. */
export function compareCombination(enabled,disabled,expectedCases) {
  for(const report of [enabled,disabled]) {
    assert.equal(report.suiteKind,'KNOWN_HELDOUT_REGRESSION')
    assert.equal(collectionStatus(report,expectedCases).exitCode,0,'Complete stable collection required')
  }
  for(const key of ['candidateCommit','datasetSha256','corpusSha256','sourceStateSha256']) {
    assert.match(enabled[key],key==='candidateCommit'?/^[a-f0-9]{40}$/:/^[a-f0-9]{64}$/)
    assert.equal(enabled[key],disabled[key],key+' changed')
  }
  assert.equal(enabled.runtimeBuild.sha256,disabled.runtimeBuild.sha256,'Running code changed')
  for(const report of [enabled,disabled]) {
    assert.match(report.candidateGuideCatalog?.corpusSha256??'',/^[a-f0-9]{64}$/,'Actual candidate corpus required')
    assert.equal(report.candidateSemanticState?.ready,true,'Ready semantic state required for this matrix')
    assert.equal(typeof report.candidateSemanticState.model,'string')
    assert.ok(Number.isInteger(report.candidateSemanticState.dimensions)&&report.candidateSemanticState.dimensions>0)
  }
  assert.ok(sameJson(enabled.candidateGuideCatalog,disabled.candidateGuideCatalog),'Actual candidate corpus changed')
  compareCombinationConfiguration(enabled.runtime,disabled.runtime)
  // Both groups must preserve the same semantic retrieval setup.
  const semanticFields=['enabled','configured','ready','mode','model','dimensions','indexedChunks']
  const semantic=report=>Object.fromEntries(semanticFields.map(key=>[key,report.candidateSemanticState?.[key]]))
  assert.ok(sameJson(semantic(enabled),semantic(disabled)),'Semantic configuration changed')
  assert.deepEqual([...enabled.selectedCaseIds].sort(),[...disabled.selectedCaseIds].sort())
  const runIds=new Set();let turns=0
  for(const id of enabled.selectedCaseIds) {
    const a=enabled.records.find(row=>row.caseId===id),b=disabled.records.find(row=>row.caseId===id)
    for(let index=0;index<a.turns.length;index++) {
      assert.equal(a.turns[index].prompt,b.turns[index].prompt)
      for(const turn of [a.turns[index],b.turns[index]]) {
        assert.ok(!runIds.has(turn.runId),'Repeated Run across groups');runIds.add(turn.runId)
      }
      turns++
    }
  }
  return {schemaVersion:'upgrade-combination-rollback-v1',scope:'KNOWN_CASE_COMBINED_ENABLE_DISABLE',
    changedFeatures:combinationFeatures,cases:enabled.selectedCaseIds.length,turnsPerGroup:turns,
    contentReview:'REQUIRED',singleFeatureBenefit:'NOT_EVALUATED',releaseDecision:'NOT_EVALUATED'}
}

function evidenceDirectory(input) {
  const directory=realpathSync(input),within=relative(realpathSync('artifacts'),directory)
  assert.ok(within&&!within.startsWith('..')&&!isAbsolute(within),'Use a directory inside artifacts')
  return directory
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const [enabledPath,disabledPath,output]=process.argv.slice(2)
  if(!enabledPath||!disabledPath||!output)throw Error('Usage: upgrade-combination-verify.mjs ENABLED_DIRECTORY DISABLED_DIRECTORY NEW_OUTPUT_JSON')
  const directories=[enabledPath,disabledPath].map(evidenceDirectory)
  const bytes=directories.map(directory=>readFileSync(resolve(directory,'baseline.json'),'utf8'))
  const reports=bytes.map(text=>JSON.parse(text)),suite=loadFrozenSuite()
  for(const report of reports) {
    assert.equal(report.datasetSha256,suite.datasetSha256)
    assert.equal(report.corpusSha256,suite.manifest.corpus.sha256)
  }
  const expected=suite.cases.filter(row=>row.split==='heldout').map(row=>({...row,split:'dev'}))
  const result=compareCombination(...reports,expected)
  result.events=reports.map((report,index)=>verifySelectedEvents(report,file=>{
    assert.match(file,/^[A-Z0-9-]+-[0-9]+-events\.json$/)
    return readFileSync(resolve(directories[index],file),'utf8')
  }))
  result.reportSha256=bytes.map(digest)
  writeFileSync(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify({cases:result.cases,turnsPerGroup:result.turnsPerGroup,contentReview:result.contentReview}))
}
