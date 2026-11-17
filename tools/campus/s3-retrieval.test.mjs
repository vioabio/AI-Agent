import test from 'node:test'
import assert from 'node:assert/strict'
import { assessRetrieval, summarizeRetrieval } from './s3-retrieval.mjs'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { frozenFileDigest } from './evaluation.mjs'
import { auditCorpus } from './audit-s3-corpus.mjs'
import { sourceSupport } from './s2-quality-regression.mjs'
import { checkExpandedReport } from './s3-source-regression.mjs'
const chunk = (doc, id = doc) => ({ view: { documentId: doc, chunk: { id } } })
test('document recall does not double-count chunks from the same source; MRR uses chunk rank', () => {
  const item = { id: 'a', relevantDocumentIds: ['a', 'b'] }
  const row = assessRetrieval(item, [chunk('other'), chunk('a', 'a1'), chunk('a', 'a2')], true)
  assert.equal(row.recallAt5, 0.5); assert.equal(row.reciprocalRankAt5, 0.5)
})
test('empty positive retrieval and failed source verification remain in the denominator', () => {
  const cases = [{ id: 'a', relevantDocumentIds: ['a'] }, { id: 'b', relevantDocumentIds: ['b'] }]
  const rows = [assessRetrieval(cases[0], [chunk('a')], true), assessRetrieval(cases[1], [], false)]
  assert.equal(summarizeRetrieval(cases, rows).documentRecallWithinTop5Chunks, 0.5)
  assert.equal(summarizeRetrieval(cases, rows).sourcesVerified, 1)
  assert.throws(() => summarizeRetrieval(cases, rows.slice(1)))
})
test('negative scope cases do not inflate positive recall and false matches are counted', () => {
  const item = { id: 'negative', relevantDocumentIds: [] }
  const summary = summarizeRetrieval([item], [assessRetrieval(item, [chunk('irrelevant')], true)])
  assert.equal(summary.documentRecallWithinTop5Chunks, null); assert.equal(summary.negativeEmptyAccuracy, 0)
  assert.equal(assessRetrieval(item, [], false).emptyCorrect, false)
  assert.equal(assessRetrieval({ id: 'positive', relevantDocumentIds: ['x'] }, [chunk('x')], false).recallAt5, 0)
  assert.throws(() => assessRetrieval(item, Array(6).fill(chunk('x')), true))
})
test('fixed development plan binds the real corpus and contains only known source labels', () => {
  const suite = JSON.parse(readFileSync(new URL('../../evaluation/campus/s3/retrieval-dev.json', import.meta.url), 'utf8'))
  const corpusUrl = new URL(`../../${suite.corpusFile}`, import.meta.url)
  assert.equal(suite.corpusSha256, frozenFileDigest(fileURLToPath(corpusUrl)))
  const docs = JSON.parse(readFileSync(corpusUrl, 'utf8')).documents.map(d => d.id)
  assert.equal(suite.split, 'development-only'); assert.equal(suite.cases.length, 13)
  assert.equal(new Set(suite.cases.map(c => c.id)).size, 13)
  for (const item of suite.cases) for (const id of item.relevantDocumentIds) assert.ok(docs.includes(id))
})
const expanded=()=>JSON.parse(readFileSync(new URL('../../src/main/resources/pokemon-guides/expanded-v2.json',import.meta.url),'utf8'))
const live=()=>JSON.parse(readFileSync(new URL('../../src/main/resources/pokemon-guides/expanded-v5.json',import.meta.url),'utf8'))
test('expanded corpus source metadata and normalized code identities audit successfully',()=>{
  const report=auditCorpus(live())
  assert.equal(report.documentCount,12);assert.equal(report.uniqueCodeFiles,5)
  assert.equal(report.humanReview,'pending')
  const bad=live();bad.documents[0].provenance.codeReferences[0].sha256='0'.repeat(64)
  assert.throws(()=>auditCorpus(bad),/Stale code reference/)
})
test('upstream URLs and code paths cannot turn the source audit into an arbitrary file reader',()=>{
  const bad=live();bad.documents[0].provenance.codeReferences[0].path='../../application-local.yml'
  assert.throws(()=>auditCorpus(bad),/Unsafe code reference/)
  const url=live();url.documents[2].provenance.references[0].url='https://secret@example.com/'
  assert.throws(()=>auditCorpus(url),/Invalid reference-only URL/)
})
test('expanded evidence labels bind exact source phrases and a separate frozen corpus',()=>{
  const suite=JSON.parse(readFileSync(new URL('../../evaluation/campus/s3/retrieval-expanded-dev-v2.json',import.meta.url),'utf8'))
  assert.equal(suite.cases.length,24);assert.equal(new Set(suite.cases.map(c=>c.id)).size,24)
  assert.equal(suite.corpusSha256,frozenFileDigest(fileURLToPath(new URL('../../'+suite.corpusFile,import.meta.url))))
  for(const row of suite.cases)for(const label of row.relevantEvidence||[])assert.ok(expanded().documents.find(d=>d.id===label.documentId)?.content.includes(label.contains))
})
test('document hit alone cannot give credit for a missing evidence phrase',()=>{
  const item={id:'evidence',relevantDocumentIds:['a'],relevantEvidence:[{documentId:'a',contains:'真正需要的条件'}]}
  const c={view:{documentId:'a',chunk:{id:'a-1',content:'只命中了另一个段落。'}}}
  const row=assessRetrieval(item,[c],true)
  assert.equal(row.recallAt5,1);assert.equal(row.seedEvidenceCoverageAt5,0)
})
test('new corpus support is explicit and does not rewrite the old frozen source checker',()=>{
  const doc=expanded().documents[4]
  const text=`**${doc.title}**\n作者：${doc.author} · 更新：${doc.updatedAt}\n\n> ${doc.content.split('\n')[0]}`
  assert.equal(sourceSupport(text).supported,false)
  assert.equal(sourceSupport(text,expanded().documents).supported,true)
})
test('expanded model checks reject a runtime with missing or different corpus identity',()=>{
  assert.throws(()=>checkExpandedReport({},expanded(),'expected'),/did not attest/)
  assert.throws(()=>checkExpandedReport({guideCatalog:{corpusSha256:'other',catalogId:expanded().catalogId}},expanded(),'expected'),/did not attest/)
})


test('source binding v5 preserves archived bodies and rejects stale bindings',()=>{
  const archived=JSON.parse(readFileSync(new URL('../../src/main/resources/pokemon-guides/expanded-v1.json',import.meta.url),'utf8'))
  const current=live()
  assert.notEqual(current.catalogId,archived.catalogId)
  assert.deepEqual(current.documents.map(d=>[d.id,d.content]),archived.documents.map(d=>[d.id,d.content]))
  assert.throws(()=>auditCorpus(archived),/Stale code reference/)
  assert.throws(()=>auditCorpus(expanded()),/Stale code reference/)
  const previous=JSON.parse(readFileSync(new URL('../../src/main/resources/pokemon-guides/expanded-v3.json',import.meta.url),'utf8'))
  assert.throws(()=>auditCorpus(previous),/Stale code reference/)
  assert.equal(auditCorpus(current).humanReview,'pending')
})
