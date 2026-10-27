export const machineCraftingScope='GENERAL_SV_CRAFTING_NOT_SPECIFIC_RECIPE_UNLOCK_OR_LEGALITY'
export const machineCraftingSource='https://www.pokemon.co.jp/ex/sv/sc/story/220803_03/'
const fields=['schemaVersion','gameReleaseId','language','scope','checkedAt','title','sourceTitle','sourceUrl','sourceSection','sourcePageSha256','reviewStatus','facts','nextCheck','limitation']
const text=value=>typeof value==='string'&&value.trim().length>0&&value.length<=300&&![...value].some(c=>c.charCodeAt(0)<32)
export function normalizeMachineCraftingGuide(raw,gameReleaseId){
  const fail=()=>{throw Error('制作说明的版本或来源无效，请稍后重试')}
  if(!raw||typeof raw!=='object'||Object.keys(raw).length!==fields.length||fields.some(k=>!Object.hasOwn(raw,k)))fail()
  if(raw.schemaVersion!=='pokemon-machine-crafting-guide-v1'||gameReleaseId!=='pokemon-scarlet-violet'||raw.gameReleaseId!==gameReleaseId||raw.language!=='zh-CN'||raw.scope!==machineCraftingScope||raw.sourceUrl!==machineCraftingSource||raw.checkedAt!=='2026-10-27'||raw.reviewStatus!=='PROJECT_PARAPHRASE_PENDING_HUMAN_REVIEW'||raw.sourcePageSha256!=='sha256:376ab8c91b51c43c0508260ef503ebd06e5eb5bc388e9ac9bc6767ccb0c2d738')fail()
  for(const key of ['title','sourceTitle','sourceSection','nextCheck','limitation'])if(!text(raw[key]))fail()
  if(!Array.isArray(raw.facts)||raw.facts.length!==4||!raw.facts.every(text))fail()
  return structuredClone(raw)
}
