export const machineRecipeScope='COMMUNITY_RECIPE_REFERENCE_NOT_UNLOCK_ACQUISITION_OR_LEGALITY'
export const machineRecipeCatalogSha256='sha256:4852256e2f3cb25a9ae5545051dd346e5305ee2f0f7431efe3ed5809b86ef2df'
export const machineRecipeSources=[{id:'pokemondb',publisher:'Pokémon Database',url:'https://pokemondb.net/scarlet-violet/tms',pageSha256:'sha256:c1e57b5bc5538377ac73d818efe1776f0152cdf7df105130021d7aacceba9c30'},{id:'serebii',publisher:'Serebii',url:'https://www.serebii.net/scarletviolet/tm.shtml',pageSha256:'sha256:9728facc8345aa97c084aaaf66a0c32629eda343aca16ebfc0dbcb4f2723d05a'}]
export function normalizeMachineRecipe(raw,expected){
  const fail=()=>{throw Error('配方参考与当前招式、编号或来源不一致，请重试')}
  if(!raw||Object.keys(raw).length!==13||raw.schemaVersion!=='pokemon-machine-recipe-reference-v1'||raw.scope!==machineRecipeScope||raw.catalogSha256!==machineRecipeCatalogSha256||raw.gameReleaseId!=='pokemon-scarlet-violet'||raw.gameReleaseId!==expected.gameReleaseId||raw.moveSlug!==expected.moveSlug||raw.checkedAt!=='2026-10-27'||raw.reviewStatus!=='PENDING_HUMAN_AND_IN_GAME_VERIFICATION'||!Array.isArray(raw.sources)||!Array.isArray(raw.materials))fail()
  if(raw.status==='NOT_CATALOGUED'){if(raw.label!==null||raw.nameZh!==null||raw.leaguePoints!==null||raw.materials.length||raw.sources.length)fail();return structuredClone(raw)}
  if(raw.label!==expected.label||!['CONSISTENT_REFERENCE','SOURCE_DISAGREEMENT'].includes(raw.status)||typeof raw.nameZh!=='string'||!/^\p{Script=Han}{1,12}$/u.test(raw.nameZh)||raw.sources.length!==2)fail()
  raw.sources.forEach((source,i)=>{if(Object.keys(source||{}).length!==4||Object.keys(machineRecipeSources[i]).some(k=>source[k]!==machineRecipeSources[i][k]))fail()})
  if(raw.status==='SOURCE_DISAGREEMENT'){if(raw.leaguePoints!==null||raw.materials.length)fail();return structuredClone(raw)}
  if(!Number.isSafeInteger(raw.leaguePoints)||raw.leaguePoints<1||raw.leaguePoints>100000||raw.materials.length<1||raw.materials.length>3||new Set(raw.materials.map(m=>m?.name)).size!==raw.materials.length)fail()
  for(const m of raw.materials)if(!m||Object.keys(m).length!==2||typeof m.name!=='string'||m.name.length>80||!/^([A-Za-z]+ )*[A-Za-z]+$/.test(m.name)||!Number.isSafeInteger(m.quantity)||m.quantity<1||m.quantity>99)fail()
  return structuredClone(raw)
}
