import test from 'node:test'
import assert from 'node:assert/strict'
import {rankingMetrics,evaluateRetrieval} from './retrieval-metrics.mjs'
test('known ordering computes recall, reciprocal rank and binary nDCG',()=>{
 const r=rankingMetrics(['a','b'],['x','a','b'],3)
 assert.equal(r.recallAtK,1);assert.equal(r.mrrAtK,.5)
 assert.ok(Math.abs(r.ndcgAtK-(1/Math.log2(3)+.5)/(1+1/Math.log2(3)))<1e-12)
})
test('relevant results below k do not count as hits',()=>{
 assert.deepEqual(rankingMetrics(['a'],['x','a'],1),{recallAtK:0,mrrAtK:0,ndcgAtK:0})
})
test('missing labels and duplicate candidates cannot silently inflate metrics',()=>{
 assert.throws(()=>rankingMetrics([],['a'],3));assert.throws(()=>rankingMetrics(['a'],['a','a'],3));assert.throws(()=>rankingMetrics(['a','a'],['a'],3))
})
test('macro metrics retain dataset identity and never approve release',()=>{
 const r=evaluateRetrieval({schemaVersion:'retrieval-labels-v1',datasetSha256:'a'.repeat(64),k:1,cases:[{caseId:'1',labelSource:'reviewed source',relevantChunkIds:['a'],rankedChunkIds:['a']},{caseId:'2',labelSource:'reviewed source',relevantChunkIds:['b'],rankedChunkIds:[]}]})
 assert.equal(r.macro.recallAtK,.5);assert.equal(r.releaseDecision,'NOT_EVALUATED')
})
