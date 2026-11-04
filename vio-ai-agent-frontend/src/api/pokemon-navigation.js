const normalized = value => String(value || '').trim().toLowerCase()
const escapePattern = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const matcherCache = new WeakMap()

export function matchingPokemonNames(query, names, limit = 12) {
  const q = normalized(query)
  if (!q) return []
  return names.filter(entry => [entry.nameZh, entry.slug, ...(entry.aliases || []), String(entry.nationalNo)]
    .some(value => normalized(value).includes(q))).slice(0, limit)
}

export function factSearchParameters(query, nationalNo = '', names = []) {
  const q = normalized(query)
  // The main input is authoritative; a previous advanced number must not filter a new name.
  if (q) {
    if (/^#?\d+$/.test(q)) return { nationalNo: Number(q.replace('#', '')) }
    const entry = names.find(item => [item.nameZh, item.slug, ...(item.aliases || [])].some(name => normalized(name) === q))
    return entry ? { nationalNo: entry.nationalNo } : { slug: q }
  }
  return nationalNo.trim() ? { nationalNo: Number(nationalNo.trim()) } : {}
}

/** Resolve only names present in this answer; prior turns cannot supply unrelated targets. */
export function pokemonTargetsForMessage(content, names) {
  const text = String(content || '')
  if (!matcherCache.has(names)) {
    matcherCache.set(names, names.flatMap(entry => [...new Set([entry.nameZh, entry.slug, ...(entry.aliases || [])])]
      .filter(Boolean).map(alias => ({ entry, expression: /^[a-z0-9-]+$/i.test(alias)
        ? new RegExp(`(?<![a-z0-9-])${escapePattern(alias).replaceAll('-', '[ -]')}(?![a-z0-9-])`, 'gi')
        : new RegExp(escapePattern(alias), 'g') }))))
  }
  const spans = []
  for (const { entry, expression } of matcherCache.get(names)) {
    expression.lastIndex = 0
    for (const match of text.matchAll(expression)) spans.push({ entry, start: match.index, end: match.index + match[0].length })
  }
  const targets = new Map()
  for (const span of spans.sort((a, b) => a.start - b.start || b.end - a.end)) {
    // “仙子伊布” is one species; its substring “伊布” is not another explicit mention.
    if (spans.some(other => other.start <= span.start && other.end >= span.end && other.end - other.start > span.end - span.start)) continue
    if (!targets.has(span.entry.nationalNo)) targets.set(span.entry.nationalNo, span.entry)
  }
  return [...targets.values()].slice(0, 6)
}
