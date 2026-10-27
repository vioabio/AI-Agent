import {observationHash} from './evaluation.mjs'
import {collectToolInputs,fieldMismatches} from './tool-inputs.mjs'
export function eventFileName(value) {if(typeof value!=='string'||!/^S2-[A-Z]+-\d{2}-[1-9][0-9]*-events\.json$/.test(value))throw Error('Unsafe event filename');return value}
export function auditMechanicObservation(record,events,resources,{requireRead=false,requiredKinds=[],allowedByKind=null,memberName=null}={}) {
 if(record.observationSha256!==observationHash(record)||events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join('')!==record.answer)throw Error('Changed observation or answer')
 const calls=collectToolInputs(events).filter(c=>c.tool==='lookupPokemonResource')
 const matches=calls.filter(c=>c.bound&&['SUCCEEDED','EXECUTED','DEDUPLICATED'].includes(c.status)).map(call=>{
  const source=resources.find(r=>!fieldMismatches(call.evidence?.fields,{'/kind':r.kind,'/slug':r.slug,'/dataReleaseId':r.dataReleaseId}).length)
  if(!source)return {callId:call.callId,knownProjection:false,displayMatches:false}
  const marker=`**${source.nameZh}（${source.kind==='ability'?'特性':source.kind==='move'?'招式':'性格'}）**`,start=record.answer.indexOf(marker)
  const ends=start<0?[]:['\n**','\n### '].map(m=>record.answer.indexOf(m,start+marker.length)).filter(i=>i>=0),next=ends.length?Math.min(...ends):undefined,block=start<0?'':record.answer.slice(start,next)
  const expectation=source.explanationZh||(source.kind==='nature'?source.neutralNature?'无提升或降低字段':`提升${stat(source.increasedStat)}，降低${stat(source.decreasedStat)}`:source.descriptionExcerpt)
  const association=/资料候选：([^\n；]+)/.exec(block)?.[1].split('、')||[]
  return {callId:call.callId,sourceId:`resource-${source.kind}-${source.slug}`,kind:source.kind,slug:source.slug,projectionSha256:source.projectionSha256,
   knownProjection:true,candidateMatches:!allowedByKind||allowedByKind[source.kind]?.includes(source.slug)===true&&(!memberName||association.includes(memberName)),displayMatches:start>=0&&!!expectation&&block.includes(expectation),scope:'Bound supplied identity plus known HTTP projection and finite display check; not proof of all mechanism semantics'}
 })
 const coveredKinds=[...new Set(matches.filter(x=>x.displayMatches&&x.candidateMatches!==false).map(x=>x.kind))],missingKinds=requiredKinds.filter(k=>!coveredKinds.includes(k))
 return {caseId:record.caseId,repetition:record.repetition,observationSha256:record.observationSha256,resourceCalls:calls.length,successfulKnown:matches.filter(x=>x.knownProjection).length,
  displayed:matches.filter(x=>x.displayMatches).length,unmatched:matches.filter(x=>!x.displayMatches).length,required:requireRead,requiredKinds,coveredKinds,missingKinds,
  requiredReadPresent:(!requireRead||matches.some(x=>x.displayMatches&&x.candidateMatches!==false))&&missingKinds.length===0,matches}
}
function stat(s){return {'attack':'攻击','defense':'防御','special-attack':'特攻','special-defense':'特防','speed':'速度'}[s]}
