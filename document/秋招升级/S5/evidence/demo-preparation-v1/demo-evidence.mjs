import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest} from './evaluation.mjs'
export function compareDemoSources(recorded,hashSource){
 if(!recorded||!Object.keys(recorded).length)throw Error('Recorded source identity required')
 const changed=[],missing=[]
 for(const [file,sha]of Object.entries(recorded)){
  if(!/^src\/main\/java\/[A-Za-z0-9_\/-]+\.java$/.test(file)||file.includes('..')||!/^[a-f0-9]{64}$/.test(sha))throw Error('Invalid public Java source identity')
  let actual;try{actual=hashSource(file)}catch{missing.push(file);continue}
  if(actual!==sha)changed.push(file)
 }
 return {recordedFiles:Object.keys(recorded).length,currentSourceMatchesHistoricalRun:!changed.length&&!missing.length,changed,missing,scope:'Source bytes only; not compilation, runtime readiness or semantic quality'}
}
export function summarizeDemoEvidence({live,confirmation,tests},hashSource){
 const identity=compareDemoSources(live.sourceSha256,hashSource)
 if(!Array.isArray(live.records)||!live.records.length||!Array.isArray(confirmation.rows))throw Error('Actual run and confirmation records required')
 const seen=new Set();for(const row of live.records){if(!row.caseId||seen.has(row.caseId)||typeof row.answer!=='string'||!Number.isSafeInteger(row.actualModelCalls)||row.actualModelCalls<0)throw Error('Invalid run case');seen.add(row.caseId)}
 const protocols=live.protocolComparison
 if(protocols?.planned!==live.records.length||!['v1Passed','v2Passed'].every(k=>Number.isInteger(protocols[k])&&protocols[k]>=0&&protocols[k]<=live.records.length))throw Error('Invalid historical protocol denominator')
 const confirmed=new Set();for(const row of confirmation.rows){const original=live.records.find(r=>r.caseId===row.id);if(!original||confirmed.has(row.id)||row.answerSha256!==digest(original.answer)||row.overallHumanAccepted!==true)throw Error('Wrong or duplicate confirmation binding');confirmed.add(row.id)}
 const backend=tests.tests
 if(!backend||!Number.isInteger(backend.backend)||backend.backend<0||backend.failures!==0||backend.errors!==0||!Number.isInteger(backend.skipped))throw Error('Valid historical test result required')
 return {schemaVersion:'campus-demo-evidence-v1',historicalCases:live.records.length,historicalModelCalls:live.records.reduce((n,r)=>n+(r.actualModelCalls||0),0),
  historicalProtocolResults:live.protocolComparison,humanOverallConfirmed:confirmed.size,confirmationScope:'Specific historical answers; not current code or all project quality',
  historicalBackend:{tests:backend.backend,failures:backend.failures,errors:backend.errors,skipped:backend.skipped},sourceIdentity:identity,
  currentLiveAcceptance:identity.currentSourceMatchesHistoricalRun?'NOT_ESTABLISHED_BY_SOURCE_MATCH_ALONE':'NOT_COVERED_BY_HISTORICAL_MODEL_RUN',
  s5Complete:false,videoRecorded:false,cleanEnvironmentVerified:false,formalAdmissions:0}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [output,...extra]=process.argv.slice(2);if(!output||extra.length||fs.existsSync(output))throw Error('Supply a fresh evidence report filename')
 const files={live:'document/秋招升级/S3/evidence/team-draft-contract-v3/observations.json',confirmation:'document/秋招升级/S2/evidence/team-human-confirmation-v1/report.json',tests:'document/秋招升级/S2/evidence/team-human-confirmation-v1/verification-summary.json'}
 const loaded=Object.fromEntries(Object.entries(files).map(([k,p])=>[k,JSON.parse(fs.readFileSync(p))]))
 const report=summarizeDemoEvidence(loaded,p=>digest(fs.readFileSync(p)))
 report.inputSha256=Object.fromEntries(Object.values(files).map(p=>[p,digest(fs.readFileSync(p))]))
 fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n',{flag:'wx'})
 console.log(JSON.stringify({output,currentSourceMatchesHistoricalRun:report.sourceIdentity.currentSourceMatchesHistoricalRun,humanOverallConfirmed:report.humanOverallConfirmed,s5Complete:false}))
}
