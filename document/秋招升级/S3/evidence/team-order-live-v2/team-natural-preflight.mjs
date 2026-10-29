import {digest,localBase} from './evaluation.mjs'
export const NATURAL_PLAN_SHA256='73c7005a5684298ff372470091c59ddc3615237df8c56d9775a139a4ccb10b5c'
export const REFERENCE_PROTOCOL_SHA256='9a3fe5f74ff31784eda25826e45990d9330621f7b06c994cb822f8c55b99a74f'
export function fixedNaturalInputs(planText,protocolText){
 if(digest(planText.replaceAll('\r\n','\n'))!==NATURAL_PLAN_SHA256||digest(protocolText.replaceAll('\r\n','\n'))!==REFERENCE_PROTOCOL_SHA256)throw Error('Fixed natural plan or protocol changed')
 return {plan:JSON.parse(planText),protocol:JSON.parse(protocolText)}
}
export function requireNaturalRuntime(runtime){
 if(runtime?.teamBuildCheckPolicy?.answerVersion!=='bound-team-build-answer-v2'||runtime.teamBuildCheckPolicy.requestRouteVersion!=='explicit-team-build-route-v1'||!['checkPokemonTeamBuild','readCurrentTeam','lookupPokemonReference'].every(t=>runtime.tools?.includes(t)))throw Error('Wrong runtime tools or implementation version')
}
// Only retries the read-only session probe, never registration, team creation or a model task.
export async function waitForNaturalSession(base,probe,{attempts=10,intervalMs=500,wait=ms=>new Promise(r=>setTimeout(r,ms))}={}){
 if(new URL(localBase(base)).port!=='18249')throw Error('Only dedicated port 18249 is supported')
 if(!Number.isInteger(attempts)||attempts<1||attempts>20||!Number.isInteger(intervalMs)||intervalMs<0||intervalMs>1000)throw Error('Invalid readiness bounds')
 for(let attempt=1;attempt<=attempts;attempt++){
  let session
  try{session=await probe()}catch{if(attempt<attempts){await wait(intervalMs);continue}throw Error('Isolated session unavailable; no model task started')}
  if(session?.mode!=='local')throw Error('Dedicated local account mode required')
  return {status:'READY',probeAttempts:attempt}
 }
}
