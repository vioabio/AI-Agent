// Explicit, provisional development cases from two user reports; never publishes into the frozen benchmark.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { digest, frozenFileDigest, loadSuite, validateSuite } from './evaluation.mjs'
import { validateSnapshot } from '../reference-cache.mjs'
import { auditQuestionIntake } from './question-intake.mjs'
import { pokemonFactQuestion } from '../../vio-ai-agent-frontend/src/api/reference-prompts.js'
import { factProjection } from './reference-case.mjs'

const types = ['一般','火','水','电','草','冰','格斗','毒','地面','飞行','超能力','虫','岩石','幽灵','龙','恶','钢','妖精']
const typeKeys = ['normal','fire','water','electric','grass','ice','fighting','poison','ground','flying','psychic','bug','rock','ghost','dragon','dark','steel','fairy']
const typeZh = value => { const i = typeKeys.indexOf(value); if (i < 0) throw Error('Unknown type'); return types[i] }
export function referenceFromSnapshots(pokemon, species) {
  const p = pokemon.body, s = species.body
  if (p.species.name !== s.name) throw Error('Mismatched species source')
  const names = s.names.filter(n => n.language.name.toLowerCase() === 'zh-hans')
  if (names.length !== 1) throw Error('Missing unambiguous Chinese name')
  const stats = Object.fromEntries(p.stats.map(row => [row.stat.name, row.base_stat]))
  const fact = factProjection({ fact: { formId: p.id, speciesId: s.id, nationalNo: s.id, slug: p.name, speciesNameZh: names[0].name,
    dataReleaseId: 'pokeapi-reference-v1', gameReleaseId: 'pokemon-scarlet-violet', sourceUrl: pokemon.envelope.url,
    checksum: 'sha256:' + digest(pokemon.envelope.sha256 + species.envelope.sha256), releaseStatus: 'LOCAL_REFERENCE',
    type1: typeZh(p.types[0].type.name), type2: p.types[1] ? typeZh(p.types[1].type.name) : null,
    hp: stats.hp, attack: stats.attack, defense: stats.defense, spAttack: stats['special-attack'], spDefense: stats['special-defense'], speed: stats.speed } })
  return { fact, aliases: [p.name, String(p.id), fact.speciesNameZh],
    availableAbilitySlugs: p.abilities.map(a => a.ability.name),
    availableMoveSlugs: (p.moves || []).filter(m => m.version_group_details?.some(v => v.version_group.name === 'scarlet-violet')).map(m => m.move.name),
    scope: 'Captured Pokemon/species fields and version-group move names; not ability effects, competition legality or current metagame' }
}
export function buildUserReportSeed(bundleDirectory, destination, {corpusFile='src/main/resources/pokemon-guides/expanded-v1.json',datasetId='campus-user-report-dev-v1'}={}) {
  if (fs.existsSync(destination)) throw Error('Never overwrite a provisional suite')
  const intakeFile = 'evaluation/campus/question-intake/candidates-v1.json', intake = JSON.parse(fs.readFileSync(intakeFile))
  auditQuestionIntake(intake, loadSuite('evaluation/campus/s2'), frozenFileDigest('evaluation/campus/s2/manifest.json'))
  if(!['src/main/resources/pokemon-guides/expanded-v1.json','src/main/resources/pokemon-strategy/overview-v1.json'].includes(corpusFile)
      ||!/^campus-user-report-dev-v[1-9][0-9]*$/.test(datasetId))throw Error('Unsupported source version')
  const corpus = JSON.parse(fs.readFileSync(corpusFile))
  const manifest = JSON.parse(fs.readFileSync(path.join(bundleDirectory, 'manifest.json')))
  const entry = file => {
    const metadata = manifest.files.find(m => m.file === file)
    if (!metadata) throw Error('Missing paired public snapshot')
    const raw = fs.readFileSync(path.join(bundleDirectory, file))
    if (digest(raw) !== metadata.fileSha256) throw Error('Changed public snapshot')
    return validateSnapshot(raw, file)
  }
  const references = Object.fromEntries(['squirtle','froslass'].map(slug => [slug, referenceFromSnapshots(entry(`pokemon-${slug}.json`), entry(`pokemon-species-${slug}.json`))]))
  references.squirtle.requireLookup = true; references.froslass.requireLookup = false
  const capturedAt = new Date().toISOString(), candidates = intake.candidates
  const sources = Object.entries(references).map(([slug, reference]) => ({ id: `pokemon-${slug}`, locator: reference.fact.sourceUrl,
    sha256: reference.fact.checksum, capturedAt, scope: reference.scope, fact: reference.fact,
    pokemonSnapshot: manifest.files.find(f => f.file === `pokemon-${slug}.json`), speciesSnapshot: manifest.files.find(f => f.file === `pokemon-species-${slug}.json`) }))
  for (const d of corpus.documents) sources.push({ id: 'guide-' + d.id, locator: `${corpusFile}#${d.id}`, sha256: digest(d.content), capturedAt,
    scope: 'Project-authored notes, pending independent human fact review', document: d })
  const context = { gameReleaseId: 'pokemon-scarlet-violet', battleFormat: 'casual-singles', language: 'zh-CN', rulesetVersionId: 'casual-v1', dataReleaseId: 'pokeapi-reference-v1' }
  const cases = ['squirtle','froslass'].map((slug, i) => {
    const candidate = candidates[i], reference = references[slug]
    const originalExpectedName = candidate.context.species
    if (reference.fact.speciesNameZh !== originalExpectedName) throw Error('Candidate species differs from captured source')
    const ids = [`pokemon-${slug}`, 'guide-training-configuration-v1', 'guide-reference-vs-rules-v1',
      ...corpus.documents.filter(d=>d.applicability?.subjects?.includes(slug)).map(d=>'guide-'+d.id)]
    return { id: `S2-GUIDES-${9 + i < 10 ? '0' : ''}${9 + i}`, category: 'guides', groupId: candidate.groupProposal, split: 'dev',
      prompt: i === 0 ? candidate.promptOriginal : pokemonFactQuestion(reference.fact), versions: context, preconditions: { team: 'probe' },
      expected: { outcome: 'answer', requiredTools: ['searchPokemonGuides'], forbiddenTools: ['searchWeb','previewTeamChanges','applyConfirmedTeamChanges','writeFile','terminal'],
        referenceFixture: slug, forbiddenPatterns: ['Let me proceed with the final answer','I see the issue','我已经有足够资料','checksum','run-[a-f0-9-]{20,}'],
        criteria: [
          { id: 'species-facts', text: `介绍${reference.fact.speciesNameZh}，区分种族值与实际能力，事实符合本次捕获来源`, sourceIds: [`pokemon-${slug}`] },
          { id: 'configuration-boundary', text: '培养建议给出条件与取舍，不把缺省值或未读取的特性/招式效果当成已确认事实', sourceIds: ids },
          { id: 'metagame-boundary', text: '说明本地参考缺少当前热门阵容/正式赛制证据，不编造排名、胜率或合法性', sourceIds: ids },
          { id: 'source-relevance', text: '引用实际存在且与本问题相关，资料不足时明确说明；通用攻略不能冒充完整物种战术答案', sourceIds: ids },
          { id: 'user-facing-answer', text: '用中文给出可读正文，不输出内部规划、工具行为猜测或原始JSON；未执行操作不宣称成功', sourceIds: ids }
        ] }, annotation: { by: 'Codex AI assisted source-linked authoring', date: capturedAt.slice(0,10), humanReview: 'pending',
        candidateId: candidate.id, originKind: candidate.origin.kind, promptOriginal: candidate.promptOriginal,
        transformation: i === 0 ? 'ORIGINAL_USER_TEXT' : 'CURRENT_UI_REFERENCE_PROMPT_WITH_VERIFIED_FACT_CARD',
        promptBuilderSha256: digest(fs.readFileSync('vio-ai-agent-frontend/src/api/reference-prompts.js')) } }
  })
  const stats = value => ({ hp: value, atk: value, def: value, spa: value, spd: value, spe: value })
  const fixtures = { context, references, damage: {}, teams: { probe: { name: '用户反馈只读回归隔离队伍', rulesetId: 'casual-v1', dataReleaseId: 'pokeapi-reference-v1',
    members: [{ formId: 7, slot: 1, build: { level: 50, nature: 'hardy', ability: '', item: '', moves: [], evs: stats(0), ivs: stats(31) } }] } } }
  const values = { 'cases.json': cases, 'sources.json': sources, 'splits.json': { dev: cases.map(c => c.id), heldout: [] }, 'fixtures.json': fixtures }
  const bytes = Object.fromEntries(Object.entries(values).map(([file, value]) => [file, JSON.stringify(value, null, 2) + '\n']))
  const release = { schemaVersion: 1, datasetId, caseCount: 2, targetCaseCount: 100,
    status: 'PROVISIONAL_DEVELOPMENT_NOT_HUMAN_ACCEPTED', independentTrafficSample: false, requiredEvaluationMode: 'READ_ONLY_TOOLS', createdAt: capturedAt,
    originIntakeSha256: frozenFileDigest(intakeFile), frozenBaseSha256: intake.base.manifestSha256, corpusSha256: frozenFileDigest(corpusFile),
    publicBundleManifestSha256: digest(fs.readFileSync(path.join(bundleDirectory, 'manifest.json'))), newFormalAdmissions: 0,
    sha256: Object.fromEntries(Object.entries(bytes).map(([file, value]) => [file, digest(value)])) }
  validateSuite({ manifest: release, cases, sources, splits: values['splits.json'], fixtures })
  fs.mkdirSync(destination, { recursive: true })
  for (const [file, value] of Object.entries(bytes)) fs.writeFileSync(path.join(destination, file), value, { flag: 'wx' })
  fs.writeFileSync(path.join(destination, 'manifest.json'), JSON.stringify(release, null, 2) + '\n', { flag: 'wx' })
  return { datasetId: release.datasetId, provisionalCases: cases.length, formalAdmissions: 0, facts: Object.keys(references), path: destination }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [bundle, output, ...extra] = process.argv.slice(2)
  if (!bundle || !output || extra.length) throw Error('Usage: node tools/campus/build-user-report-seed.mjs PUBLIC_BUNDLE NEW_SUITE')
  console.log(JSON.stringify(buildUserReportSeed(bundle, output)))
}
