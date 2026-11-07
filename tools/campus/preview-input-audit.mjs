import {collectToolInputs,checkToolInput} from './tool-inputs.mjs'

/** Supplied input evidence only; does not apply a proposal or prove strategy quality. */
export function auditPreviewArguments(events,{teamId,slot,level}){
 if(!Number.isSafeInteger(teamId)||teamId<1||!Number.isInteger(slot)||slot<1||slot>6||!Number.isInteger(level)||level<1||level>100)throw Error('Explicit bounded preview target required')
 const calls=collectToolInputs(events),previews=calls.filter(c=>c.tool==='previewTeamChanges')
 const first=events.findIndex(e=>e.type==='tool.started'&&e.payload?.toolName==='previewTeamChanges')
 const read=checkToolInput(collectToolInputs(first<0?[]:events.slice(0,first)),{tool:'readCurrentTeam',fields:{'/teamId':teamId},exactArguments:{teamId}})
 const inputs=checkToolInput(calls,{tool:'previewTeamChanges',fields:{'/teamId':teamId,'/changes/length':1,'/changes/0/slot':slot,'/changes/0/level':level}})
 const allowed=new Set(['/teamId','/changes/length','/changes/0/slot','/changes/0/level','/rationale'])
 const nullable=/^\/changes\/0\/(?:moves|ability|item|nature|evs|ivs)$/
 const checks={savedTeamReadBeforePreview:read.pass,requestedPatch:inputs.pass,
  allPreviewCallsBound:previews.length>0&&previews.every(c=>c.bound&&['SUCCEEDED','EXECUTED','DEDUPLICATED'].includes(c.status)),
  boundedPolicy:previews.length>0&&previews.every(c=>c.evidence?.projectionVersion==='bounded-team-preview-input-v1'&&c.evidence.fieldLimit===144),
  reasonHashed:previews.length>0&&previews.every(c=>c.evidence?.fields?.['/rationale']?.kind==='SHA256_JSON'&&/^[a-f0-9]{64}$/.test(c.evidence.fields['/rationale'].sha256)),
  noUnrequestedNonNullValues:previews.length>0&&previews.every(c=>Object.entries(c.evidence?.fields||{}).every(([key,p])=>allowed.has(key)||nullable.test(key)&&p.kind==='LITERAL'&&p.value===null))}
 return {schemaVersion:'single-field-preview-input-audit-v1',pass:Object.values(checks).every(Boolean),checks,inputAudit:inputs,readAudit:read,
  observedPreviewCalls:previews.length,newModelCalls:0,authorizesWrite:false,
  scope:'One requested non-null field plus optional null preservation; complete bounded projections and terminal digests, not resolved defaults, approval, storage effects or semantic quality.'}
}
