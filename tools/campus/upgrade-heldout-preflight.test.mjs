import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {loadFrozenSuite} from './upgrade-baseline.mjs'
import {heldoutPlan} from './upgrade-heldout-preflight.mjs'

const corpus=JSON.parse(readFileSync('src/main/resources/pokemon-strategy/overview-v1.json','utf8'))
test('independent preflight retains frozen questions and rubric identity with separate candidate corpus',()=>{
  const suite=loadFrozenSuite(),plan=heldoutPlan(suite,corpus)
  assert.equal(plan.questionCount,20);assert.ok(plan.turnCount>=20)
  assert.equal(plan.rubricSha256,suite.manifest.files['rubric.json'])
  assert.ok(plan.cases.every(row=>Object.keys(row).sort().join(',')==='caseId,category,turns'))
  assert.equal(JSON.stringify(plan).includes('prompt'),false)
})
test('missing frozen source labels or a lost heldout case cannot pass preflight',()=>{
  const suite=loadFrozenSuite()
  assert.throws(()=>heldoutPlan({...suite,cases:suite.cases.filter(row=>row.split==='dev')},corpus))
  assert.throws(()=>heldoutPlan(suite,{documents:[]}),/SOURCE_LABEL_MISSING/)
})
