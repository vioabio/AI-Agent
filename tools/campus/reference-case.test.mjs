import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { digest, loadSuite, frozenFileDigest } from './evaluation.mjs'
import { resolveRuntimeClasses, factProjection, checkReferenceCase, validateReferenceSuite } from './reference-case.mjs'
import { referenceFromSnapshots } from './build-user-report-seed.mjs'
const suite = loadSuite('evaluation/campus/user-reports-v1')
const item = suite.cases[0], fixture = suite.fixtures.references.squirtle
const call = (query='squirtle',release='pokeapi-reference-v1') => {
  const inputEvidence={schemaVersion:'tool-input-evidence-v1',scope:'SUPPLIED_ARGUMENTS_ONLY',coverage:'COMPLETE',toolVersion:'fixture-v1',parameterDigest:'a'.repeat(64),
    fields:{'/query':{kind:'SHA256_JSON',sha256:digest(JSON.stringify(query))},'/dataReleaseId':{kind:'LITERAL',value:release}}}
  return [{type:'tool.started',payload:{governanceEventId:'call-1',toolName:'lookupPokemonReference',inputEvidence}},
    {type:'tool.finished',payload:{governanceEventId:'call-1',toolName:'lookupPokemonReference',status:'EXECUTED',toolVersion:'fixture-v1',parameterDigest:'a'.repeat(64)}}]
}
const record=()=>({referenceBefore:fixture.fact,referenceAfter:fixture.fact,answer:'杰尼龟是水属性；当前强势阵容缺少可核对证据。'})
test('provisional two-report suite remains development only with original origins and no formal admission',()=>{
  validateReferenceSuite(suite)
  assert.equal(suite.manifest.caseCount,2);assert.equal(suite.manifest.newFormalAdmissions,0)
  assert.equal(suite.splits.heldout.length,0);assert.equal(suite.cases[1].annotation.originKind,'USER_REPORTED_APP_PROMPT')
  assert.equal(suite.fixtures.references.froslass.fact.speed,110)
  assert.equal(suite.fixtures.references.froslass.fact.type2,'幽灵')
})
test('matching snapshots and bound target arguments pass only finite engineering checks',()=>{
  const result=checkReferenceCase(item,fixture,record(),call())
  assert.ok(Object.values(result.checks).every(Boolean));assert.deepEqual(result.lookup.matchingCallIds,['call-1'])
  const wrong=checkReferenceCase(item,fixture,record(),call('froslass'))
  assert.equal(wrong.checks.referenceInputVerified,false)
  assert.equal(checkReferenceCase(item,fixture,record(),call('squirtle','other-release')).checks.referenceInputVerified,false)
  assert.equal(checkReferenceCase(item,fixture,record(),[]).checks.referenceInputVerified,false)
})
test('wrong API facts, planning leakage, internal identifiers and missing requested species stay failures',()=>{
  const changed=record();changed.referenceAfter={...fixture.fact,hp:999}
  assert.equal(checkReferenceCase(item,fixture,changed,call()).checks.referenceAfterMatches,false)
  const leak=record();leak.answer='杰尼龟 checksum sha256:private'
  assert.equal(checkReferenceCase(item,fixture,leak,call()).checks.publicAnswerFormat,false)
  const missing=record();missing.answer='只有通用攻略'
  assert.equal(checkReferenceCase(item,fixture,missing,call()).checks.requestedSpeciesPresent,false)
})
test('optional lookup requires the exact current UI question with verified fact fields',()=>{
  const changed=structuredClone(suite);changed.cases[1].prompt='介绍雪妖女';assert.throws(()=>validateReferenceSuite(changed),/exact verified/)
  const wrong=structuredClone(suite);wrong.fixtures.references.froslass.fact.dataReleaseId='other';assert.throws(()=>validateReferenceSuite(wrong),/version/)
  const snow=suite.fixtures.references.froslass
  assert.equal(checkReferenceCase(suite.cases[1],snow,{referenceBefore:snow.fact,referenceAfter:snow.fact,answer:'雪妖女资料'},[]).checks.referenceInputVerified,true)
})
test('runtime class lookup derives actual packages and rejects absent, ambiguous or unsafe names',()=>{
  const sources={'src/main/java/com/vio/config/EvaluationToolPolicy.java':'source','src/main/java/com/vio/controller/LocalRuntimeController.java':'source'}
  assert.equal(resolveRuntimeClasses(sources,{EvaluationToolPolicy:'b'.repeat(64)})[0].file,'target/classes/com/vio/config/EvaluationToolPolicy.class')
  assert.throws(()=>resolveRuntimeClasses(sources,{Missing:'b'.repeat(64)}),/Missing/)
  assert.throws(()=>resolveRuntimeClasses({...sources,'src/main/java/other/EvaluationToolPolicy.java':'source'},{EvaluationToolPolicy:'b'.repeat(64)}),/ambiguous/)
  assert.throws(()=>resolveRuntimeClasses(sources,{'../private':'b'.repeat(64)}),/Invalid/)
})
test('paired source extraction keeps base stats and rejects wrong species mappings',()=>{
  const stats=['hp','attack','defense','special-attack','special-defense','speed'].map((name,i)=>({stat:{name},base_stat:40+i}))
  const p={body:{id:7,name:'squirtle',species:{name:'squirtle'},stats,types:[{type:{name:'water'}}],abilities:[{ability:{name:'torrent'}}]},envelope:{url:'https://pokeapi.co/api/v2/pokemon/7/',sha256:'sha256:p'}}
  const s={body:{id:7,name:'squirtle',names:[{language:{name:'zh-Hans'},name:'杰尼龟'}]},envelope:{sha256:'sha256:s'}}
  const result=referenceFromSnapshots(p,s)
  assert.equal(result.fact.spAttack,43);assert.equal(result.fact.type1,'水');assert.equal(result.fact.type2,null)
  assert.equal(result.fact.checksum,'sha256:'+digest('sha256:psha256:s'))
  assert.equal(factProjection({fact:result.fact}).hp,40)
  assert.throws(()=>referenceFromSnapshots(p,{...s,body:{...s.body,name:'other'}}),/Mismatched/)
})

