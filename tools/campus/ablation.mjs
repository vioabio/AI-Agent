import { createHash } from 'node:crypto'
import { normalizeModelUsage } from '../../vio-ai-agent-frontend/src/api/model-usage.js'
export const modes = ['MODEL_ONLY', 'GUIDES_ONLY', 'READ_ONLY_TOOLS']
const readonly = new Set(['searchPokemonGuides', 'lookupPokemonReference', 'lookupPokemonResource', 'lookupPokemonTeamReference', 'calculateBattleDamage', 'checkTypeEffectiveness', 'getTypeChart'])
export const evidenceHash = value => createHash('sha256').update(JSON.stringify(value === undefined ? null : value)).digest('hex')
// JSON object order is not configuration identity. Keep arrays ordered and keep
// observation hashes byte-compatible with existing human-review bindings.
function identityHash(value) {
  const canonical = item => Array.isArray(item) ? item.map(canonical)
    : item !== null && typeof item === 'object'
      ? Object.fromEntries(Object.keys(item).sort().map(key => [key, canonical(item[key])])) : item
  return evidenceHash(canonical(value))
}
export function validateAblationPlan(plan) {
  if (plan?.schemaVersion !== 'campus-tool-ablation-plan-v1' || plan.split !== 'development-only'
    || JSON.stringify(plan.modes) !== JSON.stringify(modes) || !Array.isArray(plan.cases) || !plan.cases.length
    || new Set(plan.cases.map(c => c.id)).size !== plan.cases.length
    || plan.cases.some(c => !/^[A-Z0-9-]+$/.test(c.id) || typeof c.prompt !== 'string' || !c.prompt.trim()
      || !Array.isArray(c.relevantDocumentIds) || !c.relevantDocumentIds.length || !c.semanticCriteria?.length)) throw Error('Invalid fixed ablation plan')
  return plan
}
export function allowedTools(mode, names) {
  if (!modes.includes(mode) || !Array.isArray(names)) return false
  if (mode === 'MODEL_ONLY') return names.length === 0
  if (mode === 'GUIDES_ONLY') return names.every(name => name === 'searchPokemonGuides')
  return names.every(name => readonly.has(name))
}
export function tokenTotals(attempts = []) {
  const rows=attempts.map(normalizeModelUsage)
  if(!rows.length||rows.some(r=>!r||r.usageStatus!=='COMPLETE'||!r.totals))return null
  return rows.reduce((sum,r)=>({inputTokens:sum.inputTokens+r.totals.inputTokens,outputTokens:sum.outputTokens+r.totals.outputTokens,
    totalTokens:sum.totalTokens+r.totals.totalTokens}),{inputTokens:0,outputTokens:0,totalTokens:0})
}
function decode(text) { return text.replace(/\\([\\`*_\[\]|])/g, '$1').replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&amp;', '&') }
export function excerptEvidence(answer, documents) {
  const blocks = []; let current = []
  const flush = () => { if (current.length) { blocks.push(decode(current.join('\n'))); current = [] } }
  for (const line of answer.split('\n')) { const match = /^> ?(.*)$/.exec(line); if (match) current.push(match[1]); else flush() }
  flush()
  return blocks.map(text => ({ text, documentIds: documents.filter(d => text.trim() && d.content.includes(text)).map(d => d.id) }))
}
export function compareAblation(plan, captures) {
  validateAblationPlan(plan)
  if (captures.length !== modes.length || modes.some(mode => captures.filter(c => c.mode === mode).length !== 1)) throw Error('Missing or duplicate mode')
  const first = captures[0]
  for (const capture of captures) {
    if(capture.sourcesUnchanged!==true||capture.setupError||capture.cleanupFailed||capture.runtime?.evaluationPolicy?.mode!==capture.mode
      ||capture.runtime.evaluationPolicy.active!==true)throw Error('Unverified capture identity')
    if (capture.planSha256 !== first.planSha256 || capture.corpusSha256 !== first.corpusSha256
      || capture.runtime?.configuredProvider !== first.runtime?.configuredProvider || capture.runtime?.configuredModelId !== first.runtime?.configuredModelId
      || identityHash(capture.runtime?.generationDefaults) !== identityHash(first.runtime?.generationDefaults)
      || identityHash(capture.runtime?.contextBudgetPolicy) !== identityHash(first.runtime?.contextBudgetPolicy)
      || identityHash(capture.runtime?.taskBudgetPolicy) !== identityHash(first.runtime?.taskBudgetPolicy)
      || identityHash(capture.sourceSha256) !== identityHash(first.sourceSha256)
      || identityHash(capture.runtime?.implementationSha256) !== identityHash(first.runtime?.implementationSha256)) throw Error('Comparison identity or configured generation differs')
    if (capture.records?.length !== plan.cases.length || plan.cases.some(item => capture.records.filter(r => r.caseId === item.id).length !== 1)) throw Error('Lost planned case')
  }
  const rows = modes.flatMap(mode => {
    const capture = captures.find(c => c.mode === mode)
    return plan.cases.map(item => {
      const r = capture.records.find(r => r.caseId === item.id), completed = r.collected === true && r.status === 'COMPLETED' && r.historyMatches === true && !r.error && !r.cleanupFailed
      const quoted = r.excerpts || [], supported = new Set(quoted.filter(q => q.documentIds?.length).flatMap(q => q.documentIds))
      return { mode, caseId: item.id, completed, toolBoundary: allowedTools(mode, r.startedTools || []) && allowedTools(mode, r.runtimeTools || []),
        modelCalls: Number.isInteger(r.modelCalls) ? r.modelCalls : null, elapsedMs: r.elapsedMs ?? null,
        tokens:tokenTotals(r.modelUsageAttempts),cost:'UNKNOWN_NOT_BILL_RECONCILED',
        quotedBlocks: quoted.length, corpusMatchedBlocks: quoted.filter(q => q.documentIds?.length).length,
        relevantDocumentExcerptPresent: item.relevantDocumentIds.some(id => supported.has(id)),
        usageMatchesHistory: r.usageMatchesHistory === true, observationHash: evidenceHash(r), semanticAcceptance: 'PENDING_HUMAN_REVIEW' }
    })
  })
  return { schemaVersion: 'tool-ablation-comparison-v1', denominator: modes.length * plan.cases.length, rows,
    engineeringPass: rows.filter(r => r.completed && r.toolBoundary && r.usageMatchesHistory && r.modelCalls > 0).length,
    scope: 'Same configured model and prompts, differing tool availability and program evidence guards; literal corpus matching is not citation relevance, semantic quality, causal RAG gain or heldout acceptance' }
}
