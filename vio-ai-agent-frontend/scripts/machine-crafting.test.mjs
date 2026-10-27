import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {normalizeMachineCraftingGuide,machineCraftingSource} from '../src/api/machine-crafting.js'
const guide=()=>JSON.parse(readFileSync(new URL('../../src/main/resources/pokemon-machines/crafting-guide-v1.json',import.meta.url),'utf8'))
test('official crafting notes keep general scope and an independent copy',()=>{
  const input=guide(),result=normalizeMachineCraftingGuide(input,'pokemon-scarlet-violet')
  assert.equal(result.sourceUrl,machineCraftingSource);assert.equal(result.facts.length,4)
  input.facts[0]='changed';assert.notEqual(result.facts[0],'changed')
  assert.match(result.limitation,/不证明某个编号已解锁/)
})
test('wrong game, changed source, changed date and missing scope are rejected',()=>{
  assert.throws(()=>normalizeMachineCraftingGuide(guide(),'pokemon-sword-shield'))
  for(const [key,value]of [['gameReleaseId','pokemon-sword-shield'],['sourceUrl','javascript:alert(1)'],['checkedAt','2099-01-01'],['scope','LEGAL'],['sourcePageSha256','sha256:'+'b'.repeat(64)],['reviewStatus','HUMAN_APPROVED']]){
    const raw=guide();raw[key]=value;assert.throws(()=>normalizeMachineCraftingGuide(raw,'pokemon-scarlet-violet'))
  }
})
test('malformed and excessive text or unexpected fields cannot become a guide',()=>{
  for(const mutate of [r=>delete r.limitation,r=>r.extra='x',r=>r.facts=[],r=>r.facts[0]=null,r=>r.nextCheck='x'.repeat(301),r=>r.facts[0]='\u0000']){
    const raw=guide();mutate(raw);assert.throws(()=>normalizeMachineCraftingGuide(raw,'pokemon-scarlet-violet'))
  }
})
