import assert from 'node:assert/strict'
import {readFileSync,writeFileSync,mkdirSync,existsSync,realpathSync} from 'node:fs'
import {resolve,dirname,relative,isAbsolute,join} from 'node:path'
import {execFile} from 'node:child_process'
import {promisify} from 'node:util'
import {randomBytes} from 'node:crypto'
import {localEvaluationSession} from './local-evaluation-session.mjs'
import {localBase,digest,sameJson} from './evaluation.mjs'
import {stage2Team} from './stage2-regression.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {publicFingerprint} from './upgrade-baseline.mjs'

const execute=promisify(execFile)
const [directory,url,processFile,restartedDirectory]=process.argv.slice(2)
assert.ok(directory&&url&&processFile&&restartedDirectory)
const output=resolve(directory),artifacts=realpathSync('artifacts'),base=localBase(url)
assert.equal(dirname(output),artifacts);assert.equal(existsSync(output),false)
const metadataPath=realpathSync(processFile),within=relative(artifacts,metadataPath)
assert.ok(within&&!within.startsWith('..')&&!isAbsolute(within))
const meta=JSON.parse(readFileSync(metadataPath,'utf8').replace(/^\uFEFF/,''))
assert.equal(meta.status,'READY');assert.equal(meta.semanticSearch,true)
assert.notEqual(meta.port,8123);assert.equal(Number(new URL(base).port),meta.port)
mkdirSync(output)
const session=localEvaluationSession(base),api=session.api
const report={schemaVersion:'stage6-restart-persistence-v1',caseId:'S6-12',status:'RUNNING',
  startedAt:new Date().toISOString(),cleanup:[],qualityReview:'REQUIRED',releaseDecision:'NOT_EVALUATED',
  sourceSha256:Object.fromEntries(['tools/campus/upgrade-stage6-restart.mjs','tools/campus/local-evaluation-session.mjs',
    'ops/local/start-ablation-backend.ps1','ops/local/restart-ablation-backend.ps1'].map(file=>[file,digest(readFileSync(file))]))}
