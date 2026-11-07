import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest,canonical,sameJson} from './evaluation.mjs'
import {buildTeamReview} from './team-answer-review.mjs'
// Overall human confirmation is intentionally separate from per-criterion evidence.
export function compileTeamAttestation(bundle,claim){
 const {bundleSha256,...content}=bundle
 if(digest(JSON.stringify(canonical(content)))!==bundleSha256||claim?.bundleSha256!==bundleSha256)throw Error('Wrong attestation bundle')
 if(claim.schemaVersion!=='team-overall-human-attestation-v1'||claim.source!=='USER_CHAT'||claim.reviewerType!=='human'||claim.attested!==true||claim.decision!=='pass'||typeof claim.statement!=='string'||!claim.statement.trim()||typeof claim.reviewer!=='string'||!claim.reviewer.trim()||typeof claim.confirmedOn!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(claim.confirmedOn)||!Number.isFinite(Date.parse(claim.confirmedOn))||new Date(claim.confirmedOn).toISOString().slice(0,10)!==claim.confirmedOn||typeof claim.recordedAt!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(claim.recordedAt)||!Number.isFinite(Date.parse(claim.recordedAt)))throw Error('Explicit human chat attestation required')
 if(!Array.isArray(claim.rows)||!claim.rows.length)throw Error('Explicit answer bindings required')
 const seen=new Set(),accepted=claim.rows.map(row=>{
  const original=bundle.rows.find(r=>r.id===row.id)
  if(!original||seen.has(row.id)||row.answerSha256!==original.answerSha256)throw Error('Wrong duplicate or stale answer binding')
  seen.add(row.id)
  return {id:row.id,answerSha256:row.answerSha256,overallHumanAccepted:true,engineering:original.engineering,attestedTaskPass:original.engineering.status==='COMPLETED'&&original.engineering.v2Pass}
 })
 return {schemaVersion:'team-overall-attestation-report-v1',bundleSha256,denominator:bundle.rows.length,humanOverallAccepted:accepted.length,attestedTaskPass:accepted.filter(r=>r.attestedTaskPass).length,unconfirmed:bundle.rows.length-accepted.length,criterionLevelReviewsAdded:0,rows:accepted,identityAssurance:'SELF_REPORTED_USER_CHAT_NOT_AUTHENTICATED',formalAdmissions:0,scope:'Overall human approval only; no invented criterion reasons/quotes and no new model experiment or formal-suite admission'}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [directory,bundleFile,claimFile,output,...extra]=process.argv.slice(2)
 if(!directory||!bundleFile||!claimFile||!output||extra.length||fs.existsSync(output))throw Error('CAPTURE_DIR BUNDLE CLAIM NEW_OUTPUT required')
 const bundle=JSON.parse(fs.readFileSync(bundleFile)),claim=JSON.parse(fs.readFileSync(claimFile))
 if(!sameJson(bundle,buildTeamReview(directory)))throw Error('Attestation does not match original capture')
 const report=compileTeamAttestation(bundle,claim);fs.mkdirSync(output,{recursive:true})
 fs.writeFileSync(path.join(output,'claim.json'),JSON.stringify(claim,null,2)+'\n',{flag:'wx'})
 fs.writeFileSync(path.join(output,'report.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
 console.log(JSON.stringify({humanOverallAccepted:report.humanOverallAccepted,attestedTaskPass:report.attestedTaskPass,criterionLevelReviewsAdded:0}))
}
