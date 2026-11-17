import assert from 'node:assert/strict'
import {readFileSync, writeFileSync, existsSync, realpathSync} from 'node:fs'
import {resolve, dirname, basename, relative, isAbsolute} from 'node:path'
import {digest, sameJson} from './evaluation.mjs'
import {collectionStatus} from './upgrade-collection-status.mjs'
import {verifySelectedEvents} from './upgrade-event-evidence.mjs'
import {stage6RoutingCases, specialistAllowlists, verifyRoleEvents} from './stage6-routing.mjs'

const [input, destination] = process.argv.slice(2)
assert.ok(input && destination)
const source = realpathSync(input), output = resolve(destination), artifactRoot = realpathSync('artifacts')
const relativeSource = relative(artifactRoot, source)
assert.ok(relativeSource && !relativeSource.startsWith('..') && !isAbsolute(relativeSource))
assert.equal(dirname(output), artifactRoot); assert.equal(existsSync(output), false)
const bytes = readFileSync(source), report = JSON.parse(bytes)
const cases = stage6RoutingCases()
assert.equal(report.suiteKind, 'STAGE6_ROLE_ROUTING')
assert.equal(report.datasetSha256, digest(cases))
assert.deepEqual(report.selectedCaseIds, cases.map(row => row.id))
assert.equal(collectionStatus(report, cases).exitCode, 0)
assert.ok(sameJson(report.testTeam.before, report.testTeam.after))
const policy = readFileSync('src/main/java/com/vio/vioaiagent/agent/SpecialistRole.java')
assert.equal(report.rolePolicySourceSha256, digest(policy))
const allowlists = specialistAllowlists(policy.toString('utf8'))
function eventsFile(file) {
  assert.equal(basename(file), file)
  return readFileSync(resolve(dirname(source), file), 'utf8')
}
const integrity = verifySelectedEvents(report, eventsFile), records = []
const allowedRoles = {GREETING:[], FACT:['POKEMON_RESEARCH'], TEAM:['TEAM_PLANNING'],
  DAMAGE:['DAMAGE_CALCULATION'], MIXED:['TEAM_PLANNING','DAMAGE_CALCULATION']}
for (const row of report.records) {
  const kind = row.caseId.slice('S6-01-'.length), turn = row.turns[0]
  const events = JSON.parse(eventsFile(turn.eventsFile))
  const observed = verifyRoleEvents(events, report.availableTools, allowlists)
  assert.ok(observed.roles.every(role => allowedRoles[kind].includes(role)), 'TASK_ROLE_MISMATCH')
  assert.ok(observed.tools.every(tool => !tool.boundRole || allowedRoles[kind].includes(tool.boundRole)), 'TASK_TOOL_ROLE_MISMATCH')
  assert.ok(observed.tools.every(tool => !/terminal|shell|command|write|save|confirm|preview/i.test(tool.name)), 'READ_ONLY_CASE_WRITE')
  if (kind === 'GREETING') assert.ok(observed.tools.every(tool => tool.name === 'doTerminate'))
  if (['DAMAGE','MIXED'].includes(kind)) {
    const results = events.filter(event => event.type === 'tool.finished'
      && event.payload.toolName === 'calculateBattleDamage' && event.payload.status === 'SUCCEEDED')
    assert.ok(results.length > 0, 'DAMAGE_RESULT_REQUIRED')
    for (const event of results) {
      assert.equal(event.payload.outputEvidence.typedRequestDigest, report.damageOracles.baseline.result.conditionValidation.typedRequestDigest)
      assert.equal(event.payload.outputEvidence.outputDigest, report.damageOracles.baseline.resultSha256)
    }
  }
  records.push({caseId:row.caseId, ...observed})
}
const result = {schemaVersion:'stage6-role-verification-v1', status:'STRUCTURAL_CHECKS_PASSED',
  sourceSha256:digest(bytes), policySourceSha256:digest(policy), integrity, records,
  qualityReview:'REQUIRED', releaseDecision:'NOT_EVALUATED'}
writeFileSync(output, JSON.stringify(result, null, 2) + '\n', {flag:'wx'})
console.log(JSON.stringify({status:result.status, tasks:records.length}))
