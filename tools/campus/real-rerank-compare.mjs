import assert from 'node:assert/strict'
import {readFileSync,writeFileSync,existsSync} from 'node:fs'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest,frozenFileDigest} from './evaluation.mjs'
import {assessRetrieval,summarizeRetrieval} from './s3-retrieval.mjs'

export function compareRealRerank(report,dataset) {
  assert.equal(report.schemaVersion,'real-guide-rerank-comparison-v1')
  assert.equal(report.status,'COLLECTED_PENDING_REVIEW')
  assert.equal(report.corpusSha256,dataset.corpusSha256)
  assert.equal(report.rounds,3)
  assert.equal(report.candidateK,12)
  assert.equal(report.answerTopK,5)
  const modes=['BM25','HYBRID','HYBRID_RERANK']
  assert.equal(report.records.length,modes.length*report.rounds*dataset.cases.length)
  const keys=new Set()
  for(const row of report.records) {
    const item=dataset.cases.find(item=>item.id===row.caseId)
    assert.ok(item&&modes.includes(row.mode))
    assert.ok(Number.isInteger(row.round)&&row.round>=0&&row.round<report.rounds)
    const key=[row.round,row.mode,row.caseId].join('/')
    assert.ok(!keys.has(key));keys.add(key)
    assert.equal(row.query,item.query)
    assert.equal(row.sourcesVerified,true)
    assert.ok(Number.isFinite(row.retrievalMs)&&row.retrievalMs>=0)
    assert.ok(Array.isArray(row.candidateIds)&&row.candidateIds.length<=12)
    assert.equal(new Set(row.candidateIds).size,row.candidateIds.length)
    assert.ok(row.hits.every(hit=>row.candidateIds.includes(hit.view.chunk.id)))
    if(row.mode!=='BM25') {
      assert.equal(row.semantic.ready,true)
      assert.equal(row.semantic.mode,'HYBRID')
    }
    if(row.mode==='HYBRID_RERANK') {
      const baseline=report.records.find(other=>other.round===row.round&&other.caseId===row.caseId&&other.mode==='HYBRID')
      assert.deepEqual(row.candidateIds,baseline?.candidateIds,'Paired candidate pool changed')
      const observation=row.rerankObservation
      assert.ok(['RERANKED','EMPTY','RRF_FALLBACK','RRF_BUDGET_FALLBACK'].includes(observation?.state))
      assert.deepEqual(observation.ranks.map(rank=>rank.chunkId),row.candidateIds)
      assert.deepEqual(observation.ranks.filter(rank=>rank.selected).map(rank=>rank.chunkId).sort(),row.hits.map(hit=>hit.view.chunk.id).sort())
      if(observation.state==='RERANKED') {
        assert.ok(observation.ranks.length>0)
        assert.ok(observation.ranks.every(rank=>Number.isFinite(rank.score)))
        assert.ok(observation.ranks.every(rank=>Number.isInteger(rank.rerankRank)&&rank.rerankRank>=1&&rank.rerankRank<=observation.ranks.length))
        assert.equal(new Set(observation.ranks.map(rank=>rank.rerankRank)).size,observation.ranks.length)
      }
    }
  }
  const summaries={}
  for(const mode of modes) {
    const selected=report.records.filter(row=>row.mode===mode)
    const rounds=Array.from({length:report.rounds},(_,round)=>summarizeRetrieval(dataset.cases,
      selected.filter(row=>row.round===round).map(row=>assessRetrieval(dataset.cases.find(item=>item.id===row.caseId),row.hits,row.sourcesVerified))))
    const times=selected.map(row=>row.retrievalMs).sort((a,b)=>a-b)
    summaries[mode]={rounds,recallAt5:rounds.reduce((sum,r)=>sum+r.documentRecallWithinTop5Chunks,0)/rounds.length,
      mrrAt5:rounds.reduce((sum,r)=>sum+r.mrrAt5,0)/rounds.length,
      negativeEmptyAccuracy:rounds.reduce((sum,r)=>sum+r.negativeEmptyAccuracy,0)/rounds.length,
      latencyMs:{p50:times[Math.ceil(times.length*.5)-1],p95:times[Math.ceil(times.length*.95)-1]},
      states:selected.reduce((counts,row)=>{const state=row.rerankObservation?.state||mode;counts[state]=(counts[state]||0)+1;return counts},{})}
  }
  const baseline=summaries.HYBRID,candidate=summaries.HYBRID_RERANK
  const delta={recallAt5:candidate.recallAt5-baseline.recallAt5,mrrAt5:candidate.mrrAt5-baseline.mrrAt5,
    negativeEmptyAccuracy:candidate.negativeEmptyAccuracy-baseline.negativeEmptyAccuracy,
    p95Ms:candidate.latencyMs.p95-baseline.latencyMs.p95}
  return {schemaVersion:'real-rerank-reviewed-comparison-v1',scope:report.scope,datasetSha256:report.datasetSha256,
    corpusSha256:report.corpusSha256,candidatePoolsIdentical:true,records:report.records.length,summaries,delta,
    recommendation:delta.recallAt5<0||delta.mrrAt5<=0||delta.negativeEmptyAccuracy<0||candidate.states.RRF_FALLBACK
      ?'KEEP_DEFAULT_OFF':'OPTIONAL_ENABLE_FOR_MEASURED_SCOPE',
    chatModelCallCount:0,providerBillingUsage:null,answerQuality:'NOT_MEASURED_BY_RETRIEVAL_BENCHMARK',
    evidence:'SOURCE_LINKED_DEVELOPMENT_SEEDS_THREE_ROUNDS_NOT_STATISTICAL_SIGNIFICANCE'}
}

if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const [input,output]=process.argv.slice(2)
  assert.ok(input&&output&&!existsSync(output))
  const file='evaluation/upgrade-v1.1/u6/stage5-retrieval-dev.json'
  const dataset=JSON.parse(readFileSync(file,'utf8'))
  const report=JSON.parse(readFileSync(input,'utf8'))
  assert.equal(report.datasetSha256,frozenFileDigest(file))
  assert.equal(dataset.casesSha256,digest(dataset.cases))
  assert.equal(dataset.corpusSha256,frozenFileDigest(dataset.corpusFile))
  const documents=JSON.parse(readFileSync(dataset.corpusFile,'utf8')).documents
  for(const row of report.records)for(const hit of row.hits) {
    const original=documents.find(document=>document.id===hit.view.documentId)
    const chunk=hit.view.chunk
    assert.ok(original)
    assert.equal(chunk.contentHash,'sha256:'+digest(chunk.content))
    assert.equal(original.content.slice(chunk.metadata.startOffset,chunk.metadata.endOffset),chunk.content)
  }
  const comparison=compareRealRerank(report,dataset)
  comparison.inputSha256=frozenFileDigest(input)
  writeFileSync(output,JSON.stringify(comparison,null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify({records:comparison.records,delta:comparison.delta,recommendation:comparison.recommendation}))
}
