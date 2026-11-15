/**
 * v1.1 领域版本兼容注册表。
 *
 * UI 使用的值只能作为 alias；发送到 Run/评测/发布链前必须解析为唯一的
 * canonical ID。这里刻意沿用 v1.0 已冻结的 casual-singles/casual-doubles，
 * 不在前端静默改名或把尚未评审的 ranked 文本映射到错误规则。
 */
export const BATTLE_FORMAT_ALIASES = Object.freeze({
  'casual-singles': 'casual-singles',
  singles: 'casual-singles',
  'casual-doubles': 'casual-doubles',
  doubles: 'casual-doubles'
})

export const BATTLE_FORMAT_OPTIONS = Object.freeze([
  Object.freeze({ alias: 'casual-singles', id: 'casual-singles', label: '休闲单打' }),
  Object.freeze({ alias: 'casual-doubles', id: 'casual-doubles', label: '休闲双打' })
])

export const GAME_RELEASE_ALIASES = Object.freeze({
  'pokemon-scarlet-violet': 'pokemon-scarlet-violet',
  'scarlet-violet': 'pokemon-scarlet-violet',
  sv: 'pokemon-scarlet-violet'
})

export const CANONICAL_VERSION_IDS = Object.freeze({
  gameReleaseId: 'pokemon-scarlet-violet',
  dlcId: 'the-hidden-treasure-of-area-zero',
  dataReleaseId: 'pokemon-scarlet-violet-data-v1'
})

function normalized(value) {
  return typeof value === 'string' ? value.trim().toLowerCase() : ''
}

/**
 * 解析 UI alias；未知、空值和多义值均 fail-closed，不返回猜测结果。
 */
export function resolveBattleFormat(alias) {
  const key = normalized(alias)
  const id = BATTLE_FORMAT_ALIASES[key]
  if (!id) {
    const error = new Error(`未注册的赛制 alias：${alias || '（空）'}`)
    error.code = 'UNREGISTERED_BATTLE_FORMAT_ALIAS'
    throw error
  }
  return Object.freeze({ alias: key, battleFormatId: id })
}

export function resolveGameRelease(alias) {
  const key = normalized(alias)
  const id = GAME_RELEASE_ALIASES[key]
  if (!id) {
    const error = new Error(`未注册的游戏版本 alias：${alias || '（空）'}`)
    error.code = 'UNREGISTERED_GAME_RELEASE_ALIAS'
    throw error
  }
  return Object.freeze({ alias: key, gameReleaseId: id })
}

/**
 * Run 请求序列化适配：保留 alias 供 UI/审计展示，同时把 canonical ID 放入
 * battleFormat 与 battleFormatId。旧后端忽略新增字段时仍可读取 battleFormat。
 */
export function serializeBattleFormat(alias) {
  const resolved = resolveBattleFormat(alias)
  return {
    battleFormat: resolved.battleFormatId,
    battleFormatId: resolved.battleFormatId,
    battleFormatAlias: resolved.alias
  }
}

/** 同时规范化游戏版本和赛制，供 Run 请求序列化适配层调用。 */
export function serializeVersionedContext({ gameReleaseId, battleFormat } = {}) {
  const game = resolveGameRelease(gameReleaseId)
  const format = resolveBattleFormat(battleFormat)
  return {
    gameReleaseId: game.gameReleaseId,
    gameReleaseAlias: game.alias,
    ...serializeBattleFormat(format.alias)
  }
}

/**
 * 生成 POST /chat/runs 可直接发送的字段。
 *
 * <p>serializeVersionedContext 还会返回 alias/审计字段，适合 UI 展示；
 * CreateRunRequest 的 OpenAPI additionalProperties=false，因此网络 payload
 * 只能携带后端 DTO 声明的 gameReleaseId/battleFormat。单独的适配器避免
 * 视图层把审计辅助字段误扩散到线上请求。</p>
 */
export function serializeRunContext(input = {}) {
  const serialized = serializeVersionedContext(input)
  return {
    gameReleaseId: serialized.gameReleaseId,
    battleFormat: serialized.battleFormat
  }
}

/** Restore only structured metadata from the latest user message. */
export function conversationContextFromHistory(messages) {
  const metadata = Array.isArray(messages)
    ? messages.findLast(message => message?.role === 'user')?.metadata : null
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) return {}
  const context = {}
  for (const field of ['gameReleaseId', 'language', 'rulesetVersionId', 'dataReleaseId']) {
    if (typeof metadata[field] === 'string' && /^[a-zA-Z0-9._:-]{1,128}$/.test(metadata[field]))
      context[field] = metadata[field]
  }
  const format = metadata.battleFormatId ?? metadata.battleFormat
  if (typeof format === 'string' && /^[a-zA-Z0-9._:-]{1,128}$/.test(format))
    context.battleFormat = BATTLE_FORMAT_ALIASES[normalized(format)] || format
  return context
}

export function isCanonicalBattleFormat(value) {
  const key = normalized(value)
  return Object.values(BATTLE_FORMAT_ALIASES).includes(key) && key === value
}
