import {buildCheckIdentity,normalizeBuildCheck,buildCheckLabels} from './build-check.js'
export const teamBuildCheckScope='SUPPLIED_TEAM_MEMBER_CHECKS_NOT_JOINT_OR_TEAM_LEGALITY'
export const teamBuildCheckIdentity=buildCheckIdentity
export function teamBuildQuestion(request){return `请检查整队配置，保留原成员和未填写字段；只核对，不保存、不生成修改预览。逐项匹配不代表联合可得或赛制合法。\n${JSON.stringify(request)}`}
export function duplicateFormSlots(request){const groups=new Map();for(const m of request.members){if(!groups.has(m.pokemonId))groups.set(m.pokemonId,[]);groups.get(m.pokemonId).push(m.slot)}return [...groups.values()].filter(s=>s.length>1)}
export function normalizeTeamBuildCheck(raw,request){
 const fail=()=>{throw Error('整队报告与当前成员配置或逐项结果不一致，请重新检查')}
 if(!raw||Object.keys(raw).length!==8||raw.schemaVersion!=='pokemon-team-build-check-v1'||raw.scope!==teamBuildCheckScope||raw.jointStatus!=='NOT_VERIFIED'||!/^sha256:[a-f0-9]{64}$/.test(raw.reportSha256||'')||teamBuildCheckIdentity(raw.request)!==teamBuildCheckIdentity(request)||!Array.isArray(request.members)||request.members.length<1||request.members.length>6||new Set(request.members.map(m=>m.slot)).size!==request.members.length||request.members.some(m=>!Number.isInteger(m.slot)||m.slot<1||m.slot>6)||!Array.isArray(raw.members)||raw.members.length!==request.members.length)fail()
 const counts=Object.fromEntries(Object.keys(buildCheckLabels).map(k=>[k,0]))
 raw.members.forEach((member,i)=>{const expected=request.members[i];if(Object.keys(member||{}).length!==2||member.slot!==expected.slot)fail();const report=normalizeBuildCheck(member.report,{pokemonId:expected.pokemonId,build:expected.build,dataReleaseId:request.dataReleaseId,gameReleaseId:request.gameReleaseId});counts[report.status]++})
 if(teamBuildCheckIdentity(counts)!==teamBuildCheckIdentity(raw.counts)||teamBuildCheckIdentity(duplicateFormSlots(request))!==teamBuildCheckIdentity(raw.duplicateFormSlots))fail()
 return JSON.parse(JSON.stringify(raw))
}
