import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {createHash} from 'node:crypto'
import {extractAcquisition,commonAcquisitionRegions} from './machine-acquisition.mjs'
const row=(sourceId,paths)=>({sourceId,locations:paths.map(path=>({path,label:'original name'}))})
const db='/location/paldea-socarrat-trail',sr='/pokearth/paldea/socarrattrail.shtml'
test('only explicitly paired paths yield common broad regions',()=>{
 const result=commonAcquisitionRegions({observations:[row('pokemondb',[db]),row('serebii',[sr,'/pokearth/paldea/cascarrafa.shtml'])]})
 assert.deepEqual(result,{status:'COMMON_REGION_REFERENCE',regions:[{id:'socarrat-trail',nameEn:'Socarrat Trail'}]})
})
test('source omission and different nonempty lists do not imply unavailable locations',()=>{
 assert.equal(commonAcquisitionRegions({observations:[row('pokemondb',[]),row('serebii',[sr])]}).status,'SOURCE_INCOMPLETE')
 assert.equal(commonAcquisitionRegions({observations:[row('pokemondb',[db]),row('serebii',['/pokearth/paldea/cascarrafa.shtml'])]}).status,'NO_COMMON_REGION')
 assert.throws(()=>commonAcquisitionRegions({observations:[row('pokemondb',[db])]}))
})
test('the whole Great Crater is not automatically equated to Area Zero',()=>{
 assert.deepEqual(commonAcquisitionRegions({observations:[row('pokemondb',['/location/paldea-the-great-crater-of-paldea']),row('serebii',['/pokearth/paldea/areazero.shtml'])]}),{status:'NO_COMMON_REGION',regions:[]})
})
test('raw location names and one-source unlock note remain observations, wrong or duplicate rows fail',()=>{
 const html='<td class="fooinfo" rowspan="3">TM011</td><a href="/attackdex-sv/waterpulse.shtml">Water Pulse</a><a href="'+sr+'">Socarrat Trail</a> Crafting Recipe: Defeat Team Star Boss Mela'
 const result=extractAcquisition(html,'serebii','water-pulse','TM011');assert.equal(result.unlockNote,'Defeat Team Star Boss Mela');assert.equal(result.locations[0].path,sr)
 assert.throws(()=>extractAcquisition(html+html,'serebii','water-pulse','TM011'));assert.throws(()=>extractAcquisition(html,'serebii','protect','TM011'))
})
test('fixed catalog has three partial-overlap references, one incomplete source and no confirmed unlock',()=>{
 const catalog=JSON.parse(fs.readFileSync(new URL('../../src/main/resources/pokemon-machines/acquisition-catalog-v1.json',import.meta.url),'utf8'))
 const results=catalog.records.map(r=>[r.moveSlug,commonAcquisitionRegions(r,catalog.regionMappings)]);assert.equal(results.filter(([,r])=>r.status==='COMMON_REGION_REFERENCE').length,3);assert.equal(results.find(([s])=>s==='ice-beam')[1].status,'SOURCE_INCOMPLETE')
})
test('Java and browser identify the exact normalized catalog',()=>{
 const raw=fs.readFileSync(new URL('../../src/main/resources/pokemon-machines/acquisition-catalog-v1.json',import.meta.url),'utf8').replaceAll('\r\n','\n'),sha='sha256:'+createHash('sha256').update(raw).digest('hex')
 assert.ok(fs.readFileSync(new URL('../../src/main/java/com/vio/vioaiagent/pokemon/reference/MachineAcquisitionCatalog.java',import.meta.url),'utf8').includes('"'+sha+'"'))
 assert.ok(fs.readFileSync(new URL('../../vio-ai-agent-frontend/src/api/machine-acquisition.js',import.meta.url),'utf8').includes("'"+sha+"'"))
})
