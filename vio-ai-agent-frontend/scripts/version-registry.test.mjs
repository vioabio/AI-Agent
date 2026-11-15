import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  BATTLE_FORMAT_ALIASES,
  resolveBattleFormat,
  conversationContextFromHistory,
  serializeRunContext,
  serializeVersionedContext
} from '../src/api/version-registry.js'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const contract = JSON.parse(fs.readFileSync(path.resolve(scriptDir, '../../contracts/version-registry-v1.1.json'), 'utf8'))
const fixture = JSON.parse(fs.readFileSync(path.resolve(scriptDir, '../../data/v1.1-fixture/fixture.json'), 'utf8'))

test('history context restores the latest user metadata including canonical format and reference version', () => {
  const messages=[{role:'user',metadata:{dataReleaseId:'old'}},
    {role:'user',metadata:{gameReleaseId:'pokemon-scarlet-violet',dataReleaseId:'pokeapi-reference-v1',
      language:'zh-CN',rulesetVersionId:'casual-v1',battleFormatId:'casual-doubles',tool:'ignored'}},
    {role:'assistant',metadata:{dataReleaseId:'unrelated'}}]
  assert.deepEqual(conversationContextFromHistory(messages),{
    gameReleaseId:'pokemon-scarlet-violet',language:'zh-CN',rulesetVersionId:'casual-v1',
    dataReleaseId:'pokeapi-reference-v1',battleFormat:'casual-doubles'
  })
  assert.equal(messages[1].metadata.tool,'ignored')
  assert.equal(conversationContextFromHistory([{role:'user',metadata:{battleFormat:'singles'}}]).battleFormat,'casual-singles')
})

test('history context ignores message text, assistant metadata, legacy gaps and malformed values', () => {
  for(const input of [null,{},[],[{role:'assistant',metadata:{dataReleaseId:'wrong'}}],
    [{role:'user',content:'{"dataReleaseId":"wrong"}'}],
    [{role:'user',metadata:{dataReleaseId:'old'}},{role:'user'}],
    [{role:'user',metadata:{dataReleaseId:{value:'bad'},language:'<script>',battleFormatId:[],rulesetVersionId:'x'.repeat(129)}}]])
    assert.deepEqual(conversationContextFromHistory(input),{})
})

test('前端赛制 alias 与版本契约完全一致', () => {
  assert.deepEqual(BATTLE_FORMAT_ALIASES, contract.aliases.battleFormatId)
  const canonical = new Set(contract.canonical.battleFormatIds)
  for (const [alias, id] of Object.entries(BATTLE_FORMAT_ALIASES)) {
    assert.ok(canonical.has(id), `${alias} 必须指向已冻结 ID`)
  }
})

test('未知赛制 alias fail-closed，已冻结 ID 保持不改名', () => {
  assert.deepEqual(resolveBattleFormat('singles'), {
    alias: 'singles', battleFormatId: 'casual-singles'
  })
  assert.equal(resolveBattleFormat('casual-doubles').battleFormatId, 'casual-doubles')
  assert.throws(() => resolveBattleFormat('ranked-singles'), error =>
    error?.code === 'UNREGISTERED_BATTLE_FORMAT_ALIAS')
})

test('Run 序列化和 fixture 都使用 canonical battleFormatId', () => {
  const serialized = serializeVersionedContext({
    gameReleaseId: 'scarlet-violet', battleFormat: 'singles'
  })
  assert.equal(serialized.gameReleaseId, 'pokemon-scarlet-violet')
  assert.equal(serialized.battleFormatAlias, 'singles')
  assert.equal(serialized.battleFormatId, 'casual-singles')
  assert.equal(serialized.battleFormat, 'casual-singles')
  assert.equal(fixture.canonicalVersions.battleFormatId, 'casual-singles')
})

test('Run 网络 payload 只保留 CreateRunRequest 声明字段，不发送 alias 审计辅助字段', () => {
  assert.deepEqual(serializeRunContext({
    gameReleaseId: 'scarlet-violet', battleFormat: 'singles'
  }), {
    gameReleaseId: 'pokemon-scarlet-violet',
    battleFormat: 'casual-singles'
  })
})
