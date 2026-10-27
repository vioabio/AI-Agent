import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {normalizeTeamBuildCheck,duplicateFormSlots,teamBuildQuestion} from '../src/api/team-build-check.js'
import {teamBuildCheckRequest,teamBuildCheckFixture} from '../e2e/fixtures/team-build-check.js'
test('per-member reports, counts and duplicate observations preserve original order',()=>{
 const q=teamBuildCheckRequest();q.members.push({...structuredClone(q.members[0]),slot:3});const r=normalizeTeamBuildCheck(teamBuildCheckFixture(q),q)
 assert.equal(r.counts.REFERENCE_MATCHED,2);assert.deepEqual(r.duplicateFormSlots,[[1,3]]);assert.equal(r.jointStatus,'NOT_VERIFIED');assert.deepEqual(duplicateFormSlots(q),[[1,3]])
})
test('wrong slots, changed moves and mixed reports cannot be used for current team',()=>{
 const q=teamBuildCheckRequest();for(const change of [r=>r.members[0].slot=2,r=>r.members[0].report.request.build.moves[0]='fake',r=>r.request.members[0].pokemonId=478,r=>r.members=[]]){const r=teamBuildCheckFixture(q);change(r);assert.throws(()=>normalizeTeamBuildCheck(r,q))}
})
test('joint legality cannot be upgraded and counts cannot hide failures',()=>{
 const q=teamBuildCheckRequest();for(const change of [r=>r.jointStatus='VERIFIED',r=>r.counts.REFERENCE_MATCHED=6,r=>r.counts.INCOMPLETE='0',r=>r.duplicateFormSlots=[[1,2]],r=>r.scope='LEGAL']){const r=teamBuildCheckFixture(q);change(r);assert.throws(()=>normalizeTeamBuildCheck(r,q))}
})
test('empty oversized and repeated team slots are not accepted',()=>{
 for(const size of [0,7]){const q=teamBuildCheckRequest();q.members=Array.from({length:size},(_,i)=>({...structuredClone(q.members[0]),slot:i+1}));assert.throws(()=>normalizeTeamBuildCheck(teamBuildCheckFixture(q),q))}
 const q=teamBuildCheckRequest();q.members.push(structuredClone(q.members[0]));assert.throws(()=>normalizeTeamBuildCheck(teamBuildCheckFixture(q),q))
})
test('normalized report is isolated from later input and output mutation',()=>{
 const q=teamBuildCheckRequest(),raw=teamBuildCheckFixture(q),r=normalizeTeamBuildCheck(raw,q);raw.counts.REFERENCE_MATCHED=9;q.members[0].build.level=51;assert.equal(r.counts.REFERENCE_MATCHED,1);assert.equal(r.request.members[0].build.level,50)
})
test('team question keeps original missing fields and read-only instructions without report hashes',()=>{
 const q=teamBuildCheckRequest();q.members[0].build.ability=null;const prompt=teamBuildQuestion(q)
 assert.match(prompt,/只核对，不保存、不生成修改预览/);assert.deepEqual(JSON.parse(prompt.slice(prompt.indexOf('\n')+1)),q);assert.doesNotMatch(prompt,/reportSha256|jointStatus/)
 const java=readFileSync(new URL('../../src/main/java/com/vio/vioaiagent/agent/TeamBuildRequestRoute.java',import.meta.url),'utf8');assert.equal(prompt.split('\n')[0],java.match(/UI_HEADER="([^"]+)"/)[1])
})
