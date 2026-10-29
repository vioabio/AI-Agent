import test from 'node:test'
import assert from 'node:assert/strict'
import {digest,canonical} from './evaluation.mjs'
import {expectedInputFields} from './tool-inputs.mjs'
import {auditTeamPlanning} from './team-planning.mjs'
const request={dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet',members:[{slot:3,pokemonId:7,build:null},{slot:1,pokemonId:478,build:null}]}
const hash='sha256:'+'a'.repeat(64),expected={request,reportSha256:hash,mode:'MODEL_TOOL_SELECTION',teamId:12}
function call(tool,args,id){const parameterDigest=digest(JSON.stringify(canonical(args)));return [
 {type:'tool.started',payload:{toolName:tool,governanceEventId:id,inputEvidence:{schemaVersion:'tool-input-evidence-v1',scope:'SUPPLIED_ARGUMENTS_ONLY',coverage:'COMPLETE',toolVersion:'test-v1',parameterDigest,fields:Object.fromEntries(Object.entries(expectedInputFields(args)).map(([k,value])=>[k,{kind:'LITERAL',value}]))}}},
 {type:'tool.finished',payload:{toolName:tool,governanceEventId:id,status:'SUCCEEDED',toolVersion:'test-v1',parameterDigest,...(tool==='checkPokemonTeamBuild'?{outputEvidence:{schemaVersion:'team-build-output-evidence-v1',status:'VERIFIED',reportSha256:hash,parameterDigest,jointStatus:'NOT_VERIFIED'}}:{})}}]}
const events=()=>[...call('readCurrentTeam',{teamId:12},'read'),...call('checkPokemonTeamBuild',{request},'check'),{type:'run.completed'}]
test('complete model-planned team preserves ordered null members and saved read',()=>assert.equal(auditTeamPlanning(events(),expected).pass,true))
test('reading saved team after checking fails ordering even when both calls succeeded',()=>{const e=events();assert.equal(auditTeamPlanning([...e.slice(2,4),...e.slice(0,2),e[4]],expected).checks.readBeforeCheck,false)})
test('wrong saved ID cannot satisfy the prerequisite',()=>assert.equal(auditTeamPlanning([...call('readCurrentTeam',{teamId:13},'read'),...events().slice(2)],expected).pass,false))
test('intermediate subset still fails despite a complete final report',()=>assert.equal(auditTeamPlanning([...call('checkPokemonTeamBuild',{request:{...request,members:request.members.slice(0,1)}},'subset'),...events()],expected).pass,false))
test('failed intermediate calls cannot disappear from the score',()=>{const bad=call('checkPokemonTeamBuild',{request},'failed');bad[1].payload.status='FAILED';assert.equal(auditTeamPlanning([...bad,...events()],expected).checks.allCallsSucceeded,false)})
test('program execution cannot be scored as model selection',()=>{const e=events();e[3].payload.selectionOrigin='PROGRAM_SUPPLIED_TEAM_REQUEST';assert.equal(auditTeamPlanning(e,expected).checks.executionMode,false)})
test('partial input evidence stays unverified',()=>{const e=events();e[2].payload.inputEvidence.coverage='PARTIAL';assert.equal(auditTeamPlanning(e,expected).checks.fullInputs,false)})
test('changed output binding and post-terminal calls fail',()=>{const e=events();e[3].payload.outputEvidence.parameterDigest='b'.repeat(64);assert.equal(auditTeamPlanning(e,expected).pass,false);assert.equal(auditTeamPlanning([e[4],...events().slice(0,4)],expected).pass,false)})
test('missing completion, approvals and extra operations fail',()=>{assert.equal(auditTeamPlanning(events().slice(0,-1),expected).pass,false);assert.equal(auditTeamPlanning([...events(),{type:'approval.required'}],expected).pass,false);assert.equal(auditTeamPlanning([...call('previewTeamChange',{},'write'),...events()],expected).pass,false)})
