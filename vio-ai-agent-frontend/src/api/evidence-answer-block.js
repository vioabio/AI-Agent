const digest = value => typeof value === 'string' && /^sha256:[a-f0-9]{64}$/.test(value)
const exact = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key))

export function parseDamageEvidence(value, targetBlockId, projectionDigest) {
  if (!exact(value, ['schemaVersion', 'kind', 'targetBlockId', 'requestDigest', 'projectionDigest', 'engineVersion', 'scope', 'sources']) || value.schemaVersion !== 'answer-block-v1' || value.kind !== 'evidence' || value.targetBlockId !== targetBlockId || !/^damage-[a-f0-9]{64}$/.test(value.targetBlockId) || value.projectionDigest !== projectionDigest || !digest(value.projectionDigest) || !digest(value.requestDigest) || value.engineVersion !== '0.12.0' || value.scope !== 'PUBLIC_REFERENCE_AND_LOCAL_ENGINE_NOT_LEGALITY') return null
  if (!Array.isArray(value.sources) || value.sources.length !== 2 || new Set(value.sources.map(source => source?.role)).size !== 2 || !value.sources.every(source => exact(source, ['role', 'url', 'checksum']) && ['attacker', 'defender'].includes(source.role) && typeof source.url === 'string' && /^https:\/\/pokeapi\.co\/api\/v2\/pokemon\/[1-9][0-9]{0,5}\/$/.test(source.url) && digest(source.checksum))) return null
  return { ...value, sources: value.sources.map(source => ({ ...source })) }
}
