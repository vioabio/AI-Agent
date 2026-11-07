import {sameJson} from './evaluation.mjs'
import {collectToolInputs,checkToolInput,expectedInputFields} from './tool-inputs.mjs'
export function auditBuildCheck(record,events,expected){
 const fields=Object.fromEntries(Object.entries(expectedInputFields({request:expected.request})).filter(([,v])=>v!==null))
 const inputCheck=checkToolInput(collectToolInputs(events),{tool:'checkPokemonBuild',fields}),ids=new Set(inputCheck.checks.filter(c=>c.pass).map(c=>c.callId)),grouped=new Map()
 for(const event of events.filter(e=>e.type==='tool.finished'&&e.payload?.toolName==='checkPokemonBuild'&&ids.has(e.payload.governanceEventId))){const p=event.payload;if(!grouped.has(p.governanceEventId))grouped.set(p.governanceEventId,[]);grouped.get(p.governanceEventId).push(p)}
 const outputs=[...grouped.values()].map(rows=>{const p=rows[0],proof=p.outputEvidence;return {callId:p.governanceEventId,notifications:rows.length,pass:rows.every(r=>sameJson(r.outputEvidence,proof))&&proof?.schemaVersion==='build-check-output-evidence-v1'&&proof.status==='VERIFIED'&&proof.parameterDigest===p.parameterDigest&&proof.toolVersion===p.toolVersion&&proof.pokemonId===expected.request.pokemonId&&proof.checkStatus===expected.status&&proof.reportSha256===expected.reportSha256&&proof.scope===expected.scope}})
 const body=(record.answer||'').split('### 配招逐项核对')[1]?.split('### ')[0]||'',textByStatus={REFERENCE_MATCHED:'组合仍待审核',INCOMPLETE:'配置仍有缺项',REFERENCE_MISMATCH:'部分已填配置与本次参考不一致'}
 const checks={completed:record.status==='COMPLETED',boundInput:inputCheck.pass,boundOutput:outputs.length>0&&outputs.every(x=>x.pass),statusDisplayed:body.includes(textByStatus[expected.status]||'部分参考读取失败'),
  rowsDisplayed:expected.moves.every(m=>body.includes(m.supplied)&&body.includes(m.status==='RECORDED'?'有朱紫学习记录':m.status==='NOT_RECORDED'?'该快照未记录':'未取得同一快照')),
  limitsDisplayed:body.includes('四招共同可得')&&body.includes('没有修改或保存队伍'),noInternalIds:!/(?:sha256:|reportSha256|referenceChecksum|dataReleaseId)/.test(body),noApproval:!events.some(e=>e.type==='approval.required')}
 return {pass:Object.values(checks).every(x=>x===true),checks,inputCheck,outputs,scope:'Supplied configuration, same reference report and required displayed gaps; not joint learnability, strategy or formal legality'}
}
