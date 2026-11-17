import assert from 'node:assert/strict'
import {readFileSync,writeFileSync,mkdirSync,existsSync,realpathSync} from 'node:fs'
import {resolve,dirname,relative,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {loadFrozenSuite,publicFingerprint} from './upgrade-baseline.mjs'
import {digest,frozenFileDigest,localBase} from './evaluation.mjs'
import {localEvaluationSession} from './local-evaluation-session.mjs'
import {coreComponents,additionalComponents,componentManifestVersion} from './runtime-component-manifest.mjs'

export function heldoutPlan(suite,corpus) {
  const cases=suite.cases.filter(row=>row.split==='heldout')
  assert.equal(cases.length,20)
  const documents=new Set(corpus.documents.map(row=>row.id))
  for(const row of cases)for(const id of row.expected.relevantDocumentIds)assert.ok(documents.has(id),'SOURCE_LABEL_MISSING')
  return {datasetSha256:suite.datasetSha256,caseFileSha256:suite.manifest.files['cases.json'],
    rubricSha256:suite.manifest.files['rubric.json'],historicalCorpus:suite.manifest.corpus,
    cases:cases.map(row=>({caseId:row.id,category:row.category,turns:row.turns.length})),
    questionCount:cases.length,turnCount:cases.reduce((sum,row)=>sum+row.turns.length,0),
    scope:'CANDIDATE_QUALITY_WITH_FROZEN_QUESTIONS_AND_RUBRIC_NOT_A_CONTROLLED_CORPUS_ABLATION'}
}
async function main() {
  const [directory,url,classesDirectory]=process.argv.slice(2)
  assert.ok(directory&&url&&classesDirectory)
  const artifacts=realpathSync('artifacts'),output=resolve(directory),classes=realpathSync(classesDirectory)
  assert.equal(dirname(output),artifacts);assert.equal(existsSync(output),false)
  const within=relative(artifacts,classes);assert.ok(within&&!within.startsWith('..')&&!isAbsolute(within))
  mkdirSync(output)
  const session=localEvaluationSession(localBase(url)),api=session.api
  const report={schemaVersion:'heldout-acceptance-preflight-v1',status:'RUNNING',startedAt:new Date().toISOString(),
    modelAnswersSubmitted:0,qualityReview:'NOT_STARTED',releaseDecision:'NOT_EVALUATED'}
  let authenticated=false
  try {
    const corpusFile='src/main/resources/pokemon-strategy/overview-v1.json'
    report.plan=heldoutPlan(loadFrozenSuite(),JSON.parse(readFileSync(corpusFile,'utf8')))
    await session.login('独立验收预检');authenticated=true
    const runtime=await api('/chat/runtime')
    report.configuration=publicFingerprint(runtime)
    report.componentManifestVersion=componentManifestVersion
    report.components=[...coreComponents,...additionalComponents].map(([name,folder])=>{
      const expected=digest(readFileSync(resolve(classes,'com/vio/vioaiagent',folder,name+'.class')))
      assert.equal(runtime.implementationSha256[name],expected,'RUNTIME_COMPONENT_MISMATCH_'+name)
      return {name,sha256:expected}
    })
    report.candidateCorpus=await api('/pokemon/guides/catalog')
    assert.equal(report.candidateCorpus.corpusSha256,frozenFileDigest(corpusFile))
    report.index=await api('/pokemon/guides/index');report.semantic=await api('/pokemon/guides/semantic')
    report.status='READY_FOR_FIRST_ANSWER_COLLECTION'
  }catch(error){report.status='FAIL';report.failure=String(error.message).slice(0,200);process.exitCode=1}
  finally {
    if(authenticated)try{await session.logout();report.cleanup='LOGGED_OUT'}
    catch{report.status='FAIL';report.cleanup='LOGOUT_FAILED';process.exitCode=1}
    report.finishedAt=new Date().toISOString()
    writeFileSync(resolve(output,'preflight.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
    console.log(JSON.stringify({status:report.status,questions:report.plan?.questionCount,
      turns:report.plan?.turnCount,modelAnswersSubmitted:report.modelAnswersSubmitted,failure:report.failure}))
  }
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))await main()
