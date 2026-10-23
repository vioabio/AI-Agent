// Bounded source/snapshot regression checks, not a general semantic quality score.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadSuite, frozenFileDigest } from './evaluation.mjs'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const suitePath = resolve(root, 'evaluation/campus/s2')
const suite = loadSuite(suitePath)
const documents = suite.sources.filter(s => s.excerpt?.content && s.excerpt?.title).map(s => s.excerpt)
const unescape = value => value.replace(/\\([\\`*_\[\]|])/gu, '$1').replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&amp;', '&')
export function sourceSupport(answer, capturedDocuments = documents) {
  let current, block = [], quoteCount = 0, citationCount = 0, supported = true, metadata = false
  const flush = () => {
    if (!block.length) return
    quoteCount++
    if (!current || !metadata || !current.content.includes(unescape(block.join('\n')))) supported = false
    block = []
  }
  for (const line of answer.split('\n')) {
    if (line.startsWith('> ')) { block.push(line.slice(2)); continue }
    flush()
    const heading = /^\*\*(.+)\*\*$/u.exec(line)
    if (heading) { current = capturedDocuments.find(d => d.title === unescape(heading[1])); metadata = false; citationCount++; if (!current) supported = false }
    if (line.startsWith('作者：')) metadata = !!current && unescape(line) === `作者：${current.author} · 更新：${current.updatedAt}`
  }
  flush()
  return { supported: supported && citationCount > 0 && quoteCount === citationCount, citationCount, quoteCount }
}
export function checkRecord(record, capturedDocuments = documents) {
  const checks = { storageUnchanged: record.checks?.storageUnchanged === true && record.checks?.storageUnchangedAfterCleanup === true }
  const successful = name => record.toolOutcomes?.some(t => t.tool === name && ['SUCCEEDED', 'EXECUTED'].includes(t.status))
  if (record.caseId === 'S2-GUIDES-01') {
    const support = sourceSupport(record.answer || '', capturedDocuments)
    Object.assign(checks, { completed: record.status === 'COMPLETED', historyMatches: record.checks?.historyMatches === true,
      actualSearch: successful('searchPokemonGuides'), quotesSupportedByCapturedSources: support.supported,
      noKnownUnattributedExamples: !/(猫老大|妖精Z|精神之牙|八爪武师)/u.test(record.answer || '') })
  } else if (record.caseId === 'S2-CLARIFICATION-01') {
    Object.assign(checks, { completed: record.status === 'COMPLETED', historyMatches: record.checks?.historyMatches === true,
      actualRead: successful('readCurrentTeam'), storedHardyPreserved: (record.answer || '').includes('勤奋（hardy）') && !(record.answer || '').includes('固执'),
      baseStatsSeparatedFromLevel: (record.answer || '').includes('种族值与等级无关') && !/种族值[（(]\s*50\s*级/u.test(record.answer || ''),
      missingFieldsAskedWithoutFabricatedExamples: (record.answer || '').includes('招式、特性、道具未明确') && !/随意[、，/].{0,6}大雨/u.test(record.answer || ''),
      noCalculationOrPreview: !record.toolOutcomes?.some(t => ['calculateBattleDamage', 'previewTeamChanges'].includes(t.tool)) })
  } else if (record.caseId === 'S2-TEAM-01') {
    Object.assign(checks, { waitingApproval: record.status === 'WAITING_APPROVAL', actualRead: successful('readCurrentTeam'), actualPreview: successful('previewTeamChanges'),
      onlyExpectedChange: record.checks?.onlyExpectedChange === true && record.preview?.changes?.length === 1
        && record.preview.changes[0].slot === 1 && record.preview.changes[0].field === '等级' && record.preview.changes[0].before === '50' && record.preview.changes[0].after === '51',
      cancelledAfterCheck: record.cleanupCancelled === true })
  } else throw Error('This regression checker only covers the three fixed development cases')
  return { caseId: record.caseId, repetition: record.repetition, pass: Object.values(checks).every(v => v === true), checks }
}
export function checkReport(report, capturedDocuments = documents) {
  const ids = ['S2-GUIDES-01', 'S2-CLARIFICATION-01', 'S2-TEAM-01']
  if (report.datasetSha256 !== frozenFileDigest(resolve(suitePath, 'manifest.json')) || !Number.isInteger(report.repetitions) || report.repetitions < 1
      || !Array.isArray(report.selectedCaseIds) || report.selectedCaseIds.length !== 3 || ids.some(id => !report.selectedCaseIds.includes(id))
      || report.records?.length !== 3 * report.repetitions) throw Error('Incomplete fixed regression plan or dataset mismatch')
  for (const id of ids) for (let i = 1; i <= report.repetitions; i++) if (report.records.filter(r => r.caseId === id && r.repetition === i).length !== 1) throw Error('Missing/duplicate planned observation')
  const rows = report.records.map(record => checkRecord(record, capturedDocuments))
  return { mode: 'deterministic-source-and-snapshot-regression', scope: 'Only fixed guide, incomplete-build and preview cases; not universal prose verification or human acceptance',
    datasetId: report.datasetId, runtime: report.runtime, planned: rows.length, pass: rows.filter(r => r.pass).length,
    failed: rows.filter(r => !r.pass).length, humanAcceptance: 'not performed', rows }
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [input, destination, ...extra] = process.argv.slice(2)
  if (!input || !destination || extra.length) throw Error('Usage: node tools/campus/s2-quality-regression.mjs OBSERVATIONS_JSON NEW_OUTPUT_DIRECTORY')
  const report = checkReport(JSON.parse(readFileSync(resolve(input), 'utf8'))), output = resolve(destination)
  if (existsSync(output)) throw Error('Use a new directory; original evidence is never overwritten')
  mkdirSync(output, { recursive: true }); writeFileSync(resolve(output, 'quality-regression.json'), JSON.stringify(report, null, 2) + '\n')
  console.log(`Bounded regression checks: ${report.pass}/${report.planned}; human acceptance not performed`)
  if (report.failed) process.exitCode = 1
}
