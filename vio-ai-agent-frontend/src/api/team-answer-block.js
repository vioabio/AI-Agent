import { parsePokemonBlock } from './answer-block.js'
const missing = ['尚未核对招式、特性、道具、性格、等级、EV/IV及太晶配置', '尚未验证资源可获得性、联合合法性、伤害或胜率；本卡片未保存队伍']
export function parseTeamBlock(value) {
  const keys = ['schemaVersion', 'blockId', 'kind', 'gameReleaseId', 'dataReleaseId', 'scope', 'members', 'missing']
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== keys.length || !keys.every(key => Object.hasOwn(value, key)) || value.schemaVersion !== 'answer-block-v1' || value.kind !== 'teamSuggestion' || !/^team-[a-f0-9]{64}$/.test(value.blockId) || value.gameReleaseId !== 'pokemon-scarlet-violet' || value.dataReleaseId !== 'pokeapi-reference-v1' || value.scope !== 'REFERENCE_MEMBERS_NOT_SAVED_TEAM') return null
  if (!Array.isArray(value.members) || value.members.length < 1 || value.members.length > 6 || !Array.isArray(value.missing) || value.missing.length !== missing.length || !missing.every((text, i) => value.missing[i] === text)) return null
  const members = value.members.map(parsePokemonBlock)
  if (members.some(member => !member || member.gameReleaseId !== value.gameReleaseId || member.dataReleaseId !== value.dataReleaseId) || new Set(members.map(member => member.formId)).size !== members.length) return null
  return { ...value, members, missing: [...missing] }
}
