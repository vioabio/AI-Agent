export const defaultConstraints = () => ({ revision: 0, responseStyle: 'STANDARD', trainingBudget: 'UNRESTRICTED', avoidLegendary: false, notes: '' })
export function normalizeConstraints(value) {
  if (!value || !Number.isSafeInteger(value.revision) || value.revision < 0 || value.revision >= Number.MAX_SAFE_INTEGER
      || !['STANDARD', 'CONCISE', 'DETAILED'].includes(value.responseStyle)
      || !['UNRESTRICTED', 'LOW'].includes(value.trainingBudget) || typeof value.avoidLegendary !== 'boolean'
      || typeof value.notes !== 'string' || value.notes.length > 800 || [...value.notes].some(character => {
        const code = character.codePointAt(0)
        return code !== 10 && (code < 32 || code >= 127 && code <= 159)
      })) return null
  return { revision: value.revision, responseStyle: value.responseStyle, trainingBudget: value.trainingBudget,
    avoidLegendary: value.avoidLegendary, notes: value.notes.trim() }
}
