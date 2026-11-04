import test from 'node:test'
import assert from 'node:assert/strict'
import { factSearchParameters, matchingPokemonNames, pokemonTargetsForMessage } from '../src/api/pokemon-navigation.js'

const names = [
  { nationalNo: 7, nameZh: '杰尼龟', slug: 'squirtle', aliases: ['杰尼龟', '傑尼龜'] },
  { nationalNo: 478, nameZh: '雪妖女', slug: 'froslass', aliases: ['雪妖女'] },
  { nationalNo: 133, nameZh: '伊布', slug: 'eevee', aliases: ['伊布'] }
]
test('名称与编号为互斥输入，旧编号不会过滤新名称', () => {
  assert.deepEqual(factSearchParameters('杰尼龟', '478', names), { nationalNo: 7 })
  assert.deepEqual(factSearchParameters('SQUIRTLE', '478', names), { nationalNo: 7 })
  assert.deepEqual(factSearchParameters('傑尼龜', '', names), { nationalNo: 7 })
  assert.deepEqual(factSearchParameters('#007', '478', names), { nationalNo: 7 })
  assert.deepEqual(factSearchParameters('', '478', names), { nationalNo: 478 })
  assert.deepEqual(factSearchParameters('unknown-form', '', names), { slug: 'unknown-form' })
})
test('部分中文和英文查询可索引候选', () => {
  assert.equal(matchingPokemonNames('杰尼', names)[0].nationalNo, 7)
  assert.equal(matchingPokemonNames('fros', names)[0].nationalNo, 478)
  assert.deepEqual(matchingPokemonNames('不存在', names), [])
})
test('消息只定位自身提到的宝可梦，多只按正文顺序展示，不从旧会话借名字', () => {
  assert.deepEqual(pokemonTargetsForMessage('雪妖女可以搭配杰尼龟，雪妖女速度110。', names).map(p => p.nationalNo), [478, 7])
  assert.deepEqual(pokemonTargetsForMessage('FROSLASS 与 squirtle', names).map(p => p.nationalNo), [478, 7])
  assert.deepEqual(pokemonTargetsForMessage('eeveee is not eevee-other', names), [])
  assert.deepEqual(pokemonTargetsForMessage('请先补充赛制', names), [])
})
test('长名称不会误链接其子串，英文空格也可对应slug', () => {
  const index = [...names, { nationalNo: 700, nameZh: '仙子伊布', slug: 'sylveon', aliases: [] },
    { nationalNo: 984, nameZh: '雄伟牙', slug: 'great-tusk', aliases: [] }]
  assert.deepEqual(pokemonTargetsForMessage('仙子伊布与 Great Tusk', index).map(p => p.nationalNo), [700, 984])
  assert.deepEqual(pokemonTargetsForMessage('仙子伊布进化自伊布', index).map(p => p.nationalNo), [700, 133])
})
