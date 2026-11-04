export function readLayoutPreference(key, fallback) {
  try {
    const value = JSON.parse(localStorage.getItem(`vio.workspace.${key}`))
    return typeof value === typeof fallback ? value : fallback
  } catch { return fallback }
}
export function saveLayoutPreference(key, value) {
  try { localStorage.setItem(`vio.workspace.${key}`, JSON.stringify(value)) } catch { /* Storage is optional. */ }
}
export function groupConversations(items, now = new Date()) {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1).getTime()
  const groups = new Map()
  for (const item of items.slice(0, 12)) {
    const date = new Date(item.updatedAt || item.createdAt)
    const day = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
    const label = day === today ? '今天' : day === yesterday ? '昨天' : '更早'
    if (!groups.has(label)) groups.set(label, [])
    groups.get(label).push(item)
  }
  return ['今天', '昨天', '更早'].filter(label => groups.has(label)).map(label => ({ label, items: groups.get(label) }))
}
export function statColor(value) {
  return Number(value) >= 120 ? '#58a77c' : Number(value) >= 80 ? '#dfb344' : '#d77b7e'
}
export function trapFocusWithin(event, element) {
  const nodes = [...(element?.querySelectorAll('button:enabled,a[href],input:enabled,select:enabled,textarea:enabled,summary,[tabindex="0"]') || [])]
    .filter(node => node.getClientRects().length && !node.closest('[inert]'))
  if (!nodes.length) { event.preventDefault(); element?.focus(); return }
  const first = nodes[0], last = nodes.at(-1)
  if (!nodes.includes(document.activeElement)) { event.preventDefault(); (event.shiftKey ? last : first).focus() }
  else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
