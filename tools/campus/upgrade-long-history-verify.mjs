import {existsSync, readFileSync, realpathSync, statSync, writeFileSync} from 'node:fs'
import {isAbsolute, relative, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest} from './evaluation.mjs'
import {collectionStatus} from './upgrade-collection-status.mjs'
import {verifySelectedEvents} from './upgrade-event-evidence.mjs'
import {longHistoryCases} from './upgrade-long-history.mjs'

// Structural evidence only. Answer quality requires a separate recorded review.
export function verifyLongHistory(baselineBytes, readEvents) {
  const report = JSON.parse(baselineBytes)
  const cases = longHistoryCases()
  if (report.suiteKind !== 'LONG_HISTORY_DEVELOPMENT' || report.split !== 'dev'
      || report.datasetSha256 !== digest(cases)
      || JSON.stringify(report.selectedCaseIds) !== JSON.stringify(['U3-LONG-HISTORY'])
      || report.records?.length !== 1) throw Error('LONG_HISTORY_DATASET_REQUIRED')
  const collection = collectionStatus(report, cases)
  if (collection.exitCode !== 0) throw Error('COLLECTION_INCOMPLETE')
  const config = report.runtime.configuration
  const window = config.contextBudgetPolicy?.historyTurns
  if (config.upgradeFeatures?.contextAssembly !== true || !Number.isInteger(window)
      || window < 1 || window > 10) throw Error('BOUNDED_HISTORY_CONFIGURATION_REQUIRED')
  const integrity = verifySelectedEvents(report, readEvents)
  const observations = []
  for (const index of [11, 14]) {
    const turn = report.records[0].turns[index]
    const events = JSON.parse(readEvents(turn.eventsFile))
    const contexts = events.filter(event => event.payload?.delegationEvent === 'agent.delegation.context')
    if (!contexts.length) throw Error('CONTEXT_EVIDENCE_REQUIRED')
    for (const {payload} of contexts) {
      const counts = [payload.sourceOmittedTurns, payload.assemblyOmittedTurns, payload.selectedHistoryTurns]
      if (payload.status !== 'CONTEXT_HISTORY_TRIMMED' || payload.agentRole !== 'TEAM_PLANNING'
          || counts.some(value => !Number.isInteger(value) || value < 0)
          || payload.sourceOmittedTurns < 1 || payload.selectedHistoryTurns > window
          || counts.reduce((sum, value) => sum + value, 0) !== index)
        throw Error('CONTEXT_OMISSION_MISMATCH')
    }
    const usage = events.filter(event => event.type === 'usage').at(-1)?.payload?.contextBudget
    if (usage?.status !== 'CHECKED' || usage.availableHistoryTurns !== index
        || usage.omittedHistoryTurns !== contexts[0].payload.sourceOmittedTurns)
      throw Error('HISTORY_USAGE_MISMATCH')
    const tools = events.filter(event => event.type === 'tool.started').map(event => event.payload?.toolName)
    if (!tools.length || tools.some(name => name !== 'searchPokemonGuides')) throw Error('GUIDE_TOOL_SCOPE_MISMATCH')
    observations.push({turn:index + 1, runId:turn.runId, contextEvents:contexts.length,
      sourceOmittedTurns:contexts[0].payload.sourceOmittedTurns,
      assemblyOmittedTurns:contexts[0].payload.assemblyOmittedTurns,
      selectedHistoryTurns:contexts[0].payload.selectedHistoryTurns, toolCalls:tools.length})
  }
  return {schemaVersion:'long-history-evidence-v1', status:'VERIFIED',
    scope:'LONG_HISTORY_DEVELOPMENT_EVIDENCE_ONLY', qualityReview:'REQUIRED',
    releaseDecision:'NOT_EVALUATED', baselineSha256:digest(baselineBytes), integrity, observations}
}

function artifact(path) {
  const file = realpathSync(resolve(path))
  const within = relative(realpathSync('artifacts'), file)
  if (!within || within.startsWith('..') || isAbsolute(within) || statSync(file).size > 16777216)
    throw Error('BOUNDED_ARTIFACT_REQUIRED')
  return readFileSync(file, 'utf8')
}

function main() {
  const [directory, output] = process.argv.slice(2)
  if (!directory || !output) throw Error('Usage: upgrade-long-history-verify.mjs artifacts/DIRECTORY artifacts/NEW_REPORT.json')
  const destination = resolve(output)
  if (existsSync(destination) || realpathSync(resolve(destination, '..')) !== realpathSync('artifacts'))
    throw Error('NEW_ARTIFACT_REPORT_REQUIRED')
  const result = verifyLongHistory(artifact(resolve(directory, 'baseline.json')),
    name => artifact(resolve(directory, name)))
  writeFileSync(destination, JSON.stringify(result, null, 2) + '\n', {flag:'wx'})
  console.log(JSON.stringify(result))
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
