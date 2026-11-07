import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { digest, frozenFileDigest, loadSuite, categories } from './evaluation.mjs'

const baseFile = 'evaluation/campus/s2/manifest.json'
const bounded = (value, max) => typeof value === 'string' && value.trim() && value.length <= max
const normalized = value => value.normalize('NFKC').replace(/\s+/gu, '').trim()
export function auditQuestionIntake(intake, base, expectedBaseHash) {
  if (intake?.schemaVersion !== 'campus-question-intake-v1' || intake.base?.manifest !== baseFile
      || intake.base.manifestSha256 !== expectedBaseHash || intake.base.datasetId !== base.manifest.datasetId
      || intake.base.caseCount !== base.cases.length || !Number.isInteger(intake.targetSize) || intake.targetSize < base.cases.length
      || !Array.isArray(intake.candidates)) throw Error('Invalid intake or changed frozen baseline')
  const ids = new Set(), fingerprints = new Map(), rows = []
  const seedHashes = new Map(base.cases.map(c => [digest(normalized(c.prompt)), c.id]))
  for (const item of intake.candidates) {
    if (!/^[A-Z0-9-]{1,80}$/.test(item.id) || ids.has(item.id) || !bounded(item.promptOriginal, 2000)
        || item.status !== 'candidate' || item.split !== 'unassigned' || item.humanReview !== 'pending') throw Error('Candidate intake cannot publish or assign benchmark cases')
    ids.add(item.id)
    if (!['USER_TYPED', 'USER_REPORTED_APP_PROMPT', 'AI_AUTHORED_VARIANT'].includes(item.origin?.kind)
        || !bounded(item.origin.locator, 500) || !bounded(item.origin.excerpt, 4000)
        || !/^\d{4}-\d{2}-\d{2}$/.test(item.origin.recordedAt || '')
        || !normalized(item.origin.excerpt).includes(normalized(item.promptOriginal))) throw Error('Untraceable question origin')
    if (item.origin.kind === 'AI_AUTHORED_VARIANT' && (!item.origin.derivedFrom || !intake.candidates.some(c => c.id === item.origin.derivedFrom && c.id !== item.id && c.origin.kind !== 'AI_AUTHORED_VARIANT'))) throw Error('AI variants need an original candidate and remain non-independent')
    if (!categories.includes(item.categoryProposal) || !bounded(item.groupProposal, 150) || !item.context
        || item.context.source !== 'USER_REPORTED_UI_CONTEXT_NOT_VERIFIED_GOLD'
        || !item.reviewFocus?.length || item.reviewFocus.some(t => !bounded(t, 1000))
        || !item.sourcesToCollect?.length || item.sourcesToCollect.some(t => !bounded(t, 1000))) throw Error('Missing candidate context or pending evidence needs')
    const fingerprint = digest(normalized(item.promptOriginal)), duplicateCandidate = fingerprints.get(fingerprint) || null
    fingerprints.set(fingerprint, item.id)
    rows.push({ id: item.id, originKind: item.origin.kind, promptSha256: fingerprint, exactSeedMatch: seedHashes.get(fingerprint) || null,
      exactCandidateMatch: duplicateCandidate, status: 'PENDING_SOURCE_GROUPING_AND_HUMAN_REVIEW' })
  }
  return { schemaVersion: 'campus-question-intake-audit-v1', targetSize: intake.targetSize, frozenFormalCases: base.cases.length,
    pendingCandidates: rows.length, userReportedCandidates: rows.filter(r => r.originKind !== 'AI_AUTHORED_VARIANT').length,
    aiAuthoredVariants: rows.filter(r => r.originKind === 'AI_AUTHORED_VARIANT').length, newBenchmarkAdmissions: 0,
    unfilledFormalSlots: Math.max(0, intake.targetSize - base.cases.length), exactDuplicateCandidates: rows.filter(r => r.exactSeedMatch || r.exactCandidateMatch).length,
    groupingReview: 'PENDING_SEMANTIC_DEDUPLICATION', rows,
    scope: 'Question intake only. Existing source-linked seeds are not independently verified user samples; candidates and AI variants do not enlarge the benchmark or assign heldout membership.' }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [file = 'evaluation/campus/question-intake/candidates-v1.json', ...extra] = process.argv.slice(2)
  if (extra.length) throw Error('Usage: node tools/campus/question-intake.mjs [CANDIDATES_JSON]')
  const intake = JSON.parse(fs.readFileSync(file, 'utf8')), suite = loadSuite('evaluation/campus/s2')
  console.log(JSON.stringify(auditQuestionIntake(intake, suite, frozenFileDigest(baseFile)), null, 2))
}
