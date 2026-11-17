import { parseDamageEvidence } from './evidence-answer-block.js'
const text = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 80
const exact = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key))
const integer = (value, min, max) => Number.isSafeInteger(value) && value >= min && value <= max
const stats = (value, max) => Array.isArray(value) && value.length === 6 && value.every(v => integer(v, 0, max))
function member(value) {
  if (!exact(value, ['species', 'level', 'nature', 'ability', 'item', 'evs', 'ivs']) || !['species', 'nature', 'ability'].every(key => text(value[key])) || typeof value.item !== 'string' || value.item.length > 80 || !integer(value.level, 1, 100) || !stats(value.evs, 252) || value.evs.reduce((sum, n) => sum + n, 0) > 510 || !stats(value.ivs, 31)) return null
  return { ...value, evs: [...value.evs], ivs: [...value.ivs] }
}
export function parseDamageBlock(value) {
  const keys = ['schemaVersion', 'blockId', 'kind', 'attacker', 'defender', 'move', 'field', 'minimum', 'maximum', 'defenderHp', 'minimumPercent', 'maximumPercent', 'gameReleaseId', 'dataReleaseId', 'evidenceDigest']
  if (value && Object.hasOwn(value, 'evidence')) keys.push('evidence')
  if (!exact(value, keys) || value.schemaVersion !== 'answer-block-v1' || value.kind !== 'damageResult' || !/^damage-[a-f0-9]{64}$/.test(value.blockId) || !text(value.move) || value.gameReleaseId !== 'pokemon-scarlet-violet' || value.dataReleaseId !== 'pokeapi-reference-v1' || !/^sha256:[a-f0-9]{64}$/.test(value.evidenceDigest)) return null
  const attacker = member(value.attacker), defender = member(value.defender), field = value.field
  if (!attacker || !defender || !exact(field, ['gameType', 'weather', 'terrain']) || !['Singles', 'Doubles'].includes(field.gameType) || ![null, 'Sun', 'Rain', 'Sand', 'Hail', 'Snow', 'Harsh Sunshine', 'Heavy Rain', 'Strong Winds'].includes(field.weather) || ![null, 'Electric', 'Grassy', 'Misty', 'Psychic'].includes(field.terrain)) return null
  if (!integer(value.minimum, 0, 2147483647) || !integer(value.maximum, value.minimum, 2147483647) || !integer(value.defenderHp, 1, 2147483647) || !Number.isFinite(value.minimumPercent) || !Number.isFinite(value.maximumPercent) || Math.abs(value.minimumPercent - 100 * value.minimum / value.defenderHp) > 0.01 || Math.abs(value.maximumPercent - 100 * value.maximum / value.defenderHp) > 0.01) return null
  const evidence = value.evidence == null ? null : parseDamageEvidence(value.evidence, value.blockId, value.evidenceDigest)
  if (value.evidence != null && !evidence) return null
  return { ...value, attacker, defender, field: { ...field }, evidence }
}
