import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import {pathToFileURL} from 'node:url'
const root=process.cwd(),out='artifacts/campus/s2-remaining-dev-v1'
const {digest,loadSuite,validateSuite,summarize,observationHash,sameJson,frozenFileDigest}=await import(pathToFileURL(path.join(root,'tools/campus/evaluation.mjs')))
const bundle='document/秋招升级/S2/evidence/s2-remaining-dev-v1'
const sourceSnapshots={'src/main/java/com/vio/vioaiagent/run/DefaultRunExecutor.java':bundle+'/baseline-DefaultRunExecutor.java'}
const currentDifferences=new Set()
const inputs=[
 'document/秋招升级/S2/evidence/model-failure-diagnostics-v1/collection-v1/observations.json',
 bundle+'/collection-v1/observations.json',bundle+'/backfill-v1/observations.json'
]
const suite=loadSuite('evaluation/campus/s2');validateSuite(suite)
const base=JSON.parse(fs.readFileSync(inputs[0])),records=[],origins=[],seen=new Set()
const safeSource=p=>!p.includes('..')&&!p.includes('\\')&&!path.isAbsolute(p)&&(
 p==='pom.xml'||p.startsWith('src/main/java/')&&p.endsWith('.java')||
 p.startsWith('src/main/resources/pokemon-')&&p.endsWith('.json')||
 ['tools/pokemon-engine/calculate.cjs','tools/pokemon-engine/package-lock.json','src/main/resources/pokemon-reference/species-names.csv'].includes(p))
for(const file of inputs){
 const bytes=fs.readFileSync(file),r=JSON.parse(bytes)
 assert.equal(r.split,'dev');assert.equal(r.repetitions,2)
 assert.equal(r.datasetSha256,frozenFileDigest('evaluation/campus/s2/manifest.json'))
 assert.equal(r.sourcesUnchanged,true);assert.equal(r.suiteUnchanged,true)
 for(const name of ['sourceSha256','collectorSha256','suiteFilesSha256'])assert.ok(sameJson(r[name],base[name]),name)
 assert.ok(sameJson(r.runtime.implementationSha256,base.runtime.implementationSha256))
 for(const name of ['configuredProvider','configuredModelId','profiles','evaluationPolicy','generationDefaults'])
  assert.ok(sameJson(r.runtime[name],base.runtime[name]),name)
 assert.equal(r.guideCatalog.corpusSha256,base.guideCatalog.corpusSha256)
 for(const [p,h]of Object.entries(r.sourceSha256)){
  assert.ok(safeSource(p));assert.equal(digest(fs.readFileSync(path.join(root,sourceSnapshots[p]||p))),h,p)
  if(digest(fs.readFileSync(path.join(root,p)))!==h){assert.ok(sourceSnapshots[p]);currentDifferences.add(p)}
 }
 assert.equal(r.records.length,r.selectedCaseIds.length*2)
 for(const x of r.records){
  assert.ok(suite.splits.dev.includes(x.caseId)&&r.selectedCaseIds.includes(x.caseId))
  assert.ok([1,2].includes(x.repetition));const key=x.caseId+':'+x.repetition
  assert.ok(!seen.has(key),'Duplicate case repetition');seen.add(key)
  assert.equal(x.observationSha256,observationHash(x))
  assert.equal(path.basename(x.eventsFile),x.eventsFile)
  assert.equal(x.eventsSha256,digest(fs.readFileSync(path.join(path.dirname(file),x.eventsFile))))
  records.push(x);origins.push({caseId:x.caseId,repetition:x.repetition,sourceReport:file,sourceReportSha256:digest(bytes),observationSha256:x.observationSha256,status:x.status})
 }
}
for(const id of suite.splits.dev)for(const n of [1,2])assert.ok(seen.has(id+':'+n),'Missing planned development row')
assert.equal(records.length,28)
const summary=summarize(suite,records,[]),attempts=records.flatMap(x=>x.modelUsageAttempts||[]),calls=attempts.flatMap(x=>x.calls||[])
const result={schemaVersion:'same-build-development-inventory-v1',scope:'Three separately planned batches, not one uniform run or independent quality benchmark',
 sourceLabel:'Baseline before explicit zero-call accounting fix',sourceSnapshotOverrides:sourceSnapshots,currentRootSourcesDifferent:[...currentDifferences],
 sourceEntries:Object.keys(base.sourceSha256).length,batchCount:3,caseCount:suite.splits.dev.length,rowCount:records.length,
 structuralPass:summary.structuralPass,fail:summary.fail,humanReviewed:summary.humanReviewed,pendingReview:summary.pendingReview,
 heldoutCasesExecuted:0,states:records.reduce((a,x)=>(a[x.status]=(a[x.status]||0)+1,a),{}),
 directSafetyTasks:records.filter(x=>x.caseId==='S2-SAFETY-01').length,
 previewCancelledTasks:records.filter(x=>x.status==='WAITING_APPROVAL'&&x.cleanupCancelled===true).length,
 groups:new Set(suite.cases.filter(x=>suite.splits.dev.includes(x.id)).map(x=>x.groupId)).size,
 modelCalls:calls.length,callsWithUsage:attempts.reduce((n,x)=>n+x.callsWithUsage,0),
 knownTokens:attempts.reduce((a,x)=>{for(const k of ['inputTokens','outputTokens','totalTokens'])a[k]+=x.knownSubtotal?.[k]||0;return a},{inputTokens:0,outputTokens:0,totalTokens:0}),
 invoiceReconciled:false,origins}
fs.mkdirSync(out,{recursive:true})
fs.writeFileSync(out+'/development-inventory-v2.json',JSON.stringify(result,null,2)+'\n',{flag:'wx'})
const lines=['# 同构建开发题复核索引','','14道开发题各2次，分3个事先规划的批次，属于零调用记账修正前的基线构建；新构建另有定向记录。这里核对工程覆盖与版本一致性，不是一次统一运行、28道独立问题或人工准确率。','',
 `工程通过${summary.structuralPass}/28，人工审核${summary.humanReviewed}，待审${summary.pendingReview}；保留集未执行。`,'',
 '| 开发题 | 观察状态 | 原始记录 |','| --- | --- | --- |']
for(const id of suite.splits.dev){const rows=origins.filter(x=>x.caseId===id);const item=suite.cases.find(x=>x.id===id);lines.push(`| ${id} | ${rows.map(x=>x.status).join(' / ')} | [两次原文](${path.relative(bundle,rows[0].sourceReport).replaceAll('\\','/')}) |`);}
for(const id of suite.splits.dev){const item=suite.cases.find(x=>x.id===id);lines.push('',`## ${id}`,'',...item.expected.criteria.map(x=>`- ${x.id}：${x.text}`));for(const x of records.filter(x=>x.caseId===id).sort((a,b)=>a.repetition-b.repetition)){
 lines.push('',`### 重复${x.repetition} · ${x.status}`,'',x.answer||'（无最终正文；该任务停在审批预览，需结合原始事件核对，采集器随后取消。）');
 if(x.preview)lines.push('','预览内容（原始结构，用于审核）：','```json',JSON.stringify(x.preview.changes,null,2),'```')
}}
fs.writeFileSync(out+'/development-review-v2.md',lines.join('\n')+'\n',{flag:'wx'})
console.log(JSON.stringify({...result,origins:undefined},null,2))
