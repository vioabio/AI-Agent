import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { sourceSupport, checkRecord, checkReport } from './s2-quality-regression.mjs'
const sources = JSON.parse(readFileSync('evaluation/campus/s2/sources.json', 'utf8'))
const doc = sources.find(s => s.id === 'team-roles-v1').excerpt
const quote = `**${doc.title}**\n\n作者：${doc.author} · 更新：${doc.updatedAt}\n\n> ${doc.content}\n`
test('real captured original supports an extract, added quoted mechanism does not', () => {
  assert.equal(sourceSupport(quote).supported, true)
  assert.equal(sourceSupport(quote.replace(doc.content, doc.content + '猫老大必定使用顺风。')).supported, false)
})
test('a real title cannot legitimize wrong author/date or no original excerpt', () => {
  assert.equal(sourceSupport(quote.replace(doc.author, 'fabricated author')).supported, false)
  assert.equal(sourceSupport(quote.replace(`> ${doc.content}`, '无原文，只提供自编结论。')).supported, false)
})
test('preserved old hallucinated guide and nature wording fail the new bounded checks', () => {
  const old = JSON.parse(readFileSync('document/秋招升级/S2/evidence/final-observations.json', 'utf8'))
  assert.equal(checkRecord(old.records.find(r => r.caseId === 'S2-GUIDES-01')).pass, false)
  const wrongNature = old.records.find(r => r.caseId === 'S2-CLARIFICATION-01' && r.repetition === 2)
  assert.equal(checkRecord(wrongNature).checks.storedHardyPreserved, false)
})
test('both historical guide repetitions fail source support despite successful search and unchanged storage', () => {
  const report = JSON.parse(readFileSync('document/秋招升级/S2/evidence/final-observations.json', 'utf8'))
  const rows = report.records.filter(r => r.caseId === 'S2-GUIDES-01')
  assert.equal(rows.length, 2)
  for (const row of rows) {
    const result = checkRecord(row)
    assert.equal(result.checks.storageUnchanged, true)
    assert.equal(result.checks.actualSearch, true)
    assert.equal(result.checks.quotesSupportedByCapturedSources, false)
    assert.equal(result.checks.noKnownUnattributedExamples, false)
    assert.equal(result.pass, false)
  }
})
test('failed and missing observations cannot disappear from the fixed denominator', () => {
  assert.equal(checkRecord({ caseId: 'S2-GUIDES-01', repetition: 1, status: 'TIMED_OUT', answer: '', toolOutcomes: [], checks: {} }).pass, false)
  assert.throws(() => checkReport({ repetitions: 1, selectedCaseIds: [], records: [] }), /Incomplete/)
})
