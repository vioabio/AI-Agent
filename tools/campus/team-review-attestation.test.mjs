import test from 'node:test'
import assert from 'node:assert/strict'
import {compileTeamAttestation} from './team-review-attestation.mjs'
import {digest,canonical} from './evaluation.mjs'
function bundle(){const b={rows:[{id:'case',answerSha256:'a'.repeat(64),engineering:{status:'COMPLETED',v2Pass:true}}]};return {...b,bundleSha256:digest(JSON.stringify(canonical(b)))}}
function claim(b){return {schemaVersion:'team-overall-human-attestation-v1',source:'USER_CHAT',reviewerType:'human',reviewer:'Synthetic test only',attested:true,decision:'pass',statement:'Synthetic confirmation',confirmedOn:'2026-10-29',recordedAt:'2026-10-29T03:14:42Z',bundleSha256:b.bundleSha256,rows:b.rows.map(r=>({id:r.id,answerSha256:r.answerSha256}))}}
test('overall approval is bound and does not fabricate criterion reviews',()=>{const b=bundle(),r=compileTeamAttestation(b,claim(b));assert.equal(r.humanOverallAccepted,1);assert.equal(r.attestedTaskPass,1);assert.equal(r.criterionLevelReviewsAdded,0)})
test('AI unconfirmed or unsupported source cannot count as human',()=>{const b=bundle();for(const change of [{reviewerType:'ai-assisted'},{attested:false},{source:'MODEL'},{statement:''},{reviewer:''}])assert.throws(()=>compileTeamAttestation(b,{...claim(b),...change}))})
test('wrong bundle changed answer duplicate and unknown cases are rejected',()=>{const b=bundle();for(const mutate of [c=>c.bundleSha256='wrong',c=>c.rows[0].answerSha256='wrong',c=>c.rows.push(c.rows[0]),c=>c.rows[0].id='unknown']){const c=claim(b);mutate(c);assert.throws(()=>compileTeamAttestation(b,c))}})
test('human approval does not remove an engineering failure',()=>{const b=bundle();b.rows[0].engineering.v2Pass=false;const {bundleSha256,...content}=b;b.bundleSha256=digest(JSON.stringify(canonical(content)));const r=compileTeamAttestation(b,claim(b));assert.equal(r.humanOverallAccepted,1);assert.equal(r.attestedTaskPass,0)})
test('empty scope invalid dates and nonpass decisions are rejected',()=>{const b=bundle();for(const change of [{rows:[]},{confirmedOn:'2026-02-30'},{recordedAt:'2026'},{decision:'unknown'}])assert.throws(()=>compileTeamAttestation(b,{...claim(b),...change}))})
