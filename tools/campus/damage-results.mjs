// Finite result/condition comparison. Same-engine HTTP controls are not independent formula oracles.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { resolve, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { digest, frozenFileDigest, sameJson } from './evaluation.mjs'
import { collectToolInputs, expectedInputFields, fieldMismatches } from './tool-inputs.mjs'
import { auditInputCapture } from './tool-input-report.mjs'
export function resolvedArguments(result) {
 const r={generation:result.generation,dataReleaseId:result.dataReleaseId,move:result.assumptions.move,field:result.assumptions.field}
 for(const side of ['attacker','defender']){const a=result.assumptions[side];r[side]={species:a.species,build:Object.fromEntries(['level','nature','ability','item','evs','ivs'].map(key=>[key,a[key]]))}}
 return {request:r}
}
function at(value,path) {
 for(const key of path.slice(1).split('/')){if(!value||typeof value!=='object'||!Object.hasOwn(value,key))return {present:false};value=value[key]}
 return {present:true,value}
}
export function conditionOrigins(argumentsValue,paths) {
 const omittedFields=[],explicitNullFields=[],engineDefaultAbilityFields=[]
 for(const path of paths){const raw=at(argumentsValue,path);if(!raw.present)omittedFields.push(path);else if(raw.value===null)explicitNullFields.push(path)
  if(path.endsWith('/ability')&&(!raw.present||raw.value===null||typeof raw.value==='string'&&!raw.value.trim()))engineDefaultAbilityFields.push(path)}
 return {omittedFields,explicitNullFields,engineDefaultAbilityFields}
}
export function checkDamageResult(call,proof,control,expectedArguments) {
 const failures=[]
 if(!call?.bound||!['SUCCEEDED','EXECUTED','DEDUPLICATED'].includes(call.status))failures.push('INPUT_NOT_BOUND_OR_SUCCESSFUL')
 if(proof?.schemaVersion!=='damage-result-evidence-v1'||proof.status!=='VERIFIED'||proof.scope!=='SUPPORTED_CONDITIONS_AND_RANGE_ONLY'
   ||!/^[a-f0-9]{64}$/.test(proof.outputDigest||'')||proof.parameterDigest!==call?.terminal?.parameterDigest||proof.toolVersion!==call?.terminal?.toolVersion)failures.push('OUTPUT_NOT_BOUND')
 if(control?.engine!=='@smogon/calc'||control.engineVersion!=='0.12.0'||control.generation!==9||control.dataReleaseId!=='pokeapi-reference-v1'
   ||control?.conditionValidation?.schemaVersion!=='damage-condition-validation-v1'||control.conditionValidation.status!=='VERIFIED'||!/^[a-f0-9]{64}$/.test(control.conditionValidation.typedRequestDigest||'')
   ||!/^[a-f0-9]{64}$/.test(proof?.typedRequestDigest||'')||proof?.typedRequestDigest!==call?.evidence?.typedRequestDigest)failures.push('CONTROL_OR_TYPED_REQUEST_MISMATCH')
 if(!control?.assumptions||!proof?.resolvedFields)return {pass:false,failures:[...failures,'MISSING_RESOLVED_CONDITIONS']}
 const fields=expectedInputFields(resolvedArguments(control)),paths=Object.keys(fields)
 failures.push(...fieldMismatches(proof.resolvedFields,fields))
 if(!sameJson(Object.keys(proof.resolvedFields).sort(),paths.sort()))failures.push('RESOLVED_FIELD_SHAPE_MISMATCH')
 const origins=conditionOrigins(expectedArguments,paths)
 for(const[key,values]of Object.entries(origins))if(!Array.isArray(proof[key])||!sameJson([...proof[key]].sort(),values.sort()))failures.push(`ORIGIN_MISMATCH:${key}`)
 if(!sameJson(proof.range,control.range)||proof.defenderHp!==control.defenderHp)failures.push('RANGE_OR_HP_MISMATCH')
 for(const side of ['minimum','maximum'])if(!Number.isFinite(proof.percent?.[side])||!Number.isFinite(control.percent?.[side])||Math.abs(proof.percent[side]-control.percent[side])>.000001)failures.push('PERCENT_MISMATCH')
 return {pass:failures.length===0,failures:[...new Set(failures)],outputDigest:proof.outputDigest,range:proof.range,defenderHp:proof.defenderHp,
  omittedFields:proof.omittedFields,explicitNullFields:proof.explicitNullFields,engineDefaultAbilityFields:proof.engineDefaultAbilityFields,cached:call?.terminal?.cached===true}
}
export function auditDamageCapture(capture,readBound) {
 if(capture.schemaVersion!=='damage-result-capture-v1')throw Error('Unsupported result capture')
 const base=auditInputCapture({...capture,schemaVersion:'tool-input-capture-v1'},readBound)
 const rows=base.rows.map(row=>{
  const record=capture.records.find(r=>r.caseId===row.caseId&&r.repetition===row.repetition),item=capture.plan.cases.find(c=>c.id===row.caseId),failures=[...row.failures];let results=[]
  try {
   const events=readBound(record,'events'),calls=collectToolInputs(events).filter(c=>c.tool==='calculateBattleDamage'&&['SUCCEEDED','EXECUTED','DEDUPLICATED'].includes(c.status))
   results=calls.map(call=>{
    const terminal=events.filter(e=>e.type==='tool.finished'&&e.payload?.governanceEventId===call.callId).map(e=>e.payload.outputEvidence)
    const result=checkDamageResult(call,terminal.at(-1),capture.engineControls?.[item.id],item.expectedArguments)
    if(terminal.some(p=>!sameJson(p,terminal[0])))return {...result,pass:false,failures:[...result.failures,'OUTPUT_EVIDENCE_CHANGED']}
    return result
   })
   if(!results.length||results.some(r=>!r.pass))failures.push('RESOLVED_RESULT_NOT_VERIFIED')
  }catch{failures.push('RESULT_EVIDENCE_UNAVAILABLE_OR_INVALID')}
  const resolvedVerified=results.length>0&&results.every(r=>r.pass)&&row.failures.every(f=>f==='EXPECTED_INPUT_NOT_VERIFIED')
  return {...row,pass:failures.length===0,resolvedVerified,failures:[...new Set(failures)],results}
 })
 return {schemaVersion:'damage-result-report-v1',denominator:rows.length,pass:rows.filter(r=>r.pass).length,failed:rows.filter(r=>!r.pass).length,resolvedPass:rows.filter(r=>r.resolvedVerified).length,
  humanAcceptance:'NOT_PERFORMED',scope:'Supplied inputs, resolved supported conditions and same-engine control equality; not an independent damage formula or general answer accuracy benchmark.',rows}
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
 const[inputArg,outArg,...extra]=process.argv.slice(2);if(!inputArg||!outArg||extra.length)throw Error('Usage: node tools/campus/damage-results.mjs CAPTURE_JSON NEW_OUTPUT')
 const input=resolve(inputArg),out=resolve(outArg),bytes=readFileSync(input),capture=JSON.parse(bytes),planFile=new URL('../../evaluation/campus/damage-results/manifest.json',import.meta.url)
 if(existsSync(out))throw Error('Never overwrite evidence')
 if(capture.planSha256!==frozenFileDigest(planFile)||!sameJson(capture.plan,JSON.parse(readFileSync(planFile))))throw Error('Frozen plan changed')
 const report=auditDamageCapture(capture,(r,key)=>{const file=r[key+'File'];if(!file||basename(file)!==file)throw Error('Unsafe path');const raw=readFileSync(resolve(dirname(input),file));if(digest(raw)!==r[key+'Sha256'])throw Error('Evidence changed');return JSON.parse(raw)})
 report.captureSha256=digest(bytes);report.analysisSha256=digest(readFileSync(fileURLToPath(import.meta.url)));report.runtime=capture.runtime
 mkdirSync(out,{recursive:true});writeFileSync(resolve(out,'result-report.json'),JSON.stringify(report,null,2)+'\n')
 writeFileSync(resolve(out,'result-report.md'),['# 伤害实际条件与结果回归','',`计划${report.denominator}次；包含原样传入的严格检查通过${report.pass}次，失败${report.failed}次；实际条件/结果及记录核对通过${report.resolvedPass}次；人工语义验收未进行。`,'',
 '| 案例 | 轮次 | 结果 | 原因 |','| --- | ---: | --- | --- |',...report.rows.map(r=>`| ${r.caseId} | ${r.repetition} | ${r.pass?'通过':'失败'} | ${r.failures.join(', ')||'有限检查通过'} |`),'',
 'HTTP控制和Agent调用使用同一引擎；不能将相等当作独立算法正确性证明。缺失、显式null和默认特性分别统计，固定分母不筛除失败。',''].join('\n'))
 console.log(JSON.stringify({denominator:report.denominator,pass:report.pass,resolvedPass:report.resolvedPass,failed:report.failed,humanAcceptance:report.humanAcceptance}));if(report.failed)process.exitCode=1
}