let authenticated=false,pendingId,team
async function waitStatus(runId,status) {
  const deadline=Date.now()+140000
  do {
    const view=await api('/chat/runs/'+runId)
    if(view.status===status)return view
    assert.equal(['COMPLETED','FAILED','CANCELLED','TIMED_OUT'].includes(view.status),false,'UNEXPECTED_TERMINAL')
    await new Promise(done=>setTimeout(done,250))
  }while(Date.now()<deadline)
  throw Error('RUN_STATE_TIMEOUT')
}
async function create(prompt,conversationId) {
  return api('/chat/runs',{prompt,conversationId,gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',
    rulesetVersionId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',language:'zh-CN',timeoutSeconds:120},202)
}
async function vectorSnapshot() {
  const result=await execute('python',['tools/campus/stage5-vector-fixture.py','snapshot',join(meta.dataDirectory,'vectors.sqlite')],{timeout:30000})
  return JSON.parse(result.stdout)
}
try {
  await session.login('隔离重启验收');authenticated=true
  const runtime=await api('/chat/runtime');report.runtime=publicFingerprint(runtime);report.runtimeBuild=runtimeBuild(runtime)
  report.identityBefore=(await api('/auth/session')).principalId
  team=await api('/pokemon/teams',stage2Team(),201);report.teamBefore=await api('/pokemon/teams/'+team.id)
  const conversationId='s6-restart-done-'+randomBytes(10).toString('hex')
  const completed=await create('这一轮仅回复“收到”。',conversationId)
  await waitStatus(completed.runId,'COMPLETED')
  report.completedRunId=completed.runId;report.completedConversationId=conversationId
  report.messagesBefore=await api('/chat/conversations/'+conversationId+'/messages')
  assert.ok(report.messagesBefore.some(message=>message.role==='assistant'&&message.content==='收到'))
  report.semanticBefore=await api('/pokemon/guides/semantic/index',{},200,100000)
  assert.equal(report.semanticBefore.state,'READY_REUSED');assert.equal(report.semanticBefore.indexedChunks,30)
  report.indexBefore=await api('/pokemon/guides/index');report.vectorBefore=await vectorSnapshot()
  const pendingConversation='s6-restart-pending-'+randomBytes(10).toString('hex')
  const pending=await create('读取我的test1队伍，把槽位1等级从51改为52，其他字段保持当前值，生成预览等待页面确认。',pendingConversation)
  pendingId=pending.runId;report.pendingRunId=pendingId;report.pendingConversationId=pendingConversation
  await waitStatus(pendingId,'WAITING_APPROVAL')
  report.pendingPreview=await api('/chat/runs/'+pendingId+'/team-change')
  assert.equal(report.pendingPreview.status,'PENDING')
  report.pendingMessagesBefore=await api('/chat/conversations/'+pendingConversation+'/messages')
  assert.equal(report.pendingMessagesBefore.some(message=>message.role==='assistant'),false)
  assert.ok(sameJson(await api('/pokemon/teams/'+team.id),report.teamBefore))
  const prefixEvents=(await api('/chat/runs/'+pendingId+'/events?afterEventId=0')).events
  const eventBytes=JSON.stringify(prefixEvents,null,2)+'\n'
  writeFileSync(join(output,'pending-before-restart-events.json'),eventBytes,{flag:'wx'})
  report.pendingEventsSha256=digest(eventBytes)
  const restart=await execute('pwsh.exe',['-NoProfile','-File','ops/local/restart-ablation-backend.ps1',
    '-ProcessFile',processFile,'-OutputDirectory',restartedDirectory],{timeout:120000,maxBuffer:1048576})
  report.restartExit='SUCCESS'
  assert.ok(restart.stdout.includes('Isolated backend ready'))
  const afterMeta=JSON.parse(readFileSync(join(restartedDirectory,'process.json'),'utf8').replace(/^\uFEFF/,''))
  assert.notEqual(afterMeta.pid,meta.pid);assert.equal(afterMeta.dataDirectory,meta.dataDirectory)
  report.beforeProcess={pid:meta.pid,startedAt:meta.startedAt};report.afterProcess={pid:afterMeta.pid,startedAt:afterMeta.startedAt}
  await session.reauthenticate()
  report.identityAfter=(await api('/auth/session')).principalId
  assert.equal(report.identityAfter,report.identityBefore)
  report.teamAfter=await api('/pokemon/teams/'+team.id)
  report.messagesAfter=await api('/chat/conversations/'+conversationId+'/messages')
  report.pendingMessagesAfter=await api('/chat/conversations/'+pendingConversation+'/messages')
  assert.ok(sameJson(report.teamBefore,report.teamAfter))
  assert.ok(sameJson(report.messagesBefore,report.messagesAfter))
  assert.ok(sameJson(report.pendingMessagesBefore,report.pendingMessagesAfter))
  await assert.rejects(api('/chat/runs/'+pendingId),{message:'HTTP_404'})
  await assert.rejects(api('/chat/runs/'+pendingId+'/team-change/decision',
    {approvalId:report.pendingPreview.preview.proposalId,approved:true}),{message:'HTTP_404'})
  assert.ok(sameJson(await api('/pokemon/teams/'+team.id),report.teamBefore))
  report.pendingRecovery='RUN_NOT_RESUMED_404_HISTORY_PRESERVED_NO_SAVE'
  report.semanticAfter=await api('/pokemon/guides/semantic/index',{},200,100000)
  assert.equal(report.semanticAfter.state,'READY_REUSED');assert.equal(report.semanticAfter.indexedChunks,30)
  report.indexAfter=await api('/pokemon/guides/index');report.vectorAfter=await vectorSnapshot()
  assert.equal(report.indexBefore.fingerprint,report.indexAfter.fingerprint)
  assert.ok(sameJson(report.vectorBefore,report.vectorAfter))
  const afterRuntime=await api('/chat/runtime')
  assert.equal(publicFingerprint(afterRuntime).sha256,report.runtime.sha256)
  assert.equal(runtimeBuild(afterRuntime).sha256,report.runtimeBuild.sha256)
  report.status='COLLECTED_PENDING_CONTENT_REVIEW'
}catch(error){report.status='FAIL';report.failure=String(error.message).slice(0,250);process.exitCode=1}
finally {
  if(authenticated)try{await session.logout();report.cleanup.push('LOGGED_OUT')}
  catch{report.cleanup.push('LOGOUT_FAILED');report.status='FAIL';process.exitCode=1}
  report.finishedAt=new Date().toISOString()
  writeFileSync(join(output,'restart-result.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify({status:report.status,recovery:report.pendingRecovery,failure:report.failure}))
}
