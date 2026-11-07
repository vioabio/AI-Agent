import test from 'node:test'
import assert from 'node:assert/strict'
import { formatChecks, auditQuality, observationHash, generationFrom } from './answer-quality.mjs'
test('local generation requires an observed complete zero, missing usage stays unknown',()=>{
 assert.deepEqual(generationFrom([]),{generationKind:'UNKNOWN',modelCalls:0})
 const zero={coverage:'INSTRUMENTED',usageStatus:'COMPLETE',modelCallCount:0}
 assert.equal(generationFrom([zero]).generationKind,'LOCAL')
 assert.equal(generationFrom([{...zero,coverage:'NOT_INSTRUMENTED'}]).generationKind,'UNKNOWN')
 assert.equal(generationFrom([{...zero,usageStatus:'UNKNOWN'}]).generationKind,'UNKNOWN')
 assert.deepEqual(generationFrom([zero,{...zero,modelCallCount:2}]),{generationKind:'MODEL',modelCalls:2})
})
test('bounded format checks catch the recorded long greeting while keeping useful battle numbers',()=>{
 const expected={format:'one_sentence',maxCharacters:180}
 assert.equal(formatChecks(expected,'火属性招式攻击草属性目标时的基础倍率为2倍。').pass,true)
 assert.equal(formatChecks(expected,'你好！\n✅ 查属性\n✅ 推荐队伍。').pass,false)
 assert.equal(formatChecks(expected,'我可以帮你。另一句解释。').pass,false)
 assert.equal(formatChecks(expected,'当前规则是casual-v1。').failures.includes('INTERNAL_IDENTIFIER_LEAK'),true)
 assert.equal(formatChecks({format:'two_items',maxCharacters:340},'1. 查询资料。\n2. 理解培养建议。').pass,true)
})
function fixture() {
 const preferences={revision:1,responseStyle:'CONCISE',trainingBudget:'LOW',avoidLegendary:true,notes:'新手'},answer='你好，我可以帮你理解资料。'
 const item={id:'case',prompt:'你好',expected:{format:'one_sentence',maxCharacters:180,noTools:true},semanticCriteria:['实际回应用户。']}
 const record={caseId:'case',repetition:1,status:'COMPLETED',runId:'run-fixture',answer,preferences,eventsSha256:'events',historySha256:'history'}
 const capture={schemaVersion:'answer-quality-capture-v1',plan:{cases:[item],repetitions:1},records:[record]}
 const events=[{type:'answer.delta',payload:{text:answer}},{type:'run.completed',payload:{}}]
 const snapshot={...preferences,schemaVersion:'conversation-constraints-v1',source:'USER_CONFIRMED'}
 const history={prompt:'你好',answer,userPreferences:snapshot,answerPreferences:snapshot,attempts:[]}
 return {capture,record,events,history}
}
test('mechanical pass never creates human task success or drops missing records',()=>{
 const f=fixture(),result=auditQuality(f.capture,()=>f.events,()=>f.history)
 assert.equal(result.mechanicalPass,1);assert.equal(result.verifiedTaskPass,0);assert.equal(result.pendingReview,1)
 const missing=auditQuality(f.capture,()=>{throw Error('missing')},()=>f.history)
 assert.equal(missing.denominator,1);assert.equal(missing.fail,1)
 assert.throws(()=>auditQuality({...f.capture,records:[]},()=>[],()=>null),/denominator/)
})
test('unrequested tools, altered answers and preference snapshots cannot pass',()=>{
 const f=fixture()
 const tools=[...f.events,{type:'tool.finished',payload:{governanceEventId:'g',toolName:'readCurrentTeam',status:'SUCCEEDED'}}]
 assert.equal(auditQuality(f.capture,()=>tools,()=>f.history).mechanicalPass,0)
 assert.equal(auditQuality(f.capture,()=>f.events,()=>({...f.history,userPreferences:{...f.history.userPreferences,notes:'changed'}})).mechanicalPass,0)
 assert.equal(auditQuality(f.capture,()=>f.events,()=>({...f.history,userPreferences:{...f.history.userPreferences,source:'MODEL_INFERRED'}})).mechanicalPass,0)
 assert.equal(auditQuality({...f.capture,records:[{...f.record,answer:'changed'}]},()=>f.events,()=>f.history).mechanicalPass,0)
})
test('bound AI-assisted reviews stay pending and stale or duplicate reviews fail',()=>{
 const f=fixture(),review={caseId:'case',repetition:1,observationSha256:observationHash(f.record),reviewerType:'ai-assisted',reviewer:'fixture assistant',reviewedAt:'2026-10-25',note:'synthetic parser test',decisions:[{criterion:1,verdict:'pass',reason:'fixture'}]}
 assert.equal(auditQuality(f.capture,()=>f.events,()=>f.history,[review]).verifiedTaskPass,0)
 assert.equal(auditQuality(f.capture,()=>f.events,()=>f.history,[{...review,reviewerType:'human'}]).verifiedTaskPass,1) // Synthetic parser fixture, never a real review.
 assert.throws(()=>auditQuality(f.capture,()=>f.events,()=>f.history,[{...review,observationSha256:'changed'}]),/review/)
 assert.throws(()=>auditQuality(f.capture,()=>f.events,()=>f.history,[review,review]),/Duplicate/)
})
