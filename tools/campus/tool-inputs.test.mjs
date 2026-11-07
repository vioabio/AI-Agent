import test from 'node:test'
import assert from 'node:assert/strict'
import { digest, canonical } from './evaluation.mjs'
import { collectToolInputs, checkToolInput, expectedInputFields } from './tool-inputs.mjs'
function fixture(args={attackType:'火',defendType:'草'}) {
 const parameterDigest=digest(JSON.stringify(canonical(args))),inputEvidence={schemaVersion:'tool-input-evidence-v1',coverage:'COMPLETE',scope:'SUPPLIED_ARGUMENTS_ONLY',toolVersion:'1',parameterDigest,
 fields:Object.fromEntries(Object.entries(args).map(([k,v])=>['/'+k,{kind:'LITERAL',value:v}]))}
 return [{type:'tool.started',payload:{toolName:'checkTypeEffectiveness',governanceEventId:'call',inputEvidence}},
 {type:'tool.finished',payload:{toolName:'checkTypeEffectiveness',governanceEventId:'call',status:'SUCCEEDED',toolVersion:'1',parameterDigest}}]
}
const expected={tool:'checkTypeEffectiveness',fields:{'/attackType':'火','/defendType':'草'}}
test('successful tool output cannot hide reversed or wrong inputs',()=>{
 assert.equal(checkToolInput(collectToolInputs(fixture()),expected).pass,true)
 assert.equal(checkToolInput(collectToolInputs(fixture({attackType:'草',defendType:'火'})),expected).pass,false)
 const missing=fixture();delete missing[0].payload.inputEvidence
 assert.equal(checkToolInput(collectToolInputs(missing),expected).pass,false)
 const mismatch=fixture();mismatch[1].payload.parameterDigest='f'.repeat(64)
 assert.equal(checkToolInput(collectToolInputs(mismatch),expected).pass,false)
})
test('retries deduplicate, cache proofs bind, conflicting and late evidence fail',()=>{
 const events=fixture();assert.equal(collectToolInputs([events[0],events[0],events[1]]).length,1)
 const cached={type:'tool.finished',payload:{...events[1].payload,cached:true,inputEvidence:events[0].payload.inputEvidence}}
 assert.equal(checkToolInput(collectToolInputs([cached]),expected).pass,true)
 const conflict=structuredClone(events[0]);conflict.payload.inputEvidence.fields['/attackType'].value='水'
 assert.equal(checkToolInput(collectToolInputs([events[0],conflict,events[1]]),expected).pass,false)
 assert.equal(checkToolInput(collectToolInputs([{type:'run.completed'},...events]),expected).pass,false)
 const failed=structuredClone(events);failed[1].payload.status='FAILED'
 assert.equal(checkToolInput(collectToolInputs(failed),expected).pass,false)
})
test('hashed strings, explicit null, numeric conditions and exact input shape are compared without raw text',()=>{
 const args={request:{move:'Flamethrower',field:{weather:null},attacker:{build:{level:50}}}},events=fixture();
 const proof=events[0].payload.inputEvidence;proof.parameterDigest=digest(JSON.stringify(canonical(args)));events[1].payload.parameterDigest=proof.parameterDigest
 proof.fields={'/request/move':{kind:'SHA256_JSON',sha256:digest(JSON.stringify('Flamethrower'))},'/request/field/weather':{kind:'LITERAL',value:null},'/request/attacker/build/level':{kind:'LITERAL',value:50}}
 const wanted={tool:expected.tool,fields:{'/request/move':'Flamethrower','/request/field/weather':null,'/request/attacker/build/level':50},exactArguments:args}
 assert.equal(checkToolInput(collectToolInputs(events),wanted).pass,true)
 assert.equal(checkToolInput(collectToolInputs(events),{...wanted,fields:{...wanted.fields,'/request/attacker/build/level':100}}).pass,false)
 assert.equal(checkToolInput(collectToolInputs(events),{...wanted,exactArguments:{request:{move:'Flamethrower'}}}).pass,false)
 proof.coverage='PARTIAL';assert.equal(checkToolInput(collectToolInputs(events),wanted).pass,false)
})

function previewFixture(args){
 const parameterDigest=digest(JSON.stringify(canonical(args)))
 const inputEvidence={schemaVersion:'tool-input-evidence-v1',coverage:'COMPLETE',scope:'SUPPLIED_ARGUMENTS_ONLY',toolVersion:'1.0.0',parameterDigest,
  projectionVersion:'bounded-team-preview-input-v1',fieldLimit:144,
  fields:Object.fromEntries(Object.entries(expectedInputFields(args)).map(([k,v])=>[k,typeof v==='string'?{kind:'SHA256_JSON',sha256:digest(JSON.stringify(v))}:{kind:'LITERAL',value:v}]))}
 return [{type:'tool.started',payload:{toolName:'previewTeamChanges',governanceEventId:'preview',inputEvidence}},
  {type:'tool.finished',payload:{toolName:'previewTeamChanges',governanceEventId:'preview',status:'SUCCEEDED',toolVersion:'1.0.0',parameterDigest}}]
}
test('six-member preview can bind 135 fields only under its explicit bounded projection',()=>{
 const stats={hp:0,atk:0,def:0,spa:252,spd:4,spe:252}
 const args={teamId:1,changes:Array.from({length:6},(_,i)=>({slot:i+1,moves:['surf','protect','ice-beam','water-pulse'],ability:'torrent',item:'leftovers',nature:'modest',level:50,evs:stats,ivs:{hp:31,atk:31,def:31,spa:31,spd:31,spe:31}})),rationale:'private-reason'}
 const events=previewFixture(args),wanted={tool:'previewTeamChanges',fields:expectedInputFields(args),exactArguments:args}
 assert.equal(Object.keys(events[0].payload.inputEvidence.fields).length,135)
 assert.equal(checkToolInput(collectToolInputs(events),wanted).pass,true)
 assert.equal(JSON.stringify(events).includes('private-reason'),false)
 for(const mutate of [e=>e[0].payload.inputEvidence.fieldLimit=10000,e=>delete e[0].payload.inputEvidence.projectionVersion,
  e=>{for(const event of e)event.payload.toolName='calculateBattleDamage'}]){
  const changed=structuredClone(events);mutate(changed);assert.equal(collectToolInputs(changed)[0].bound,false)
 }
})
test('preview audit preserves supplied patch order, nulls and text hashes without authorizing a write',()=>{
 const args={teamId:1,changes:[{slot:6,level:51,item:null},{slot:2,ability:'torrent'}],rationale:'bounded reason'}
 const events=previewFixture(args),wanted={tool:'previewTeamChanges',fields:expectedInputFields(args),exactArguments:args}
 assert.equal(checkToolInput(collectToolInputs(events),wanted).pass,true)
 assert.equal(checkToolInput(collectToolInputs(events),{...wanted,exactArguments:{...args,changes:[...args.changes].reverse()}}).pass,false)
 const changed=structuredClone(events);changed[0].payload.inputEvidence.fields['/changes/0/level'].value=52
 assert.equal(checkToolInput(collectToolInputs(changed),wanted).pass,false)
 const omitted=structuredClone(events);omitted[0].payload.inputEvidence={schemaVersion:'tool-input-evidence-v1',coverage:'OMITTED',fields:{}}
 assert.equal(collectToolInputs(omitted)[0].bound,false)
})
