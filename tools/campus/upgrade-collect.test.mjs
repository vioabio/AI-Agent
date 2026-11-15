import test from 'node:test'
import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {spawn} from 'node:child_process'
import {mkdtempSync, readFileSync, rmSync, realpathSync} from 'node:fs'
import {join, resolve} from 'node:path'
import {coreComponents,additionalComponents} from './runtime-component-manifest.mjs'
import {stage2Team} from './stage2-regression.mjs'
import {loadIndependentSuite} from './independent-suite.mjs'

async function collect(mode) {
  const artifacts = realpathSync('artifacts')
  const parent = mkdtempSync(join(artifacts,'collection-cli-'))
  const directory = join(parent,'sample')
  let runtimeReads=0, runsCreated=0, logouts=0, teamsCreated=0
  const server=createServer((request,response)=>{
    request.resume();response.setHeader('Content-Type','application/json')
    const reply=(code,body)=>{response.statusCode=code;response.end(JSON.stringify(body))}
    if(request.url==='/api/auth/session')return reply(200,{mode:'local'})
    if(request.url==='/api/auth/local/register')return reply(201,{})
    if(request.url==='/api/auth/local/login')return reply(200,{})
    if(request.url==='/api/logout'){logouts++;response.statusCode=204;return response.end()}
    if(request.url==='/api/pokemon/calculations/engine-damage')return reply(mode==='s3-engine-failed'?503:200,{engineVersion:'fixture-only',minDamage:1,maxDamage:2})
    if(request.url==='/api/pokemon/teams'&&request.method==='POST'){teamsCreated++;return reply(201,{...stage2Team(),id:1,ownerId:'fixture'})}
    if(request.url==='/api/pokemon/guides/semantic')return reply(200,{enabled:true,configured:true,ready:true,mode:'HYBRID',state:'READY'})
    if(request.url==='/api/pokemon/guides/catalog')return reply(200,{catalogId:'fixture',corpusSha256:mode.startsWith('independent-v')?loadIndependentSuite().manifest.corpus.sha256:'fixture'})
    if(request.url==='/api/pokemon/guides/index')return reply(200,{fingerprint:'fixture'})
    if(request.url==='/api/pokemon/teams/1'){
      const team=stage2Team()
      if(mode==='s2-team-drift'&&runsCreated>0)team.members[0].build.level=52
      return reply(200,{...team,id:1,ownerId:'fixture'})
    }
    if(request.url==='/api/chat/runtime'){
      runtimeReads++
      if(mode==='setup-failed')return reply(503,{})
      const hashes=Object.fromEntries([...coreComponents,...additionalComponents].map(([name])=>[name,'a'.repeat(64)]))
      if(mode==='drift'&&runtimeReads>1)hashes.TaskSkillRegistry='b'.repeat(64)
      return reply(200,{configuredProvider:'fixture',configuredModelId:'fixture-model',upgradeFeatures:{skills:false,contextAssembly:false},implementationSha256:hashes})
    }
    if(request.url==='/api/chat/runs'&&request.method==='POST'){runsCreated++;return reply(202,{runId:'run-cli-fixture-'+runsCreated})}
    if(request.url?.includes('/events?'))return reply(200,{events:[
      {eventId:1,type:'answer.delta',payload:{text:'固定测试回答'}},
      {eventId:2,type:mode==='run-failed'?'run.failed':'run.completed',payload:{}}
    ]})
    if(/^\/api\/chat\/runs\/run-cli-fixture-\d+$/.test(request.url))return reply(200,{status:mode==='run-failed'?'FAILED':'COMPLETED',conversationId:'fixture-'+(runsCreated<=17?'A':runsCreated<=20?'B':runsCreated===21?'C':'D')})
    reply(404,{})
  })
  try {
    await new Promise(done=>server.listen(0,'127.0.0.1',done))
    const stage2=mode==='s2-regression'||mode==='s2-team-drift'
    const stage3=mode==='s3-regression'||mode==='s3-engine-failed'
    const stage4=mode==='s4-regression'
    const stage5=mode==='s5-regression'
    const child=spawn(process.execPath,[resolve('tools/campus/upgrade-collect.mjs'),directory,
      `http://127.0.0.1:${server.address().port}/api`,mode==='heldout'?'20':stage2?'14':stage4?'4':stage5?'2':'1',
      ...(mode.startsWith('independent')?['',mode==='independent-corpus-drift'?'independent-v2':mode]:mode==='heldout'?['','frozen','heldout']:mode==='frozen-regression'?['U0-FACTS-08','frozen-regression']:stage2?['','s2-regression']:stage3?['','s3-regression']:stage4?['','s4-regression']:stage5?['','s5-regression']:mode==='long-history'?['','long-history']:[])],{stdio:['ignore','pipe','pipe']})
    child.stdout.resume();let stderr='';child.stderr.on('data',chunk=>stderr+=chunk)
    const timeout=setTimeout(()=>child.kill(),30000)
    let code
    try {code=await new Promise((done,reject)=>{child.once('error',reject);child.once('close',done)})}
    finally {clearTimeout(timeout)}
    const report=JSON.parse(readFileSync(join(directory,'baseline.json'),'utf8'))
    assert.notEqual(code,null,stderr)
    assert.equal(logouts,1)
    return {code,report,runsCreated,teamsCreated}
  } finally {
    server.closeAllConnections();await new Promise(done=>server.close(done))
    assert.ok(realpathSync(parent).startsWith(join(artifacts,'collection-cli-')))
    rmSync(parent,{recursive:true,force:true})
  }
}

