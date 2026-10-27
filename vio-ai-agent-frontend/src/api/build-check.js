import {validLearningEntries} from './build-learning.js'
// Vue numeric inputs represent a cleared field as ''. Keep that omission, never fill defaults.
export function buildCheckDraft(build){
 const stats=value=>value==null?value:Object.fromEntries(Object.entries(value).map(([k,v])=>[k,v===''?null:v]))
 return {...build,level:build.level===''?null:build.level,evs:stats(build.evs),ivs:stats(build.ivs)}
}
const statuses=new Set(['REFERENCE_MATCHED','INCOMPLETE','REFERENCE_UNAVAILABLE','REFERENCE_MISMATCH','INVALID_INPUT'])
const moveStatuses=new Set(['RECORDED','NOT_RECORDED','UNRESOLVED_NAME','REFERENCE_UNAVAILABLE'])
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(k=>[k,canonical(value[k])])):value
export const buildCheckIdentity=request=>JSON.stringify(canonical(request))
export const buildCheckLabels={REFERENCE_MATCHED:'逐项参考匹配，组合仍待审核',INCOMPLETE:'配置有缺项或名称未解析',REFERENCE_UNAVAILABLE:'部分资料未能核对',REFERENCE_MISMATCH:'部分配置与本次参考不一致',INVALID_INPUT:'配置格式、重复招式或数值有问题'}
export const buildCheckMoveLabels={RECORDED:'有朱紫学习记录',NOT_RECORDED:'快照未记录，不等于不能学习',UNRESOLVED_NAME:'名称未解析',REFERENCE_UNAVAILABLE:'学习参考未取得'}
export const buildCheckAbilityLabels={MATCHED:'参考候选匹配',NOT_IN_SNAPSHOT:'不在本次候选中',MISSING:'未填写',UNRESOLVED_NAME:'名称未解析',REFERENCE_UNAVAILABLE:'资料未取得'}
export function missingBuildFields(fields){return [...new Set(fields.map(k=>k.startsWith('evs')?'努力值':k.startsWith('ivs')?'个体值':({'moves.fourSlots':'四个招式槽位',ability:'特性',item:'道具字段',nature:'性格',level:'等级',build:'培养配置'}[k]||'培养配置')))]}
export function normalizeBuildCheck(raw,request){
 if(!raw||raw.schemaVersion!=='pokemon-build-check-v1'||raw.scope!=='SUPPLIED_FIELDS_AND_REFERENCE_MEMBERSHIP_NOT_JOINT_LEGALITY'
  ||!statuses.has(raw.status)||!['VALID_SUPPLIED_FIELDS','INVALID_INPUT'].includes(raw.syntaxStatus)||!/^sha256:[a-f0-9]{64}$/.test(raw.reportSha256||'')
  ||buildCheckIdentity(raw.request)!==buildCheckIdentity(request)||!Array.isArray(raw.missingFields)||raw.missingFields.some(f=>typeof f!=='string')
  ||!Object.hasOwn(buildCheckAbilityLabels,raw.abilityStatus)||!Array.isArray(raw.moves)||raw.moves.length>4)throw Error('核对结果与当前配置不一致，请重新检查')
 const selected=request.build?.moves||[]
 if(raw.syntaxStatus!=='INVALID_INPUT'&&raw.moves.length!==selected.length)throw Error('招式核对结果不完整')
 const source=raw.source
 if(source&&(source.pokemonId!==request.pokemonId||!/^https:\/\/pokeapi\.co\/api\/v2\/pokemon\/[a-z0-9-]+\/$/.test(source.sourceUrl||'')||!/^sha256:[a-f0-9]{64}$/.test(source.pokemonChecksum||'')||typeof source.nameZh!=='string'))throw Error('成员来源不匹配')
 for(const [index,m]of raw.moves.entries()){
  if(m.slot!==index+1||m.supplied!==selected[index]||!moveStatuses.has(m.status))throw Error('招式核对顺序或内容不匹配')
  if(m.learning&&!validLearningEntries(m.learning.entries))throw Error('学习记录字段无效，请重新检查')
  if(m.status==='RECORDED'&&(!source||!m.learning||m.learning.pokemonId!==request.pokemonId||m.learning.moveSlug!==m.normalized||m.learning.sourceChecksum!==source.pokemonChecksum||!m.learning.entries?.length))throw Error('缺少同一快照的学习记录')
 }
 if(raw.status==='REFERENCE_MATCHED'&&(!source||raw.syntaxStatus==='INVALID_INPUT'||raw.missingFields.length||raw.moves.length!==4||raw.abilityStatus!=='MATCHED'||raw.moves.some(m=>m.status!=='RECORDED')))throw Error('参考匹配结论缺少必要字段')
 return JSON.parse(JSON.stringify(raw))
}
