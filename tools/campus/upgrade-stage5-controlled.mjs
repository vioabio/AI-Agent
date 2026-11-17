import assert from 'node:assert/strict'
import {readFileSync,writeFileSync,mkdirSync,existsSync,realpathSync} from 'node:fs'
import {resolve,dirname} from 'node:path'
import {spawnSync} from 'node:child_process'
import {localEvaluationSession} from './local-evaluation-session.mjs'
import {stage5Scope} from './upgrade-stage5-retrieval.mjs'
import {digest} from './evaluation.mjs'
const [directory,url,vectorFile,mode='faults']=process.argv.slice(2),output=resolve(directory)
assert.ok(['faults','model-change'].includes(mode));assert.equal(dirname(output),realpathSync('artifacts'));assert.equal(existsSync(output),false);mkdirSync(output)
const session=localEvaluationSession(url),api=session.api,report={schemaVersion:'stage5-controlled-vectors-v1',scope:'LOCAL_HTTP_SYNTHETIC_TWO_DIMENSIONAL_VECTORS_NOT_REAL_PROVIDER_QUALITY',mode,status:'RUNNING',steps:[],usage:'CONTROLLED_FIXTURE_COUNTS_ONLY',releaseDecision:'NOT_EVALUATED'}
const control=async next=>{
 const response=await fetch('http://127.0.0.1:4333/'+(next?'mode':'status'),{method:next?'POST':'GET',...(next?{body:JSON.stringify({mode:next}),headers:{'Content-Type':'application/json'}}:{}),signal:AbortSignal.timeout(5000)})
 assert.equal(response.status,200);return response.json()
}
const snapshot=action=>{
 const process=spawnSync('python',['tools/campus/stage5-vector-fixture.py',action,vectorFile],{encoding:'utf8',timeout:10000,maxBuffer:100000})
 assert.equal(process.status,0,'VECTOR_FIXTURE_OPERATION_FAILED');return JSON.parse(process.stdout)
}
const retrieve=()=>api('/pokemon/guides/retrieve?'+new URLSearchParams({...stage5Scope,query:'努力值 培养 配置'}))
let authenticated=false
try {
 await session.login('隔离向量故障验证');authenticated=true
 const fixture=await control();assert.equal(fixture.fixture,'stage5-embedding-controlled-v1')
 report.before=await api('/pokemon/guides/semantic');assert.equal(report.before.dimensions,2)
 if(mode==='model-change') {
  assert.equal(report.before.model,'stage5-fixture-v2');assert.equal(report.before.ready,false)
  const calls=(await control()).requests
  const lexical=await retrieve();assert.ok(lexical.every(hit=>hit.semanticRank===0));assert.equal((await control()).requests,calls)
  report.steps.push({case:'S5-11',phase:'OLD_MODEL_NOT_REUSED',state:await api('/pokemon/guides/semantic')})
  assert.equal((await api('/pokemon/guides/semantic/index',{},200,100000)).ready,true)
  const hybrid=await retrieve();assert.ok(hybrid.some(hit=>hit.semanticRank>0))
  assert.ok(snapshot('snapshot').indexCount>=2)
  report.steps.push({case:'S5-11',phase:'NEW_MODEL_READY',state:await api('/pokemon/guides/semantic'),snapshot:snapshot('snapshot')})
 } else {
  assert.equal(report.before.model,'stage5-fixture-v1');assert.equal((await api('/pokemon/guides/semantic/index',{},200,100000)).ready,true)
  const initial=snapshot('snapshot');await control('FAIL')
  const failed=await retrieve();assert.ok(failed.every(hit=>hit.semanticRank===0))
  const state=await api('/pokemon/guides/semantic');assert.equal(state.state,'PROVIDER_HTTP_503');assert.equal(state.mode,'BM25')
  assert.deepEqual(snapshot('snapshot'),initial)
  report.steps.push({case:'S5-09',state,unchanged:true,hits:failed})
  await control('DIMENSION');const wrong=await retrieve();assert.ok(wrong.every(hit=>hit.semanticRank===0))
  assert.equal((await api('/pokemon/guides/semantic')).state,'DIMENSION_OR_INDEX_MISMATCH');assert.deepEqual(snapshot('snapshot'),initial)
  report.steps.push({case:'S5-10',phase:'QUERY_DIMENSION_REJECTED',snapshot:initial})
  await control('READY');snapshot('tamper-one');const beforeRequest=(await control()).requests
  const corrupted=await retrieve();assert.ok(corrupted.every(hit=>hit.semanticRank===0));assert.equal((await control()).requests,beforeRequest)
  assert.equal((await api('/pokemon/guides/semantic')).state,'INDEX_INTEGRITY_ERROR')
  const corruptSnapshot=snapshot('snapshot');await control('DIMENSION')
  const rejected=await api('/pokemon/guides/semantic/index',{},200,100000);assert.equal(rejected.ready,false);assert.equal(rejected.state,'DIMENSION_OR_INDEX_MISMATCH')
  assert.deepEqual(snapshot('snapshot'),corruptSnapshot)
  report.steps.push({case:'S5-10',phase:'CORRUPTION_REJECTED_FAILED_BUILD_ATOMIC',state:rejected,unchanged:true})
  await control('READY');const repaired=await api('/pokemon/guides/semantic/index',{},200,100000);assert.equal(repaired.ready,true)
  assert.deepEqual(snapshot('snapshot'),initial)
  report.steps.push({case:'S5-10',phase:'EXPLICIT_REPAIR_READY',state:repaired})
 }
 const requests=(await control()).requests
 assert.equal((await api('/pokemon/guides/semantic/index',{},200,100000)).state,'READY_REUSED');assert.equal((await control()).requests,requests)
 report.fixtureAfter=await control();report.status='PASS'
}catch(error){report.status='FAIL';report.failure=error.code??error.name;report.failureCheck=String(error.message).slice(0,250);process.exitCode=1}
finally{
 if(authenticated)try{await session.logout();report.cleanup='LOGGED_OUT'}catch{report.status='FAIL';process.exitCode=1}
 writeFileSync(resolve(output,'controlled-result.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:report.status,steps:report.steps.map(row=>row.case+':'+(row.phase??row.state.state)),failure:report.failure,failureCheck:report.failureCheck}))
}
