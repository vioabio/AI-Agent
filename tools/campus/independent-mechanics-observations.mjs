import {sameJson} from './evaluation.mjs'
import {collectToolInputs,checkToolInput} from './tool-inputs.mjs'
export function auditIndependentMechanics(record,events,item,expected) {
 const input={kind:item.kind,slug:item.slug,dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet'}
 const inputCheck=checkToolInput(collectToolInputs(events),{tool:'lookupPokemonMechanics',fields:Object.fromEntries(Object.entries(input).map(([k,v])=>['/'+k,v])),exactArguments:input})
 const ids=new Set(inputCheck.checks.filter(c=>c.pass).map(c=>c.callId)),grouped=new Map()
 for(const event of events.filter(e=>e.type==='tool.finished'&&e.payload?.toolName==='lookupPokemonMechanics'&&ids.has(e.payload.governanceEventId))) {
  const p=event.payload;if(!grouped.has(p.governanceEventId))grouped.set(p.governanceEventId,[]);grouped.get(p.governanceEventId).push(p)
 }
 const outputChecks=[...grouped.values()].map(rows=>{const p=rows[0],proof=p.outputEvidence;return {callId:p.governanceEventId,notifications:rows.length,
  pass:rows.every(row=>sameJson(row.outputEvidence,proof))&&proof?.schemaVersion==='independent-mechanic-output-evidence-v1'&&proof.status==='VERIFIED'
   &&proof.toolVersion===p.toolVersion&&proof.parameterDigest===p.parameterDigest&&['kind','slug','sourceCommit','sourceFileSha256','descriptionSha256','projectionSha256'].every(k=>proof[k]===expected[k])}})
 const answer=record.answer||'',body=answer.split('### 机制补充（第二来源）')[1]?.split('### ')[0]||''
 const checks={completed:record.status==='COMPLETED',boundInput:inputCheck.pass,boundOutput:outputChecks.length>0&&outputChecks.every(c=>c.pass),
  displayedNote:body.includes(expected.explanationZh),displayedSource:body.includes(']('+expected.sourceUrl+')'),reviewBoundary:body.includes('待人工复核'),
  noInternals:!/(?:sha256:|sourceCommit|descriptionSha256|projectionSha256|run-[a-f0-9-]{20,})/.test(answer)}
 return {pass:Object.values(checks).every(v=>v===true),checks,inputCheck,outputChecks,
  scope:'Necessary actual input/output and displayed pinned paraphrase checks, not official game correctness, complete exceptions or human semantic acceptance'}
}
