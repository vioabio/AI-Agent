import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { compareAblation, excerptEvidence, modes, validateAblationPlan, allowedTools, tokenTotals, evidenceHash } from './ablation.mjs'
const plan = JSON.parse(fs.readFileSync('evaluation/campus/ablation/manifest.json'))
function captures() { return modes.map(mode => ({ mode, sourcesUnchanged:true,planSha256: 'fixed', corpusSha256: 'fixed', sourceSha256: { file: 'fixed' },
  runtime: { evaluationPolicy:{mode,active:true},configuredProvider: 'fixture', configuredModelId: 'fixture', generationDefaults: { temperature: 0 }, implementationSha256: { agent: 'fixed' } },
  records: plan.cases.map(c => ({ caseId: c.id, collected: true, status: 'COMPLETED', historyMatches: true,
    usageMatchesHistory: true, modelCalls: 2, startedTools: mode === 'MODEL_ONLY' ? [] : ['searchPokemonGuides'], runtimeTools: mode === 'MODEL_ONLY' ? [] : ['searchPokemonGuides'], excerpts: [] })) })) }
test('fixed development matrix preserves six planned cases, including failed collections', () => {
  validateAblationPlan(plan); const all = captures(); assert.equal(compareAblation(plan, all).engineeringPass, 6)
  all[1].records[0] = { caseId: plan.cases[0].id, collected: false }
  const result = compareAblation(plan, all); assert.equal(result.denominator, 6); assert.equal(result.engineeringPass, 5)
})
test('missing modes, cases and differing model settings cannot become a comparison', () => {
  assert.throws(() => compareAblation(plan, captures().slice(1)), /mode/)
  const a = captures(); a[1].records.pop(); assert.throws(() => compareAblation(plan, a), /planned/)
  const b = captures(); b[1].runtime.generationDefaults.temperature = 0.7; assert.throws(() => compareAblation(plan, b), /differs/)
  const c = captures(); c[2].sourceSha256.file = 'changed'; assert.throws(() => compareAblation(plan, c), /differs/)
  const d = captures(); d[0].sourcesUnchanged = false; assert.throws(() => compareAblation(plan,d),/Unverified/)
})
test('advertised and attempted tools both obey the selected arm', () => {
  assert.equal(allowedTools('MODEL_ONLY', ['searchPokemonGuides']), false)
  assert.equal(allowedTools('GUIDES_ONLY', ['previewTeamChanges']), false)
  assert.equal(allowedTools('READ_ONLY_TOOLS', ['searchWeb']), false)
  const a = captures(); a[2].records[0].startedTools = ['previewTeamChanges']; assert.equal(compareAblation(plan, a).engineeringPass, 5)
})

test('equivalent JSON object key order does not reject identical generation and budgets', () => {
  const all = captures()
  for (const c of all) {
    c.runtime.contextBudgetPolicy = { tokens: 32768, nested: { reserve: 1024, history: 4096 } }
    c.runtime.taskBudgetPolicy = { calls: 16, tokens: 131072 }
    c.runtime.generationDefaults = { temperature: 0, topP: 1 }
    c.sourceSha256 = { first: 'fixed-a', second: 'fixed-b' }
    c.runtime.implementationSha256 = { first: 'class-a', second: 'class-b' }
  }
  all[2].runtime.contextBudgetPolicy = { nested: { history: 4096, reserve: 1024 }, tokens: 32768 }
  all[2].runtime.taskBudgetPolicy = { tokens: 131072, calls: 16 }
  all[2].runtime.generationDefaults = { topP: 1, temperature: 0 }
  all[2].sourceSha256 = { second: 'fixed-b', first: 'fixed-a' }
  all[2].runtime.implementationSha256 = { second: 'class-b', first: 'class-a' }
  assert.equal(compareAblation(plan, all).engineeringPass, 6)
  // Existing observation hashes are not silently changed by configuration canonicalization.
  assert.notEqual(evidenceHash({ a: 1, b: 2 }), evidenceHash({ b: 2, a: 1 }))
})

test('real nested budget changes, class changes and array order still reject comparison', () => {
  for (const field of ['contextBudgetPolicy', 'taskBudgetPolicy', 'implementationSha256']) {
    const all = captures()
    for (const c of all) c.runtime[field] = { nested: { limit: 16 } }
    all[2].runtime[field].nested.limit = 15
    assert.throws(() => compareAblation(plan, all), /differs/)
  }
  const all = captures()
  for (const c of all) c.runtime.generationDefaults.stopSequences = ['first', 'second']
  all[2].runtime.generationDefaults.stopSequences.reverse()
  assert.throws(() => compareAblation(plan, all), /differs/)
})
test('literal escaped excerpts match frozen source text without granting semantic acceptance', () => {
  const result = excerptEvidence('原文\n> A &lt; B \\*配置\\*\n\n> 不存在的引文', [{ id: 'known', content: 'A < B *配置*' }])
  assert.deepEqual(result.map(r => r.documentIds), [['known'], []])
  assert.equal(compareAblation(plan, captures()).rows[0].semanticAcceptance, 'PENDING_HUMAN_REVIEW')
})
test('missing or partial token evidence stays unknown rather than a zero cost claim',()=>{
  assert.equal(tokenTotals([]),null);assert.equal(tokenTotals([{coverage:'INSTRUMENTED',usageStatus:'PARTIAL'}]),null)
  assert.equal(compareAblation(plan,captures()).rows[0].tokens,null)
})
