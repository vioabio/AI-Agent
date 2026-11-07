import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {auditPreviewArguments} from './preview-input-audit.mjs'
import {digest,canonical} from './evaluation.mjs'
import {expectedInputFields} from './tool-inputs.mjs'
const historical=JSON.parse(fs.readFileSync('document/秋招升级/S5/evidence/model-identity-replay-v1/observations.json'))
const expected={teamId:historical.before.id,slot:1,level:51}
function fixture(patch={slot:1,level:51,item:null}){
 const events=structuredClone(historical.tasks[1].events),args={teamId:expected.teamId,changes:[patch],rationale:'fixture-only-private-reason'}
 const parameterDigest=digest(JSON.stringify(canonical(args)))
 for(const e of events)if(e.payload?.toolName==='previewTeamChanges'){
  if(e.type==='tool.started')e.payload.inputEvidence={schemaVersion:'tool-input-evidence-v1',coverage:'COMPLETE',scope:'SUPPLIED_ARGUMENTS_ONLY',toolVersion:'1.0.0',parameterDigest,projectionVersion:'bounded-team-preview-input-v1',fieldLimit:144,
   fields:Object.fromEntries(Object.entries(expectedInputFields(args)).map(([k,v])=>[k,typeof v==='string'?{kind:'SHA256_JSON',sha256:digest(JSON.stringify(v))}:{kind:'LITERAL',value:v}]))}
  if(e.type==='tool.finished'){e.payload.parameterDigest=parameterDigest;e.payload.toolVersion='1.0.0'}
 }
 return events
}
test('complete bounded fixture certifies supplied patch only and never authorizes persistence',()=>{
 const r=auditPreviewArguments(fixture(),expected);assert.equal(r.pass,true);assert.equal(r.authorizesWrite,false);assert.equal(r.newModelCalls,0)
})
test('old OMITTED recording is not retrospectively upgraded by new projection code',()=>{
 assert.equal(auditPreviewArguments(historical.tasks[1].events,expected).pass,false)
 assert.equal(historical.tasks[1].events.find(e=>e.type==='tool.started'&&e.payload?.toolName==='previewTeamChanges').payload.inputEvidence.coverage,'OMITTED')
})
test('wrong slot, level, unrequested non-null fields and missing prerequisite read fail',()=>{
 for(const patch of [{slot:2,level:51},{slot:1,level:52},{slot:1,level:51,nature:'modest'}])assert.equal(auditPreviewArguments(fixture(patch),expected).pass,false)
 const missing=fixture().filter(e=>e.payload?.toolName!=='readCurrentTeam');assert.equal(auditPreviewArguments(missing,expected).pass,false)
})
test('partial evidence, changed terminal and invalid policy cannot certify the preview input',()=>{
 for(const mutate of [events=>events.find(e=>e.type==='tool.started'&&e.payload?.toolName==='previewTeamChanges').payload.inputEvidence.coverage='PARTIAL',
  events=>events.find(e=>e.type==='tool.finished'&&e.payload?.toolName==='previewTeamChanges').payload.parameterDigest='a'.repeat(64),
  events=>events.find(e=>e.type==='tool.started'&&e.payload?.toolName==='previewTeamChanges').payload.inputEvidence.fieldLimit=999]){
  const events=fixture();mutate(events);assert.equal(auditPreviewArguments(events,expected).pass,false)
 }
})
