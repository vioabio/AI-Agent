import test from 'node:test'
import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {coreComponents,additionalComponents} from './runtime-component-manifest.mjs'
import {combinationFeatures} from './upgrade-combination-verify.mjs'
import {verifyBrowserCombination} from './stage6-browser-evidence.mjs'

function fixture() {
  const files=new Map(),build=runtimeBuild({implementationSha256:Object.fromEntries([...coreComponents,...additionalComponents].map(([key])=>[key,'a'.repeat(64)]))})
  function group(mode) {
    const config={upgradeFeatures:Object.fromEntries(combinationFeatures.map(key=>[key,mode==='enabled']))}
    const runtime={configuration:config,sha256:digest(config)},team={members:[{build:{level:51}}]},changed={members:[{build:{level:52}}]}
    const base={runtime,runtimeAfter:structuredClone(runtime),runtimeBuild:build,runtimeBuildAfter:build,pageErrors:[],cleanup:['LOGGED_OUT'],guideCatalog:{id:'fixture'},semanticState:{ready:true,enabled:true,configured:true,mode:'HYBRID',model:'fixture',dimensions:2,indexedChunks:2},producerSha256:'b'.repeat(64)}
    const event=(kind,label,terminal)=>{
      const runId='run-'+mode+'-'+kind+'-'+label,file=label+'-events.json'
      const bytes=JSON.stringify([{runId,eventId:1,type:'answer.delta',payload:{text:'正文'}},{runId,eventId:2,type:terminal}]);files.set(mode+kind+file,bytes)
      return {runId,file,sha256:digest(bytes),terminal}
    }
    const cards={...base,schemaVersion:'provider-cards-browser-v1',status:'PASS',mode,teamMutationRequests:[],frontendComponentsSha256:{fixture:'same'},records:Array.from({length:4},(_,i)=>{
      const ref=event('cards','card'+i,'run.completed');return {checks:'PASS',narrowViewportChecked:true,turn:{runId:ref.runId,eventsFile:ref.file,eventsSha256:ref.sha256,answer:'正文'}}
    })}
    const hitl={...base,schemaVersion:'stage6-hitl-browser-v1',status:'COLLECTED_PENDING_CONTENT_REVIEW',teamBefore:team,restoredTeam:team,records:[false,true].map(approved=>{
      const ref=event('hitl',approved?'approved':'denied',approved?'run.completed':'run.failed')
      return {approved,runId:ref.runId,eventsFile:ref.file,eventsSha256:ref.sha256,outcome:{status:approved?'APPLIED':'DENIED'},decision:{approved,approvalId:'proposal'},preview:{preview:{proposalId:'proposal'}},after:approved?changed:team}
    })}
    const first=event('governance','first','run.completed'),stale=event('governance','stale','run.completed')
    const ref=row=>({runId:row.runId,eventsFile:row.file,eventsSha256:row.sha256})
    const governance={...base,schemaVersion:'stage6-conflict-account-browser-v1',status:'COLLECTED_PENDING_CONTENT_REVIEW',scope:'governance',sourceSha256:{fixture:'same'},teamBefore:team,restoredTeam:team,records:[
      {caseId:'S6-06',first:ref(first),stale:ref(stale),applied:{status:'APPLIED'},conflict:{status:'CONFLICT'},teamAfter:changed},
      {caseId:'S6-04',browserSwitch:true,privateContentHidden:true,ownTeamsOnly:true,nextConversationId:'new',oldConversationId:'old',accountEventFiles:[event('governance','seed','run.completed'),event('governance','next','run.completed')]}
    ]}
    return {cards,hitl,governance}
  }
  return {enabled:group('enabled'),disabled:group('disabled'),read:(mode,kind,file)=>files.get(mode+kind+file)}
}
test('browser combination binds twenty Runs, decisions, conflicts and account-switch evidence',()=>{
  const f=fixture(),report=verifyBrowserCombination(f.enabled,f.disabled,f.read)
  assert.equal(report.runs,20);assert.equal(report.qualityReview,'REQUIRED')
})
test('changed previews, overwritten teams, stale source code or missing narrow checks are rejected',()=>{
  for(const change of [f=>f.disabled.hitl.records[0].after={members:[{build:{level:52}}]},
    f=>f.disabled.governance.records[0].teamAfter={members:[{build:{level:53}}]},
    f=>f.disabled.cards.producerSha256='c'.repeat(64),f=>f.enabled.cards.records[0].narrowViewportChecked=false]) {
    const f=fixture();change(f);assert.throws(()=>verifyBrowserCombination(f.enabled,f.disabled,f.read))
  }
})
