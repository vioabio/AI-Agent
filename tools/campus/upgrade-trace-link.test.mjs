import test from 'node:test'
import assert from 'node:assert/strict'
import {assessTrace, linkBaseline, parseJaegerResponse} from './upgrade-trace-link.mjs'

const traceId = 'a'.repeat(32)
const span = (id, name, parent, runId) => ({traceId,spanId:id.repeat(16),name,
  ...(parent ? {parentSpanId:parent.repeat(16)} : {}),
  attributes:runId ? [{key:'agent.run.id',value:{stringValue:runId}}] : []})
const response = spans => ({result:{resourceSpans:[{scopeSpans:[{spans}]}]}})
const fixture = () => response([
  span('1','agent.run.execute',null,'run-one'),
  span('2','agent.tool.dispatch','1'),span('3','agent.tool.callback','2'),span('4','agent.model.call','3')
])
const baseline = () => ({schemaVersion:'upgrade-baseline-v1',records:[{caseId:'U0-FACTS-01',turns:[{runId:'run-one'}]}]})
const attempt = (span,id,outcome) => {
  span.attributes.push({key:'agent.run.attempt.id',value:{stringValue:id}})
  if(outcome)span.attributes.push({key:'agent.run.dispatch.outcome',value:{stringValue:outcome}})
  return span
}
const firstAttempt='00000000-0000-0000-0000-000000000001'
const secondAttempt='00000000-0000-0000-0000-000000000002'

test('approval resume links distinct attempts while retaining each dispatch and execution trace',()=>{
  const one=attempt(span('1','agent.run.execute',null,'run-one'),firstAttempt)
  const queueOne=attempt({...span('2','agent.run.dispatch',null,'run-one'),traceId:'b'.repeat(32)},firstAttempt,'STARTED')
  const two=attempt({...span('3','agent.run.execute',null,'run-one'),traceId:'c'.repeat(32)},secondAttempt)
  const queueTwo=attempt({...span('4','agent.run.dispatch',null,'run-one'),traceId:'d'.repeat(32)},secondAttempt,'STARTED')
  const result=assessTrace('run-one',response([one,queueOne,two,queueTwo]))
  assert.equal(result.status,'LINKED');assert.equal(result.attempts.length,2)
  assert.equal(result.attempts[1].execution.traceId,'c'.repeat(32))
})
test('queued cancellation links its admission parent without inventing an execution',()=>{
  const queue=attempt(span('2','agent.run.dispatch','1','run-one'),firstAttempt,'CANCELLED')
  const result=assessTrace('run-one',response([span('1','agent.run.admit',null,'run-one'),queue]))
  assert.equal(result.status,'LINKED');assert.equal(result.attempts[0].execution,null)
  assert.deepEqual(result.spans.map(span=>span.name),['agent.run.admit','agent.run.dispatch'])
})
test('attempts sharing a trace still identify their own execution spans',()=>{
  const rows=[span('1','agent.run.admit',null,'run-one'),
    attempt(span('2','agent.run.dispatch','1','run-one'),firstAttempt,'STARTED'),
    attempt(span('3','agent.run.execute','1','run-one'),firstAttempt),
    attempt(span('4','agent.run.dispatch','1','run-one'),secondAttempt,'STARTED'),
    attempt(span('5','agent.run.execute','1','run-one'),secondAttempt)]
  const result=assessTrace('run-one',response(rows))
  assert.equal(result.status,'LINKED')
  assert.notEqual(result.attempts[0].execution.spanId,result.attempts[1].execution.spanId)
})
test('duplicate identities, missing dispatch, started queue without worker and mixed formats fail closed',()=>{
  const root=()=>attempt(span('1','agent.run.execute',null,'run-one'),firstAttempt)
  const queue=()=>attempt({...span('2','agent.run.dispatch',null,'run-one'),traceId:'b'.repeat(32)},firstAttempt,'STARTED')
  assert.equal(assessTrace('run-one',response([root()])).status,'INCOMPLETE_ATTEMPT')
  assert.equal(assessTrace('run-one',response([queue()])).status,'INCOMPLETE_ATTEMPT')
  const duplicate=attempt({...span('3','agent.run.execute',null,'run-one'),traceId:'c'.repeat(32)},firstAttempt)
  assert.equal(assessTrace('run-one',response([root(),duplicate,queue()])).status,'AMBIGUOUS_RUN_ROOT')
  assert.equal(assessTrace('run-one',response([root(),span('3','agent.run.execute',null,'run-one'),queue()])).status,'AMBIGUOUS_RUN_ROOT')
})

test('Jaeger v3 empty search differs from bad endpoint and collector failure', () => {
  assert.deepEqual(parseJaegerResponse(404,JSON.stringify({error:{httpCode:404,message:'No traces found'}})),{result:{resourceSpans:[]}})
  assert.throws(()=>parseJaegerResponse(404,'404 page not found'))
  assert.throws(()=>parseJaegerResponse(503,JSON.stringify({error:{httpCode:503,message:'No traces found'}})))
  assert.throws(()=>parseJaegerResponse(200,'{}'))
})

