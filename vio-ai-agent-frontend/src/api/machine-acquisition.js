import {machineRecipeSources} from './machine-recipe.js'
export const machineAcquisitionSha256='sha256:e8cee2cd0fc2e36a1a794329d826f2fefbab988d59053d9b2ca12c850031bd3e'
export const machineAcquisitionScope='COMMON_COMMUNITY_REGIONS_NOT_EXACT_LOCATION_UNLOCK_OR_LEGALITY'
export const machineAcquisitionRegions={'south-province-area-one':'South Province (Area One)','south-province-area-four':'South Province (Area Four)','socarrat-trail':'Socarrat Trail','casseroya-lake':'Casseroya Lake'}
export function normalizeMachineAcquisition(raw,expected){
  const fail=()=>{throw Error('获取区域参考与当前招式、编号或来源不一致，请重试')}
  if(!raw||Object.keys(raw).length!==13||raw.schemaVersion!=='pokemon-machine-acquisition-reference-v1'||raw.scope!==machineAcquisitionScope||raw.catalogSha256!==machineAcquisitionSha256||raw.gameReleaseId!=='pokemon-scarlet-violet'||raw.gameReleaseId!==expected.gameReleaseId||raw.moveSlug!==expected.moveSlug||raw.checkedAt!=='2026-10-27'||raw.reviewStatus!=='PENDING_HUMAN_AND_IN_GAME_VERIFICATION'||raw.unlockStatus!=='NOT_CROSS_CHECKED'||!Array.isArray(raw.sources)||!Array.isArray(raw.regions))fail()
  if(raw.status==='NOT_CATALOGUED'){if(raw.label!==null||raw.nameZh!==null||raw.regions.length||raw.sources.length)fail();return structuredClone(raw)}
  if(raw.label!==expected.label||typeof raw.nameZh!=='string'||!/^\p{Script=Han}{1,12}$/u.test(raw.nameZh)||raw.sources.length!==2||!['COMMON_REGION_REFERENCE','SOURCE_INCOMPLETE','NO_COMMON_REGION'].includes(raw.status))fail()
  raw.sources.forEach((source,i)=>{if(Object.keys(source||{}).length!==4||Object.keys(machineRecipeSources[i]).some(k=>source[k]!==machineRecipeSources[i][k]))fail()})
  if(raw.status!=='COMMON_REGION_REFERENCE'){if(raw.regions.length)fail();return structuredClone(raw)}
  if(raw.regions.length<1||raw.regions.length>4||new Set(raw.regions.map(r=>r?.id)).size!==raw.regions.length)fail()
  for(const r of raw.regions)if(!r||Object.keys(r).length!==2||!Object.hasOwn(machineAcquisitionRegions,r.id)||machineAcquisitionRegions[r.id]!==r.nameEn)fail()
  return structuredClone(raw)
}
