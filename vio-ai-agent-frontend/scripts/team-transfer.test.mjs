import test from 'node:test'
import assert from 'node:assert/strict'
import { createTeamExport, parseTeamImport, MAX_TEAM_FILE_BYTES, teamFileName } from '../src/api/team-transfer.js'

const game = 'pokemon-scarlet-violet'
function team() {
  return { id: 99, ownerId: 'not-portable', name: '我的休闲队伍', rulesetId: 'casual-v1', dataReleaseId: 'pokeapi-reference-v1',
    members: [{ id: 88, slot: 3, formId: 7, build: { moves: ['water-pulse', 'protect'], ability: 'torrent', item: '', nature: 'modest', level: 50,
      evs: { hp: 0, atk: 0, def: 0, spa: 252, spd: 4, spe: 252 }, ivs: { hp: 31, atk: 0, def: 31, spa: 31, spd: 31, spe: 31 } } }] }
}
test('portable round trip preserves slots and complete builds but strips database and account identities', () => {
  const file = createTeamExport(team(), game)
  const restored = parseTeamImport('\uFEFF' + file, game)
  assert.deepEqual(restored.members[0].build, team().members[0].build)
  assert.equal(restored.members[0].slot, 3)
  assert.equal(restored.rulesetId, 'casual-v1')
  assert.equal(restored.dataReleaseId, 'pokeapi-reference-v1')
  assert.equal(restored.id, undefined)
  assert.equal(restored.ownerId, undefined)
  assert.equal(restored.members[0].id, undefined)
})
test('rejects broken JSON, unsupported schemas, different game and oversized files', () => {
  assert.throws(() => parseTeamImport('{', game), /JSON/)
  const file = JSON.parse(createTeamExport(team(), game))
  for (const change of [value => value.schemaVersion = 2, value => value.format = 'other', value => value.gameReleaseId = 'other']) {
    const value = structuredClone(file); change(value)
    assert.throws(() => parseTeamImport(JSON.stringify(value), game))
  }
  assert.throws(() => parseTeamImport(' '.repeat(MAX_TEAM_FILE_BYTES + 1), game), /1 MB/)
})
test('rejects invalid members, duplicate slots and forms, illegal training values and moves before draft replacement', () => {
  const changes = [
    t => t.members = [], t => t.members[0].formId = 0,
    t => t.members.push({ ...structuredClone(t.members[0]), formId: 25 }),
    t => t.members.push({ ...structuredClone(t.members[0]), slot: 4 }),
    t => t.members[0].build.level = 101, t => t.members[0].build.evs.hp = 252,
    t => t.members[0].build.ivs.hp = 32, t => t.members[0].build.evs.hp = 0.5,
    t => t.members[0].build.nature = 'invented', t => t.members[0].build.moves = ['ice-beam', 'ICE BEAM'],
    t => t.members[0].build.moves = ['a', 'b', 'c', 'd', 'e']
  ]
  for (const change of changes) { const value = team(); change(value); assert.throws(() => createTeamExport(value, game)) }
})
test('download names are safe and readable', () => {
  assert.equal(teamFileName('我的队伍'), '我的队伍.json')
  assert.equal(teamFileName('a/b:c?'), 'a_b_c_.json')
})