test('exact Run binds domain spans and retains only safe link fields', () => {
  const data = fixture()
  data.result.resourceSpans[0].scopeSpans[0].spans[3].attributes.push({key:'prompt',value:{stringValue:'private-content'}})
  const result = assessTrace('run-one',data)
  assert.equal(result.status,'LINKED');assert.equal(result.spans.length,4)
  assert.equal(result.traceUrl,'http://127.0.0.1:16686/trace/'+traceId)
  assert.equal(JSON.stringify(result).includes('private-content'),false)
  assert.equal(assessTrace('run-other',data).status,'NOT_FOUND')
})
test('authorization and commit phases remain attached to the unique worker root', () => {
  const rows = fixture().result.resourceSpans[0].scopeSpans[0].spans
  rows.push(span('5','agent.run.authorize','1'),span('6','agent.run.commit','1'))
  const result = assessTrace('run-one',response(rows))
  assert.equal(result.status,'LINKED')
  assert.deepEqual(result.spans.slice(-2).map(value=>value.name),['agent.run.authorize','agent.run.commit'])
})
test('ambiguous roots, duplicate IDs, cycles and disconnected children cannot link', () => {
  const rows = fixture().result.resourceSpans[0].scopeSpans[0].spans
  assert.equal(assessTrace('run-one',response([...rows,span('5','agent.run.execute',null,'run-one')])).status,'AMBIGUOUS_RUN_ROOT')
  assert.equal(assessTrace('run-one',response([...rows,rows[1]])).status,'INVALID_TRACE')
  assert.equal(assessTrace('run-one',response([rows[0],span('2','agent.tool.dispatch','3'),span('3','agent.tool.callback','2')])).status,'INVALID_TRACE')
  assert.equal(assessTrace('run-one',response([rows[0],span('2','agent.tool.dispatch','9')])).status,'INCOMPLETE_TRACE')
})
test('root may have an external HTTP parent and unrelated traces do not contaminate results', () => {
  const rows = fixture().result.resourceSpans[0].scopeSpans[0].spans
  rows[0].parentSpanId='f'.repeat(16)
  const result=assessTrace('run-one',response([...rows,{...span('1','agent.run.execute',null,'run-another'),traceId:'b'.repeat(32)}]))
  assert.equal(result.status,'LINKED');assert.equal(result.spans.length,4)
  rows[0].parentSpanId=rows[1].spanId
  assert.equal(assessTrace('run-one',response(rows)).status,'INVALID_TRACE')
})
test('malformed identifiers cannot be used to construct trace URLs', () => {
  const data=fixture();data.result.resourceSpans[0].scopeSpans[0].spans[0].traceId='../secret'
  assert.equal(assessTrace('run-one',data).status,'INVALID_TRACE')
  const parent=fixture();parent.result.resourceSpans[0].scopeSpans[0].spans[0].parentSpanId='invalid-parent'
  assert.equal(assessTrace('run-one',parent).status,'INVALID_TRACE')
})
test('case and turn associations are explicit and preserve the source baseline', async () => {
  const source=baseline(),before=JSON.stringify(source)
  const report=await linkBaseline(source,async()=>fixture())
  assert.equal(report.records[0].caseId,'U0-FACTS-01');assert.equal(report.records[0].turnIndex,0)
  assert.deepEqual(report.summary,{LINKED:1});assert.equal(report.releaseDecision,'NOT_EVALUATED')
  assert.equal(JSON.stringify(source),before)
})
test('unavailable query and absent trace remain distinct recorded outcomes', async () => {
  const unavailable=await linkBaseline(baseline(),async()=>{throw Error('private-network-error')})
  assert.deepEqual(unavailable.summary,{QUERY_UNAVAILABLE:1});assert.equal(JSON.stringify(unavailable).includes('private-network-error'),false)
  assert.deepEqual((await linkBaseline(baseline(),async()=>response([]))).summary,{NOT_FOUND:1})
})
test('duplicate cases or Runs and invalid baselines reject rather than inflating coverage', async () => {
  await assert.rejects(linkBaseline({},async()=>fixture()),/BASELINE_REQUIRED/)
  const duplicateCase=baseline();duplicateCase.records.push({...duplicateCase.records[0]})
  await assert.rejects(linkBaseline(duplicateCase,async()=>fixture()),/INVALID_CASE/)
  const duplicateRun=baseline();duplicateRun.records[0].turns.push({runId:'run-one'})
  await assert.rejects(linkBaseline(duplicateRun,async()=>fixture()),/INVALID_RUN/)
})

test('partial selection skips retained unexecuted cases and rejects missing selected IDs',async()=>{
  const source=baseline();source.selectedCaseIds=['U0-FACTS-01']
  source.records.push({caseId:'U0-FACTS-02',verdict:'NOT_RUN'})
  const report=await linkBaseline(source,async()=>fixture())
  assert.equal(report.total,1);assert.deepEqual(report.summary,{LINKED:1})
  for(const selection of [[],['U0-FACTS-03'],['U0-FACTS-01','U0-FACTS-01']]){
    const invalid=structuredClone(source);invalid.selectedCaseIds=selection
    await assert.rejects(linkBaseline(invalid,async()=>fixture()),/INVALID_SELECTION/)
  }
})
