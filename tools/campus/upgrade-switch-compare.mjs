import {readFileSync, statSync, realpathSync, writeFileSync} from 'node:fs'
import {resolve, relative, isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest,sameJson} from './evaluation.mjs'
import {collectionStatus} from './upgrade-collection-status.mjs'
import {loadFrozenSuite} from './upgrade-baseline.mjs'
import {verifySelectedEvents} from './upgrade-event-evidence.mjs'

const switches = new Set(['skills','contextAssembly','domainTracing','answerBlocks','rerank'])
const hash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
function quantile(values, percentile) {
  const sorted = [...values].sort((a,b) => a-b)
  return sorted[Math.ceil(sorted.length * percentile)-1]
}
function timings(turn) {
  for (const field of ['ttftMs','totalMs']) {
    if (!Number.isFinite(turn[field]) || turn[field] < 0 || turn[field] > 86400000) throw Error('TIMING_UNAVAILABLE')
  }
  if (turn.ttftMs > turn.totalMs) throw Error('TIMING_INCONSISTENT')
  return {ttftMs:turn.ttftMs,totalMs:turn.totalMs}
}

export function compareSwitch(baseline, candidate, feature, expectedCases) {
  if (!switches.has(feature)) throw Error('SUPPORTED_SWITCH_REQUIRED')
  if ([baseline,candidate].some(report => collectionStatus(report,expectedCases).exitCode !== 0)) throw Error('COMPLETE_STABLE_COLLECTION_REQUIRED')
  for (const key of ['datasetSha256','corpusSha256','sourceStateSha256']) {
    if (!hash(baseline[key]) || baseline[key] !== candidate[key]) throw Error('SOURCE_OR_DATASET_MISMATCH')
  }
  if (!/^[a-f0-9]{40}$/.test(baseline.candidateCommit || '') || baseline.candidateCommit !== candidate.candidateCommit) throw Error('COMMIT_MISMATCH')
  if (baseline.runtimeBuild.sha256 !== candidate.runtimeBuild.sha256) throw Error('LOADED_COMPONENTS_CHANGED')
  const before = structuredClone(baseline.runtime.configuration)
  const after = structuredClone(candidate.runtime.configuration)
  if (feature === 'domainTracing') {
    for (const config of [before,after]) {
      const policy = config.domainTracingPolicy
      if (!policy || !Number.isFinite(policy.sampleRate) || policy.sampleRate < 0 || policy.sampleRate > 1
          || policy.automaticTracing !== false) throw Error('DOMAIN_SAMPLING_POLICY_REQUIRED')
    }
  }
  const baselineValue = before.upgradeFeatures?.[feature]
  const candidateValue = after.upgradeFeatures?.[feature]
  if (typeof baselineValue !== 'boolean' || typeof candidateValue !== 'boolean' || baselineValue === candidateValue) throw Error('ONE_CHANGED_SWITCH_REQUIRED')
  delete before.upgradeFeatures[feature]
  delete after.upgradeFeatures[feature]
  if (!sameJson(before,after)) throw Error('OTHER_CONFIGURATION_CHANGED')
  if (digest([...baseline.selectedCaseIds].sort()) !== digest([...candidate.selectedCaseIds].sort())) throw Error('PAIRED_CASES_REQUIRED')
  const records = []
  const runIds = new Set()
  for (const id of baseline.selectedCaseIds) {
    const a = baseline.records.find(record => record.caseId === id)
    const b = candidate.records.find(record => record.caseId === id)
    for (let index=0; index<a.turns.length; index++) {
      const first = a.turns[index]
      const second = b.turns[index]
      if (first.prompt !== second.prompt || first.runId === second.runId
          || runIds.has(first.runId) || runIds.has(second.runId)) throw Error('INDEPENDENT_PAIRED_RUNS_REQUIRED')
      runIds.add(first.runId); runIds.add(second.runId)
      const aTime = timings(first), bTime = timings(second)
      records.push({caseId:id,turnIndex:index,baselineRunId:first.runId,candidateRunId:second.runId,
        baseline:aTime,candidate:bTime,delta:{ttftMs:bTime.ttftMs-aTime.ttftMs,totalMs:bTime.totalMs-aTime.totalMs}})
    }
  }
  const summary = {}
  for (const field of ['ttftMs','totalMs']) {
    summary[field] = {}
    for (const side of ['baseline','candidate','delta']) {
      const values = records.map(record => record[side][field])
      summary[field][side] = {median:quantile(values,.5),p95:quantile(values,.95)}
    }
  }
  return {schemaVersion:'upgrade-switch-timing-v1',scope:'RECORDED_PAIRED_POLLING_LATENCIES',
    feature,baselineValue,candidateValue,turns:records.length,summary,records,
    releaseDecision:'NOT_EVALUATED',qualityReview:'REQUIRED',providerRequestAttestation:'NOT_COLLECTED'}
}

function artifact(file) {
  const path = realpathSync(file)
  const within = relative(realpathSync('artifacts'),path)
  if (!within || within.startsWith('..') || isAbsolute(within) || statSync(path).size > 16777216) throw Error('ARTIFACT_REQUIRED')
  return readFileSync(path,'utf8')
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [baselineFile,candidateFile,feature,outputFile] = process.argv.slice(2)
  if (!outputFile) throw Error('Usage: upgrade-switch-compare.mjs BASELINE_JSON CANDIDATE_JSON SWITCH NEW_REPORT_JSON')
  const output = resolve(outputFile)
  const within = relative(realpathSync('artifacts'),realpathSync(resolve(output,'..')))
  if (within.startsWith('..') || isAbsolute(within)) throw Error('ARTIFACT_OUTPUT_REQUIRED')
  const a = artifact(baselineFile), b = artifact(candidateFile)
  const suite = loadFrozenSuite()
  const expected = suite.cases.filter(item => item.split === 'dev')
  const baseline = JSON.parse(a), candidate = JSON.parse(b)
  for (const input of [baseline,candidate]) {
    if (input.split !== 'dev' || input.datasetSha256 !== suite.datasetSha256
        || input.corpusSha256 !== suite.manifest.corpus.sha256) throw Error('FROZEN_DEVELOPMENT_SUITE_REQUIRED')
  }
  const report = compareSwitch(baseline,candidate,feature,expected)
  report.eventEvidence = {
    baseline:verifySelectedEvents(baseline, name => artifact(resolve(baselineFile, '..', name))),
    candidate:verifySelectedEvents(candidate, name => artifact(resolve(candidateFile, '..', name)))
  }
  report.inputSha256 = {baseline:digest(a),candidate:digest(b)}
  writeFileSync(output,JSON.stringify(report,null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify({feature:report.feature,turns:report.turns,summary:report.summary,releaseDecision:report.releaseDecision}))
}
