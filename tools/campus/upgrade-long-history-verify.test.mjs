import test from 'node:test'
import assert from 'node:assert/strict'
import {digest} from './evaluation.mjs'
import {runtimeBuild} from './upgrade-collection-status.mjs'
import {coreComponents, additionalComponents} from './runtime-component-manifest.mjs'
import {longHistoryCases} from './upgrade-long-history.mjs'
import {verifyLongHistory} from './upgrade-long-history-verify.mjs'

function fixture() {
  const cases = longHistoryCases()
  const files = new Map()
  const turns = cases[0].turns.map((prompt, index) => {
    const runId = `run-fixture-${index}`
    const events = [{eventId:1, runId, type:'answer.delta', payload:{text:'仅作结构测试'}}]
    if ([11, 14].includes(index)) events.push(
      {eventId:2, runId, type:'route.selected', payload:{delegationEvent:'agent.delegation.context',
        agentRole:'TEAM_PLANNING', status:'CONTEXT_HISTORY_TRIMMED', sourceOmittedTurns:index - 8,
        assemblyOmittedTurns:0, selectedHistoryTurns:8}},
      {eventId:3, runId, type:'tool.started', payload:{toolName:'searchPokemonGuides'}},
      {eventId:4, runId, type:'usage', payload:{contextBudget:{status:'CHECKED',
        availableHistoryTurns:index, omittedHistoryTurns:index - 8}}})
    events.push({eventId:5, runId, type:'run.completed', payload:{}})
    const eventsFile = `U3-LONG-HISTORY-${index}-events.json`
    const bytes = JSON.stringify(events)
    files.set(eventsFile, bytes)
    return {runId, prompt, status:'COMPLETED', answer:'仅作结构测试', eventsFile, eventsSha256:digest(bytes)}
  })
  const configuration = {upgradeFeatures:{contextAssembly:true}, contextBudgetPolicy:{historyTurns:8}}
  const runtime = {configuration, sha256:digest(configuration)}
  const build = runtimeBuild({implementationSha256:Object.fromEntries(
    [...coreComponents, ...additionalComponents].map(([name]) => [name, 'a'.repeat(64)]))})
  const report = {schemaVersion:'upgrade-baseline-v1', suiteKind:'LONG_HISTORY_DEVELOPMENT', split:'dev',
    datasetSha256:digest(cases), mode:'COLLECTED_NOT_REVIEWED', cleanupErrors:[],
    runtime, runtimeAfter:structuredClone(runtime), runtimeBuild:build, runtimeBuildAfter:build,
    selectedCaseIds:['U3-LONG-HISTORY'], records:[{caseId:'U3-LONG-HISTORY', verdict:'PENDING_REVIEW', turns}]}
  return {report, files, verify:() => verifyLongHistory(JSON.stringify(report), name => files.get(name)),
    changeEvents(index, change) {
      const turn = turns[index]
      const events = JSON.parse(files.get(turn.eventsFile))
      change(events)
      const bytes = JSON.stringify(events)
      files.set(turn.eventsFile, bytes)
      turn.eventsSha256 = digest(bytes)
    }}
}

test('verifies fifteen event streams and two cropped specialist turns without grading answer text', () => {
  const result = fixture().verify()
  assert.equal(result.status, 'VERIFIED')
  assert.equal(result.integrity.turns, 15)
  assert.equal(result.qualityReview, 'REQUIRED')
  assert.deepEqual(result.observations.map(row => row.sourceOmittedTurns), [3, 6])
})

test('rejects false ready diagnostics, inconsistent counts and missing context evidence', () => {
  for (const change of [
    events => {events[1].payload.status = 'CONTEXT_READY'},
    events => {events[1].payload.sourceOmittedTurns = 2},
    events => {events.splice(1, 1)}
  ]) {
    const sample = fixture()
    sample.changeEvents(11, change)
    assert.throws(sample.verify, /CONTEXT_/)
  }
})

test('rejects private team tool calls and inconsistent usage', () => {
  const sample = fixture()
  sample.changeEvents(14, events => {events[2].payload.toolName = 'readCurrentTeam'})
  assert.throws(sample.verify, /GUIDE_TOOL_SCOPE_MISMATCH/)
  const usage = fixture()
  usage.changeEvents(11, events => {events[3].payload.contextBudget.omittedHistoryTurns = 0})
  assert.throws(usage.verify, /HISTORY_USAGE_MISMATCH/)
})

test('rejects substituted events, changed questions, frozen suite and runtime drift', () => {
  const events = fixture()
  events.changeEvents(0, rows => {rows[0].runId = 'run-another'})
  assert.throws(events.verify, /EVENT_RUN_MISMATCH/)
  const prompt = fixture()
  prompt.report.records[0].turns[0].prompt = 'different'
  assert.throws(prompt.verify, /COLLECTION_INCOMPLETE/)
  const suite = fixture()
  suite.report.suiteKind = 'FROZEN'
  assert.throws(suite.verify, /LONG_HISTORY_DATASET_REQUIRED/)
  const runtime = fixture()
  runtime.report.runtimeAfter.sha256 = 'b'.repeat(64)
  assert.throws(runtime.verify, /COLLECTION_INCOMPLETE/)
})
