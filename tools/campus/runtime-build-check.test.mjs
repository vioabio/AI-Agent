import test from 'node:test'
import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {spawn} from 'node:child_process'
import {createHash} from 'node:crypto'
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,realpathSync,rmSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join,resolve} from 'node:path'
import {coreComponents,additionalComponents,componentManifestVersion} from './runtime-component-manifest.mjs'

async function compareFixture(change,{logoutStatus=204,runtimeStatus=200}={}){
 const parent=realpathSync(tmpdir()),directory=mkdtempSync(join(parent,'vio-runtime-build-')),classes=join(directory,'classes'),output=join(directory,'report.json')
 const components=[...coreComponents,...additionalComponents]
 const hashes={}
 for(const [name,folder]of components){const path=join(classes,'com/vio/vioaiagent',folder);mkdirSync(path,{recursive:true});const bytes=Buffer.from('synthetic class fixture '+name);writeFileSync(join(path,name+'.class'),bytes);hashes[name]=createHash('sha256').update(bytes).digest('hex')}
 change?.(hashes)
 const server=createServer((request,response)=>{
  request.resume();response.setHeader('Content-Type','application/json')
  if(request.url==='/api/auth/session')return response.end(JSON.stringify({mode:'local'}))
  if(request.url==='/api/auth/local/register'){response.statusCode=201;return response.end('{}')}
  if(request.url==='/api/auth/local/login')return response.end('{}')
  if(request.url==='/api/chat/runtime'){response.statusCode=runtimeStatus;return response.end(JSON.stringify({implementationSha256:hashes}))}
  if(request.url==='/api/logout'){response.statusCode=logoutStatus;return response.end('{}')}
  response.statusCode=404;response.end('{}')
 })
 try{
  await new Promise(done=>server.listen(0,'127.0.0.1',done))
  const child=spawn(process.execPath,[resolve('tools/campus/runtime-build-check.mjs'),`http://127.0.0.1:${server.address().port}/api`,classes,output],{stdio:['ignore','pipe','pipe']})
  let stderr='';child.stdout.resume();child.stderr.on('data',chunk=>stderr+=chunk)
  const code=await new Promise((done,reject)=>{child.on('error',reject);child.on('close',done)})
  const report=JSON.parse(readFileSync(output,'utf8'))
  assert.equal(code,report.status==='ARTIFACTS_MATCH'?0:report.status==='STALE_OR_DIFFERENT_ARTIFACTS'?1:2,stderr)
  return report
 }finally{
  server.closeAllConnections();await new Promise(done=>server.close(done))
  assert.ok(realpathSync(directory).startsWith(join(parent,'vio-runtime-build-')))
  rmSync(directory,{recursive:true,force:true})
 }
}
test('CLI compares six core classes plus guide and all training components',{timeout:20000},async()=>{
 const report=await compareFixture()
 assert.equal(report.status,'ARTIFACTS_MATCH');assert.equal(report.classes.length,6)
 assert.equal(report.schemaVersion,'runtime-artifact-comparison-v3');assert.equal(report.cleanup,'LOGGED_OUT')
 assert.deepEqual(report.additionalComponents.map(row=>row.name),additionalComponents.map(([name])=>name))
 assert.equal(report.componentManifestVersion,componentManifestVersion)
 assert.ok(report.additionalComponents.every(row=>row.matches))
})

test('logout and diagnostic failures are blocked rather than successful build checks',{timeout:20000},async()=>{
 const logout=await compareFixture(null,{logoutStatus:500})
 assert.equal(logout.status,'CHECK_BLOCKED');assert.equal(logout.cleanup,'LOGOUT_UNVERIFIED')
 const unavailable=await compareFixture(null,{runtimeStatus:503})
 assert.equal(unavailable.status,'CHECK_BLOCKED');assert.equal(unavailable.reason,'HTTP_503')
 assert.equal(unavailable.cleanup,'LOGGED_OUT')
})

test('nested damage source validator is required for current card evidence coverage',async()=>{
 for(const name of ['EvidenceAnswerBlock$Source','LocalDamageEngine$Combatant']){
  const report=await compareFixture(hashes=>delete hashes[name])
  assert.equal(report.status,'STALE_OR_DIFFERENT_ARTIFACTS')
  assert.equal(report.additionalComponents.find(row=>row.name===name).matches,false)
 }
})
const requiredHelpers = [
 'SavedTrainingResources', 'TrainingIntentState', 'TrainingTargetCheck', 'TrainingLearningCheck',
 'TrainingResourceBudget', 'SavedTrainingGymReview', 'TrainingStatProjection', 'TrainingLevelConstraints',
 'StaticStatCalculator', 'TrainingProgressCheck', 'GymResourcePolicy', 'GuideRevisionPolicy',
 'TeamMutationIntent', 'ReferenceRulesReply', 'GymFocusedFactReply', 'AgentToolObservation',
 'AgentTracingConfiguration', 'ToolExecutionGateway', 'TaskSkillRegistry', 'TaskContextAssembler',
 'GuideReferenceIdentity', 'LocalPokeApiRepository', 'RegionalFormName'
]
// Give each child-process check its own deadline and failure identity.
for (const name of requiredHelpers) {
 test('missing '+name+' cannot pass when core classes match', {timeout:30000}, async()=>{
  const report=await compareFixture(hashes=>delete hashes[name])
  assert.equal(report.status,'STALE_OR_DIFFERENT_ARTIFACTS')
  assert.ok(report.classes.every(row=>row.matches))
  assert.equal(report.additionalComponents.find(row=>row.name===name).matches,false)
 })
}
test('stale training roadmap cannot pass when core classes match', {timeout:30000}, async()=>{
 const report=await compareFixture(hashes=>hashes.SavedTrainingRoadmap='0'.repeat(64))
 assert.equal(report.status,'STALE_OR_DIFFERENT_ARTIFACTS')
 assert.ok(report.classes.every(row=>row.matches))
 assert.equal(report.additionalComponents.find(row=>row.name==='SavedTrainingRoadmap').matches,false)
})
