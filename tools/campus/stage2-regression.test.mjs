import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {stage2Cases,stage2Team} from './stage2-regression.mjs'

test('stage2 fixtures retain fourteen documented questions and the cross-version preparation turn',()=>{
  const source=readFileSync('document/测试文档/测试文档_阶段2_资料与队伍问答.md','utf8')
  const cases=stage2Cases()
  assert.equal(cases.length,14)
  assert.equal(new Set(cases.map(item=>item.id)).size,14)
  cases.forEach((item,index)=>{
    assert.equal(item.id,'S2-'+String(index+1).padStart(2,'0'))
    assert.ok(source.includes(item.turns.at(-1)))
    assert.equal(item.split,'dev')
  })
  assert.equal(cases.reduce((sum,item)=>sum+item.turns.length,0),15)
  assert.equal(cases[13].turns.length,2)
})

test('stage2 team keeps the documented partial build without inventing missing moves or tera type',()=>{
  const team=stage2Team(),member=team.members[0]
  assert.equal(team.name,'test1');assert.equal(team.rulesetId,'casual-v1')
  assert.equal(member.formId,445);assert.equal(member.slot,1)
  assert.deepEqual(member.build.moves,['aerial-ace','bulldoze'])
  assert.equal(member.build.level,51);assert.equal(member.build.nature,'hardy')
  assert.equal(member.build.ability,'rough-skin');assert.equal(member.build.item,'')
  assert.equal(Object.hasOwn(member.build,'teraType'),false)
  assert.ok(Object.values(member.build.evs).every(value=>value===0))
  assert.ok(Object.values(member.build.ivs).every(value=>value===31))
})
