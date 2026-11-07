import {digest,sameJson} from './evaluation.mjs'
import {collectToolInputs,checkToolInput} from './tool-inputs.mjs'
const fields=['schemaVersion','pokemonId','pokemonSlug','moveSlug','dataReleaseId','gameReleaseId','versionGroup','status','entries','sourceUrl','sourceChecksum','retrievedAt','projectionChecksum']
export function projectionChecksum(value) {
 const body=Object.fromEntries(fields.filter(k=>k!=='projectionChecksum').sort().map(k=>[k,value[k]]))
 body.entries=value.entries.map(r=>({methodKey:r.methodKey,levelLearnedAt:r.levelLearnedAt,order:r.order}))
 return 'sha256:'+digest(JSON.stringify(body))
}
export function checkLearnsetProjection(actual,item,source) {
 const selected=(source.body.moves||[]).filter(m=>m.move?.name===item.moveSlug)
 if(selected.length>1)throw Error('Ambiguous source move')
 const rows=(selected[0]?.version_group_details||[]).filter(v=>v.version_group?.name==='scarlet-violet').map(v=>({methodKey:v.move_learn_method.name,levelLearnedAt:v.level_learned_at,order:v.order??null}))
 const unique=[...new Map(rows.map(r=>[JSON.stringify(r),r])).values()].sort((a,b)=>a.methodKey.localeCompare(b.methodKey)||a.levelLearnedAt-b.levelLearnedAt||(a.order??-1)-(b.order??-1))
 const checks={exactFields:sameJson(Object.keys(actual).sort(),[...fields].sort()),schema:actual.schemaVersion==='pokemon-learnset-facts-v1',
  target:actual.pokemonId===item.pokemonId&&actual.pokemonSlug===source.body.name&&source.body.id===item.pokemonId&&actual.moveSlug===item.moveSlug,
  version:actual.versionGroup==='scarlet-violet'&&actual.gameReleaseId==='pokemon-scarlet-violet'&&actual.dataReleaseId==='pokeapi-reference-v1',
  declaredExpectedMatchesSource:sameJson(unique,item.expectedEntries),actualEntries:sameJson(actual.entries,item.expectedEntries),
  missingIsExplicit:actual.status===(unique.length?'RECORDED':'NOT_RECORDED_IN_SNAPSHOT'),
  source:actual.sourceUrl===source.envelope.url&&actual.sourceChecksum===source.envelope.sha256&&Date.parse(actual.retrievedAt)===Date.parse(source.envelope.retrievedAt),
  checksum:actual.projectionChecksum===projectionChecksum(actual)}
 return {pass:Object.values(checks).every(v=>v===true),checks}
}
export function auditLearnsetRecord(record,events,item,expected) {
 const input={pokemonId:item.pokemonId,moveSlug:item.moveSlug,dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet'}
 const inputCheck=checkToolInput(collectToolInputs(events),{tool:'lookupPokemonLearnset',fields:Object.fromEntries(Object.entries(input).map(([k,v])=>['/'+k,v])),exactArguments:input})
 const matching=new Set(inputCheck.checks.filter(c=>c.pass).map(c=>c.callId))
 const results=events.filter(e=>e.type==='tool.finished'&&e.payload?.toolName==='lookupPokemonLearnset'&&matching.has(e.payload.governanceEventId)).map(e=>{
  const p=e.payload,proof=p.outputEvidence
  return {callId:p.governanceEventId,pass:proof?.schemaVersion==='pokemon-learnset-output-evidence-v1'&&proof.status==='VERIFIED'
   &&proof.parameterDigest===p.parameterDigest&&proof.toolVersion===p.toolVersion&&proof.pokemonId===item.pokemonId&&proof.moveSlug===item.moveSlug
   &&proof.versionGroup==='scarlet-violet'&&proof.recordStatus===expected.status&&proof.entryCount===expected.entries.length
   &&proof.sourceChecksum===expected.sourceChecksum&&proof.projectionChecksum===expected.projectionChecksum}
 })
 const answer=record.answer||'',body=answer.split('### 招式学习途径（本次读取）')[1]?.split('### ')[0]||''
 const methods={machine:'招式学习器（machine）','level-up':'等级提升（level-up）',egg:'蛋招式（egg）',tutor:'教学（tutor）'}
 const checks={completed:record.status==='COMPLETED',boundInput:inputCheck.pass,boundOutput:results.length>0&&results.every(r=>r.pass),
  displayedPair:body.includes('**'+item.nameZh+' · '+item.moveSlug+'**')||body.includes('**'+expected.pokemonSlug+' · '+item.moveSlug+'**'),
  displayedRows:expected.entries.length?expected.entries.every(r=>body.includes(methods[r.methodKey]||'上游方式：'+r.methodKey)
   &&(r.methodKey!=='level-up'||body.includes(r.levelLearnedAt===0?'等级字段为0，未提供具体学习操作':'记录等级：'+r.levelLearnedAt))):body.includes('不能据此断言无法学习'),
  displayedSource:body.includes(']('+expected.sourceUrl+')'),noInternals:!/(?:sha256:|projectionChecksum|sourceChecksum|\bTM\d{1,4}\b)/i.test(answer)}
 return {pass:Object.values(checks).every(v=>v===true),checks,inputCheck,outputChecks:results,
  scope:'Actual argument/output identity and necessary displayed rows only; not complete operations, independent mechanics or human quality acceptance'}
}
