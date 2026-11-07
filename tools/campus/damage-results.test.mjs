import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { digest } from './evaluation.mjs'
import { expectedInputFields } from './tool-inputs.mjs'
import { resolvedArguments, conditionOrigins, checkDamageResult, auditDamageCapture } from './damage-results.mjs'
function fixture() {
 const source=JSON.parse(readFileSync(new URL('../../evaluation/campus/s01/damage-reference.json',import.meta.url))),control=source.result
 control.conditionValidation={schemaVersion:'damage-condition-validation-v1',status:'VERIFIED',typedRequestDigest:'b'.repeat(64)}
 const expected={request:source.input},fields=expectedInputFields(resolvedArguments(control)),literals=new Set(['/request/dataReleaseId','/request/field/gameType','/request/field/weather','/request/field/terrain'])
 const resolvedFields=Object.fromEntries(Object.entries(fields).map(([path,value])=>[path,typeof value==='string'&&!literals.has(path)?{kind:'SHA256_JSON',sha256:digest(JSON.stringify(value))}:{kind:'LITERAL',value}]))
 const call={bound:true,status:'SUCCEEDED',evidence:{typedRequestDigest:'b'.repeat(64)},terminal:{toolVersion:'1',parameterDigest:'a'.repeat(64),cached:false}}
 const proof={schemaVersion:'damage-result-evidence-v1',status:'VERIFIED',scope:'SUPPORTED_CONDITIONS_AND_RANGE_ONLY',outputDigest:'c'.repeat(64),typedRequestDigest:'b'.repeat(64),toolVersion:'1',parameterDigest:'a'.repeat(64),
 resolvedFields,range:control.range,percent:control.percent,defenderHp:control.defenderHp,...conditionOrigins(expected,Object.keys(fields))}
 return {control,expected,call,proof}
}
test('verified supplied calls still require matching resolved levels, fields and output range',()=>{
 const f=fixture();assert.equal(checkDamageResult(f.call,f.proof,f.control,f.expected).pass,true)
 f.control.conditionValidation.typedRequestDigest='e'.repeat(64) // Equivalent aliases may have a different control DTO hash.
 assert.equal(checkDamageResult(f.call,f.proof,f.control,f.expected).pass,true)
 const altered=structuredClone(f.proof);altered.resolvedFields['/request/attacker/build/level'].value=100
 assert.equal(checkDamageResult(f.call,altered,f.control,f.expected).pass,false)
 const range=structuredClone(f.proof);range.range.maximum+=1;assert.equal(checkDamageResult(f.call,range,f.control,f.expected).pass,false)
 const stale=structuredClone(f.proof);stale.parameterDigest='d'.repeat(64);assert.equal(checkDamageResult(f.call,stale,f.control,f.expected).pass,false)
})
test('missing containers, explicit nulls and engine default abilities remain distinct',()=>{
 const paths=['/request/attacker/build/ability','/request/field/weather']
 assert.deepEqual(conditionOrigins({request:{attacker:{build:null},field:{weather:null}}},paths),{omittedFields:[paths[0]],explicitNullFields:[paths[1]],engineDefaultAbilityFields:[paths[0]]})
 const f=fixture();f.proof.explicitNullFields=[];assert.equal(checkDamageResult(f.call,f.proof,f.control,f.expected).pass,false)
})
test('missing or unbound output evidence cannot use a good control to pass',()=>{
 const f=fixture();assert.equal(checkDamageResult(f.call,null,f.control,f.expected).pass,false)
 assert.equal(checkDamageResult({...f.call,bound:false},f.proof,f.control,f.expected).pass,false)
 f.proof.typedRequestDigest='d'.repeat(64);assert.equal(checkDamageResult(f.call,f.proof,f.control,f.expected).pass,false)
 const missingPercent=fixture();delete missingPercent.control.percent;assert.equal(checkDamageResult(missingPercent.call,missingPercent.proof,missingPercent.control,missingPercent.expected).pass,false)
})
test('missing result evidence remains in the fixed denominator',()=>{
 const capture={schemaVersion:'damage-result-capture-v1',plan:{repetitions:1,cases:[{id:'one',prompt:'计算',tool:'calculateBattleDamage',expectedArguments:{request:{generation:9}}}]},records:[{caseId:'one',repetition:1,status:'NOT_RUN'}]}
 const report=auditDamageCapture(capture,()=>{throw Error('missing')});assert.equal(report.denominator,1);assert.equal(report.failed,1);assert.equal(report.humanAcceptance,'NOT_PERFORMED')
})
