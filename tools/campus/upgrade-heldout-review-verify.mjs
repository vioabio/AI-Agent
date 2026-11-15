import assert from 'node:assert/strict'
import {existsSync,readFileSync,writeFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {loadFrozenSuite} from './upgrade-baseline.mjs'
import {digest} from './evaluation.mjs'
import {verifyHeldoutReview} from './heldout-review.mjs'

const [directory,reviewFile,output]=process.argv.slice(2)
if(!directory||!reviewFile||!output)throw Error('Usage: upgrade-heldout-review-verify.mjs EVIDENCE_DIRECTORY REVIEW_JSON NEW_OUTPUT_JSON')
assert.ok(!existsSync(output),'Refuse to overwrite verification evidence')
const suite=loadFrozenSuite(),bytes=readFileSync(resolve(directory,'baseline.json'),'utf8')
const baseline=JSON.parse(bytes),reviewBytes=readFileSync(reviewFile,'utf8')
assert.equal(baseline.datasetSha256,suite.datasetSha256)
assert.equal(baseline.rubricSha256,suite.manifest.files['rubric.json'])
assert.equal(baseline.corpusSha256,suite.manifest.corpus.sha256)
const verified=verifyHeldoutReview(bytes,JSON.parse(reviewBytes),suite.cases,
  file=>readFileSync(resolve(directory,file),'utf8'))
const result={...verified,sourceSha256:digest(bytes),reviewSha256:digest(reviewBytes),
  note:'Evidence integrity only. Content verdicts are AI-assisted and require human review.'}
writeFileSync(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'})
console.log(JSON.stringify(result))
