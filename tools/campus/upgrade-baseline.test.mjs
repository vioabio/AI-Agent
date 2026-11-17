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

test('domain sampling participates in fingerprints without exposing exporter settings',()=>{
  const original=publicFingerprint({domainTracingPolicy:{sampleRate:0.25,automaticTracing:false,endpoint:'private',headers:{key:'secret'}}})
  assert.deepEqual(original.configuration.domainTracingPolicy,{sampleRate:0.25,automaticTracing:false})
  assert.doesNotMatch(JSON.stringify(original),/private|secret|headers|endpoint/)
  assert.notEqual(original.sha256,publicFingerprint({domainTracingPolicy:{sampleRate:1,automaticTracing:false}}).sha256)
  assert.notEqual(original.sha256,publicFingerprint({domainTracingPolicy:{sampleRate:0.25,automaticTracing:true}}).sha256)
  for(const invalid of [-1,2,NaN,Infinity,'1'])
    assert.deepEqual(publicFingerprint({domainTracingPolicy:{sampleRate:invalid}}).configuration.domainTracingPolicy,{})
})
test('history window and token safety settings participate in the configuration fingerprint',()=>{
  const configured={contextBudgetPolicy:{contextTokens:32768,historyTokens:4096,historyTurns:8,safetyPercent:125}}
  const first=publicFingerprint(configured)
  assert.equal(first.configuration.contextBudgetPolicy.historyTurns,8)
  for(const override of [{historyTurns:1},{safetyPercent:100}])
    assert.notEqual(first.sha256,publicFingerprint({contextBudgetPolicy:{...configured.contextBudgetPolicy,...override}}).sha256)
})
test('preparation rejects outside evidence directory and invalid split',()=>{
  assert.throws(()=>prepare('../outside','dev'),/under artifacts/)
  assert.throws(()=>prepare('artifacts/invalid','all'),/split/)
})

test('diagnostics distinguish HTTP500 from connection failures without copying provider body',()=>{
 assert.equal(collectionFailure(new Error('HTTP_500')),'HTTP_500')
 assert.equal(collectionFailure({cause:{code:'ECONNREFUSED'}}),'TRANSPORT_ECONNREFUSED')
 assert.equal(collectionFailure(new Error('private credential detail')),'COLLECTION_UNAVAILABLE')
 assert.equal(collectionFailure(new Error('RUNTIME_COMPONENTS_UNAVAILABLE')),'RUNTIME_COMPONENTS_UNAVAILABLE')
})

test('upgrade switches are fingerprinted but arbitrary nested data is dropped',()=>{
 const observed=publicFingerprint({upgradeFeatures:{answerBlocks:true,skills:false,apiKey:'private'}})
 assert.deepEqual(observed.configuration.upgradeFeatures,{answerBlocks:true,skills:false})
 assert.notEqual(observed.sha256,publicFingerprint({upgradeFeatures:{answerBlocks:false,skills:false}}).sha256)
})
