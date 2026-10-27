import test from 'node:test'
import assert from 'node:assert/strict'
import {normalizeMachineAcquisition} from '../src/api/machine-acquisition.js'
import {machineAcquisitionFixture} from '../e2e/fixtures/machine-acquisition.js'
const expected={moveSlug:'protect',label:'TM007',gameReleaseId:'pokemon-scarlet-violet'}
test('region subset remains separately copied, source lists are not declared fully equal',()=>{
 const raw=machineAcquisitionFixture('protect'),r=normalizeMachineAcquisition(raw,expected);assert.equal(r.regions.length,2);assert.equal(r.unlockStatus,'NOT_CROSS_CHECKED');raw.regions[0].nameEn='changed';assert.equal(r.regions[0].nameEn,'South Province (Area One)')
})
test('incomplete, unmatched and uncatalogued states cannot carry a location assertion',()=>{
 const e={...expected,moveSlug:'ice-beam',label:'TM135'},r=machineAcquisitionFixture('ice-beam');assert.equal(normalizeMachineAcquisition(r,e).status,'SOURCE_INCOMPLETE');r.regions=[{id:'socarrat-trail',nameEn:'Socarrat Trail'}];assert.throws(()=>normalizeMachineAcquisition(r,e))
 const unknown=machineAcquisitionFixture('surf');assert.equal(normalizeMachineAcquisition(unknown,{...e,moveSlug:'surf'}).status,'NOT_CATALOGUED')
 const none=machineAcquisitionFixture('protect');none.status='NO_COMMON_REGION';none.regions=[];assert.equal(normalizeMachineAcquisition(none,expected).status,'NO_COMMON_REGION')
})
test('wrong move, number, game, source and confirmed unlock cannot be reused',()=>{
 for(const mutate of [r=>r.moveSlug='hydro-pump',r=>r.label='TM999',r=>r.gameReleaseId='pokemon-sword-shield',r=>r.sources[0].url='javascript:alert(1)',r=>r.unlockStatus='CONFIRMED',r=>r.catalogSha256='sha256:'+'b'.repeat(64)]){const r=machineAcquisitionFixture('protect');mutate(r);assert.throws(()=>normalizeMachineAcquisition(r,expected))}
})
test('unknown, duplicated or renamed regions are rejected',()=>{
 for(const mutate of [r=>r.regions.push(r.regions[0]),r=>r.regions[0]={id:'area-zero',nameEn:'Area Zero'},r=>r.regions[0].nameEn='<script>bad</script>',r=>r.regions=[]]){const r=machineAcquisitionFixture('protect');mutate(r);assert.throws(()=>normalizeMachineAcquisition(r,expected))}
})
test('missing or changed API shapes never become usable hints',()=>{
 for(const mutate of [r=>delete r.scope,r=>r.extra='x',r=>r.regions=null,r=>r.sources=[],r=>r.status='AVAILABLE']){const r=machineAcquisitionFixture('protect');mutate(r);assert.throws(()=>normalizeMachineAcquisition(r,expected))}
})
