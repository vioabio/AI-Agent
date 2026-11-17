import test from 'node:test'
import assert from 'node:assert/strict'
import {verifyTimeoutRecovery} from './mcp-timeout-recovery.mjs'

function fixture() {
  return {scope:'PRODUCTION_STDIO_CLIENT_DEADLINE_AND_RECOVERY',tools:['calculateBattleDamage'],
    observation:{invalidFrames:0,duplicateResponses:0,forcedTermination:false},
    records:Array.from({length:5},(_,index)=>({requestId:index*4+3,expired:'MCP_REQUEST_TIMEOUT',timeoutMs:1,ping:{result:{}},tools:['calculateBattleDamage'],
      recovered:{result:{content:[{type:'text',text:JSON.stringify({conditionValidation:{status:'VERIFIED'},range:{minimum:13,maximum:16}})}]}}}))}
}
test('five client deadline cases require valid same-connection recovery results and clean shutdown',()=>{
  assert.equal(verifyTimeoutRecovery(fixture()).successfulRecoveryCalls,5)
})
test('late duplicate responses, forced shutdown, wrong ranges or poisoned discovery fail recovery checks',()=>{
  for(const mutate of [r=>r.observation.duplicateResponses=1,r=>r.observation.forcedTermination=true,
    r=>r.records[0].recovered.result.isError=true,r=>r.records[1].tools=[],
    r=>r.records[0].expired='OTHER',r=>r.records.pop(),r=>r.records[1].requestId=r.records[0].requestId,
    r=>r.records[0].recovered.result.content[0].text=JSON.stringify({conditionValidation:{status:'VERIFIED'},range:{minimum:14,maximum:16}})]){
    const report=fixture();mutate(report);assert.throws(()=>verifyTimeoutRecovery(report))
  }
})
