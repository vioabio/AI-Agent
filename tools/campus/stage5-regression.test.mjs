import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {stage5Cases} from './stage5-regression.mjs'
import {digest,frozenFileDigest} from './evaluation.mjs'
import {verifyGuideHit} from './upgrade-stage5-retrieval.mjs'

test('stage5 fixtures bind source-linked development labels and the two documented answer questions',()=>{
 const dataset=JSON.parse(readFileSync('evaluation/upgrade-v1.1/u6/stage5-retrieval-dev.json','utf8'))
 assert.equal(dataset.corpusSha256,frozenFileDigest(dataset.corpusFile));assert.equal(dataset.casesSha256,digest(dataset.cases))
 assert.equal(dataset.cases.length,8);assert.equal(dataset.cases.filter(row=>row.relevantDocumentIds.length===0).length,2)
 const docs=JSON.parse(readFileSync(dataset.corpusFile,'utf8')).documents
 assert.ok(dataset.cases.flatMap(row=>row.relevantDocumentIds).every(id=>docs.some(doc=>doc.id===id)))
 const specification=readFileSync('document/测试文档/测试文档_阶段5_Embedding与混合检索.md','utf8')
 assert.deepEqual(stage5Cases().map(row=>row.id),['S5-05','S5-08'])
 for(const row of stage5Cases())assert.ok(specification.includes(row.turns[0].replace(/。$/,'')))
})
function sourceFixture(){
 const text='训练说明',hash='sha256:'+digest(text)
 const view={chunk:{id:'fixture-chunk',content:text,contentHash:hash},documentId:'fixture',documentVersion:'fixture-version',gameReleaseId:'pokemon-scarlet-violet',language:'zh-CN',battleFormatId:'casual-singles',rulesetVersionId:'casual-v1'}
 const located={document:{id:'fixture',content:'前言'+text},citation:{chunkId:'fixture-chunk',documentVersion:'fixture-version',text,contentHash:hash,startOffset:2,endOffset:6}}
 return {hit:{view},located}
}
test('retrieval source checks bind scope, raw content hash and exact document offsets',()=>{
 const sample=sourceFixture();assert.equal(verifyGuideHit(sample.hit,sample.located),true)
 for(const change of [s=>s.hit.view.language='en-US',s=>s.hit.view.chunk.content='changed',s=>s.located.citation.startOffset=0,s=>s.located.document.id='other']){
  const other=sourceFixture();change(other);assert.throws(()=>verifyGuideHit(other.hit,other.located))
 }
})
