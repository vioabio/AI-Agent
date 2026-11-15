import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {stage6RoutingCases, stage6InjectionCases, injectionCorpus, injectionMarker, specialistAllowlists, verifyRoleEvents} from './stage6-routing.mjs'

const roles = specialistAllowlists(readFileSync('src/main/java/com/vio/vioaiagent/agent/SpecialistRole.java','utf8'))
const start = role => ({type:'route.selected', payload:{delegationEvent:'agent.delegation.started', agentRole:role}})
const finish = role => ({type:'route.selected', payload:{delegationEvent:'agent.delegation.completed', agentRole:role}})
const tool = name => ({type:'tool.started', payload:{toolName:name}})
test('five representative tasks retain distinct ids and complete damage conditions', () => {
  const cases = stage6RoutingCases()
  assert.equal(cases.length, 5)
  assert.equal(new Set(cases.map(row => row.id)).size, 5)
  assert.ok(cases.find(row => row.id.endsWith('MIXED')).turns[0].includes('calculateBattleDamage'))
})
test('delegated tools must satisfy role and runtime availability simultaneously', () => {
  const events = [start('DAMAGE_CALCULATION'), tool('calculateBattleDamage'), finish('DAMAGE_CALCULATION')]
  assert.equal(verifyRoleEvents(events,['calculateBattleDamage'],roles).delegatedToolCalls,1)
  assert.throws(() => verifyRoleEvents(events,[],roles), /TOOL_NOT_AVAILABLE/)
  assert.throws(() => verifyRoleEvents([start('POKEMON_RESEARCH'), ...events.slice(1)],['calculateBattleDamage'],roles), /ROLE_TOOL_NOT_ALLOWED/)
  assert.throws(() => verifyRoleEvents([{...tool('calculateBattleDamage'),payload:{toolName:'calculateBattleDamage',agentRole:'DAMAGE_CALCULATION'}}],['calculateBattleDamage'],roles), /UNBOUND_TOOL_ROLE/)
  const direct = {type:'tool.started',payload:{toolName:'calculateBattleDamage',agentRole:'DAMAGE_CALCULATION',selectionOrigin:'PROGRAM_BOUND_EXPLICIT_DAMAGE'}}
  assert.equal(verifyRoleEvents([direct],['calculateBattleDamage'],roles).programOrSupervisorToolCalls,1)
  assert.throws(() => verifyRoleEvents([{...direct,payload:{...direct.payload,toolName:'previewTeamChanges'}}],['previewTeamChanges'],roles), /PROGRAM_TOOL_MISMATCH/)
})
test('program routes may use zero roles while overlapping and unfinished roles fail', () => {
  assert.equal(verifyRoleEvents([],[],roles).roles.length,0)
  assert.throws(() => verifyRoleEvents([start('TEAM_PLANNING')],[],roles), /SPECIALIST_NOT_FINISHED/)
  assert.throws(() => verifyRoleEvents([start('TEAM_PLANNING'),start('DAMAGE_CALCULATION')],[],roles), /OVERLAPPING_SPECIALISTS/)
})
test('injection fixture changes only isolated document contents and preserves its source corpus', () => {
  const source = JSON.parse(readFileSync('src/main/resources/pokemon-strategy/overview-v1.json','utf8'))
  const before = JSON.stringify(source), copy = injectionCorpus(source)
  assert.equal(JSON.stringify(source), before)
  assert.equal(copy.documents.filter(row => row.content.includes(injectionMarker)).length, 3)
  assert.equal(stage6InjectionCases().length, 2)
  assert.ok(copy.documents.filter(row => row.content.includes(injectionMarker)).every(row => row.content.includes('u6-injected-team')))
})
