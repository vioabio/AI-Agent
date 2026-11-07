import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { auditSpeciesGuides } from './build-species-guides.mjs'
import { assessRetrieval,summarizeRetrieval } from './s3-retrieval.mjs'
const json=file=>JSON.parse(fs.readFileSync(file,'utf8'))
const base=json('src/main/resources/pokemon-guides/expanded-v1.json')
const corpus=()=>json('src/main/resources/pokemon-strategy/overview-v1.json')
const sources=()=>json('src/main/resources/pokemon-strategy/sources-v1.json')
test('new source version preserves original bodies and binds species notes to pinned statistical projections',()=>{
  assert.deepEqual(auditSpeciesGuides(corpus(),sources(),base),{documents:14,speciesNotes:2,baseDocumentsUnchanged:true,humanReview:'pending',scope:'Pinned factual projections and original conditional suggestions, not a competitive strategy gold set'})
})
test('changed statistical projection, original body or conditional note fails the source audit',()=>{
  const fact=sources();fact.references[0].fact.speed++
  assert.throws(()=>auditSpeciesGuides(corpus(),fact,base))
  for(const index of [0,12,13]){const modified=corpus();modified.documents[index].content+=' 不支持的补充';assert.throws(()=>auditSpeciesGuides(modified,sources(),base))}
})
test('known irrelevant source recall is recorded separately from positive recall',()=>{
  const item={id:'relevance',relevantDocumentIds:['target'],excludedDocumentIds:['unrelated']}
  const rows=[assessRetrieval(item,[{view:{documentId:'target',chunk:{id:'one'}}},{view:{documentId:'unrelated',chunk:{id:'two'}}}],true)]
  const report=summarizeRetrieval([item],rows)
  assert.equal(report.documentRecallWithinTop5Chunks,1);assert.equal(report.exclusionViolations,1)
})
