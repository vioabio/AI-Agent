import test from 'node:test'
import assert from 'node:assert/strict'
import {matrix,assessPair,resultStatus} from './mcp-http-equivalence.mjs'

const result=value=>({result:{isError:false,content:[{type:'text',text:JSON.stringify(value)}]}})
test('matching wrong facts cannot pass just because both entries agree',()=>{
 const item=matrix.find(x=>x.kind==='fact'),value={fact:{formId:7,hp:999,speed:43,dataReleaseId:'pokeapi-reference-v1'}}
 assert.equal(assessPair(item,{status:200,body:value},result(value)).passed,false)
 value.fact.hp=44;assert.equal(assessPair(item,{status:200,body:value},result(value)).passed,true)
})
test('does not discard provenance, defaults or timestamps to force equivalence',()=>{
 const item=matrix.find(x=>x.kind==='fact'),value={fact:{formId:7,hp:44,speed:43,dataReleaseId:'pokeapi-reference-v1'},retrievedAt:'a',sourceUrl:'public'}
 assert.equal(assessPair(item,{status:200,body:value},result({...value,retrievedAt:'b'})).check,'EXACT_DOMAIN_JSON_MISMATCH')
})
test('authentication failure and server failure cannot count as invalid-input rejection',()=>{
 const item=matrix.find(x=>x.kind==='reject'),mcp={result:{isError:true}}
 for(const status of [200,401,403,404,500])assert.equal(assessPair(item,{status},mcp).passed,false)
 assert.equal(assessPair(item,{status:400},mcp).passed,true)
 assert.equal(assessPair(item,{status:400},result({})).passed,false)
})
test('zero and positive damage must be finite, verified and match the expected boundary',()=>{
 const zero=matrix.find(x=>x.kind==='zero'),positive=matrix.find(x=>x.kind==='positive')
 const value={conditionValidation:{status:'VERIFIED'},range:{minimum:0,maximum:0}}
 assert.equal(assessPair(zero,{status:200,body:value},result(value)).passed,true)
 assert.equal(assessPair(positive,{status:200,body:value},result(value)).passed,false)
 value.conditionValidation.status='UNVERIFIED';assert.equal(assessPair(zero,{status:200,body:value},result(value)).passed,false)
})
test('missing cases, blocked environment and cleanup failure cannot be reported as all passed',()=>{
 const rows=matrix.map(item=>({id:item.id,status:'PASS'}))
 assert.equal(resultStatus(rows,[]),'PASS');assert.equal(resultStatus(rows.slice(1),[]),'BLOCKED')
 assert.equal(resultStatus(rows,['LOGOUT_UNVERIFIED']),'BLOCKED')
 assert.equal(resultStatus(rows,['MCP_FORCED_TERMINATION']),'FAIL')
 assert.equal(resultStatus([{status:'FAIL'}],[]),'FAIL')
})
