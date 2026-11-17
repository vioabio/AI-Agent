import {readFileSync,realpathSync,statSync,existsSync,writeFileSync} from 'node:fs'
import {resolve,relative,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest,sameJson,frozenFileDigest} from './evaluation.mjs'
import {collectionStatus} from './upgrade-collection-status.mjs'
import {verifySelectedEvents} from './upgrade-event-evidence.mjs'
import {stage4Cases} from './stage4-regression.mjs'
import {stage2Team} from './stage2-regression.mjs'

export function verifyStage4(bytes,readEvents,specificationSha256) {
 const report=JSON.parse(bytes),cases=stage4Cases()
 if(report.suiteKind!=='STAGE4_CONVERSATION_REGRESSION'||report.split!=='dev'
   ||report.datasetSha256!==digest(cases)||report.testSpecification?.sha256!==specificationSha256
   ||report.testSpecification?.file!=='document/测试文档/测试文档_阶段4_连续对话与历史状态.md'
   ||report.records?.length!==4||!sameJson(report.selectedCaseIds,cases.map(row=>row.id))
   ||report.semanticCases!==16||report.fillerTurns!==9)throw Error('STAGE4_SPECIFICATION_MISMATCH')
 if(collectionStatus(report,cases).exitCode!==0)throw Error('COLLECTION_INCOMPLETE')
 const team=report.testTeam,before=team?.before
 if(!before||!Array.isArray(before.members)||!Number.isSafeInteger(before.id)||before.id<1)throw Error('TEST_TEAM_REQUIRED')
 const fixture={name:before.name,rulesetId:before.rulesetId,dataReleaseId:before.dataReleaseId,members:before.members.map(({formId,slot,build})=>({formId,slot,build}))}
 if(!sameJson(fixture,stage2Team())||team.unchanged!==true||!sameJson(before,team.after)
  ||report.records.some(row=>row.teamUnchanged!==true||row.teamSnapshotSha256!==digest(before)))throw Error('TEST_TEAM_SNAPSHOT_MISMATCH')
 const integrity=verifySelectedEvents(report,readEvents),conversations=new Set(),turns=[]
 for(const row of report.records) {
  const expected=cases.find(item=>item.id===row.caseId),ids=new Set()
  for(const [index,turn] of row.turns.entries()) {
   if(turn.semanticCaseId!==expected.turnCaseIds[index])throw Error('SEMANTIC_CASE_MAPPING_MISMATCH')
   const events=JSON.parse(readEvents(turn.eventsFile))
   if(typeof turn.conversationId!=='string'||!turn.conversationId)throw Error('CONVERSATION_ID_REQUIRED')
   ids.add(turn.conversationId)
   if(events.some(e=>e.type==='tool.started'&&['previewTeamChanges','applyConfirmedTeamChanges','writeFile','terminal','executeCommand'].includes(e.payload?.toolName)))throw Error('STAGE4_READ_ONLY_VIOLATION')
   turns.push({caseId:turn.semanticCaseId,runId:turn.runId,kind:/^S4-L0/.test(turn.semanticCaseId)?'FILLER':'SEMANTIC'})
  }
  if(ids.size!==1)throw Error('CONVERSATION_CHANGED_WITHIN_GROUP')
  const id=[...ids][0];if(conversations.has(id))throw Error('CONVERSATIONS_NOT_ISOLATED')
  conversations.add(id)
 }
 return {schemaVersion:'stage4-evidence-v1',status:'VERIFIED',conversations:4,semanticCases:16,fillerTurns:9,integrity,turns,
  baselineSha256:digest(bytes),qualityReview:'REQUIRED',releaseDecision:'NOT_EVALUATED'}
}
function artifact(file){
 const actual=realpathSync(resolve(file)),within=relative(realpathSync('artifacts'),actual)
 if(!within||within.startsWith('..')||isAbsolute(within)||statSync(actual).size>16777216)throw Error('BOUNDED_ARTIFACT_REQUIRED')
 return readFileSync(actual,'utf8')
}
function main(){
 const [directory,output]=process.argv.slice(2),destination=resolve(output??'.')
 if(!directory||!output||existsSync(destination)||realpathSync(resolve(destination,'..'))!==realpathSync('artifacts'))throw Error('NEW_ARTIFACT_REPORT_REQUIRED')
 const report=verifyStage4(artifact(resolve(directory,'baseline.json')),name=>artifact(resolve(directory,name)),frozenFileDigest('document/测试文档/测试文档_阶段4_连续对话与历史状态.md'))
 writeFileSync(destination,JSON.stringify(report,null,2)+'\n',{flag:'wx'})
 console.log(JSON.stringify({status:report.status,semanticCases:report.semanticCases,fillerTurns:report.fillerTurns,turns:report.integrity.turns,qualityReview:report.qualityReview}))
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))main()
