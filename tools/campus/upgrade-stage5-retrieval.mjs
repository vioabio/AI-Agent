import assert from 'node:assert/strict'
import {readFileSync,writeFileSync,mkdirSync,existsSync,realpathSync} from 'node:fs'
import {resolve,dirname} from 'node:path'
import {fileURLToPath} from 'node:url'
import {performance} from 'node:perf_hooks'
import {digest,frozenFileDigest,localBase} from './evaluation.mjs'
import {localEvaluationSession} from './local-evaluation-session.mjs'
import {publicFingerprint} from './upgrade-baseline.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {assessRetrieval,summarizeRetrieval} from './s3-retrieval.mjs'

export const stage5Scope={gameReleaseId:'pokemon-scarlet-violet',language:'zh-CN',battleFormatId:'casual-singles',rulesetVersionId:'casual-v1'}
export function verifyGuideHit(hit,located,scope=stage5Scope) {
 const view=hit.view,chunk=view.chunk,citation=located.citation,document=located.document
 for(const [key,value] of Object.entries(scope))assert.equal(view[key],value)
 assert.equal(chunk.contentHash,'sha256:'+digest(chunk.content))
 assert.equal(citation.chunkId,chunk.id);assert.equal(citation.documentVersion,view.documentVersion)
 assert.equal(citation.text,chunk.content);assert.equal(citation.contentHash,chunk.contentHash)
 assert.equal(document.id,view.documentId)
 assert.equal(document.content.slice(citation.startOffset,citation.endOffset),chunk.content)
 return true
}
export function summarizeStage5Retrieval(dataset,records) {
 assert.equal(records.length,dataset.cases.length)
 return summarizeRetrieval(dataset.cases,records.map(row=>assessRetrieval(dataset.cases.find(item=>item.id===row.caseId),row.hits,row.sourcesVerified)))
}
async function main(){
 const [directory,url,mode]=process.argv.slice(2),output=resolve(directory),base=localBase(url)
 assert.ok(['BM25','HYBRID'].includes(mode));assert.equal(dirname(output),realpathSync('artifacts'));assert.equal(existsSync(output),false);mkdirSync(output)
 const fixtureFile='evaluation/upgrade-v1.1/u6/stage5-retrieval-dev.json',dataset=JSON.parse(readFileSync(fixtureFile,'utf8'))
 assert.equal(dataset.corpusSha256,frozenFileDigest(dataset.corpusFile));assert.equal(dataset.casesSha256,digest(dataset.cases))
 const report={schemaVersion:'stage5-retrieval-collection-v1',status:'RUNNING',mode,datasetSha256:frozenFileDigest(fixtureFile),corpusSha256:dataset.corpusSha256,producerSha256:digest(readFileSync(new URL(import.meta.url))),records:[],scopeChecks:[],repeated:[],startedAt:new Date().toISOString(),usage:'UNKNOWN_NOT_OBSERVED',cost:'UNKNOWN_NOT_INVOICE',qualityReview:'REQUIRED',releaseDecision:'NOT_EVALUATED'}
 const session=localEvaluationSession(base),api=session.api
 let authenticated=false
 async function query(text,scope=stage5Scope) {
  const start=performance.now(),hits=await api('/pokemon/guides/retrieve?'+new URLSearchParams({...scope,query:text}))
  assert.ok(Array.isArray(hits)&&hits.length<=5)
  for(const hit of hits)verifyGuideHit(hit,await api('/pokemon/guides/chunks/'+hit.view.chunk.id),scope)
  return {query:text,hits,sourcesVerified:true,latencyMs:performance.now()-start,state:await api('/pokemon/guides/semantic')}
 }
 try {
  await session.login('阶段5检索对照');authenticated=true
  const runtime=await api('/chat/runtime');report.runtime=publicFingerprint(runtime);report.runtimeBuild=runtimeBuild(runtime)
  report.indexBefore=await api('/pokemon/guides/index');report.catalog=await api('/pokemon/guides/catalog')
  assert.equal(report.catalog.corpusSha256,dataset.corpusSha256)
  report.before=await api('/pokemon/guides/semantic')
  if(mode==='BM25'){assert.equal(report.before.enabled,false)}
  else {
   assert.equal(report.before.enabled,true);assert.equal(report.before.configured,true)
   report.build=await api('/pokemon/guides/semantic/index',{},200,100000)
   assert.equal(report.build.ready,true);assert.equal(report.build.mode,'HYBRID')
   report.reused=await api('/pokemon/guides/semantic/index',{},200,100000)
   assert.equal(report.reused.state,'READY_REUSED')
  }
  for(const item of dataset.cases){report.records.push({caseId:item.id,...await query(item.query)});writeFileSync(resolve(output,'retrieval-result.json'),JSON.stringify(report,null,2)+'\n')}
  for(const change of [{gameReleaseId:'pokemon-sword-shield'},{language:'en-US'},{rulesetVersionId:'qa-unknown-rules'}]){
   const row=await query('努力值 培养 配置',{...stage5Scope,...change});assert.equal(row.hits.length,0)
   report.scopeChecks.push({...change,hits:row.hits.length,state:row.state})
  }
  for(const text of ['努力值 培养 配置','努力值 培养 配置','努力值 培养 配置','队伍修改预览与确认'])report.repeated.push(await query(text))
  report.after=await api('/pokemon/guides/semantic');report.indexAfter=await api('/pokemon/guides/index')
  assert.equal(report.indexBefore.fingerprint,report.indexAfter.fingerprint)
  const after=await api('/chat/runtime');assert.equal(publicFingerprint(after).sha256,report.runtime.sha256);assert.equal(runtimeBuild(after).sha256,report.runtimeBuild.sha256)
  if(mode==='HYBRID')for(const id of ['S5-Q01','S5-Q02']){
   const row=report.records.find(r=>r.caseId===id);assert.equal(row.state.mode,'HYBRID');assert.ok(row.hits.some(hit=>hit.semanticRank>0))
   assert.ok(row.hits.some(hit=>hit.view.documentId==='training-configuration-v1'),'TRAINING_SOURCE_NOT_RETRIEVED')
  }
  if(mode==='BM25')assert.ok(report.records.every(row=>row.hits.every(hit=>hit.semanticRank===0)))
  report.metrics=summarizeStage5Retrieval(dataset,report.records);report.status='COLLECTED_PENDING_CONTENT_REVIEW'
 }catch(error){report.status='FAIL';report.failure=error.code??error.name;report.failureCheck=String(error.message).slice(0,250);process.exitCode=1}
 finally {
  if(authenticated)try{await session.logout();report.cleanup='LOGGED_OUT'}catch{report.cleanup='LOGOUT_UNVERIFIED';report.status='FAIL';process.exitCode=1}
  report.finishedAt=new Date().toISOString();writeFileSync(resolve(output,'retrieval-result.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({status:report.status,mode,records:report.records.length,metrics:report.metrics,failure:report.failure,failureCheck:report.failureCheck}))
 }
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))await main()
