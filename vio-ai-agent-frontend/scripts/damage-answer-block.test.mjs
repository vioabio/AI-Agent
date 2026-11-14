import test from 'node:test'
import assert from 'node:assert/strict'
import { parseDamageBlock } from '../src/api/damage-answer-block.js'
import { createRunViewModel, reduceRunEvent } from '../src/api/run-contract.js'
const member = () => ({ species: 'Squirtle', level: 50, nature: 'Hardy', ability: 'Torrent', item: '', evs: [0, 0, 0, 0, 0, 0], ivs: [31, 31, 31, 31, 31, 31] })
const block = () => ({ schemaVersion: 'answer-block-v1', blockId: `damage-${'a'.repeat(64)}`, kind: 'damageResult', attacker: member(), defender: member(), move: 'Tackle', field: { gameType: 'Singles', weather: null, terrain: null }, minimum: 10, maximum: 20, defenderHp: 100, minimumPercent: 10, maximumPercent: 20, gameReleaseId: 'pokemon-scarlet-violet', dataReleaseId: 'pokeapi-reference-v1', evidenceDigest: `sha256:${'b'.repeat(64)}` })
const evidence = () => ({ schemaVersion: 'answer-block-v1', kind: 'evidence', targetBlockId: block().blockId, requestDigest: `sha256:${'c'.repeat(64)}`, projectionDigest: block().evidenceDigest, engineVersion: '0.12.0', scope: 'PUBLIC_REFERENCE_AND_LOCAL_ENGINE_NOT_LEGALITY', sources: ['attacker', 'defender'].map(role => ({ role, url: 'https://pokeapi.co/api/v2/pokemon/7/', checksum: `sha256:${'d'.repeat(64)}` })) })

test('optional evidence binds to the card, copies sources and preserves legacy results', () => {
  const raw = { ...block(), evidence: evidence() }, parsed = parseDamageBlock(raw)
  assert.ok(parsed.evidence); parsed.evidence.sources[0].url = 'changed'
  assert.equal(raw.evidence.sources[0].url, 'https://pokeapi.co/api/v2/pokemon/7/')
  assert.equal(parseDamageBlock(block()).evidence, null)
  assert.equal(parseDamageBlock({ ...block(), evidence: null }).evidence, null)
})

test('cross-card, malformed and unsafe evidence falls back to readable text', () => {
  const invalid = [
    { ...evidence(), targetBlockId: `damage-${'e'.repeat(64)}` },
    { ...evidence(), projectionDigest: `sha256:${'e'.repeat(64)}` },
    { ...evidence(), command: 'save' }, { ...evidence(), engineVersion: 'unknown' },
    { ...evidence(), sources: [evidence().sources[0], evidence().sources[0]] },
    { ...evidence(), sources: [null, null] },
  ]
  for (const url of ['javascript:alert(1)', 'file:///secret', 'https://evil.example/7/', 'https://pokeapi.co/api/v2/pokemon/7/?token=x', 'https://user@pokeapi.co/api/v2/pokemon/7/']) {
    const changed = evidence(); changed.sources[0].url = url; invalid.push(changed)
  }
  for (const changed of invalid) assert.equal(parseDamageBlock({ ...block(), evidence: changed }), null)
})
test('damage keeps resolved defaults and valid zero damage without sharing mutable arrays', () => {
  const raw = block(), parsed = parseDamageBlock(raw)
  assert.equal(parsed.maximum, 20); parsed.attacker.ivs[0] = 0; assert.equal(raw.attacker.ivs[0], 31)
  assert.ok(parseDamageBlock({ ...raw, minimum: 0, maximum: 0, minimumPercent: 0, maximumPercent: 0 }))
})
test('incorrect percentages, extra fields and invalid configurations cannot become a card', () => {
  for (const changed of [{ ...block(), maximumPercent: 99 }, { ...block(), command: 'write' }, { ...block(), minimum: -1 }, { ...block(), defenderHp: 0 }, { ...block(), maximumPercent: Infinity }, { ...block(), field: { gameType: 'Singles', weather: 'invalid', terrain: null } }]) assert.equal(parseDamageBlock(changed), null)
})
test('replayed damage block is deduplicated by block id and wrong versions preserve text', () => {
  let model = createRunViewModel({ runId: 'r' })
  model = reduceRunEvent(model, { id: '1', type: 'context.confirmed', data: { gameReleaseId: 'pokemon-scarlet-violet', dataReleaseId: 'pokeapi-reference-v1' } })
  model = reduceRunEvent(model, { id: '2', type: 'answer.delta', data: { text: '已计算的正文' } })
  for (const id of ['3', '4']) model = reduceRunEvent(model, { id, type: 'calculation.card', data: { answerBlock: block() } })
  assert.equal(model.cards.calculations.length, 1)
  model = reduceRunEvent(model, { id: '5', type: 'calculation.card', data: { answerBlock: { ...block(), dataReleaseId: 'wrong' } } })
  assert.equal(model.cards.calculations.length, 1); assert.equal(model.answer, '已计算的正文')
})
