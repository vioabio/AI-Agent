// Portable team files contain configuration only, never account/database identifiers.
export const MAX_TEAM_FILE_BYTES = 1024 * 1024
const stats = ['hp', 'atk', 'def', 'spa', 'spd', 'spe']
const natures = new Set('hardy lonely brave adamant naughty bold docile relaxed impish lax timid hasty serious jolly naive modest mild quiet bashful rash calm gentle sassy careful quirky'.split(' '))

function object(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}格式不正确`)
  return value
}
function text(value, label, max, optional = false) {
  if (optional && (value == null || value === '')) return ''
  if (typeof value !== 'string' || !value.trim() || value.length > max || [...value].some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127)) throw new Error(`${label}为空或长度、字符不符合要求`)
  return value.trim()
}
function integer(value, min, max, label) {
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`${label}超出范围`)
  return value
}
function training(value, max, label) {
  object(value, label)
  return Object.fromEntries(stats.map(stat => [stat, integer(value[stat], 0, max, label)]))
}
function build(value) {
  object(value, '成员配置')
  if (!Array.isArray(value.moves) || value.moves.length > 4) throw new Error('招式最多四个')
  const moves = value.moves.map(move => text(move, '招式', 80))
  if (new Set(moves.map(move => move.toLowerCase().replaceAll('-', ' '))).size !== moves.length) throw new Error('招式不能重复')
  const nature = text(value.nature, '性格', 16).toLowerCase()
  if (!natures.has(nature)) throw new Error('未知的性格配置')
  const evs = training(value.evs, 252, '努力值')
  if (Object.values(evs).reduce((sum, n) => sum + n, 0) > 510) throw new Error('努力值总和不能超过 510')
  return { moves, ability: text(value.ability, '特性', 80, true), item: text(value.item, '道具', 80, true),
    nature, level: integer(value.level, 1, 100, '等级'), evs, ivs: training(value.ivs, 31, '个体值') }
}
export function normalizeTransferTeam(value) {
  object(value, '队伍')
  if (!Array.isArray(value.members) || value.members.length < 1 || value.members.length > 6) throw new Error('队伍需要 1–6 位成员')
  const members = value.members.map(member => {
    object(member, '成员')
    return { formId: integer(member.formId, 1, Number.MAX_SAFE_INTEGER, '成员编号'),
      slot: integer(member.slot, 1, 6, '成员槽位'), build: build(member.build) }
  }).sort((a, b) => a.slot - b.slot)
  if (new Set(members.map(member => member.slot)).size !== members.length) throw new Error('成员槽位不能重复')
  if (new Set(members.map(member => member.formId)).size !== members.length) throw new Error('队伍不能重复使用同一宝可梦形态')
  return { name: text(value.name, '队伍名称', 128), rulesetId: text(value.rulesetId, '规则版本', 64),
    dataReleaseId: text(value.dataReleaseId, '数据版本', 128), members }
}
export function createTeamExport(team, gameReleaseId) {
  return JSON.stringify({ format: 'vio-pokemon-team', schemaVersion: 1,
    gameReleaseId: text(gameReleaseId, '游戏版本', 128), team: normalizeTransferTeam(team) }, null, 2)
}
export function parseTeamImport(contents, gameReleaseId) {
  if (new TextEncoder().encode(contents).length > MAX_TEAM_FILE_BYTES) throw new Error('队伍文件不能超过 1 MB')
  let file
  try { file = JSON.parse(contents.replace(/^\uFEFF/, '')) } catch { throw new Error('无法解析 JSON 队伍文件') }
  object(file, '文件')
  if (file.format !== 'vio-pokemon-team' || file.schemaVersion !== 1) throw new Error('不支持的队伍文件格式或版本，请选择本项目导出的 JSON 文件')
  if (file.gameReleaseId !== gameReleaseId) throw new Error('队伍游戏版本与当前工作区不一致，请切换版本后重试')
  return normalizeTransferTeam(file.team)
}
export function teamFileName(name) {
  const safe = [...name].map(char => char.charCodeAt(0) < 32 || /[<>:"/\\|?*]/.test(char) ? '_' : char).join('')
  return `${safe.replace(/[. ]+$/g, '').slice(0, 80) || 'pokemon-team'}.json`
}
