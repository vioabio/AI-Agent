import test from 'node:test'
import assert from 'node:assert/strict'
import {compareRealRerank} from './real-rerank-compare.mjs'

function fixture() {
  const dataset={corpusSha256:'fixture',cases:[{id:'positive',query:'配置',relevantDocumentIds:['doc']},{id:'empty',query:'未知',relevantDocumentIds:[]}]}
  const records=[]
  for(let round=0;round<3;round++)for(const mode of ['BM25','HYBRID','HYBRID_RERANK'])for(const item of dataset.cases) {
    const hits=item.id==='positive'?[{view:{documentId:'doc',chunk:{id:'chunk'}}}]:[]
    records.push({round,mode,caseId:item.id,query:item.query,hits,sourcesVerified:true,retrievalMs:1,
      candidateIds:hits.map(hit=>hit.view.chunk.id),semantic:{ready:true,mode:'HYBRID'},
      rerankObservation:{state:hits.length?'RERANKED':'EMPTY',ranks:hits.map(hit=>({chunkId:hit.view.chunk.id,selected:true,rerankRank:1,score:1}))}})
  }
  return {dataset,report:{schemaVersion:'real-guide-rerank-comparison-v1',status:'COLLECTED_PENDING_REVIEW',corpusSha256:'fixture',rounds:3,candidateK:12,answerTopK:5,records}}
}
test('real rerank comparison uses all rounds and keeps defaults conservative for equal quality',()=>{
  const {report,dataset}=fixture(),result=compareRealRerank(report,dataset)
  assert.equal(result.records,18)
  assert.equal(result.delta.mrrAt5,0)
  assert.equal(result.recommendation,'KEEP_DEFAULT_OFF')
  assert.equal(result.providerBillingUsage,null)
  assert.equal(result.chatModelCallCount,0)
})
test('missing, duplicate and nonfinite measurements cannot produce a completed comparison',()=>{
  for(const mutate of [r=>r.records.pop(),r=>r.records[0]=r.records[1],r=>r.records[0].retrievalMs=NaN,r=>r.records[0].sourcesVerified=false]) {
    const {report,dataset}=fixture();mutate(report)
    assert.throws(()=>compareRealRerank(report,dataset))
  }
})
test('candidate drift and invalid supplier results cannot acquire evidence status',()=>{
  for(const mutate of [r=>r.records.find(row=>row.mode==='HYBRID_RERANK').candidateIds.push('new'),
    r=>r.records.find(row=>row.mode==='HYBRID_RERANK').rerankObservation.ranks[0].score=Infinity,
    r=>r.records.find(row=>row.mode==='HYBRID_RERANK').rerankObservation.ranks[0].selected=false,
    r=>r.corpusSha256='drift']) {
    const {report,dataset}=fixture();mutate(report)
    assert.throws(()=>compareRealRerank(report,dataset))
  }
})