test('CLI exits zero only for a complete stable collection',{timeout:40000},async()=>{
  const result=await collect('success')
  assert.equal(result.code,0);assert.equal(result.runsCreated,1)
  assert.equal(result.report.collection.status,'COLLECTED_PENDING_REVIEW')
  assert.equal(result.report.runtimeBuild.sha256,result.report.runtimeBuildAfter.sha256)
})
test('heldout first-answer mode preserves all twenty cases and thirty-three turns',{timeout:40000},async()=>{
  const result=await collect('heldout')
  assert.equal(result.code,0);assert.equal(result.report.split,'heldout')
  assert.equal(result.report.records.length,20);assert.equal(result.runsCreated,33)
  assert.equal(result.report.suiteKind,'FROZEN_HELDOUT_FIRST_ANSWERS')
  assert.equal(result.teamsCreated,0)
  assert.ok(result.report.records.every(row=>row.verdict==='PENDING_REVIEW'))
})
test('known heldout retry is explicitly development regression and cannot replace first answers',{timeout:40000},async()=>{
  const result=await collect('frozen-regression')
  assert.equal(result.code,0);assert.equal(result.report.split,'dev')
  assert.equal(result.report.suiteKind,'KNOWN_HELDOUT_REGRESSION')
  assert.deepEqual(result.report.selectedCaseIds,['U0-FACTS-08'])
  assert.equal(result.runsCreated,1);assert.equal(result.teamsCreated,0)
})
test('CLI fails for failed Run and loaded component drift',{timeout:80000},async()=>{
  for(const mode of ['run-failed','drift']){
    const result=await collect(mode)
    assert.equal(result.code,1);assert.equal(result.report.collection.status,'INCOMPLETE')
  }
})
test('CLI reports setup failure and creates no model Run',{timeout:40000},async()=>{
  const result=await collect('setup-failed')
  assert.equal(result.code,2);assert.equal(result.runsCreated,0)
  assert.equal(result.report.collection.status,'SETUP_BLOCKED')
})
test('long history is collected as a separate development scenario with fifteen independent Runs',{timeout:40000},async()=>{
  const result=await collect('long-history')
  assert.equal(result.code,0);assert.equal(result.runsCreated,15)
  assert.equal(result.report.suiteKind,'LONG_HISTORY_DEVELOPMENT')
  assert.deepEqual(result.report.selectedCaseIds,['U3-LONG-HISTORY'])
  assert.equal(result.report.records.length,1);assert.equal(result.report.records[0].turns.length,15)
  assert.equal(Object.keys(result.report.producerDependenciesSha256).length,8)
})

test('new candidate collection binds its own frozen dataset without creating a private team',{timeout:40000},async()=>{
  const result=await collect('independent-v2')
  assert.equal(result.code,0);assert.equal(result.report.suiteKind,'NEW_FIRST_ANSWER_CANDIDATE')
  assert.equal(result.report.records.length,12);assert.equal(result.report.selectedCaseIds.length,1)
  assert.equal(result.runsCreated,1);assert.equal(result.teamsCreated,0)
  assert.equal(result.report.datasetSha256,loadIndependentSuite().datasetSha256)
  assert.equal(result.report.corpusSha256,loadIndependentSuite().manifest.corpus.sha256)
  assert.equal(result.report.rubricSha256,loadIndependentSuite().manifest.files['rubric.json'])
})

