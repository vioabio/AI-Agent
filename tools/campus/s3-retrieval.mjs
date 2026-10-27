// Local retrieval-only HTTP observations. No LLM, credential or private configuration is recorded.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomBytes } from 'node:crypto'
import { digest, frozenFileDigest, localBase } from './evaluation.mjs'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const defaultDatasetFile = resolve(root, 'evaluation/campus/s3/retrieval-dev.json')
export function assessRetrieval(item, chunks, verified) {
  if (!Array.isArray(chunks) || chunks.length > 5) throw Error('Expected at most five real chunks')
  const docs = [...new Set(chunks.map(c => c.view.documentId))]
  const expected = new Set(item.relevantDocumentIds)
  const retrieved = docs.filter(id => expected.has(id)).length
  const rank = chunks.findIndex(c => expected.has(c.view.documentId)) + 1
  const positive = expected.size > 0
  const evidence = item.relevantEvidence || []
  return { caseId: item.id, expected: [...expected], retrieved: docs,
    excludedDocumentsRetrieved:docs.filter(id=>(item.excludedDocumentIds||[]).includes(id)),
    chunkIds: chunks.map(c => c.view.chunk.id), positive,
    recallAt5: positive ? (verified === true ? retrieved / expected.size : 0) : null,
    reciprocalRankAt5: positive ? (verified === true && rank > 0 ? 1 / rank : 0) : null,
    emptyCorrect: positive ? null : verified === true && chunks.length === 0, sourcesVerified: verified === true,
    seedEvidenceCoverageAt5: evidence.length ? (verified === true ? evidence.filter(label => chunks.some(c =>
      c.view.documentId === label.documentId && c.view.chunk.content.includes(label.contains))).length / evidence.length : 0) : null }
}
export function summarizeRetrieval(cases, rows) {
  if (rows.length !== cases.length || new Set(rows.map(r => r.caseId)).size !== cases.length
      || cases.some(c => !rows.some(r => r.caseId === c.id))) throw Error('Missing or duplicate planned cases')
  const positive = rows.filter(r => r.positive), negative = rows.filter(r => !r.positive)
  const mean = (items, key) => items.length ? items.reduce((sum, row) => sum + (row[key] ?? 0), 0) / items.length : null
  return { planned: cases.length, positive: positive.length, negative: negative.length,
    documentRecallWithinTop5Chunks: mean(positive, 'recallAt5'), mrrAt5: mean(positive, 'reciprocalRankAt5'),
    negativeEmptyAccuracy: mean(negative.map(row => ({ correct: row.emptyCorrect ? 1 : 0 })), 'correct'),
    sourcesVerified: rows.filter(r => r.sourcesVerified === true).length,
    exclusionViolations:rows.filter(r=>r.excludedDocumentsRetrieved?.length).length,
    seedEvidenceCoverageAt5: mean(positive.filter(row => typeof row.seedEvidenceCoverageAt5 === 'number'), 'seedEvidenceCoverageAt5'),
    scope: 'Source-linked development seeds, not exhaustive relevance judgments, human acceptance, heldout quality, or an S2 ablation result.' }
}
async function main() {
  const [baseArg, outputArg, datasetArg, ...extra] = process.argv.slice(2)
  if (!baseArg || !outputArg || extra.length) throw Error('Usage: node tools/campus/s3-retrieval.mjs LOCAL_API_BASE NEW_OUTPUT_DIRECTORY [evaluation/campus/s3/DATASET.json]')
  const base = localBase(baseArg), output = resolve(outputArg)
  if (existsSync(output)) throw Error('Use a new output directory')
  const datasetFile = datasetArg ? resolve(root, datasetArg) : defaultDatasetFile
  if (!['evaluation/campus/s3','evaluation/campus/s3-species'].some(folder=>datasetFile.startsWith(resolve(root,folder)+(process.platform==='win32'?'\\':'/')))) throw Error('Dataset must be inside a registered retrieval directory')
  const suite = JSON.parse(readFileSync(datasetFile, 'utf8'))
  if (!suite.corpusFile || !['src/main/resources/pokemon-guides/','src/main/resources/pokemon-strategy/'].some(folder=>suite.corpusFile.startsWith(folder)) || suite.corpusFile.includes('..')) throw Error('Unsafe corpus path')
  if (suite.corpusSha256 !== frozenFileDigest(resolve(root, suite.corpusFile))) throw Error('Frozen source identity mismatch')
  const sources = JSON.parse(readFileSync(resolve(root, suite.corpusFile), 'utf8')).documents
  const cookies = new Map()
  const api = async (path, body, expected = 200) => {
    const response = await fetch(base + path, { method: body ? 'POST' : 'GET', redirect: 'error', signal: AbortSignal.timeout(30000),
      headers: { Origin: 'http://localhost:3000', 'Content-Type': 'application/json',
        Cookie: [...cookies].map(([n, v]) => `${n}=${v}`).join('; '),
        ...(cookies.get('CSRF-TOKEN') ? { 'X-CSRF-TOKEN': cookies.get('CSRF-TOKEN') } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}) })
    for (const line of response.headers.getSetCookie()) { const [name, ...value] = line.split(';')[0].split('='); cookies.set(name, value.join('=')) }
    if (response.status !== expected) throw Error(`HTTP ${response.status}; expected ${expected}`)
    const value = await response.json()
    return value && Object.hasOwn(value, 'code') && Object.hasOwn(value, 'data') ? value.data : value
  }
  mkdirSync(output, { recursive: true })
  const report = { mode: 'real-local-http-lexical-retrieval-no-model', datasetId: suite.datasetId,
    datasetSha256: frozenFileDigest(datasetFile), corpusSha256: suite.corpusSha256,
    startedAt: new Date().toISOString(), planned: suite.cases.map(c => c.id), rows: [] }
  try {
    if ((await api('/auth/session')).mode !== 'local') throw Error('Requires local accounts')
    const username = `s3_${randomBytes(6).toString('hex')}`, password = randomBytes(24).toString('base64url')
    await api('/auth/local/register', { username, password, nickname: 'S3 检索验证' }, 201)
    await api('/auth/local/login', { username, password })
    report.runtime = await api('/chat/runtime'); report.index = await api('/pokemon/guides/index')
    report.catalog = await api('/pokemon/guides/catalog')
    if (report.catalog.corpusSha256 !== suite.corpusSha256) throw Error('Runtime corpus differs from the frozen evaluation corpus')
    for (const [name, folder] of Object.entries({ LocalGuideSearch: 'guides', GuideChunks: 'guides', GuideApplicability:'guides', SqliteGuideKnowledgeRetriever: 'guides', RunToolScope: 'reference' })) {
      if (report.runtime.implementationSha256?.[name] !== digest(readFileSync(resolve(root, `target/classes/com/vio/vioaiagent/pokemon/${folder}/${name}.class`)))) throw Error(`Stale runtime: ${name}`)
    }
    for (const item of suite.cases) {
      try {
        const query = new URLSearchParams({ ...suite.defaults, ...item.scope, query: item.query })
        const chunks = await api(`/pokemon/guides/retrieve?${query}`)
        const locators = []
        for (const chunk of chunks) {
          const hit = await api(`/pokemon/guides/chunks/${encodeURIComponent(chunk.view.chunk.id)}`)
          const original = sources.find(d => d.id === hit.document.id), citation = hit.citation
          if (!original || original.content !== hit.document.content || original.title !== hit.document.title
              || original.author !== hit.document.author || original.updatedAt !== hit.document.updatedAt
              || citation.text !== original.content.slice(citation.startOffset, citation.endOffset)
              || citation.chunkId !== chunk.view.chunk.id || citation.text !== chunk.view.chunk.content
              || citation.contentHash !== `sha256:${digest(citation.text)}`) throw Error('Source locator does not match the frozen original')
          if (JSON.stringify(hit.document.provenance || null) !== JSON.stringify(original.provenance || null)) throw Error('Source attribution differs from frozen original')
          if (JSON.stringify(hit.document.applicability || null) !== JSON.stringify(original.applicability || null)) throw Error('Source retrieval scope differs from frozen original')
          locators.push(hit)
        }
        report.rows.push({ ...assessRetrieval(item, chunks, true), chunks, locators })
      } catch (error) {
        report.rows.push({ ...assessRetrieval(item, [], false), error: error.message })
        process.exitCode = 1
      }
    }
    report.summary = summarizeRetrieval(suite.cases, report.rows)
  } catch (error) { report.setupError = error.message; process.exitCode = 1 }
  report.finishedAt = new Date().toISOString()
  writeFileSync(resolve(output, 'retrieval-observations.json'), JSON.stringify(report, null, 2) + '\n')
  console.log(JSON.stringify(report.summary || { setupError: report.setupError }))
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main()
