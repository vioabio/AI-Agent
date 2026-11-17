import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {randomBytes} from 'node:crypto'
import {localEvaluationSession} from './local-evaluation-session.mjs'
import {digest,localBase,eventBoundaryReached,sameJson} from './evaluation.mjs'
import {publicFingerprint} from './upgrade-baseline.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {verifyTurnEvents} from './upgrade-event-evidence.mjs'

const [directory,url]=process.argv.slice(2),output=path.resolve(directory)
assert.equal(path.dirname(output),fs.realpathSync('artifacts'));assert.equal(fs.existsSync(output),false);fs.mkdirSync(output)
const session=localEvaluationSession(localBase(url)),api=session.api
const fixture='http://127.0.0.1:4334'
const report={schemaVersion:'controlled-rerank-application-v1',scope:'SYNTHETIC_TRANSPORT_AND_FALLBACK',qualityBenefit:'NOT_EVALUATED',records:[],cleanup:[]}
let authenticated=false,activeRun
async function control(mode) {
  const r=await fetch(fixture+(mode?'/mode':'/status'),{method:mode?'POST':'GET',headers:{'Content-Type':'application/json'},...(mode?{body:JSON.stringify({mode})}:{})})
  assert.equal(r.status,200);return r.json()
}
try {
  const initial=await control();assert.equal(initial.fixture,'u6-controlled-rerank-v1')
  await session.login('精排受控集成');authenticated=true
  const runtime=await api('/chat/runtime');assert.equal(runtime.upgradeFeatures.rerank,true)
  report.runtime=publicFingerprint(runtime);report.runtimeBuild=runtimeBuild(runtime);report.catalog=await api('/pokemon/guides/catalog')
  for(const mode of ['READY','FAIL','DUPLICATE','MISSING','OUT_OF_RANGE','MALFORMED','TIMEOUT','OVERSIZED','REDIRECT','READY']) {
    await control(mode);const before=await control(),started=Date.now(),id='U6-RERANK-'+String(report.records.length+1).padStart(2,'0')
    const prompt='请分别说明招式的英文标识与中文显示名作用。'
    const run=await api('/chat/runs',{prompt,conversationId:'rerank-'+randomBytes(8).toString('hex'),gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',rulesetVersionId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',language:'zh-CN',timeoutSeconds:30},202)
    activeRun=run.runId;let status,events
    do {
      events=(await api('/chat/runs/'+run.runId+'/events?afterEventId=0')).events;status=(await api('/chat/runs/'+run.runId)).status
      if(eventBoundaryReached(status,events))break
      await new Promise(done=>setTimeout(done,100))
    }while(Date.now()-started<40000)
    const answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload.text).join(''),file=id+'-0-events.json',bytes=JSON.stringify(events,null,2)+'\n'
    fs.writeFileSync(path.join(output,file),bytes,{flag:'wx'})
    const turn={runId:run.runId,prompt,status,answer,eventsFile:file,eventsSha256:digest(bytes)}
    verifyTurnEvents(turn,id,0,bytes);activeRun=null
    const observations=events.filter(e=>e.type==='retrieval.finished'&&e.payload.rerankObservation).map(e=>e.payload.rerankObservation)
    assert.equal(observations.length,1);const observation=observations[0]
    assert.equal(observation.state,mode==='READY'?'RERANKED':'RRF_FALLBACK')
    const after=await control();assert.equal(after.requests,before.requests+1);assert.equal(after.redirects,0)
    const call=after.calls.at(-1);assert.equal(observation.ranks.length,call.documentHashes.length)
    for(const rank of observation.ranks) {
      const source=await api('/pokemon/guides/chunks/'+encodeURIComponent(rank.chunkId))
      assert.equal(digest(source.citation.text),call.documentHashes[rank.recallRank-1])
    }
    const selected=observation.ranks.filter(row=>row.selected)
    if(mode==='READY')assert.deepEqual(selected.map(row=>row.recallRank).sort((a,b)=>b-a),Array.from({length:selected.length},(_,i)=>observation.ranks.length-i))
    else assert.deepEqual(selected.map(row=>row.recallRank),Array.from({length:selected.length},(_,i)=>i+1))
    assert.ok(!answer.includes('CONTROLLED_PRIVATE_ERROR'))
    report.records.push({mode,turn,observation,providerCall:call,elapsedMs:Date.now()-started})
    console.log(id+': '+observation.state)
  }
  const beforeEmpty=await control()
  const empty=await api('/pokemon/guides/retrieve?'+new URLSearchParams({query:'qaemptyorbitxyz9187',gameReleaseId:'pokemon-scarlet-violet'}))
  assert.deepEqual(empty,[]);assert.equal((await control()).requests,beforeEmpty.requests)
  report.emptySkipsProvider=true
  report.runtimeAfter=publicFingerprint(await api('/chat/runtime'));report.runtimeBuildAfter=runtimeBuild(await api('/chat/runtime'))
  assert.equal(report.runtimeAfter.sha256,report.runtime.sha256);assert.equal(report.runtimeBuildAfter.sha256,report.runtimeBuild.sha256)
  assert.ok(sameJson(report.catalog,await api('/pokemon/guides/catalog')))
  report.status='CONTROLLED_CHECKS_PASSED'
}catch(error){report.status='FAILED';report.failure=/^[A-Z][A-Z0-9_]{1,100}$/.test(error.message)?error.message:error.code||error.name;process.exitCode=1}
finally {
  if(activeRun)try{await api('/chat/runs/'+activeRun+'/cancel',{})}catch{report.cleanup.push('CANCEL_FAILED');report.status='FAILED';process.exitCode=1}
  try{await control('READY')}catch{report.cleanup.push('FIXTURE_RESET_FAILED');report.status='FAILED';process.exitCode=1}
  if(authenticated)try{await session.logout();report.cleanup.push('LOGGED_OUT')}catch{report.cleanup.push('LOGOUT_FAILED');report.status='FAILED';process.exitCode=1}
  fs.writeFileSync(path.join(output,'result.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify({status:report.status,cases:report.records.length,emptySkipsProvider:report.emptySkipsProvider,failure:report.failure}))
}
