import test from 'node:test'
import assert from 'node:assert/strict'
import {normalizeMachineRecipe} from '../src/api/machine-recipe.js'
import {machineRecipeFixture} from '../e2e/fixtures/machine-recipe.js'
const expected={moveSlug:'protect',label:'TM007',gameReleaseId:'pokemon-scarlet-violet'}
test('consistent recipe retains LP and material quantities and returns a separate copy',()=>{
 const raw=machineRecipeFixture('protect'),r=normalizeMachineRecipe(raw,expected);assert.equal(r.leaguePoints,400);assert.equal(r.materials[0].quantity,3);raw.materials[0].quantity=99;assert.equal(r.materials[0].quantity,3)
})
test('conflicting and absent recipes cannot expose a usable LP or material list',()=>{
 const e={...expected,moveSlug:'water-pulse',label:'TM011'},raw=machineRecipeFixture('water-pulse');assert.equal(normalizeMachineRecipe(raw,e).status,'SOURCE_DISAGREEMENT')
 raw.leaguePoints=1500;assert.throws(()=>normalizeMachineRecipe(raw,e))
 const missing=machineRecipeFixture('surf');assert.equal(normalizeMachineRecipe(missing,{...expected,moveSlug:'surf'}).status,'NOT_CATALOGUED');missing.materials=[{name:'Other',quantity:1}];assert.throws(()=>normalizeMachineRecipe(missing,{...expected,moveSlug:'surf'}))
})
test('old move, wrong number, wrong game or different source cannot be reused',()=>{
 for(const change of [r=>r.moveSlug='ice-beam',r=>r.label='TM999',r=>r.gameReleaseId='pokemon-sword-shield',r=>r.sources[0].url='javascript:alert(1)',r=>r.catalogSha256='sha256:'+'0'.repeat(64),r=>r.sources[1].pageSha256='sha256:'+'b'.repeat(64),r=>r.reviewStatus='OFFICIAL_VERIFIED']){const r=machineRecipeFixture('protect');change(r);assert.throws(()=>normalizeMachineRecipe(r,expected))}
})
test('fractional negative duplicate and excessive materials are rejected',()=>{
 for(const change of [r=>r.leaguePoints=-1,r=>r.leaguePoints=4.5,r=>r.materials[0].quantity=0,r=>r.materials[0].quantity=3.5,r=>r.materials.push(r.materials[0]),r=>r.materials[0].name='<script>bad</script>',r=>r.materials[0].name='x'.repeat(81)]){const r=machineRecipeFixture('protect');change(r);assert.throws(()=>normalizeMachineRecipe(r,expected))}
})
test('changed API shape is not silently coerced to a recipe',()=>{
 for(const change of [r=>delete r.status,r=>r.extra='x',r=>r.materials=null,r=>r.sources=null]){const r=machineRecipeFixture('protect');change(r);assert.throws(()=>normalizeMachineRecipe(r,expected))}
})
