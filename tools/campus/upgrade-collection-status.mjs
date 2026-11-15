import {digest} from './evaluation.mjs'
import {coreComponents, additionalComponents, componentManifestVersion, componentManifestSha256} from './runtime-component-manifest.mjs'

export function runtimeBuild(runtime) {
  const components = {}
  for (const [name] of [...coreComponents,...additionalComponents]) {
    const value = runtime?.implementationSha256?.[name]
    if (typeof value !== 'string' || !/^[a-f0-9]{64}$/.test(value)) throw Error('RUNTIME_COMPONENTS_UNAVAILABLE')
    components[name] = value
  }
  return {manifestVersion:componentManifestVersion,manifestSha256:componentManifestSha256,components,sha256:digest(components)}
}

function validBuild(build) {
  try {
    const rebuilt = runtimeBuild({implementationSha256:build?.components})
    return build.manifestVersion === rebuilt.manifestVersion && build.manifestSha256 === rebuilt.manifestSha256
      && build.sha256 === rebuilt.sha256 && Object.keys(build.components).length === Object.keys(rebuilt.components).length
  } catch { return false }
}

export function collectionStatus(report, expectedCases) {
  const errors = []
  if (report.mode === 'SETUP_BLOCKED') return {status:'SETUP_BLOCKED',exitCode:2,errors:['SETUP_BLOCKED']}
  if (report.schemaVersion !== 'upgrade-baseline-v1') errors.push('BASELINE_SCHEMA_REQUIRED')
  if (report.mode !== 'COLLECTED_NOT_REVIEWED') errors.push('COLLECTION_NOT_FINISHED')
  if (!Array.isArray(report.cleanupErrors) || report.cleanupErrors.length) errors.push('CLEANUP_INCOMPLETE')
  const before = report.runtime
  const after = report.runtimeAfter
  if (!before?.configuration || !after?.configuration
      || before.sha256 !== digest(before.configuration) || after.sha256 !== digest(after.configuration)
      || before.sha256 !== after.sha256) errors.push('RUNTIME_CHANGED_OR_UNVERIFIED')
  if (!validBuild(report.runtimeBuild) || !validBuild(report.runtimeBuildAfter)
      || report.runtimeBuild.sha256 !== report.runtimeBuildAfter.sha256) errors.push('RUNTIME_BUILD_CHANGED_OR_UNVERIFIED')
  const selected = report.selectedCaseIds
  const expected = new Map(expectedCases.map(item => [item.id, item]))
  if (!Array.isArray(selected) || !selected.length || new Set(selected).size !== selected.length
      || selected.some(id => !expected.has(id))) return {status:'INCOMPLETE',exitCode:1,errors:[...errors,'INVALID_SELECTION']}
  const records = Array.isArray(report.records) ? report.records : []
  const runIds = new Set()
  for (const id of selected) {
    const matches = records.filter(record => record.caseId === id)
    if (matches.length !== 1) { errors.push('CASE_MISSING_OR_DUPLICATE'); continue }
    const record = matches[0]
    const item = expected.get(id)
    if (record.verdict !== 'PENDING_REVIEW' || !Array.isArray(record.turns)
        || record.turns.length !== item.turns.length) { errors.push('CASE_INCOMPLETE'); continue }
    record.turns.forEach((turn, index) => {
      if (!turn || turn.prompt !== item.turns[index] || turn.status !== 'COMPLETED'
          || typeof turn.answer !== 'string' || !turn.answer.trim()) errors.push('TURN_INCOMPLETE')
      if (typeof turn?.runId !== 'string' || !/^run-[a-zA-Z0-9-]{1,76}$/.test(turn.runId)
          || runIds.has(turn.runId)) errors.push('RUN_ID_INVALID_OR_DUPLICATE')
      runIds.add(turn?.runId)
    })
  }
  return {status:errors.length ? 'INCOMPLETE' : 'COLLECTED_PENDING_REVIEW',
    exitCode:errors.length ? 1 : 0,errors:[...new Set(errors)]}
}
