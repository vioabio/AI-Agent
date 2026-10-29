// Offline engineering checks only; never a semantic or model-quality verdict.
import {collectToolInputs,checkToolInput,expectedInputFields} from './tool-inputs.mjs'
export function auditTeamPlanning(events,{request,reportSha256,mode,teamId}) {
 if(!Array.isArray(events)||!request?.members?.length||request.members.length>6||!['MODEL_TOOL_SELECTION','PROGRAM_SUPPLIED_TEAM_REQUEST'].includes(mode)||! /^sha256:[a-f0-9]{64}$/.test(reportSha256))throw Error('Explicit request, report hash and execution mode required')
 const calls=collectToolInputs(events),team=calls.filter(c=>c.tool==='checkPokemonTeamBuild')
 const inputs=checkToolInput(calls,{tool:'checkPokemonTeamBuild',fields:expectedInputFields({request}),exactArguments:{request}})
 const finished=events.filter(e=>e.type==='tool.finished'&&e.payload?.toolName==='checkPokemonTeamBuild')
 const outputs=finished.length>0&&finished.every(({payload:p})=>p.outputEvidence?.schemaVersion==='team-build-output-evidence-v1'&&p.outputEvidence.status==='VERIFIED'&&p.outputEvidence.reportSha256===reportSha256&&p.outputEvidence.parameterDigest===p.parameterDigest&&p.outputEvidence.jointStatus==='NOT_VERIFIED')
 let read=null,readBeforeCheck=true
 if(teamId!==undefined){
  read=checkToolInput(calls,{tool:'readCurrentTeam',fields:{'/teamId':teamId}})
  const firstCheck=events.findIndex(e=>e.type==='tool.started'&&e.payload?.toolName==='checkPokemonTeamBuild')
  // Bind a successful read to the expected ID before the first check, not merely somewhere in the run.
  readBeforeCheck=firstCheck>=0&&checkToolInput(collectToolInputs(events.slice(0,firstCheck)),{tool:'readCurrentTeam',fields:{'/teamId':teamId}}).pass
 }
 const checks={completed:events.filter(e=>['run.completed','run.failed','run.cancelled','run.timed_out'].includes(e.type)).length===1&&events.some(e=>e.type==='run.completed'),
  fullInputs:inputs.pass,allCallsSucceeded:team.length>0&&team.every(c=>c.bound&&['SUCCEEDED','EXECUTED','DEDUPLICATED'].includes(c.status)),
  boundOutputs:outputs,executionMode:finished.length>0&&finished.every(e=>(e.payload.selectionOrigin||'MODEL_TOOL_SELECTION')===mode),
  savedRead:read===null||read.pass,readBeforeCheck,
  readOnly:!events.some(e=>e.type==='approval.required')&&calls.every(c=>['checkPokemonTeamBuild','readCurrentTeam','doTerminate'].includes(c.tool))}
 return {schemaVersion:'team-planning-audit-v1',mode,checks,pass:Object.values(checks).every(Boolean),inputAudit:inputs,readAudit:read,humanReviewed:0,scope:'Engineering evidence only; PARTIAL input evidence cannot pass; no semantic correctness claim'}
}