test('candidate retries are labelled known regression and wrong source corpus blocks collection',{timeout:40000},async()=>{
  const retry=await collect('independent-v2-regression')
  assert.equal(retry.code,0);assert.equal(retry.report.suiteKind,'KNOWN_INDEPENDENT_CANDIDATE_REGRESSION')
  const drift=await collect('independent-corpus-drift')
  assert.equal(drift.code,2);assert.equal(drift.runsCreated,0)
  assert.equal(drift.report.setupError,'CANDIDATE_CORPUS_MISMATCH')
})
test('overnight candidate and retry bind the v3 dataset and retain their separate labels',{timeout:80000},async()=>{
  const suite=loadIndependentSuite(resolve('evaluation/upgrade-v1.1/u6/independent-v3'))
  for(const mode of ['independent-v3','independent-v3-regression']){
    const result=await collect(mode)
    assert.equal(result.code,0)
    assert.equal(result.report.records.length,20)
    assert.equal(result.report.datasetSha256,suite.datasetSha256)
    assert.equal(result.report.suiteKind,mode.endsWith('-regression')?'KNOWN_INDEPENDENT_CANDIDATE_REGRESSION':'NEW_FIRST_ANSWER_CANDIDATE')
  }
})

test('stage2 collection creates the documented team and checks all fourteen case snapshots',{timeout:40000},async()=>{
  const result=await collect('s2-regression')
  assert.equal(result.code,0);assert.equal(result.runsCreated,15)
  assert.equal(result.report.suiteKind,'STAGE2_BUSINESS_REGRESSION')
  assert.equal(result.report.testTeam.unchanged,true)
  assert.equal(result.report.records.length,14)
  assert.ok(result.report.records.every(row=>row.teamUnchanged===true))
})

test('stage2 cannot report a clean collection after test team configuration changes',{timeout:40000},async()=>{
  const result=await collect('s2-team-drift')
  assert.equal(result.code,1)
  assert.equal(result.report.testTeam.unchanged,false)
  assert.ok(result.report.collection.errors.includes('CLEANUP_INCOMPLETE'))
})

test('stage3 records live direct-engine oracles before collecting model answers',{timeout:40000},async()=>{
  const result=await collect('s3-regression')
  assert.equal(result.code,0)
  assert.equal(result.report.suiteKind,'STAGE3_STANDARD_REGRESSION')
  assert.equal(result.report.damageOracles.baseline.input.attacker.build.evs.spa,252)
  assert.equal(result.report.damageOracles.changed.input.attacker.build.evs.spa,0)
  assert.deepEqual(result.report.notSelectedProceduralCases,['S3-10','S3-11','S3-12','S3-13'])
  assert.equal(result.report.testTeam.unchanged,true)
})

test('stage3 engine setup failure creates no model Run',{timeout:40000},async()=>{
  const result=await collect('s3-engine-failed')
  assert.equal(result.code,2)
  assert.equal(result.runsCreated,0)
  assert.equal(result.report.collection.status,'SETUP_BLOCKED')
})

test('stage4 preserves four independent conversations and separates filler from semantic turns',{timeout:40000},async()=>{
  const result=await collect('s4-regression')
  assert.equal(result.code,0);assert.equal(result.runsCreated,25)
  assert.equal(result.report.suiteKind,'STAGE4_CONVERSATION_REGRESSION')
  assert.equal(result.report.semanticCases,16);assert.equal(result.report.fillerTurns,9)
  assert.equal(result.report.records[0].turns.at(-1).semanticCaseId,'S4-L11')
  assert.equal(result.report.testTeam.unchanged,true)
})
test('stage5 records actual semantic diagnostics without creating private teams',{timeout:40000},async()=>{
  const result=await collect('s5-regression')
  assert.equal(result.code,0);assert.equal(result.runsCreated,2);assert.equal(result.teamsCreated,0)
  assert.equal(result.report.suiteKind,'STAGE5_GUIDE_ANSWER_REGRESSION')
  assert.ok(result.report.records.every(row=>row.turns[0].semanticStatus.mode==='HYBRID'))
})
