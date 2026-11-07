import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {buildLearningNotes,validLearningEntries} from '../src/api/build-learning.js'
import {normalizeBuildCheck,buildCheckDraft} from '../src/api/build-check.js'
import {buildCheckFixture,buildCheckRequest} from '../e2e/fixtures/build-check.js'

const vectors=JSON.parse(fs.readFileSync(new URL('../../evaluation/campus/learning-review-v1/presentation-cases.json',import.meta.url))).cases
for(const row of vectors)test(`shared learning presentation: ${row.id}`,()=>{
  assert.deepEqual(buildLearningNotes({status:'RECORDED',learning:{entries:row.entries}},row.level),row.expected)
})
test('malformed entry types are rejected instead of displayed as a learning condition',()=>{
  for(const entry of [{methodKey:'machine',levelLearnedAt:'0',order:null},{methodKey:'level-up',levelLearnedAt:101,order:null},{methodKey:'<script>',levelLearnedAt:0,order:null},{methodKey:'machine',levelLearnedAt:0,order:-1}]){
    assert.equal(validLearningEntries([entry]),false)
    const q=buildCheckRequest(),r=buildCheckFixture(q);r.moves[0].learning.entries=[entry];assert.throws(()=>normalizeBuildCheck(r,q))
  }
})
test('displayed rows are bounded and missing or failed rows never acquire invented details',()=>{
  const entries=Array.from({length:10},(_,i)=>({methodKey:'level-up',levelLearnedAt:i+1,order:null}));entries.push({...entries[0],order:1})
  const notes=buildLearningNotes({status:'RECORDED',learning:{entries}},5)
  assert.equal(notes.length,7);assert.equal(notes.at(-1),'另有 4 条记录，详见来源；不据此推断其他路径。')
  assert.deepEqual(buildLearningNotes({status:'REFERENCE_UNAVAILABLE',learning:{entries}},5),[])
  assert.deepEqual(buildLearningNotes({status:'RECORDED'},5),[])
})
test('cleared numeric form fields become explicit omissions without replacing zero or invalid text',()=>{
  const original={level:'',evs:{hp:'',atk:0,spa:252},ivs:{hp:31,atk:''}}
  assert.deepEqual(buildCheckDraft(original),{level:null,evs:{hp:null,atk:0,spa:252},ivs:{hp:31,atk:null}})
  assert.equal(original.level,'');assert.equal(buildCheckDraft({level:'bad',evs:null,ivs:null}).level,'bad')
})
