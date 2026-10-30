import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {auditIdentityReplay} from './team-identity-replay.mjs'
import {auditCapturedIdentity} from './team-identity-replay-audit.mjs'
import {digest} from './evaluation.mjs'
const evidence='document/秋招升级/S5/evidence/model-identity-replay-v1/'
const bytes=fs.readFileSync(evidence+'observations.json')
const original=JSON.parse(fs.readFileSync('evaluation/campus/demo-recording-v1/plan.json'))
const plan={...JSON.parse(fs.readFileSync('evaluation/campus/demo-identity-replay-v1/plan.json')),
 readOnlyPrompt:original.readOnlyPrompt,changePromptTemplate:original.changePromptTemplate}
const base=JSON.parse(bytes),proof=JSON.parse(fs.readFileSync(evidence+'history-binding.json'))
const provenance=()=>({historyBinding:structuredClone(proof),observationSha256:digest(bytes)})
const audit=(r=structuredClone(base),p=provenance())=>auditIdentityReplay(r,plan,p)

test('captured literal-name lookups precede aggregation; distinct run session IDs are not conversation IDs',()=>{
 const result=audit();assert.equal(result.pass,true);assert.equal(result.actualModelCalls,5)
 assert.deepEqual(result.distinctToolCalls,[3,2]);assert.equal(result.protocolResults.originalV1Pass,false)
 assert.equal(result.protocolResults.scopedReferencesV2Pass,true);assert.equal(result.checks.sameConversation,true)
 assert.notEqual(base.tasks[0].events[0].sessionId,base.tasks[1].events[0].sessionId)
 assert.equal(result.fullRecruitingVideoComplete,false);assert.equal(result.tokenByTokenStreamingVerified,false)
 assert.equal(result.strictPreviewInputCertified,false);assert.equal(result.previewInputEvidence[0].coverage,'OMITTED')
 assert.equal(result.formalAdmissions,0);assert.equal(result.newHumanReviews,0)
})

test('reference reads after the first aggregate cannot authorize that earlier call',()=>{
 const r=structuredClone(base),events=r.tasks[0].events
 const refs=events.filter(e=>e.payload?.toolName==='lookupPokemonReference')
 r.tasks[0].events=[...events.filter(e=>e.payload?.toolName!=='lookupPokemonReference'),...refs]
 assert.equal(audit(r).checks.literalNamesBeforeAggregate,false)
})

test('a cached top-level pass cannot hide a wrong member ID in actual input evidence',()=>{
 const r=structuredClone(base)
 for(const e of r.tasks[0].events)if(e.payload?.toolName==='checkPokemonTeamBuild'&&e.payload.inputEvidence)
  e.payload.inputEvidence.fields['/request/members/1/pokemonId']={kind:'LITERAL',value:872}
 assert.equal(audit(r).checks.allAggregateCallsBound,false)
})

test('post-run history supplement must bind the exact observation, owners and answers',()=>{
 for(const mutate of [p=>p.observationSha256='a'.repeat(64),p=>p.historyBinding.bindings[0].answerSha256='b'.repeat(64),
  p=>p.historyBinding.bindings[1].conversationId='another',p=>p.historyBinding.bindings[1].ownerSha256='c'.repeat(64)]){
  const p=provenance();mutate(p);assert.equal(audit(undefined,p).pass,false)
 }
})

test('unrelated field changes, early writes or missing reload readback fail the continuous flow',()=>{
 for(const mutate of [r=>r.after.members[0].build.nature='timid',r=>r.afterReadOnly.members[0].build.level=51,
  r=>r.noWriteBeforeConfirmation=false,r=>r.reloadReadBack=false,r=>r.preview.changes.push(r.preview.changes[0])]){
  const r=structuredClone(base);mutate(r);assert.equal(audit(r).pass,false)
 }
})

test('actual usage must match captured usage events and cannot be replaced with program execution',()=>{
 const r=structuredClone(base);r.tasks[0].modelCalls=0;assert.equal(audit(r).checks.realUsage,false)
})

test('different runtime classes or events from another run fail identity checks',()=>{
 const r=structuredClone(base);r.runtimeAfter.implementationSha256.ToolCallAgent='changed';assert.equal(audit(r).checks.runtimeStable,false)
 const empty=structuredClone(base);empty.runtime.implementationSha256={};empty.runtimeAfter.implementationSha256={};assert.equal(audit(empty).checks.runtimeStable,false)
 const other=structuredClone(base);other.tasks[1].events[0].runId='other-run';assert.equal(audit(other).checks.ownRunEvidence,false)
})

test('missing original HTTP baseline cannot be replaced by a final model report',()=>{
 const r=structuredClone(base);delete r.readOnlyReferenceBefore;assert.throws(()=>audit(r),/pre-run HTTP/)
})

test('original failed recording remains a failed separate observation',()=>{
 const old=JSON.parse(fs.readFileSync('document/秋招升级/S5/evidence/model-recording-v1/readonly-failed.json'))
 assert.equal(old.pass,false);assert.equal(old.tasks[0].audit.pass,false)
 assert.throws(()=>auditIdentityReplay(old,plan,provenance()),/Two captured tasks/)
})

function temporary(t){
 const folder=fs.mkdtempSync(path.join(os.tmpdir(),'identity-replay-cli-'))
 t.after(()=>{if(path.dirname(path.resolve(folder))!==path.resolve(os.tmpdir())||!path.basename(folder).startsWith('identity-replay-cli-'))throw Error('Unexpected temporary path');fs.rmSync(folder,{recursive:true,force:true})})
 return folder
}
test('offline CLI preserves input, binds recorded collector and refuses an existing output',t=>{
 const folder=temporary(t),output=path.join(folder,'audit.json'),before=fs.readFileSync(evidence+'observations.json')
 const result=auditCapturedIdentity(evidence,output);assert.equal(result.pass,true);assert.equal(result.newModelCalls,0)
 assert.deepEqual(fs.readFileSync(evidence+'observations.json'),before)
 const saved=fs.readFileSync(output);assert.throws(()=>auditCapturedIdentity(evidence,output),/fresh/);assert.deepEqual(fs.readFileSync(output),saved)
})
test('changed recorded collector is rejected without writing a report',t=>{
 const folder=temporary(t)
 for(const file of ['observations.json','history-binding.json','replay-plan.json','preflight.json','recorded-collector.spec.js'])fs.copyFileSync(evidence+file,path.join(folder,file))
 fs.appendFileSync(path.join(folder,'recorded-collector.spec.js'),'\n// changed')
 const output=path.join(folder,'audit.json');assert.throws(()=>auditCapturedIdentity(folder,output),/Changed replay plan/)
 assert.equal(fs.existsSync(output),false)
})
