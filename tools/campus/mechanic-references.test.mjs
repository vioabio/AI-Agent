import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {digest} from './evaluation.mjs'
import {projectResource,seal,descriptionHashes} from './build-mechanic-references.mjs'
const catalog=JSON.parse(fs.readFileSync('src/main/resources/pokemon-mechanics/references-v1.json','utf8'))
const envelope=(kind,body)=>{const payload=JSON.stringify(body);return {payload,url:`https://pokeapi.co/api/v2/${kind}/${body.name}/`,sha256:'sha256:'+digest(payload),retrievedAt:'2026-10-26T12:00:00Z'}}
test('twelve resource projections and catalog identity are intact, all paraphrases bind an exact SV text hash',()=>{
 const meta=JSON.parse(fs.readFileSync('src/main/resources/pokemon-mechanics/references-v1.meta.json','utf8'))
 assert.equal(catalog.records.length,12);assert.equal(meta.catalogSha256,'sha256:'+digest(fs.readFileSync('src/main/resources/pokemon-mechanics/references-v1.json','utf8').replaceAll('\r\n','\n')))
 for(const row of catalog.records){const {projectionSha256,...fields}=row;assert.equal(seal(fields).projectionSha256,projectionSha256);if(row.explanationZh)assert.equal(row.descriptionSha256,descriptionHashes[`${row.kind}:${row.slug}`]);}
})
test('old generation description is not silently promoted to the SV scope',()=>{
 const body={id:65,name:'overgrow',names:[],flavor_text_entries:[{flavor_text:'Legacy text',language:{name:'en'},version_group:{name:'sword-shield'}}]}
 assert.equal(projectResource('ability',envelope('ability',body)).descriptionExcerpt,undefined)
})
test('changed but valid upstream SV text cannot inherit the old Chinese paraphrase',()=>{
 const body={id:67,name:'torrent',names:[],flavor_text_entries:[{flavor_text:'Changed upstream text',language:{name:'en'},version_group:{name:'scarlet-violet'}}]}
 const result=projectResource('ability',envelope('ability',body));assert.equal(result.descriptionExcerpt,'Changed upstream text');assert.equal(result.explanationZh,undefined)
})
test('foreign source URLs and damaged payload digests fail before projecting any facts',()=>{
 const raw=envelope('ability',{id:65,name:'overgrow'});assert.throws(()=>projectResource('ability',{...raw,url:'https://example.com/'}));assert.throws(()=>projectResource('ability',{...raw,sha256:'wrong'}));
})
test('ambiguous version rows are rejected and long English excerpts remain explicitly partial',()=>{
 const row={flavor_text:Array(30).fill('word').join(' '),language:{name:'en'},version_group:{name:'scarlet-violet'}}
 const body={id:65,name:'overgrow',flavor_text_entries:[row]},result=projectResource('ability',envelope('ability',body));assert.equal(result.descriptionExcerpt.split(' ').length,25);assert.equal(result.descriptionTruncated,true)
 assert.throws(()=>projectResource('ability',envelope('ability',{...body,flavor_text_entries:[row,row]})))
})
test('missing nature directions cannot become an invented neutral nature',()=>{
 assert.throws(()=>projectResource('nature',envelope('nature',{id:1,name:'hardy'})))
 const result=projectResource('nature',envelope('nature',{id:1,name:'hardy',increased_stat:null,decreased_stat:null}));assert.equal(result.neutralNature,true)
})
