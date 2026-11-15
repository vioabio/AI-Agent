import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {frozenFileDigest} from './evaluation.mjs'

export const independentDirectory=path.resolve(fileURLToPath(new URL('../../evaluation/upgrade-v1.1/u6/independent-v2',import.meta.url)))
export function loadIndependentSuite(directory=independentDirectory) {
  const read=file=>JSON.parse(fs.readFileSync(path.join(directory,file),'utf8'))
  const manifest=read('manifest.json')
  assert.equal(manifest.schemaVersion,'independent-candidate-dataset-v1')
  for(const file of ['cases.json','rubric.json'])assert.equal(frozenFileDigest(path.join(directory,file)),manifest.files[file])
  const cases=read('cases.json'),rubric=read('rubric.json')
  assert.equal(cases.length,manifest.caseCount);assert.equal(new Set(cases.map(row=>row.id)).size,cases.length)
  assert.equal(cases.reduce((count,row)=>count+row.turns.length,0),manifest.turnCount)
  const root=path.resolve(fileURLToPath(new URL('../..',import.meta.url)))
  for(const source of [manifest.corpus,...manifest.additionalSources]) {
    const file=path.resolve(root,source.path),relative=path.relative(root,file)
    assert.ok(relative&&!relative.startsWith('..')&&!path.isAbsolute(relative))
    assert.equal(frozenFileDigest(file),source.sha256)
  }
  const documents=new Set(JSON.parse(fs.readFileSync(path.join(root,manifest.corpus.path),'utf8')).documents.map(row=>row.id))
  for(const row of cases) {
    assert.equal(row.split,'dev');assert.ok(row.turns.length>0&&row.turns.every(prompt=>typeof prompt==='string'&&prompt.trim()))
    assert.ok(row.expected.rubric.trim());assert.ok(row.expected.relevantDocumentIds.every(id=>documents.has(id)))
    assert.equal(row.scope.gameReleaseId,'pokemon-scarlet-violet');assert.equal(row.scope.battleFormat,'casual-singles')
    assert.equal(row.scope.rulesetVersionId,'casual-v1');assert.equal(row.scope.language,'zh-CN')
  }
  for(const [category,count] of Object.entries(manifest.categoryCounts))assert.equal(cases.filter(row=>row.category===category).length,count)
  assert.equal(cases.length,Object.values(manifest.categoryCounts).reduce((sum,count)=>sum+count,0))
  return {cases,rubric,manifest,datasetSha256:frozenFileDigest(path.join(directory,'manifest.json'))}
}
