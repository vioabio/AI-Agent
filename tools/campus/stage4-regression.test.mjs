import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {stage4Cases} from './stage4-regression.mjs'

test('stage4 preserves sixteen documented semantic turns and nine filler turns',()=>{
 const source=readFileSync('document/测试文档/测试文档_阶段4_连续对话与历史状态.md','utf8'),groups=stage4Cases()
 assert.deepEqual(groups.map(row=>row.id),['S4-A','S4-B','S4-C','S4-D'])
 assert.deepEqual(groups.map(row=>row.turns.length),[17,3,1,4])
 for(const group of groups){assert.equal(group.turns.length,group.turnCaseIds.length);for(const prompt of group.turns)assert.ok(source.includes(prompt),prompt)}
 assert.equal(groups.flatMap(group=>group.turnCaseIds).filter(id=>/^S4-L0/.test(id)).length,9)
 assert.equal(groups.flatMap(group=>group.turnCaseIds).filter(id=>!/^S4-L0/.test(id)).length,16)
 assert.equal(groups[0].turns[3],'根据我实际保存的招式，建议怎么出招？')
 assert.equal(groups[0].turns[4],'再看第六位馆主，需要调整哪些成员？')
})
