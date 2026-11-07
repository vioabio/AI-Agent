import {readFileSync} from 'node:fs'
import {joinRecipe} from '../../../tools/campus/machine-recipes.mjs'
const c=JSON.parse(readFileSync(new URL('../../../src/main/resources/pokemon-machines/recipe-catalog-v1.json',import.meta.url),'utf8'))
// These are project-relative paths from e2e/fixtures, independent of the browser bundle.
export function machineRecipeFixture(moveSlug){
 const row=structuredClone(c.records.find(r=>r.moveSlug===moveSlug))
 return {schemaVersion:'pokemon-machine-recipe-reference-v1',gameReleaseId:c.gameReleaseId,moveSlug,label:row?.label??null,nameZh:row?.nameZh??null,...(row?joinRecipe(row):{status:'NOT_CATALOGUED',leaguePoints:null,materials:[]}),sources:row?structuredClone(c.sources):[],checkedAt:c.checkedAt,reviewStatus:c.reviewStatus,scope:c.scope,catalogSha256:'sha256:4852256e2f3cb25a9ae5545051dd346e5305ee2f0f7431efe3ed5809b86ef2df'}
}
