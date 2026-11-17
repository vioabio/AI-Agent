import test from 'node:test'
import assert from 'node:assert/strict'
import {compareRetrieval} from './retrieval-comparison.mjs'
function input(method,ranked=['x','a']){return {schemaVersion:'retrieval-labels-v1',datasetSha256:'a'.repeat(64),corpusSha256:'b'.repeat(64),method,k:2,cases:[{caseId:'dev-1',querySha256:'c'.repeat(64),labelSource:'review-v1',relevantChunkIds:['a'],rankedChunkIds:ranked}]}}
test('paired report measures ranking delta but does not approve release',()=>{
 const r=compareRetrieval(input('BM25'),input('HYBRID_RERANK',['a','x']))
 assert.equal(r.macro.delta.recallAtK,0);assert.equal(r.macro.delta.mrrAtK,.5);assert.ok(r.macro.delta.ndcgAtK>0);assert.equal(r.releaseDecision,'NOT_EVALUATED')
})
test('different corpus, dataset or cutoff cannot be compared',()=>{
 for(const [key,value] of [['corpusSha256','d'.repeat(64)],['datasetSha256','d'.repeat(64)],['k',1]]){
  const b=input('candidate');b[key]=value;assert.throws(()=>compareRetrieval(input('baseline'),b),/MISMATCH/)
 }
})
test('changed questions, labels or reviewer source are rejected',()=>{
 for(const [key,value] of [['querySha256','d'.repeat(64)],['relevantChunkIds',['z']],['labelSource','different']]){
  const b=input('candidate');b.cases[0][key]=value;assert.throws(()=>compareRetrieval(input('baseline'),b),/MISMATCH/)
 }
})
test('missing cases and duplicate cases cannot produce a paired average',()=>{
 const b=input('candidate');b.cases[0].caseId='different';assert.throws(()=>compareRetrieval(input('baseline'),b))
 b.cases=[b.cases[0],b.cases[0]];assert.throws(()=>compareRetrieval(input('baseline'),b))
})
test('a regression is preserved as a negative delta',()=>{
 const r=compareRetrieval(input('baseline',['a']),input('candidate',['x']))
 assert.equal(r.macro.delta.recallAtK,-1);assert.equal(r.macro.delta.mrrAtK,-1)
})
