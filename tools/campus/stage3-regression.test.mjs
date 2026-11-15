import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {stage3Cases,stage3DamageInputs} from './stage3-regression.mjs'

test('stage3 keeps fixed damage inputs intact except the explicit EV variant',()=>{
 const {baseline,changed}=stage3DamageInputs()
 const reference=JSON.parse(readFileSync('evaluation/campus/s01/damage-reference.json','utf8'))
 assert.deepEqual(baseline,reference.input)
 const restored=structuredClone(changed)
 restored.attacker.build.evs.spa=252
 assert.deepEqual(restored,baseline)
 assert.equal(changed.attacker.build.evs.spa,0)
 assert.equal(baseline.attacker.build.teraType,undefined)
})

test('stage3 covers ten standard cases and keeps procedural faults out of normal collection',()=>{
 const cases=stage3Cases()
 assert.deepEqual(cases.map(row=>row.id),['S3-01','S3-02','S3-03','S3-04','S3-05','S3-06','S3-07','S3-08','S3-09','S3-14'])
 assert.equal(cases.reduce((sum,row)=>sum+row.turns.length,0),12)
 assert.equal(cases[4].turns[0].includes('"move":'),false)
 assert.ok(cases[5].turns[0].includes('"spa":253'))
 assert.ok(cases[6].turns[0].includes('qa-unknown-release'))
 assert.ok(cases[2].turns[0].includes('\n参考成员：'))
})
