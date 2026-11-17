import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {spawnSync} from 'node:child_process'
import {digest} from './evaluation.mjs'
import {verifyPartialTermination,verifyPartialCombination} from './partial-output-evidence.mjs'
import {publicFingerprint} from './upgrade-baseline.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {combinationFeatures} from './upgrade-combination-verify.mjs'
import {coreComponents,additionalComponents} from './runtime-component-manifest.mjs'

function fixture(status,runId='run-fixture') {
  const events=[{runId,eventId:1,type:'run.started',payload:{}},
    {runId,eventId:2,type:'answer.delta',payload:{text:'部分测试回答'}},
    {runId,eventId:3,type:status==='cancelled'?'run.cancelled':'run.timed_out',payload:{}}]
  const bytes=JSON.stringify(events)
  return {events,bytes,row:{runId,status,cursor:'2',eventsSha256:digest(bytes),uiPartialOnce:true,uiNotCompleted:true,runCreations:1,
    providerBefore:{decisions:0,streams:0,tailAttempts:0},providerAfter:{decisions:1,streams:1,tailAttempts:1}}}
}
test('partial answer exists before cancellation or deadline and remains unique after replay',()=>{
  for(const status of ['cancelled','timed_out']){
    const f=fixture(status);assert.equal(verifyPartialTermination(f.bytes,f.row).lateProviderAttempt,true)
  }
})
test('late output, missing partial text, provider restarts and absent tail attempts fail verification',()=>{
  for(const mutate of [f=>f.row.cursor='1',f=>f.row.cursor='3',f=>f.events.push({runId:'run-fixture',eventId:4,type:'fact.card',payload:{}}),
    f=>f.row.providerAfter.streams=2,f=>f.row.providerAfter.tailAttempts=0,
    f=>f.events[1].payload.text='fabricated',f=>f.events[2].runId='foreign']){
    const f=fixture('cancelled');mutate(f);const bytes=JSON.stringify(f.events)
    assert.throws(()=>verifyPartialTermination(bytes,{...f.row,eventsSha256:digest(bytes)}))
  }
})
function combination() {
  const files=new Map()
  const build=runtimeBuild({implementationSha256:Object.fromEntries([...coreComponents,...additionalComponents].map(([key])=>[key,'a'.repeat(64)]))})
  function group(mode){
    const runtime=publicFingerprint({upgradeFeatures:Object.fromEntries(combinationFeatures.map(key=>[key,mode==='enabled']))})
    const records=['cancelled','timed_out'].map(status=>{
      const f=fixture(status,mode+'-'+status),file=status+'-partial-events.json';files.set(mode+file,f.bytes)
      return {...f.row,eventsFile:file}
    })
    return {schemaVersion:'partial-output-browser-v1',mode,status:'PASS',pageErrors:[],runtime,runtimeAfter:structuredClone(runtime),
      runtimeBuild:build,runtimeBuildAfter:build,producerSha256:'b'.repeat(64),frontendSha256:{fixture:'a'.repeat(64)},records}
  }
  return {enabled:group('enabled'),disabled:group('disabled'),read:(mode,file)=>files.get(mode+file)}
}
test('partial combination binds four distinct Runs and leaves quality benefit unmeasured',()=>{
  const c=combination(),report=verifyPartialCombination(c.enabled,c.disabled,c.read)
  assert.equal(report.runs,4);assert.equal(report.qualityBenefit,'NOT_EVALUATED')
  for(const mutate of [c=>c.disabled.records[0].runId=c.enabled.records[0].runId,
    c=>c.enabled.records[0].runCreations=2,c=>c.disabled.frontendSha256.fixture='changed',
    c=>c.enabled.runtimeAfter.configuration.upgradeFeatures.rerank=true]){
    const c=combination();mutate(c);assert.throws(()=>verifyPartialCombination(c.enabled,c.disabled,c.read))
  }
})
test('controlled browser guard refuses personal backend and remote control before launching Chrome',()=>{
  const script=path.resolve('tools/campus/partial-output-browser.mjs')
  for(const env of [{VIO_PARTIAL_TEST_API:'http://127.0.0.1:8123/api'},
    {VIO_PARTIAL_TEST_API:'https://example.com/api'},
    {VIO_PARTIAL_TEST_API:'http://127.0.0.1:18124/api',VIO_PARTIAL_CONTROL:'https://example.com/control'}]){
    const result=spawnSync(process.execPath,[script],{env:{...process.env,...env},encoding:'utf8',timeout:5000})
    assert.notEqual(result.status,0);assert.doesNotMatch(result.stderr,/browserType.launch/)
  }
  assert.ok(fs.existsSync(script))
})

test('partial verifier CLI accepts underscore names and rejects report-directory traversal',()=>{
  fs.mkdirSync('artifacts',{recursive:true})
  const artifacts=fs.realpathSync('artifacts'),directory=fs.mkdtempSync(path.join(artifacts,'partial-verifier-test-'))
  assert.equal(path.dirname(fs.realpathSync(directory)),artifacts)
  const c=combination(),script=path.resolve('tools/campus/upgrade-partial-combination-verify.mjs')
  try {
    for(const mode of ['enabled','disabled']){
      const target=path.join(directory,mode);fs.mkdirSync(target)
      fs.writeFileSync(path.join(target,'partial-browser.json'),JSON.stringify(c[mode]))
      for(const row of c[mode].records)fs.writeFileSync(path.join(target,row.eventsFile),c.read(mode,row.eventsFile))
    }
    const output=path.join(directory,'verified.json')
    const invoke=file=>spawnSync(process.execPath,[script,path.join(directory,'enabled'),path.join(directory,'disabled'),file],{encoding:'utf8',timeout:10000})
    assert.equal(invoke(output).status,0)
    assert.equal(JSON.parse(fs.readFileSync(output)).runs,4)
    c.enabled.records[0].eventsFile='../foreign.json'
    fs.writeFileSync(path.join(directory,'enabled','partial-browser.json'),JSON.stringify(c.enabled))
    const rejected=path.join(directory,'rejected.json')
    assert.notEqual(invoke(rejected).status,0);assert.equal(fs.existsSync(rejected),false)
  }finally{fs.rmSync(directory,{recursive:true,force:true})}
})
