import test from 'node:test'
import assert from 'node:assert/strict'
import {digest,observationHash} from './evaluation.mjs'
import {auditMechanicObservation,eventFileName} from './mechanic-observations.mjs'
const source={kind:'ability',slug:'snow-cloak',dataReleaseId:'pokeapi-reference-v1',nameZh:'雪隐',explanationZh:'下雪时提升闪避能力。',projectionSha256:'sha256:'+'a'.repeat(64)}
test('event evidence stays in the explicitly selected capture directory',()=>{
 assert.equal(eventFileName('S2-GUIDES-10-2-events.json'),'S2-GUIDES-10-2-events.json')
 for(const value of ['../private.json','..\\private.json','C:/private.json','other.json',null])assert.throws(()=>eventFileName(value))
})
function fixture({status='SUCCEEDED',slug='snow-cloak',display=true}={}) {
 const answer=display?'### 机制资料\n**雪隐（特性）**\n\n项目中文释义（待复核）：下雪时提升闪避能力。\n':''
 const record={caseId:'S2-GUIDES-10',repetition:1,answer,checks:{}}
 record.observationSha256=observationHash(record)
 const evidence={schemaVersion:'tool-input-evidence-v1',scope:'SUPPLIED_ARGUMENTS_ONLY',coverage:'COMPLETE',toolVersion:'fixture-v1',parameterDigest:'b'.repeat(64),fields:{'/kind':{kind:'LITERAL',value:'ability'},'/slug':{kind:'SHA256_JSON',sha256:digest(JSON.stringify(slug))},'/dataReleaseId':{kind:'LITERAL',value:'pokeapi-reference-v1'}}}
 const payload={governanceEventId:'one',toolName:'lookupPokemonResource',toolVersion:'fixture-v1',parameterDigest:evidence.parameterDigest,inputEvidence:evidence}
 return {record,events:[{type:'tool.started',payload},{type:'tool.finished',payload:{...payload,status}},{type:'answer.delta',payload:{text:answer}},{type:'run.completed',payload:{}}]}
}
test('known successful bound read and displayed explanation are checked without promoting it to human quality',()=>{
 const f=fixture(),r=auditMechanicObservation(f.record,f.events,[source],{requireRead:true});assert.equal(r.successfulKnown,1);assert.equal(r.displayed,1);assert.equal(r.requiredReadPresent,true)
})
test('wrong identity, missing display and failed resource remain missing when a read is required',()=>{
 for(const options of [{slug:'cursed-body'},{display:false},{status:'FAILED'}]){const f=fixture(options),r=auditMechanicObservation(f.record,f.events,[source],{requireRead:true});assert.equal(r.requiredReadPresent,false)}
})
test('changed observation text or event text cannot reuse an old audit binding',()=>{
 const f=fixture();assert.throws(()=>auditMechanicObservation({...f.record,answer:'changed'},f.events,[source]));f.events[2].payload.text='changed';assert.throws(()=>auditMechanicObservation(f.record,f.events,[source]));
})
test('pure introduction without a requested resource read does not become an invented successful read',()=>{
 const f=fixture({display:false,status:'FAILED'}),r=auditMechanicObservation(f.record,f.events,[source]);assert.equal(r.successfulKnown,0);assert.equal(r.displayed,0);assert.equal(r.requiredReadPresent,true)
})
test('one successful ability does not satisfy the additionally required move category',()=>{
 const f=fixture(),r=auditMechanicObservation(f.record,f.events,[source],{requireRead:true,requiredKinds:['ability','move'],allowedByKind:{ability:['snow-cloak'],move:['ice-beam']}})
 assert.deepEqual(r.coveredKinds,['ability']);assert.deepEqual(r.missingKinds,['move']);assert.equal(r.requiredReadPresent,false)
})
test('an unrelated member resource cannot cover the requested category',()=>{
 const f=fixture(),r=auditMechanicObservation(f.record,f.events,[source],{requireRead:true,requiredKinds:['ability'],allowedByKind:{ability:['torrent'],move:[]}})
 assert.equal(r.matches[0].candidateMatches,false);assert.deepEqual(r.missingKinds,['ability']);assert.equal(r.requiredReadPresent,false)
})
test('both candidate-bound categories and their actual text must be present',()=>{
 const f=fixture(),move={...source,kind:'move',slug:'ice-beam',nameZh:'冰冻光束',explanationZh:'有可能使目标冰冻。'}
 f.record.answer+='\n**冰冻光束（招式）**\n\n项目中文释义（待复核）：有可能使目标冰冻。\n';f.record.observationSha256=observationHash(f.record)
 const input=structuredClone(f.events[0].payload);input.governanceEventId='two';input.inputEvidence.fields['/kind'].value='move';input.inputEvidence.fields['/slug'].sha256=digest(JSON.stringify('ice-beam'))
 f.events.splice(2,0,{type:'tool.started',payload:input},{type:'tool.finished',payload:{...input,status:'SUCCEEDED'}});f.events.find(e=>e.type==='answer.delta').payload.text=f.record.answer
 const r=auditMechanicObservation(f.record,f.events,[source,move],{requireRead:true,requiredKinds:['ability','move'],allowedByKind:{ability:['snow-cloak'],move:['ice-beam']}})
 assert.deepEqual(r.missingKinds,[]);assert.equal(r.requiredReadPresent,true)
})
test('a generic resource display does not prove that the answer associated it with the requested member',()=>{
 const f=fixture(),options={requireRead:true,requiredKinds:['ability'],allowedByKind:{ability:['snow-cloak']},memberName:'雪妖女'}
 assert.equal(auditMechanicObservation(f.record,f.events,[source],options).requiredReadPresent,false)
 f.record.answer=f.record.answer.replace('项目中文释义','资料候选：雪妖女；不代表已选择。\n\n项目中文释义');f.record.observationSha256=observationHash(f.record);f.events.find(e=>e.type==='answer.delta').payload.text=f.record.answer
 assert.equal(auditMechanicObservation(f.record,f.events,[source],options).requiredReadPresent,true)
})
