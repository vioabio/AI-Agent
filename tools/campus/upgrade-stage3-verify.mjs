import {readFileSync,realpathSync,statSync,existsSync,writeFileSync} from 'node:fs'
import {resolve,relative,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest,sameJson,frozenFileDigest} from './evaluation.mjs'
import {collectionStatus} from './upgrade-collection-status.mjs'
import {verifySelectedEvents} from './upgrade-event-evidence.mjs'
import {stage3Cases,stage3DamageInputs} from './stage3-regression.mjs'
import {stage2Team} from './stage2-regression.mjs'

export function verifyStage3(bytes,readEvents,specificationSha256,referenceSha256) {
 const report=JSON.parse(bytes),cases=stage3Cases()
 if(report.suiteKind!=='STAGE3_STANDARD_REGRESSION'||report.split!=='dev'
   ||report.datasetSha256!==digest(cases)||report.testSpecification?.sha256!==specificationSha256
   ||report.testSpecification?.file!=='document/测试文档/测试文档_阶段3_伤害计算与Agent协作.md'
   ||report.damageReferenceSha256!==referenceSha256||report.records?.length!==10
   ||!sameJson(report.selectedCaseIds,cases.map(row=>row.id)))throw Error('STAGE3_SPECIFICATION_MISMATCH')
 if(collectionStatus(report,cases).exitCode!==0)throw Error('COLLECTION_INCOMPLETE')
 for(const [name,input] of Object.entries(stage3DamageInputs())) {
  const oracle=report.damageOracles?.[name]
  if(!sameJson(oracle?.input,input)||oracle.resultSha256!==digest(oracle.result)
    ||oracle.result.conditionValidation?.status!=='VERIFIED'
    ||!/^[a-f0-9]{64}$/.test(oracle.result.conditionValidation.typedRequestDigest)
    ||!Number.isFinite(oracle.result.range?.minimum)||!Number.isFinite(oracle.result.range?.maximum)
    ||oracle.result.range.minimum<0||oracle.result.range.maximum<oracle.result.range.minimum)throw Error('DAMAGE_ORACLE_INVALID')
 }
 const team=report.testTeam,before=team?.before
 if(!before||!Array.isArray(before.members)||!Number.isSafeInteger(before.id)||before.id<1)throw Error('TEST_TEAM_REQUIRED')
 const fixture={name:before.name,rulesetId:before.rulesetId,dataReleaseId:before.dataReleaseId,members:before.members.map(({formId,slot,build})=>({formId,slot,build}))}
 if(!sameJson(fixture,stage2Team())||team.unchanged!==true||!sameJson(before,team.after)
   ||report.records.some(row=>row.teamUnchanged!==true||row.teamSnapshotSha256!==digest(before)))throw Error('TEST_TEAM_SNAPSHOT_MISMATCH')
 const integrity=verifySelectedEvents(report,readEvents),calculations=[]
 for(const row of report.records)for(const [index,turn] of row.turns.entries()) {
  const events=JSON.parse(readEvents(turn.eventsFile))
  if(events.some(e=>e.type==='tool.started'&&['applyConfirmedTeamChanges','writeFile','terminal','executeCommand'].includes(e.payload?.toolName)))throw Error('UNAUTHORIZED_STAGE3_WRITE')
  const outputs=events.filter(e=>e.type==='tool.finished'&&e.payload?.toolName==='calculateBattleDamage'&&e.payload.status==='SUCCEEDED'&&e.payload.outputEvidence)
  if(['S3-04','S3-05','S3-06','S3-07','S3-09'].includes(row.caseId)&&outputs.length)throw Error('UNEXPECTED_CALCULATION')
  if(row.caseId==='S3-09'&&events.some(e=>e.type==='tool.started'&&e.payload?.toolName==='calculateBattleDamage'))throw Error('FORBIDDEN_CALCULATION')
  const required=index===0&&['S3-01','S3-02','S3-03','S3-08','S3-14'].includes(row.caseId)
  if(required&&!outputs.length)throw Error('CALCULATION_EVIDENCE_MISSING')
  const oracle=report.damageOracles[row.caseId==='S3-08'?'changed':'baseline']
  const observed=new Set()
  for(const event of outputs) {
   const evidence=event.payload.outputEvidence
   if(evidence.status!=='VERIFIED'||evidence.typedRequestDigest!==oracle.result.conditionValidation.typedRequestDigest
     ||evidence.outputDigest!==oracle.resultSha256)throw Error('CALCULATION_ORACLE_MISMATCH')
   const identity=event.payload.governanceEventId
   if(typeof identity!=='string'||!identity)throw Error('CALCULATION_ID_REQUIRED')
   if(!observed.has(identity))calculations.push({caseId:row.caseId,runId:turn.runId,typedRequestDigest:evidence.typedRequestDigest,outputDigest:evidence.outputDigest})
   observed.add(identity)
  }
 }
 return {schemaVersion:'stage3-evidence-v1',status:'VERIFIED',cases:10,integrity,calculations,qualityReview:'REQUIRED',
  baselineSha256:digest(bytes),proceduralCases:'NOT_EXECUTED_BY_THIS_COLLECTOR',releaseDecision:'NOT_EVALUATED'}
}
function artifact(file) {
 const actual=realpathSync(resolve(file)),within=relative(realpathSync('artifacts'),actual)
 if(!within||within.startsWith('..')||isAbsolute(within)||statSync(actual).size>16777216)throw Error('BOUNDED_ARTIFACT_REQUIRED')
 return readFileSync(actual,'utf8')
}
function main(){
 const [directory,output]=process.argv.slice(2),destination=resolve(output??'.')
 if(!directory||!output||existsSync(destination)||realpathSync(resolve(destination,'..'))!==realpathSync('artifacts'))throw Error('NEW_ARTIFACT_REPORT_REQUIRED')
 const report=verifyStage3(artifact(resolve(directory,'baseline.json')),name=>artifact(resolve(directory,name)),
  frozenFileDigest('document/测试文档/测试文档_阶段3_伤害计算与Agent协作.md'),frozenFileDigest('evaluation/campus/s01/damage-reference.json'))
 writeFileSync(destination,JSON.stringify(report,null,2)+'\n',{flag:'wx'})
 console.log(JSON.stringify({status:report.status,cases:report.cases,turns:report.integrity.turns,calculations:report.calculations.length,qualityReview:report.qualityReview}))
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))main()
