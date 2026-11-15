import assert from 'node:assert/strict'
import {test} from 'node:test'
import {verifySoak} from './mcp-soak.mjs'
const report=()=>({scope:'PRODUCTION_STDIO_BOUNDED_RATE_SOAK',requestedSeconds:3600,durationSeconds:3601,
  samples:[{pending:0,clientRss:10,serverRss:20},{pending:0,clientRss:20,serverRss:30}],
  pings:7000,successfulLookups:60,successfulCalculations:60,
  observation:{invalidFrames:0,duplicateResponses:0,forcedTermination:false}})
test('soak evidence retains duration, recovery, memory observations and clean closing',()=>{
  assert.equal(verifySoak(report()).status,'VERIFIED')
})
test('shortened or incomplete runs cannot become an hourly pass',()=>{
  for(const change of [{durationSeconds:20},{pings:0},{samples:[]},{successfulCalculations:0}])
    assert.throws(()=>verifySoak({...report(),...change}))
})
test('pending work, invalid memory samples and protocol corruption fail validation',()=>{
  assert.throws(()=>verifySoak({...report(),samples:[{pending:1,clientRss:1,serverRss:1},{pending:0,clientRss:1,serverRss:1}]}))
  assert.throws(()=>verifySoak({...report(),samples:[{pending:0,clientRss:1,serverRss:0},{pending:0,clientRss:1,serverRss:1}]}))
  for(const flag of ['invalidFrames','duplicateResponses','forcedTermination']) {
    const r=report();r.observation[flag]=true;assert.throws(()=>verifySoak(r))
  }
})
