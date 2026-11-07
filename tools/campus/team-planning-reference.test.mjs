import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {spawnSync} from 'node:child_process'
import {auditTeamPlanningWithReferences} from './team-planning-reference.mjs'
import {auditTeamPlanning} from './team-planning.mjs'
import {digest,canonical} from './evaluation.mjs'
import {expectedInputFields} from './tool-inputs.mjs'
const request={dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet',members:[{slot:6,pokemonId:7,build:null}]}
const expected={request,reportSha256:'sha256:'+'a'.repeat(64),mode:'MODEL_TOOL_SELECTION'}
const aliases={'7':['7','squirtle','杰尼龟'],'478':['478','froslass','雪妖女']}
function call(tool,args,id){const parameterDigest=digest(JSON.stringify(canonical(args)));return [{type:'tool.started',payload:{toolName:tool,governanceEventId:id,inputEvidence:{schemaVersion:'tool-input-evidence-v1',scope:'SUPPLIED_ARGUMENTS_ONLY',coverage:'COMPLETE',toolVersion:'test-v1',parameterDigest,fields:Object.fromEntries(Object.entries(expectedInputFields(args)).map(([k,v])=>[k,typeof v==='string'?{kind:'SHA256_JSON',sha256:digest(JSON.stringify(v))}:{kind:'LITERAL',value:v}]))}}},{type:'tool.finished',payload:{toolName:tool,governanceEventId:id,status:'SUCCEEDED',toolVersion:'test-v1',parameterDigest,...(tool==='checkPokemonTeamBuild'?{outputEvidence:{schemaVersion:'team-build-output-evidence-v1',status:'VERIFIED',reportSha256:expected.reportSha256,parameterDigest,jointStatus:'NOT_VERIFIED'}}:{})}}]}
const lookup=(query='squirtle',dataReleaseId=request.dataReleaseId)=>call('lookupPokemonReference',{query,dataReleaseId},'lookup')
const tail=()=>[...call('checkPokemonTeamBuild',{request},'team'),{type:'run.completed'}]
const audit=events=>auditTeamPlanningWithReferences(events,expected,aliases)
test('v2 accepts scoped hashed lookup while v1 remains strict',()=>{const e=[...lookup(),...tail()];assert.equal(audit(e).pass,true);assert.equal(auditTeamPlanning(e,expected).pass,false)})
test('no lookups still permits the original complete path',()=>assert.equal(audit(tail()).pass,true))
test('catalogue cannot expand queries beyond the actual team',()=>{assert.equal(audit([...lookup('froslass'),...tail()]).pass,false);assert.equal(audit([...lookup('https://example.com'),...tail()]).pass,false)})
test('wrong version and unknown extra arguments fail',()=>{assert.equal(audit([...lookup('squirtle','wrong'),...tail()]).pass,false);assert.equal(audit([...call('lookupPokemonReference',{query:'squirtle',dataReleaseId:request.dataReleaseId,url:'https://example.com'},'extra'),...tail()]).pass,false)})
test('missing partial or changed input evidence cannot pass',()=>{for(const mutate of [e=>delete e[0].payload.inputEvidence,e=>e[0].payload.inputEvidence.coverage='PARTIAL',e=>e[1].payload.parameterDigest='b'.repeat(64)]){const e=lookup();mutate(e);assert.equal(audit([...e,...tail()]).pass,false)}})
test('failed or unfinished reference reads are not ignored',()=>{const e=lookup();e[1].payload.status='FAILED';assert.equal(audit([...e,...tail()]).pass,false);assert.equal(audit([lookup()[0],...tail()]).pass,false)})
test('post terminal reads and approvals fail',()=>{assert.equal(audit([...tail(),...lookup()]).pass,false);assert.equal(audit([...lookup(),{type:'approval.required'},...tail()]).pass,false)})
test('other tools and changed member order remain failures',()=>{assert.equal(audit([...lookup(),...call('searchWeb',{query:'test'},'search'),...tail()]).pass,false);const other={...request,members:[{slot:1,pokemonId:7,build:null}]};assert.equal(audit([...lookup(),...call('checkPokemonTeamBuild',{request:other},'wrong'),{type:'run.completed'}]).pass,false)})
test('missing explicit member catalogue is rejected',()=>assert.throws(()=>auditTeamPlanningWithReferences(tail(),expected,{'478':['froslass']}),/Missing/))
function fixture(t){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'team-reference-audit-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}))
 const bytes=JSON.stringify([...lookup(),...tail()]);fs.writeFileSync(path.join(dir,'events.json'),bytes)
 fs.writeFileSync(path.join(dir,'observations.json'),JSON.stringify({records:[{caseId:'synthetic',eventsFile:'events.json',eventsSha256:digest(bytes),request,httpBefore:{reportSha256:expected.reportSha256},executionMode:expected.mode,pass:false}]}))
 return {dir,output:path.join(dir,'audit.json'),run:output=>spawnSync(process.execPath,['tools/campus/team-planning-reference-audit.mjs',dir,output],{encoding:'utf8'})}
}
test('CLI keeps source immutable and reports protocols separately without overwriting',t=>{
 const f=fixture(t),source=fs.readFileSync(path.join(f.dir,'observations.json'));assert.equal(f.run(f.output).status,0)
 const result=JSON.parse(fs.readFileSync(f.output));assert.equal(result.v1Passed,0);assert.equal(result.v2Passed,1);assert.equal(result.newModelCalls,0)
 assert.deepEqual(fs.readFileSync(path.join(f.dir,'observations.json')),source);const output=fs.readFileSync(f.output)
 assert.notEqual(f.run(f.output).status,0);assert.deepEqual(fs.readFileSync(f.output),output)
})
test('CLI refuses changed evidence before creating an audit output',t=>{
 const f=fixture(t);fs.appendFileSync(path.join(f.dir,'events.json'),' ');const r=f.run(f.output)
 assert.notEqual(r.status,0);assert.match(r.stderr,/Changed event evidence/);assert.equal(fs.existsSync(f.output),false)
})
