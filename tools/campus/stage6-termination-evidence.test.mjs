import test from 'node:test'
import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {verifyTerminatedApproval,verifyTerminationCombination} from './stage6-termination-evidence.mjs'
import {combinationFeatures} from './upgrade-combination-verify.mjs'
import {coreComponents,additionalComponents} from './runtime-component-manifest.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import fs from 'node:fs'
import path from 'node:path'
import {spawnSync} from 'node:child_process'

function fixture(status) {
  const events=[{runId:'run-a',eventId:1,type:'run.started',payload:{}},
    {runId:'run-a',eventId:2,type:'approval.required',payload:{approvalId:'preview-a',teamChange:{proposalId:'preview-a'}}},
    {runId:'run-a',eventId:3,type:status==='cancelled'?'run.cancelled':'run.timed_out',payload:{}}]
  const bytes=JSON.stringify(events)
  return {events,bytes,reference:{runId:'run-a',status,proposalId:'preview-a',eventsSha256:digest(bytes)}}
}
test('cancelled and timed-out previews preserve terminal state during duplicate and late-event replay',()=>{
  for(const status of ['cancelled','timed_out']) {
    const f=fixture(status)
    assert.equal(verifyTerminatedApproval(f.bytes,f.reference).approvalCleared,true)
  }
})
test('approval identity, terminal identity, ordering and source bytes remain bound',()=>{
  for(const mutate of [f=>f.events[1].payload.approvalId='foreign',
    f=>f.events[1].payload.teamChange.proposalId='foreign',
    f=>f.events[2].type='run.completed',f=>f.events.push({...f.events[0],eventId:4}),
    f=>f.events[2].eventId=2]) {
    const f=fixture('cancelled');mutate(f)
    const bytes=JSON.stringify(f.events)
    assert.throws(()=>verifyTerminatedApproval(bytes,{...f.reference,eventsSha256:digest(bytes)}))
  }
  const f=fixture('timed_out')
  assert.throws(()=>verifyTerminatedApproval(f.bytes,{...f.reference,eventsSha256:'changed'}))
})

function combination() {
  const files=new Map()
  function group(mode) {
    const configuration={upgradeFeatures:Object.fromEntries(combinationFeatures.map(key=>[key,mode==='enabled']))}
    const runtime={configuration,sha256:digest(configuration)}
    const build=runtimeBuild({implementationSha256:Object.fromEntries([...coreComponents,...additionalComponents].map(([name])=>[name,'a'.repeat(64)]))})
    const team={dataReleaseId:'pokeapi-reference-v1',members:[{build:{level:51}}]}
    const report={schemaVersion:'stage6-termination-browser-v1',mode,status:'PASS',pageErrors:[],cleanup:['LOGGED_OUT'],
      runtime,runtimeAfter:structuredClone(runtime),runtimeBuild:build,runtimeBuildAfter:build,
      teamBefore:team,teamAfter:structuredClone(team),semanticState:{ready:true},guideCatalog:{id:'fixture'},
      producerSha256:'b'.repeat(64),frontendSha256:{fixture:'a'.repeat(64)},attempts:[],records:[]}
    for(const status of ['cancelled','timed_out']) {
      const f=fixture(status),runId=mode+'-'+status
      f.events.forEach(event=>event.runId=runId)
      const bytes=JSON.stringify(f.events),file=status+'-events.json';files.set(mode+file,bytes)
      report.attempts.push({runId,dataReleaseId:team.dataReleaseId})
      report.records.push({runId,status,proposalId:'preview-a',eventsFile:file,eventsSha256:digest(bytes),
        preview:{status:'PENDING',preview:{proposalId:'preview-a'}},lateDecisionStatus:status==='timed_out'?410:409,
        outcome:{status:status==='timed_out'?'EXPIRED':'CANCELLED'},approvalButtonsHidden:true,reloadApprovalHidden:true,
        teamAfter:structuredClone(team)})
    }
    return report
  }
  return {enabled:group('enabled'),disabled:group('disabled'),read:(mode,file)=>files.get(mode+file)}
}
test('paired approval termination evidence keeps release and human review separate',()=>{
  const c=combination(),report=verifyTerminationCombination(c.enabled,c.disabled,c.read)
  assert.equal(report.runs,4);assert.equal(report.releaseDecision,'NOT_EVALUATED')
})
test('version drift, late writes, live buttons and accepted expired previews block paired verification',()=>{
  for(const change of [c=>c.enabled.attempts[1].dataReleaseId='wrong',
    c=>c.disabled.records[0].teamAfter.members[0].build.level=52,
    c=>c.enabled.records[1].lateDecisionStatus=200,
    c=>c.enabled.records[0].approvalButtonsHidden=false,
    c=>c.disabled.records[1].reloadApprovalHidden=false,
    c=>c.disabled.frontendSha256.fixture='changed',
    c=>c.enabled.runtimeAfter.configuration.upgradeFeatures.rerank=true]) {
    const c=combination();change(c);assert.throws(()=>verifyTerminationCombination(c.enabled,c.disabled,c.read))
  }
})

test('termination verifier CLI accepts timed_out filenames and rejects paths outside its report directory',()=>{
  fs.mkdirSync('artifacts',{recursive:true})
  const artifactRoot=fs.realpathSync('artifacts')
  const directory=fs.mkdtempSync(path.join(artifactRoot,'termination-verifier-test-'))
  assert.equal(path.dirname(fs.realpathSync(directory)),artifactRoot)
  const c=combination(),script=path.resolve('tools/campus/upgrade-termination-combination-verify.mjs')
  try {
    for(const mode of ['enabled','disabled']) {
      const target=path.join(directory,mode);fs.mkdirSync(target)
      fs.writeFileSync(path.join(target,'browser-result.json'),JSON.stringify(c[mode]))
      for(const row of c[mode].records)fs.writeFileSync(path.join(target,row.eventsFile),c.read(mode,row.eventsFile))
    }
    const invoke=output=>spawnSync(process.execPath,[script,path.join(directory,'enabled'),path.join(directory,'disabled'),output],{encoding:'utf8',timeout:10000})
    const output=path.join(directory,'verified.json'),result=invoke(output)
    assert.equal(result.status,0,result.stderr)
    assert.equal(JSON.parse(fs.readFileSync(output)).runs,4)
    c.enabled.records[1].eventsFile='../escape.json'
    fs.writeFileSync(path.join(directory,'enabled','browser-result.json'),JSON.stringify(c.enabled))
    const invalid=path.join(directory,'invalid.json')
    assert.notEqual(invoke(invalid).status,0);assert.equal(fs.existsSync(invalid),false)
  }finally {fs.rmSync(directory,{recursive:true,force:true})}
})
