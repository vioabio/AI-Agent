import test from 'node:test'
import assert from 'node:assert/strict'
import {cases,summarize} from './campus-independent-20261120.mjs'
import {digest} from './evaluation.mjs'

const capture=()=>({datasetSha256:digest(JSON.stringify(cases,null,2)+'\n'),records:[]})
test('unexecuted tasks stay pending and unknown, never become local or pass',()=>{
  const report=summarize(cases,capture(),()=>[])
  assert.equal(report.verdicts.PASS,0)
  assert.equal(report.verdicts.PENDING,30)
  assert.equal(report.byGeneration.UNKNOWN.groups,30)
})
test('reject mismatched dataset and invented human PASS',()=>{
  assert.throws(()=>summarize(cases,{...capture(),datasetSha256:'wrong'},()=>[]))
  assert.throws(()=>summarize(cases,capture(),()=>[],[{caseId:cases[0].id,verdict:'PASS'}]))
})
test('missing usage is unknown and mutated event evidence is rejected',()=>{
  const data=capture(),events=[]
  data.records=[{caseId:cases[0].id,turns:[{runId:'run',eventFile:'a.json',eventsSha256:digest('[]\n')}]}]
  assert.equal(summarize(cases,data,()=>events).rows[0].generation,'UNKNOWN')
  assert.throws(()=>summarize(cases,data,()=>[{type:'changed'}]))
})
test('fault PASS requires fault evidence even with a human verdict',()=>{
  const data=capture(),item=cases.at(-1)
  const record={caseId:item.id,teamUnchanged:true,turns:[{runId:'run',eventFile:'a.json',eventsSha256:digest('[]\n')}]}
  data.records=[record];data.configurationUnchanged=true
  const review={caseId:item.id,observationSha256:digest(record),reviewerType:'human',reviewer:'tester',reviewedAt:'2026-11-20T00:00:00Z',evidence:'events',verdict:'PASS'}
  assert.throws(()=>summarize(cases,data,()=>[],[review]))
})
