import {readFileSync,realpathSync,statSync,existsSync,writeFileSync} from 'node:fs'
import {resolve,relative,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest,sameJson,frozenFileDigest} from './evaluation.mjs'
import {collectionStatus} from './upgrade-collection-status.mjs'
import {verifySelectedEvents} from './upgrade-event-evidence.mjs'
import {stage2Cases,stage2Team} from './stage2-regression.mjs'

export function verifyStage2(bytes,readEvents,specificationSha256) {
  const report=JSON.parse(bytes),cases=stage2Cases()
  if(report.suiteKind!=='STAGE2_BUSINESS_REGRESSION'||report.split!=='dev'
      ||report.datasetSha256!==digest(cases)||report.testSpecification?.sha256!==specificationSha256
      ||report.testSpecification?.file!=='document/测试文档/测试文档_阶段2_资料与队伍问答.md'
      ||report.records?.length!==14||!sameJson(report.selectedCaseIds,cases.map(item=>item.id)))
    throw Error('STAGE2_SPECIFICATION_MISMATCH')
  if(collectionStatus(report,cases).exitCode!==0)throw Error('COLLECTION_INCOMPLETE')
  const team=report.testTeam
  if(!team?.before||!Array.isArray(team.before.members)||!Number.isSafeInteger(team.before.id)||team.before.id<1)
    throw Error('TEST_TEAM_REQUIRED')
  const actual={name:team.before.name,rulesetId:team.before.rulesetId,dataReleaseId:team.before.dataReleaseId,
    members:team.before.members.map(({formId,slot,build})=>({formId,slot,build}))}
  if(!sameJson(actual,stage2Team()))throw Error('TEST_TEAM_FIXTURE_MISMATCH')
  if(team.unchanged!==true||!sameJson(team.before,team.after)
      ||report.records.some(row=>row.teamUnchanged!==true||row.teamSnapshotSha256!==digest(team.before)))
    throw Error('TEST_TEAM_SNAPSHOT_MISMATCH')
  const integrity=verifySelectedEvents(report,readEvents)
  const forbidden=new Set(['previewTeamChanges','applyConfirmedTeamChanges','writeFile','terminal','executeCommand','downloadFile'])
  const toolCalls=[]
  for(const row of report.records)for(const turn of row.turns){
    const events=JSON.parse(readEvents(turn.eventsFile))
    for(const event of events.filter(event=>event.type==='tool.started')){
      if(forbidden.has(event.payload?.toolName))throw Error('READ_ONLY_STAGE_EXECUTION_VIOLATION')
      toolCalls.push({caseId:row.caseId,runId:turn.runId,toolName:event.payload?.toolName})
    }
    if(events.some(event=>event.type==='approval.required'))throw Error('READ_ONLY_STAGE_APPROVAL_VIOLATION')
  }
  return {schemaVersion:'stage2-evidence-v1',status:'VERIFIED',scope:'STAGE2_DEVELOPMENT_EVIDENCE_ONLY',
    qualityReview:'REQUIRED',releaseDecision:'NOT_EVALUATED',baselineSha256:digest(bytes),
    cases:14,integrity,testTeamSnapshotSha256:digest(team.before),toolCalls}
}

function artifact(file) {
  const actual=realpathSync(resolve(file)),within=relative(realpathSync('artifacts'),actual)
  if(!within||within.startsWith('..')||isAbsolute(within)||statSync(actual).size>16777216)throw Error('BOUNDED_ARTIFACT_REQUIRED')
  return readFileSync(actual,'utf8')
}
function main(){
  const [directory,output]=process.argv.slice(2)
  if(!directory||!output)throw Error('Usage: upgrade-stage2-verify.mjs artifacts/COLLECTION artifacts/NEW_REPORT.json')
  const destination=resolve(output)
  if(existsSync(destination)||realpathSync(resolve(destination,'..'))!==realpathSync('artifacts'))throw Error('NEW_ARTIFACT_REPORT_REQUIRED')
  const specificationSha256=frozenFileDigest('document/测试文档/测试文档_阶段2_资料与队伍问答.md')
  const result=verifyStage2(artifact(resolve(directory,'baseline.json')),name=>artifact(resolve(directory,name)),specificationSha256)
  writeFileSync(destination,JSON.stringify(result,null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify({status:result.status,cases:result.cases,turns:result.integrity.turns,qualityReview:result.qualityReview}))
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))main()
