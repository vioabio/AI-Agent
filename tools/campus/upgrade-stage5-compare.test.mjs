import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {compareStage5} from './upgrade-stage5-compare.mjs'
const dataset=JSON.parse(readFileSync('evaluation/upgrade-v1.1/u6/stage5-retrieval-dev.json','utf8'))
function fixture(){
 const report={status:'COLLECTED_PENDING_CONTENT_REVIEW',mode:'BM25',before:{enabled:false},datasetSha256:'a'.repeat(64),corpusSha256:dataset.corpusSha256,runtimeBuild:{sha256:'b'.repeat(64)},indexBefore:{fingerprint:'index-fixture'},records:dataset.cases.map(row=>({caseId:row.id,query:row.query,sourcesVerified:true,latencyMs:1,hits:row.relevantDocumentIds.map(id=>({view:{documentId:id,chunk:{id:'chunk-'+id}},lexicalRank:1,semanticRank:0}))}))}
 const candidate=structuredClone(report);candidate.mode='HYBRID';candidate.before.enabled=true
 return {report,candidate}
}
test('paired stage5 comparison accepts equal retrieval performance without inventing improvement',()=>{
 const {report,candidate}=fixture(),result=compareStage5(report,candidate,dataset)
 assert.equal(result.status,'COMPARABLE');assert.equal(result.delta.mrrAt5,0);assert.equal(result.delta.documentRecallWithinTop5Chunks,0)
 assert.equal(result.releaseDecision,'NOT_EVALUATED')
})
test('paired stage5 comparison rejects query, scope, build and corpus substitutions',()=>{
 for(const change of [c=>c.records[0].query='other',c=>c.runtimeBuild.sha256='c'.repeat(64),c=>c.corpusSha256='d'.repeat(64),c=>c.indexBefore.fingerprint='other',c=>c.records[0].sourcesVerified=false]){
  const {report,candidate}=fixture();change(candidate);assert.throws(()=>compareStage5(report,candidate,dataset))
 }
})