test('collector supports a separate suite while default frozen benchmark membership stays unchanged',()=>{
  for(const args of [['validate'],['validate','--suite','evaluation/campus/user-reports-v1']]) {
    const result=spawnSync(process.execPath,['tools/campus/s2-evaluate.mjs',...args],{encoding:'utf8'})
    assert.equal(result.status,0,result.stderr)
    assert.equal(JSON.parse(result.stdout).cases,args.length===1?36:2)
  }
})

test('report preserves not-run denominator and rejects mismatched collection identity',()=>{
  fs.mkdirSync('tmp',{recursive:true});const folder=fs.mkdtempSync(path.resolve('tmp/user-report-recompute-'))
  const capture={datasetId:suite.manifest.datasetId,datasetSha256:frozenFileDigest('evaluation/campus/user-reports-v1/manifest.json'),
    suiteUnchanged:true,selectedCaseIds:suite.cases.map(c=>c.id),repetitions:2,mode:'fixture-not-real',startedAt:'fixture',
    records:suite.cases.flatMap(c=>[1,2].map(repetition=>({caseId:c.id,repetition,status:'NOT_RUN',answer:'',toolOutcomes:[],checks:{collected:false}})))}
  const file=path.join(folder,'observations.json');fs.writeFileSync(file,JSON.stringify(capture))
  const result=spawnSync(process.execPath,['tools/campus/s2-evaluate.mjs','report','--suite','evaluation/campus/user-reports-v1','--report',file,'--output',path.join(folder,'report')],{encoding:'utf8'})
  assert.equal(result.status,0,result.stderr);const summary=JSON.parse(fs.readFileSync(path.join(folder,'report/summary.json')))
  assert.equal(summary.denominator,4);assert.equal(summary.fail,4);assert.equal(summary.pass,0)
  capture.sourcesUnchanged=false;fs.writeFileSync(file,JSON.stringify(capture))
  const bad=spawnSync(process.execPath,['tools/campus/s2-evaluate.mjs','report','--suite','evaluation/campus/user-reports-v1','--report',file,'--output',path.join(folder,'rejected')],{encoding:'utf8'})
  assert.notEqual(bad.status,0);assert.ok(!fs.existsSync(path.join(folder,'rejected')))
})
