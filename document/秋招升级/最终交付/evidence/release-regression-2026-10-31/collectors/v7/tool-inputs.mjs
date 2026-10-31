// Read-only audit of application-owned events. Field digests are equality evidence, not encryption.
import { digest, canonical, sameJson } from './evaluation.mjs'
const sha=/^[a-f0-9]{64}$/
export function expectedInputFields(value,path='') {
 if(Array.isArray(value))return Object.assign({[path+'/length']:value.length},...value.map((v,i)=>expectedInputFields(v,path+'/'+i)))
 if(value&&typeof value==='object')return Object.assign({},...Object.entries(value).map(([key,v])=>expectedInputFields(v,path+'/'+key)))
 return {[path]:value}
}
function validEvidence(e,tool) {
 const preview=tool==='previewTeamChanges'
 if(preview&&(e?.projectionVersion!=='bounded-team-preview-input-v1'||e.fieldLimit!==144))return false
 if(e?.schemaVersion!=='tool-input-evidence-v1' || e.scope!=='SUPPLIED_ARGUMENTS_ONLY'
   || !['COMPLETE','PARTIAL'].includes(e.coverage) || typeof e.toolVersion!=='string'
   || !sha.test(e.parameterDigest) || !e.fields || Array.isArray(e.fields) || typeof e.fields!=='object'
   || Object.keys(e.fields).length>(preview?144:96))return false
 return Object.entries(e.fields).every(([path,p])=>/^\/(?:[A-Za-z][A-Za-z0-9]*|[0-5])(?:\/(?:[A-Za-z][A-Za-z0-9]*|[0-5])){0,7}$/.test(path)
   && p && (p.kind==='SHA256_JSON'?sha.test(p.sha256)&&Object.keys(p).length===2:
     p.kind==='LITERAL'&&Object.hasOwn(p,'value')&&(p.value===null||typeof p.value==='string'||Number.isSafeInteger(p.value))&&Object.keys(p).length===2))
}
export function collectToolInputs(events) {
 const calls=new Map();let ended=false
 for(const event of events) {
  if(['run.completed','run.failed','run.cancelled','run.timed_out'].includes(event.type)){ended=true;continue}
  if(!['tool.started','tool.finished'].includes(event.type))continue
  const p=event.payload||{},id=p.governanceEventId;if(!id||!p.toolName)continue
  if(!calls.has(id))calls.set(id,{callId:id,tool:p.toolName,status:'STARTED',evidence:null,failures:[]})
  const call=calls.get(id)
  if(ended)call.failures.push('AFTER_TERMINAL')
  if(call.tool!==p.toolName)call.failures.push('CALL_ID_REUSED')
  if(p.inputEvidence) {
   if(call.evidence&&!sameJson(call.evidence,p.inputEvidence))call.failures.push('INPUT_CHANGED')
   call.evidence=p.inputEvidence
  }
  if(event.type==='tool.finished') {
   const terminal={status:p.status||'UNKNOWN',toolVersion:p.toolVersion,parameterDigest:p.parameterDigest,cached:p.cached===true}
   if(call.terminal&&!sameJson(call.terminal,terminal))call.failures.push('TERMINAL_CHANGED')
   call.terminal=terminal;call.status=terminal.status
  }
 }
 return [...calls.values()].map(call=>{
  const e=call.evidence,t=call.terminal
  if(!e)call.failures.push('MISSING_INPUT_EVIDENCE')
  else if(!validEvidence(e,call.tool))call.failures.push('INVALID_INPUT_EVIDENCE')
  else {
   if(e.coverage!=='COMPLETE')call.failures.push('PARTIAL_INPUT_EVIDENCE')
   if(!t||t.toolVersion!==e.toolVersion||t.parameterDigest!==e.parameterDigest)call.failures.push('UNBOUND_TERMINAL')
  }
  return {...call,bound:call.failures.length===0,failures:[...new Set(call.failures)]}
 })
}
export function checkToolInput(calls,expected) {
 if(!expected?.tool||!expected.fields||typeof expected.fields!=='object'||!Object.keys(expected.fields).length)throw Error('Explicit expected input fields required')
 const selected=calls.filter(c=>c.tool===expected.tool),successful=selected.filter(c=>['SUCCEEDED','EXECUTED','DEDUPLICATED'].includes(c.status)),checks=[]
 for(const call of successful) {
  const failures=[...call.failures]
  failures.push(...fieldMismatches(call.evidence?.fields,expected.fields))
  if(expected.exactArguments&&call.evidence?.parameterDigest!==digest(JSON.stringify(canonical(expected.exactArguments))))failures.push('EXACT_ARGUMENTS_MISMATCH')
  checks.push({callId:call.callId,pass:call.bound&&failures.length===0,cached:call.terminal?.cached===true,failures:[...new Set(failures)]})
 }
 return {tool:expected.tool,pass:successful.length>0&&checks.every(c=>c.pass),observed:selected.length,successful:successful.length,checks,
  failure:successful.length?'':'NO_SUCCESSFUL_CALL',scope:'Supplied arguments only; no claim about resolved defaults, tool outputs or general answer correctness'}
}
export function fieldMismatches(fields,expected) {
 const failures=[]
 for(const[path,value]of Object.entries(expected)) {
  const proof=fields?.[path]
  if(!proof)failures.push(`MISSING_FIELD:${path}`)
  else if(proof.kind==='LITERAL'?!sameJson(proof.value,value):proof.kind!=='SHA256_JSON'||typeof value!=='string'||proof.sha256!==digest(JSON.stringify(value)))failures.push(`FIELD_MISMATCH:${path}`)
 }
 return failures
}
