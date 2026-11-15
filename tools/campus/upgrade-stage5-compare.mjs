import assert from 'node:assert/strict'
import {readFileSync,writeFileSync,realpathSync,statSync,existsSync} from 'node:fs'
import {resolve,relative,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest} from './evaluation.mjs'
import {summarizeStage5Retrieval} from './upgrade-stage5-retrieval.mjs'

export function compareStage5(baseline,candidate,dataset) {
 assert.equal(baseline.status,'COLLECTED_PENDING_CONTENT_REVIEW');assert.equal(candidate.status,'COLLECTED_PENDING_CONTENT_REVIEW')
 assert.equal(baseline.mode,'BM25');assert.equal(candidate.mode,'HYBRID')
 assert.equal(baseline.before.enabled,false);assert.equal(candidate.before.enabled,true)
 assert.equal(baseline.datasetSha256,candidate.datasetSha256);assert.equal(baseline.corpusSha256,candidate.corpusSha256)
 assert.equal(baseline.runtimeBuild.sha256,candidate.runtimeBuild.sha256)
 assert.equal(baseline.indexBefore.fingerprint,candidate.indexBefore.fingerprint)
 for(const report of [baseline,candidate]){
  assert.equal(new Set(report.records.map(row=>row.caseId)).size,dataset.cases.length)
  for(const expected of dataset.cases){
   const actual=report.records.find(row=>row.caseId===expected.id)
   assert.equal(actual?.query,expected.query);assert.equal(actual.sourcesVerified,true)
   assert.ok(Number.isFinite(actual.latencyMs)&&actual.latencyMs>=0)
  }
 }
 const a=summarizeStage5Retrieval(dataset,baseline.records),b=summarizeStage5Retrieval(dataset,candidate.records)
 const latency=report=>{const sorted=report.records.map(row=>row.latencyMs).sort((x,y)=>x-y);return {p50:sorted[Math.ceil(sorted.length*.5)-1],p95:sorted[Math.ceil(sorted.length*.95)-1],scope:'RETRIEVAL_PLUS_CANONICAL_SOURCE_CHECKS_MS'}}
 return {schemaVersion:'stage5-paired-comparison-v1',status:'COMPARABLE',datasetSha256:baseline.datasetSha256,corpusSha256:baseline.corpusSha256,indexFingerprint:baseline.indexBefore.fingerprint,
  baseline:a,candidate:b,delta:{documentRecallWithinTop5Chunks:b.documentRecallWithinTop5Chunks-a.documentRecallWithinTop5Chunks,mrrAt5:b.mrrAt5-a.mrrAt5,negativeEmptyAccuracy:b.negativeEmptyAccuracy-a.negativeEmptyAccuracy},latency:{baseline:latency(baseline),candidate:latency(candidate)},labels:dataset.labelOrigin,releaseDecision:'NOT_EVALUATED'}
}
function artifact(file){const actual=realpathSync(resolve(file)),within=relative(realpathSync('artifacts'),actual);assert.ok(within&&!within.startsWith('..')&&!isAbsolute(within)&&statSync(actual).size<=16777216);return readFileSync(actual)}
function main(){
 const [a,b,output]=process.argv.slice(2),destination=resolve(output??'.')
 assert.ok(a&&b&&output);assert.equal(existsSync(destination),false);assert.equal(realpathSync(resolve(destination,'..')),realpathSync('artifacts'))
 const bytes=[artifact(a),artifact(b)],dataset=JSON.parse(readFileSync('evaluation/upgrade-v1.1/u6/stage5-retrieval-dev.json','utf8'))
 const report=compareStage5(JSON.parse(bytes[0]),JSON.parse(bytes[1]),dataset);report.inputsSha256=bytes.map(digest)
 writeFileSync(destination,JSON.stringify(report,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:report.status,baseline:report.baseline.mrrAt5,candidate:report.candidate.mrrAt5,delta:report.delta}))
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))main()
