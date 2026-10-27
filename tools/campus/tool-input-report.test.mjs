import test from 'node:test'
import assert from 'node:assert/strict'
import { auditInputCapture } from './tool-input-report.mjs'
import { expectedInputFields } from './tool-inputs.mjs'
import { digest, canonical } from './evaluation.mjs'
function fixture() {
 const args={attackType:'火',defendType:'草'},parameterDigest=digest(JSON.stringify(canonical(args)))
 const evidence={schemaVersion:'tool-input-evidence-v1',coverage:'COMPLETE',scope:'SUPPLIED_ARGUMENTS_ONLY',toolVersion:'1',parameterDigest,
 fields:Object.fromEntries(Object.entries(args).map(([k,v])=>['/'+k,{kind:'LITERAL',value:v}]))}
 const usage={schemaVersion:'model-usage-v1',attemptId:'a1111111-1111-4111-a111-111111111111',scope:'EXECUTION_ATTEMPT',coverage:'INSTRUMENTED',modelCallCount:1,callsWithUsage:1,
 usageStatus:'COMPLETE',recordsTruncated:false,totals:{inputTokens:1,outputTokens:1,totalTokens:2},modelDurationMs:1,
 calls:[{callNo:1,mode:'CALL',outcome:'RETURNED',durationMs:1,usageStatus:'PROVIDER_REPORTED',tokens:{inputTokens:1,outputTokens:1,totalTokens:2}}]}
 const events=[{type:'tool.started',payload:{toolName:'checkTypeEffectiveness',governanceEventId:'call',inputEvidence:evidence}},
 {type:'tool.finished',payload:{toolName:'checkTypeEffectiveness',governanceEventId:'call',status:'SUCCEEDED',toolVersion:'1',parameterDigest}},
 {type:'answer.delta',payload:{text:'2倍。'}},{type:'usage',payload:usage},{type:'run.completed',payload:{modelUsage:usage}}]
 for(const event of events)event.runId='run-fixture'
 const history={prompt:'火打草',answer:'2倍。',attempts:[usage]},capture={schemaVersion:'tool-input-capture-v1',plan:{repetitions:1,cases:[{id:'one',prompt:'火打草',tool:'checkTypeEffectiveness',expectedArguments:args}]},records:[{caseId:'one',repetition:1,runId:'run-fixture',status:'COMPLETED',answer:'2倍。'}]}
 return {capture,events,history,read:(_,key)=>key==='events'?events:history}
}
test('fixed report verifies inputs and history without granting human semantic acceptance',()=>{
 const f=fixture(),r=auditInputCapture(f.capture,f.read);assert.equal(r.pass,1);assert.equal(r.humanAcceptance,'NOT_PERFORMED')
 assert.deepEqual(expectedInputFields({moves:['flamethrower'],weather:null}),{'/moves/length':1,'/moves/0':'flamethrower','/weather':null})
 f.history.answer='changed';assert.equal(auditInputCapture(f.capture,f.read).failed,1)
})
test('missing evidence remains in denominator; duplicate observations cannot pass',()=>{
 const f=fixture(),r=auditInputCapture(f.capture,()=>{throw Error('missing')});assert.equal(r.denominator,1);assert.equal(r.failed,1)
 const copy=structuredClone(f.capture);copy.plan.repetitions=2;copy.records.push({...copy.records[0],runId:'different'});assert.throws(()=>auditInputCapture(copy,f.read),/duplicate/)
 f.events[0].payload.inputEvidence.fields['/attackType'].value='草';assert.equal(auditInputCapture(f.capture,f.read).failed,1)
})
test('events from another run and repeated run IDs cannot become new observations',()=>{
 const f=fixture();f.events[0].runId='other-run';assert.equal(auditInputCapture(f.capture,f.read).failed,1)
 const copy=structuredClone(f.capture);copy.plan.repetitions=2;copy.records.push({...copy.records[0],repetition:2});assert.throws(()=>auditInputCapture(copy,f.read),/Duplicate run/)
})
