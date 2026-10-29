// A separate protocol: retain v1 results, permit only explicitly scoped reference reads.
import {auditTeamPlanning} from './team-planning.mjs'
import {collectToolInputs,checkToolInput} from './tool-inputs.mjs'
export function auditTeamPlanningWithReferences(events,expected,queriesByPokemonId){
 if(!queriesByPokemonId||typeof queriesByPokemonId!=='object'||Array.isArray(queriesByPokemonId))throw Error('Explicit reference-query catalogue required')
 const ids=new Set(expected.request?.members?.map(m=>String(m.pokemonId)))
 const queries=[...ids].flatMap(id=>{
  const aliases=queriesByPokemonId[id]
  if(!Array.isArray(aliases)||!aliases.length||aliases.some(q=>typeof q!=='string'||!q||q.length>80))throw Error('Missing or invalid member aliases')
  return aliases
 })
 const calls=collectToolInputs(events),reads=calls.filter(c=>c.tool==='lookupPokemonReference')
 const referenceReads=reads.map(call=>{
  const proofs=queries.map(query=>checkToolInput([call],{tool:'lookupPokemonReference',fields:{'/query':query,'/dataReleaseId':expected.request.dataReleaseId},exactArguments:{query,dataReleaseId:expected.request.dataReleaseId}}))
  return {callId:call.callId,pass:proofs.some(p=>p.pass),failures:proofs.some(p=>p.pass)?[]:['REFERENCE_INPUT_UNBOUND_OR_OUTSIDE_EXPECTED_SCOPE']}
 })
 // Strip only this separately audited tool; all other operations still face the original corridor.
 const base=auditTeamPlanning(events.filter(e=>!(e.payload?.toolName==='lookupPokemonReference'&&['tool.started','tool.finished'].includes(e.type))),expected)
 const checks={...base.checks,referenceReads:referenceReads.every(r=>r.pass)}
 return {...base,schemaVersion:'team-planning-reference-audit-v2',checks,pass:Object.values(checks).every(Boolean),referenceReads,protocol:'SCOPED_REFERENCE_READS_V2',scope:'Separate retrospective protocol; v1 scores unchanged. Validates supplied lookup aliases/version and terminal binding, not returned fact quality, new model runs or semantic correctness.'}
}
