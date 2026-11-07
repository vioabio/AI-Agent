// Re-audits preserved events without changing observations or issuing any model/HTTP request.
import fs from 'node:fs'
import path from 'node:path'
import {digest} from './evaluation.mjs'
import {collectToolInputs,checkToolInput,expectedInputFields} from './tool-inputs.mjs'
const [directory,output,...extra]=process.argv.slice(2)
if(!directory||!output||extra.length||fs.existsSync(output))throw Error('Supply preserved observations and a fresh audit file')
const report=JSON.parse(fs.readFileSync(path.join(directory,'observations.json'),'utf8'))
const rows=report.records.map(row=>{
 const bytes=fs.readFileSync(path.join(directory,row.eventsFile));if(digest(bytes)!==row.eventsSha256)throw Error('Changed preserved events')
 const events=JSON.parse(bytes),inputs=collectToolInputs(events),saved=row.caseId==='read-saved-team-then-check'
 const readCheck=saved?checkToolInput(inputs,{tool:'readCurrentTeam',fields:{'/teamId':report.teamBefore.id}}):null
 const teamCheck=checkToolInput(inputs,{tool:'checkPokemonTeamBuild',fields:expectedInputFields({request:row.request})})
 const checks={...row.checks,correctSavedRead:!saved||readCheck.pass,allInputsPreserved:teamCheck.pass}
 const latest=events.filter(e=>e.type==='tool.finished'&&e.payload?.toolName==='checkPokemonTeamBuild'&&e.payload.status==='SUCCEEDED').at(-1)?.payload
 return {caseId:row.caseId,originalPass:row.pass,pass:Object.values(checks).every(v=>v===true),checks,finalBoundOutput:latest?.outputEvidence?.reportSha256===row.httpBefore.reportSha256,actualModelCalls:row.actualModelCalls,teamInputAudit:teamCheck,savedReadAudit:readCheck,eventsSha256:row.eventsSha256}
})
const result={schemaVersion:'team-agent-event-reaudit-v1',observationsSha256:digest(fs.readFileSync(path.join(directory,'observations.json'))),collectorSha256:digest(fs.readFileSync('tools/campus/team-agent-reaudit.mjs')),scope:'Input evidence is paired from tool.started and tool.finished by the existing auditor; no gate is lowered, subset calls still fail strict coverage',rows,strictPassed:rows.filter(r=>r.pass).length,planned:2,allFinalOutputsBound:rows.every(r=>r.finalBoundOutput),savedTeamUnchanged:report.savedTeamUnchanged,modelCalls:rows.reduce((n,r)=>n+r.actualModelCalls,0),humanReviewed:0,formalAdmissions:0}
fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({strictPassed:result.strictPassed,planned:2,allFinalOutputsBound:result.allFinalOutputsBound,savedTeamUnchanged:result.savedTeamUnchanged,modelCalls:result.modelCalls}))
