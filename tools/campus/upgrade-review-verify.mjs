import {readFileSync,statSync,realpathSync,writeFileSync,existsSync} from 'node:fs'
import {resolve,relative,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest} from './evaluation.mjs'
import {loadFrozenSuite,root} from './upgrade-baseline.mjs'
import {matchesCurrentBuild} from './runtime-component-manifest.mjs'

/** Evidence integrity only: does not judge answer quality or confer release approval. */
export function verifyDevelopmentReview({baselineBytes,review,readEvents,expectedCases,datasetSha256,comparison}){
 const baseline=JSON.parse(baselineBytes),errors=[]
 const check=(condition,code)=>{if(!condition)errors.push(code)}
 check(baseline.schemaVersion==='upgrade-baseline-v1'&&baseline.split==='dev','DEVELOPMENT_BASELINE_REQUIRED')
 check(review.schemaVersion==='u0-development-content-review-v1'&&review.scope==='DEVELOPMENT_SET_NOT_INDEPENDENT_HELDOUT_ACCEPTANCE','REVIEW_SCOPE_REQUIRED')
 check(review.reviewMethod==='AI_ASSISTED_BODY_SOURCE_AND_TOOL_EVENT_REVIEW','REVIEW_METHOD_REQUIRED')
 check(review.sourceSha256===digest(baselineBytes),'BASELINE_HASH_MISMATCH')
 check(baseline.datasetSha256===datasetSha256,'DATASET_IDENTITY_MISMATCH')
 check(baseline.mode==='COLLECTED_NOT_REVIEWED'&&Array.isArray(baseline.cleanupErrors)&&baseline.cleanupErrors.length===0,'COLLECTION_OR_CLEANUP_INCOMPLETE')
 const requiredClasses=new Set(['AccountScopeReply','DefaultRunExecutor','ToolCallAgent','GuideAnswerEvidence','VioManus'])
 check(comparison?.additionalComponents===undefined||Array.isArray(comparison.additionalComponents)&&comparison.additionalComponents.every(c=>c&&c.matches===true&&/^[a-f0-9]{64}$/.test(c.expected)&&c.actual===c.expected),'ADDITIONAL_BUILD_COMPONENT_MISMATCH')
 const currentFormat=comparison?.schemaVersion==='runtime-artifact-comparison-v3'
 if(comparison?.schemaVersion==='runtime-artifact-comparison-v2')requiredClasses.add('PokemonAgentRunService')
 const legacyBuild=['runtime-artifact-comparison-v1','runtime-artifact-comparison-v2'].includes(comparison?.schemaVersion)&&Array.isArray(comparison?.classes)&&comparison.classes.length===requiredClasses.size&&new Set(comparison.classes.map(c=>c?.name)).size===requiredClasses.size&&comparison.status==='ARTIFACTS_MATCH'&&comparison.classes.every(c=>c&&requiredClasses.has(c.name)&&c.matches===true&&/^[a-f0-9]{64}$/.test(c.expected)&&c.expected===c.actual)
 check(currentFormat?matchesCurrentBuild(comparison):legacyBuild,'BUILD_COMPARISON_UNVERIFIED')
 const records=Array.isArray(baseline.records)?baseline.records:[],reviews=Array.isArray(review.records)?review.records:[]
 const expected=new Map(expectedCases.map(item=>[item.id,item])),selected=baseline.selectedCaseIds||[]
 check(records.length===expected.size&&reviews.length===expected.size&&selected.length===expected.size&&new Set(selected).size===expected.size&&selected.every(id=>expected.has(id)),'INCOMPLETE_CASE_COVERAGE')
 check(new Set(records.map(item=>item.caseId)).size===records.length&&new Set(reviews.map(item=>item.caseId)).size===reviews.length,'DUPLICATE_CASE_ID')
 const counts={PASS:0,FAIL:0,BLOCKED:0},seen=new Set()
 let turns=0
 for(const record of records){
  const item=expected.get(record.caseId),assessment=reviews.find(row=>row.caseId===record.caseId)
  check(Boolean(item)&&Boolean(assessment),'CASE_REVIEW_MISSING');if(!item||!assessment)continue
  seen.add(record.caseId)
  check(Object.hasOwn(counts,assessment.verdict)&&typeof assessment.note==='string'&&assessment.note.trim().length>0,'REVIEW_VERDICT_OR_REASON_REQUIRED')
  if(Object.hasOwn(counts,assessment.verdict))counts[assessment.verdict]++
  const evidence=Array.isArray(assessment.evidence)?assessment.evidence:[],observed=Array.isArray(record.turns)?record.turns:[]
  check(observed.length===item.turns.length&&evidence.length===item.turns.length,'INCOMPLETE_TURN_COVERAGE')
  for(let i=0;i<observed.length;i++){
   const turn=observed[i],binding=evidence[i];turns++
   check(turn.prompt===item.turns[i],'PROMPT_CHANGED')
   check(Boolean(binding)&&binding.runId===turn.runId&&binding.eventsFile===turn.eventsFile&&binding.eventsSha256===turn.eventsSha256&&binding.answerSha256===digest(turn.answer||''),'REVIEW_BINDING_MISMATCH')
   if(assessment.verdict==='PASS')check(turn.status==='COMPLETED'&&typeof turn.answer==='string'&&turn.answer.trim().length>0,'PASS_REQUIRES_COMPLETE_ANSWER')
   try{
    if(!/^[A-Z0-9-]+-\d+-events\.json$/.test(turn.eventsFile||''))throw Error()
    const bytes=readEvents(turn.eventsFile);check(digest(bytes)===turn.eventsSha256,'EVENT_HASH_MISMATCH')
    const events=JSON.parse(bytes);if(!Array.isArray(events))throw Error()
    check(events.filter(event=>event.type==='answer.delta').map(event=>event.payload?.text||'').join('')===turn.answer,'ANSWER_EVENT_MISMATCH')
   }catch{errors.push('EVENT_FILE_INVALID_OR_UNAVAILABLE')}
  }
 }
 check(seen.size===expected.size,'EXPECTED_CASE_MISSING')
 check(review.summary?.total===expected.size&&review.summary?.pass===counts.PASS&&review.summary?.fail===counts.FAIL&&review.summary?.blocked===counts.BLOCKED,'REVIEW_SUMMARY_MISMATCH')
 return {schemaVersion:'upgrade-review-integrity-v1',status:errors.length?'INVALID':'VERIFIED',buildCoverage:currentFormat?(matchesCurrentBuild(comparison)?'CURRENT_COMPONENT_MANIFEST':'CURRENT_MANIFEST_UNVERIFIED'):'LEGACY_ARCHIVE_ONLY',scope:'DEVELOPMENT_REVIEW_INTEGRITY_ONLY',releaseDecision:'NOT_EVALUATED',reviewMethod:review.reviewMethod,recordedVerdicts:counts,turns,errors:[...new Set(errors)],baselineSha256:digest(baselineBytes)}
}
function readArtifact(path){
 const directory=realpathSync(resolve(root,'artifacts')),file=realpathSync(resolve(path)),within=relative(directory,file)
 if(!within||within.startsWith('..')||isAbsolute(within)||statSync(file).size>16777216)throw Error('BOUNDED_ARTIFACT_REQUIRED')
 return readFileSync(file,'utf8')
}
function run(){
 const [directory,output]=process.argv.slice(2)
 if(!directory||!output)throw Error('Usage: upgrade-review-verify.mjs artifacts/DEV_DIRECTORY artifacts/NEW_REPORT.json')
 const destination=resolve(output),within=relative(resolve(root,'artifacts'),destination)
 if(!within||within.startsWith('..')||isAbsolute(within)||existsSync(destination)||realpathSync(resolve(destination,'..'))!==realpathSync(resolve(root,'artifacts')))throw Error('NEW_ARTIFACT_REPORT_REQUIRED')
 const baselineBytes=readArtifact(resolve(directory,'baseline.json')),review=JSON.parse(readArtifact(resolve(directory,'content-review.json'))),suite=loadFrozenSuite()
 const result=verifyDevelopmentReview({baselineBytes,review,readEvents:name=>readArtifact(resolve(directory,name)),expectedCases:suite.cases.filter(item=>item.split==='dev'),datasetSha256:suite.datasetSha256,comparison:JSON.parse(readArtifact(resolve(root,review.artifactComparison)))})
 writeFileSync(destination,JSON.stringify(result,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify(result));if(result.status!=='VERIFIED')process.exitCode=1
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))run()
