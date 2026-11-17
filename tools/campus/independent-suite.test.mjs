import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {loadIndependentSuite,independentDirectory} from './independent-suite.mjs'
import {frozenFileDigest} from './evaluation.mjs'
import {loadFrozenSuite} from './upgrade-baseline.mjs'
import {stage2Cases} from './stage2-regression.mjs'
import {stage3Cases} from './stage3-regression.mjs'
import {stage4Cases} from './stage4-regression.mjs'
import {stage5Cases} from './stage5-regression.mjs'
import {stage6RoutingCases,stage6InjectionCases} from './stage6-routing.mjs'

test('new candidate suite freezes twelve cases and sixteen turns with current sources',()=>{
  const suite=loadIndependentSuite()
  assert.equal(suite.cases.length,12);assert.equal(suite.manifest.turnCount,16)
  assert.equal(Object.keys(suite.manifest.categoryCounts).length,6)
  assert.equal(suite.manifest.scope,'NEW_FIRST_ANSWER_CANDIDATE_NOT_RELEASE_APPROVAL')
})
test('overnight candidate suite freezes twenty new cases and twenty-four turns',()=>{
  const suite=loadIndependentSuite(path.resolve('evaluation/upgrade-v1.1/u6/independent-v3'))
  assert.equal(suite.cases.length,20)
  assert.equal(suite.manifest.turnCount,24)
  const previous=new Set([...loadIndependentSuite().cases,...loadFrozenSuite().cases].flatMap(row=>row.turns))
  for(const row of suite.cases)for(const prompt of row.turns)assert.equal(previous.has(prompt),false,prompt)
})
test('new prompts do not exactly repeat the frozen or stage regression questions',()=>{
  const previous=[...loadFrozenSuite().cases,...stage2Cases(),...stage3Cases(),...stage4Cases(),...stage5Cases(),...stage6RoutingCases(),...stage6InjectionCases()]
  const used=new Set(previous.flatMap(row=>row.turns))
  for(const row of loadIndependentSuite().cases)for(const prompt of row.turns)assert.equal(used.has(prompt),false,prompt)
})
test('changed frozen bytes, category counts and source traversal are rejected',()=>{
  const artifacts=fs.realpathSync('artifacts'),directory=fs.mkdtempSync(path.join(artifacts,'candidate-suite-test-'))
  assert.equal(path.dirname(fs.realpathSync(directory)),artifacts)
  try {
    for(const mutate of [m=>m.caseCount=99,m=>m.categoryCounts.facts=3,m=>m.additionalSources[0].path='../../outside.json']){
      for(const file of ['manifest.json','cases.json','rubric.json'])fs.copyFileSync(path.join(independentDirectory,file),path.join(directory,file))
      const manifest=JSON.parse(fs.readFileSync(path.join(directory,'manifest.json')));mutate(manifest)
      fs.writeFileSync(path.join(directory,'manifest.json'),JSON.stringify(manifest))
      assert.throws(()=>loadIndependentSuite(directory))
    }
    fs.copyFileSync(path.join(independentDirectory,'manifest.json'),path.join(directory,'manifest.json'))
    fs.appendFileSync(path.join(directory,'cases.json'),' ')
    assert.throws(()=>loadIndependentSuite(directory))
    assert.notEqual(frozenFileDigest(path.join(directory,'cases.json')),loadIndependentSuite().manifest.files['cases.json'])
  }finally{fs.rmSync(directory,{recursive:true,force:true})}
})
