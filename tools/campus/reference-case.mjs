import { digest, sameJson } from './evaluation.mjs'
import { collectToolInputs } from './tool-inputs.mjs'
import { formatChecks } from './answer-quality.mjs'
import { pokemonFactQuestion } from '../../vio-ai-agent-frontend/src/api/reference-prompts.js'

export function validateReferenceSuite(suite) {
  for (const item of suite.cases) {
    if (!item.expected.referenceFixture) continue
    const fixture = suite.fixtures.references?.[item.expected.referenceFixture], fact = fixture?.fact
    if (!fact || !fixture.aliases?.length || fixture.aliases.some(v => typeof v !== 'string' || !v || v.length > 80)
        || typeof fixture.requireLookup !== 'boolean' || fact.dataReleaseId !== item.versions.dataReleaseId || fact.gameReleaseId !== item.versions.gameReleaseId
        || !['hp','attack','defense','spAttack','spDefense','speed'].every(k => Number.isInteger(fact[k]) && fact[k] > 0)
        || !/^sha256:[a-f0-9]{64}$/.test(fact.checksum) || !/^https:\/\/pokeapi\.co\/api\/v2\/pokemon\/[a-z0-9-]+\/$/.test(fact.sourceUrl)) throw Error('Invalid reference fixture/version')
    if (!fixture.requireLookup && (item.annotation.transformation !== 'CURRENT_UI_REFERENCE_PROMPT_WITH_VERIFIED_FACT_CARD'
        || item.prompt !== pokemonFactQuestion(fact))) throw Error('Lookup can only be optional for the exact verified UI fact card')
  }
  return suite
}

export function resolveRuntimeClasses(sources, implementation) {
  return Object.entries(implementation).map(([name, sha256]) => {
    if (!/^[A-Za-z][A-Za-z0-9]*$/.test(name) || !/^[a-f0-9]{64}$/.test(sha256)) throw Error('Invalid implementation identity')
    const matches = Object.keys(sources).filter(file => file.startsWith('src/main/java/') && file.endsWith('/' + name + '.java'))
    if (matches.length !== 1) throw Error('Missing or ambiguous runtime source: ' + name)
    return { name, file: matches[0].replace('src/main/java/', 'target/classes/').replace(/\.java$/, '.class'), sha256 }
  })
}

export const factProjection = detail => detail?.fact && Object.fromEntries([
  'formId', 'speciesId', 'nationalNo', 'slug', 'speciesNameZh', 'dataReleaseId', 'gameReleaseId', 'sourceUrl', 'checksum', 'releaseStatus',
  'type1', 'type2', 'hp', 'attack', 'defense', 'spAttack', 'spDefense', 'speed'
].map(key => [key, detail.fact[key] ?? null]))

export function checkReferenceCase(item, fixture, record, events) {
  if (!fixture?.fact || !fixture.aliases?.length || typeof fixture.requireLookup !== 'boolean') throw Error('Explicit reference fixture required')
  const calls = collectToolInputs(events).filter(call => ['lookupPokemonReference', 'lookupPokemonTeamReference'].includes(call.tool))
  const successful = calls.filter(call => ['SUCCEEDED', 'EXECUTED', 'DEDUPLICATED'].includes(call.status))
  const matching = successful.filter(call => {
    if (!call.bound || call.evidence?.fields?.['/dataReleaseId']?.value !== fixture.fact.dataReleaseId) return false
    return Object.entries(call.evidence.fields).some(([field, proof]) => (field === '/query' || /^\/queries\/[0-5]$/.test(field))
      && proof.kind === 'SHA256_JSON' && fixture.aliases.some(alias => proof.sha256 === digest(JSON.stringify(alias))))
  })
  const formatting = formatChecks({ maxCharacters: 12000 }, record.answer || '')
  const checks = {
    referenceBeforeMatches: sameJson(record.referenceBefore, fixture.fact),
    referenceAfterMatches: sameJson(record.referenceAfter, fixture.fact),
    referenceInputVerified: !fixture.requireLookup || matching.length > 0,
    publicAnswerFormat: formatting.pass,
    requestedSpeciesPresent: (record.answer || '').includes(fixture.fact.speciesNameZh)
  }
  return { checks, lookup: { required: fixture.requireLookup, successfulCalls: successful.length, matchingCallIds: matching.map(c => c.callId),
      scope: fixture.requireLookup ? 'Target identity and release in supplied arguments, not all output facts or advice' : 'Current UI fact card validated against the pinned snapshot; lookup not mandatory' },
    formatting, sourceIds: item.expected.criteria.flatMap(c => c.sourceIds),
    scope: 'Snapshot/API equality, bounded format and target-input checks; semantic advice, source relevance and current metagame still need review' }
}
