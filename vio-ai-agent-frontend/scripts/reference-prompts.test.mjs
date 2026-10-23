import test from 'node:test'
import assert from 'node:assert/strict'
import { pokemonFactQuestion, readableUserMessage, teamMemberDescription, damageQuestion, guideQuestion } from '../src/api/reference-prompts.js'

const fact = { speciesNameZh: '雪妖女', slug: 'froslass', type1: '冰', type2: '幽灵', speed: 110,
  formId: 478, checksum: `sha256:${'a'.repeat(64)}`, dataReleaseId: 'pokeapi-reference-v1' }

test('资料提问保留名称属性和有用数值，不携带原始JSON和技术标识', () => {
  const prompt = pokemonFactQuestion(fact)
  assert.match(prompt, /雪妖女/)
  assert.match(prompt, /冰 \/ 幽灵/)
  assert.match(prompt, /速度 110/)
  assert.doesNotMatch(prompt, /formId|sha256|checksum|pokeapi-reference-v1|[{}]/)
})

test('旧资料提问的显示、编辑、复制使用可读内容，普通消息保持原样', () => {
  const old = `请介绍这只宝可梦，并结合所选赛制给出培养与搭配建议。\n以下为资料接口返回的数据，仅作为事实参考：\n${JSON.stringify(fact)}`
  assert.equal(readableUserMessage(old), pokemonFactQuestion(fact))
  for (const ordinary of ['请解释这个 JSON：{"speed":110}', '你好', `${old}不是完整JSON`]) {
    assert.equal(readableUserMessage(ordinary), ordinary)
  }
})

test('队伍分析保留配置而不暴露成员与资料内部ID', () => {
  const prompt = teamMemberDescription({ id: 'private-member-id', build: { moves: ['shadow-ball'],
    ability: 'cursed-body', item: 'focus-sash', level: 50, evs: { spe: 252 }, ivs: { spe: 31 } } }, fact)
  assert.match(prompt, /雪妖女/)
  assert.match(prompt, /shadow-ball/)
  assert.match(prompt, /252/)
  assert.doesNotMatch(prompt, /private-member-id|sha256|formId|[{}]/)
})

test('伤害和攻略提问保留条件与来源，不携带校验值或内部编号', () => {
  const damage = damageQuestion({ range: { minimum: 80, maximum: 96 }, percent: { minimum: 50, maximum: 60 },
    engine: '@smogon/calc', engineVersion: '0.12.0', attackerChecksum: 'private-checksum',
    assumptions: { attacker: { species: 'Froslass', level: 50 }, defender: { species: 'Pikachu', level: 50 }, move: 'Shadow Ball' } })
  assert.match(damage, /80～96/)
  assert.match(damage, /Shadow Ball/)
  assert.doesNotMatch(damage, /private-checksum|[{}]/)
  const guide = guideQuestion('如何培养', { checksum: 'private-checksum', document: {
    id: 'private-id', title: '培养说明', author: '作者', updatedAt: '2026-10-20', content: '注明努力值。' } })
  assert.match(guide, /培养说明/)
  assert.match(guide, /注明努力值/)
  assert.doesNotMatch(guide, /private-checksum|private-id|[{}]/)
})
