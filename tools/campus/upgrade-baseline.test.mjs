import {test} from 'node:test'
import assert from 'node:assert/strict'
import {mkdtempSync,cpSync,readFileSync,writeFileSync,rmSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {loadFrozenSuite,suitePath,publicFingerprint,prepare,collectionFailure} from './upgrade-baseline.mjs'

test('frozen dataset balances five categories and development/heldout',()=>{
  const s=loadFrozenSuite();assert.equal(s.cases.length,40)
  assert.equal(s.cases.filter(c=>c.split==='heldout').length,20)
})
test('content mutation cannot silently reuse frozen identity',()=>{
  const dir=mkdtempSync(join(tmpdir(),'vio-u0-'))
  try{cpSync(suitePath,dir,{recursive:true});writeFileSync(join(dir,'cases.json'),readFileSync(join(dir,'cases.json'),'utf8').replace('胖丁','皮卡丘'));assert.throws(()=>loadFrozenSuite(dir),/mismatch/)}finally{rmSync(dir,{recursive:true,force:true})}
})
test('configuration fingerprint drops secrets even inside nested allowed sections',()=>{
  const result=publicFingerprint({apiKey:'secret-value',configuredModelId:'deepseek-chat',features:{answerStreaming:true,apiKey:'hidden'},agentCollaboration:{specialistsEnabled:true,password:'nested'},contextBudgetPolicy:{token:'hidden',contextTokens:32768}})
  const text=JSON.stringify(result);assert.doesNotMatch(text,/secret-value|password|hidden|nested/)
  assert.equal(result.configuration.agentCollaboration.specialistsEnabled,true)
})
test('fingerprint changes on public execution switches',()=>{
  assert.notEqual(publicFingerprint({features:{answerStreaming:true}}).sha256,publicFingerprint({features:{answerStreaming:false}}).sha256)
})
test('preparation rejects outside evidence directory and invalid split',()=>{
  assert.throws(()=>prepare('../outside','dev'),/under artifacts/)
  assert.throws(()=>prepare('artifacts/invalid','all'),/split/)
})

test('diagnostics distinguish HTTP500 from connection failures without copying provider body',()=>{
 assert.equal(collectionFailure(new Error('HTTP_500')),'HTTP_500')
 assert.equal(collectionFailure({cause:{code:'ECONNREFUSED'}}),'TRANSPORT_ECONNREFUSED')
 assert.equal(collectionFailure(new Error('private credential detail')),'COLLECTION_UNAVAILABLE')
})

test('upgrade switches are fingerprinted but arbitrary nested data is dropped',()=>{
 const observed=publicFingerprint({upgradeFeatures:{answerBlocks:true,skills:false,apiKey:'private'}})
 assert.deepEqual(observed.configuration.upgradeFeatures,{answerBlocks:true,skills:false})
 assert.notEqual(observed.sha256,publicFingerprint({upgradeFeatures:{answerBlocks:false,skills:false}}).sha256)
})
