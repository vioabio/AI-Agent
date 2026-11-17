// Strict first U2 block. Unknown kinds fail closed and must fall back to existing text.
export function parsePokemonBlock(value) {
  const keys = ['schemaVersion', 'blockId', 'kind', 'formId', 'name', 'types', 'stats', 'gameReleaseId', 'dataReleaseId', 'evidenceDigest']
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(key => !keys.includes(key)) || keys.some(key => !Object.hasOwn(value, key))) return null
  if (value.schemaVersion !== 'answer-block-v1' || value.kind !== 'pokemon' || !Number.isSafeInteger(value.formId) || value.formId < 1 || value.blockId !== `pokemon-${value.formId}`) return null
  if (!['name', 'gameReleaseId', 'dataReleaseId'].every(key => typeof value[key] === 'string' && value[key].trim() && value[key].length <= 100)) return null
  if (!Array.isArray(value.types) || value.types.length < 1 || value.types.length > 2 || !value.types.every(t => typeof t === 'string' && t.trim() && t.length <= 10)) return null
  if (!Array.isArray(value.stats) || value.stats.length !== 6 || !value.stats.every(n => Number.isInteger(n) && n >= 1 && n <= 255)) return null
  if (!/^sha256:[a-f0-9]{64}$/.test(value.evidenceDigest)) return null
  return { ...value, types: [...value.types], stats: [...value.stats] }
}
