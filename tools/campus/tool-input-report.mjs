import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { resolve, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { digest, frozenFileDigest, sameJson } from './evaluation.mjs'
import { attemptsFrom } from './answer-quality.mjs'
import { normalizeModelUsage } from '../../vio-ai-agent-frontend/src/api/model-usage.js'
import { collectToolInputs, checkToolInput, expectedInputFields } from './tool-inputs.mjs'
export function auditInputCapture(capture,readBound) {
 const plan=capture.plan
 if(capture.schemaVersion!=='tool-input-capture-v1'||!plan?.cases?.length||!Number.isInteger(plan.repetitions)||plan.repetitions<1||plan.repetitions>3
   ||capture.records?.length!==plan.cases.length*plan.repetitions||new Set(plan.cases.map(c=>c.id)).size!==plan.cases.length)throw Error('Missing fixed denominator')
 const runIds=capture.records.filter(r=>r.runId).map(r=>r.runId)
 if(new Set(runIds).size!==runIds.length)throw Error('Duplicate run observation')
 const rows=[]
 for(const item of plan.cases)for(let repetition=1;repetition<=plan.repetitions;repetition++) {
  const matching=capture.records.filter(r=>r.caseId===item.id&&r.repetition===repetition);if(matching.length!==1)throw Error('Missing or duplicate observation')
  const r=matching[0],failures=[];let inputs={pass:false,checks:[]},modelCalls=null
  try {
   const events=readBound(r,'events'),history=readBound(r,'history'),answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join('')
   if(typeof r.runId!=='string'||!r.runId||events.some(e=>e.runId!==r.runId))failures.push('RUN_BINDING_MISMATCH')
   if(r.status!=='COMPLETED'||r.error||r.cleanupFailed||!events.some(e=>e.type==='run.completed'))failures.push('RUN_NOT_COMPLETED')
   if(!answer.trim()||answer!==r.answer||history.answer!==answer||history.prompt!==item.prompt)failures.push('ANSWER_HISTORY_MISMATCH')
   const attempts=attemptsFrom(events);modelCalls=attempts.reduce((n,a)=>n+a.modelCallCount,0)
   if(!attempts.length||modelCalls<1||!sameJson(attempts.map(normalizeModelUsage),(history.attempts||[]).map(normalizeModelUsage)))failures.push('USAGE_HISTORY_MISMATCH')
   const calls=collectToolInputs(events)
   if(calls.some(c=>!['checkTypeEffectiveness','getTypeChart','calculateBattleDamage','lookupPokemonReference','lookupPokemonResource'].includes(c.tool)))failures.push('UNEXPECTED_TOOL')
   inputs=checkToolInput(calls,{tool:item.tool,fields:expectedInputFields(item.expectedArguments),exactArguments:item.expectedArguments})
   if(!inputs.pass)failures.push('EXPECTED_INPUT_NOT_VERIFIED')
  }catch{failures.push('EVIDENCE_UNAVAILABLE_OR_INVALID')}
  rows.push({caseId:item.id,repetition,pass:failures.length===0,failures,modelCalls,inputs})
 }
 return {schemaVersion:'tool-input-report-v1',denominator:rows.length,pass:rows.filter(r=>r.pass).length,failed:rows.filter(r=>!r.pass).length,
  humanAcceptance:'NOT_PERFORMED',scope:'Exact supplied input equality on a fixed development plan; not resolved defaults, output verification or general answer accuracy.',rows}
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
 const[inputArg,outArg,...extra]=process.argv.slice(2);if(!inputArg||!outArg||extra.length)throw Error('Usage: node tools/campus/tool-input-report.mjs CAPTURE_JSON NEW_OUTPUT')
 const input=resolve(inputArg),out=resolve(outArg),bytes=readFileSync(input),capture=JSON.parse(bytes),planFile=new URL('../../evaluation/campus/tool-inputs/manifest.json',import.meta.url)
 if(existsSync(out))throw Error('Never overwrite evidence')
 if(capture.planSha256!==frozenFileDigest(planFile)||!sameJson(capture.plan,JSON.parse(readFileSync(planFile))))throw Error('Frozen plan changed')
 const report=auditInputCapture(capture,(r,key)=>{const file=r[key+'File'];if(!file||basename(file)!==file)throw Error('Unsafe evidence path');const raw=readFileSync(resolve(dirname(input),file));if(digest(raw)!==r[key+'Sha256'])throw Error('Evidence changed');return JSON.parse(raw)})
 report.captureSha256=digest(bytes);report.analysisSha256=digest(readFileSync(fileURLToPath(import.meta.url)));report.runtime=capture.runtime
 mkdirSync(out,{recursive:true});writeFileSync(resolve(out,'input-report.json'),JSON.stringify(report,null,2)+'\n')
 writeFileSync(resolve(out,'input-report.md'),['# 工具实际输入开发回归','',`计划${report.denominator}次；输入/完成/历史检查通过${report.pass}次，失败${report.failed}次。人工回答语义验收未进行。`,'',
 '| 案例 | 轮次 | 结果 | 模型调用 | 输入核对 |','| --- | ---: | --- | ---: | --- |',...report.rows.map(r=>`| ${r.caseId} | ${r.repetition} | ${r.pass?'通过':'失败'} | ${r.modelCalls??'未知'} | ${r.inputs.pass?'预期传入字段及完整参数摘要相等':r.failures.join(', ')} |`),'',
 '只核对传给工具的条件，不证明工具内部默认值、输出数值、机制解释或任意问题的正确性；名称摘要不是加密。',''].join('\n'))
 console.log(JSON.stringify({denominator:report.denominator,pass:report.pass,failed:report.failed,humanAcceptance:report.humanAcceptance}));if(report.failed)process.exitCode=1
}
