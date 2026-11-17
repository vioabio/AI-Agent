import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parsePokemonBlock } from '../src/api/answer-block.js'
const block = () => ({ schemaVersion: 'answer-block-v1', blockId: 'pokemon-39', kind: 'pokemon', formId: 39, name: '胖丁', types: ['一般', '妖精'], stats: [115, 45, 20, 45, 25, 20], gameReleaseId: 'pokemon-scarlet-violet', dataReleaseId: 'pokeapi-reference-v1', evidenceDigest: `sha256:${'a'.repeat(64)}` })
test('preserves both types and canonical numeric fields without mutation', () => {
  const input = block(), parsed = parsePokemonBlock(input)
  assert.deepEqual(parsed, input); parsed.types.pop(); assert.equal(input.types.length, 2)
})
test('rejects unknown actions, wrong versions and corrupt numeric fields', () => {
  for (const input of [{ ...block(), action: 'save' }, { ...block(), schemaVersion: 'future' }, { ...block(), stats: [0, 45, 20, 45, 25, 20] }, { ...block(), evidenceDigest: 'unverified' }, { ...block(), blockId: 'pokemon-7' }]) assert.equal(parsePokemonBlock(input), null)
})

import { createRunViewModel, reduceRunEvent } from '../src/api/run-contract.js'
test('fact block replay deduplicates and rejects mismatched version without losing text', () => {
  let model = createRunViewModel({ runId: 'test-run', gameReleaseId: 'pokemon-scarlet-violet', dataReleaseId: 'pokeapi-reference-v1' })
  model = reduceRunEvent(model, { id: '1', type: 'answer.delta', data: '正文保留' })
  for (const id of ['2','3']) model = reduceRunEvent(model, { id, type: 'fact.card', data: { answerBlock: block() } })
  assert.equal(model.cards.facts.length, 1)
  model = reduceRunEvent(model, { id: '4', type: 'fact.card', data: { answerBlock: { ...block(), dataReleaseId: 'wrong' } } })
  assert.equal(model.cards.facts.length, 1)
  assert.equal(model.answer, '正文保留')
})
