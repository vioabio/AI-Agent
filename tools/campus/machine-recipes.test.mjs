import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {createHash} from 'node:crypto'
import {extractRecipe,joinRecipe} from './machine-recipes.mjs'
const db='<tr><td class="cell-num">011</td><td><a href="/move/water-pulse">Water Pulse</a></td><td>(TM Machine: 1,500 LP, 3 Buizel Fur, 3 Magikaro Scales)</td></tr>'
const sr='<td class="fooinfo" rowspan="3">TM011</td><a href="/attackdex-sv/waterpulse.shtml">Water Pulse</a><table><tr><td>1500 LP</td></tr><tr><td><a href="/itemdex/buizelfur.shtml">Buizel Fur</a> * 3</td></tr><tr><td><a href="/itemdex/magikarpscales.shtml">Magikarp Scales</a> * 3</td></tr></table>'
const observation=(sourceId,quantity=3)=>({sourceId,leaguePoints:1500,materials:[{name:'Buizel Fur',quantity},{name:'Magikarp Scales',quantity:3}]})
test('source spellings and excerpt positions are preserved without an automatic alias',()=>{
 const d=extractRecipe('prefix'+db,'pokemondb','water-pulse','TM011'),s=extractRecipe(sr,'serebii','water-pulse','TM011')
 assert.equal(d.materials[1].name,'Magikaro Scales');assert.equal(s.materials[1].name,'Magikarp Scales');assert.equal(d.excerpt.offset,6);assert.equal(d.leaguePoints,1500)
 assert.deepEqual(joinRecipe({observations:[d,s]}),{status:'SOURCE_DISAGREEMENT',leaguePoints:null,materials:[]})
})
test('any LP, quantity or material disagreement withholds the entire recipe',()=>{
 for(const change of [r=>r.leaguePoints++,r=>r.materials[0].quantity++,r=>r.materials[1].name='Other Material']){const other=observation('serebii');change(other);assert.equal(joinRecipe({observations:[observation('pokemondb'),other]}).status,'SOURCE_DISAGREEMENT')}
})
test('agreement ignores ordering while repeated materials and invalid quantities are refused',()=>{
 const other=observation('serebii');other.materials.reverse();assert.equal(joinRecipe({observations:[observation('pokemondb'),other]}).status,'CONSISTENT_REFERENCE')
 for(const change of [r=>r.materials.push(r.materials[0]),r=>r.materials[0].quantity=0,r=>r.leaguePoints=1.5,r=>delete r.materials[0].name]){const bad=observation('serebii');change(bad);assert.throws(()=>joinRecipe({observations:[observation('pokemondb'),bad]}))}
})
test('duplicate rows, wrong number, wrong move and missing source never become a recipe',()=>{
 assert.throws(()=>extractRecipe(db+db,'pokemondb','water-pulse','TM011'));assert.throws(()=>extractRecipe(db,'pokemondb','water-pulse','TM007'));assert.throws(()=>extractRecipe(sr,'serebii','protect','TM011'));assert.throws(()=>joinRecipe({observations:[observation('pokemondb')]}))
})
test('all selected reference rows retain the known conflict and three consistent recipes',()=>{
 const c=JSON.parse(fs.readFileSync(new URL('../../src/main/resources/pokemon-machines/recipe-catalog-v1.json',import.meta.url),'utf8'));assert.equal(c.records.length,4)
 const result=c.records.map(r=>[r.moveSlug,joinRecipe(r).status]);assert.equal(result.filter(r=>r[1]==='CONSISTENT_REFERENCE').length,3);assert.deepEqual(result.find(r=>r[1]==='SOURCE_DISAGREEMENT'),['water-pulse','SOURCE_DISAGREEMENT'])
})
test('Java and browser pins identify the exact normalized resource',()=>{
 const body=fs.readFileSync(new URL('../../src/main/resources/pokemon-machines/recipe-catalog-v1.json',import.meta.url),'utf8').replaceAll('\r\n','\n'),sha='sha256:'+createHash('sha256').update(body).digest('hex')
 assert.ok(fs.readFileSync(new URL('../../src/main/java/com/vio/vioaiagent/pokemon/reference/MachineRecipeCatalog.java',import.meta.url),'utf8').includes('"'+sha+'"'))
 assert.ok(fs.readFileSync(new URL('../../vio-ai-agent-frontend/src/api/machine-recipe.js',import.meta.url),'utf8').includes("'"+sha+"'"))
})
