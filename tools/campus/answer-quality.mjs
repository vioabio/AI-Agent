import { readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { resolve, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { digest } from './evaluation.mjs'
import { normalizeModelUsage } from '../../vio-ai-agent-frontend/src/api/model-usage.js'

export function toolsFrom(events) {
  const calls=new Map()
  for(const event of events)if(['tool.started','tool.finished'].includes(event.type)) {
    const p=event.payload||{},key=p.governanceEventId
    if(key && p.toolName)calls.set(key,{toolName:p.toolName,status:p.status||'STARTED'})
  }
  return [...calls.values()]
}
export function attemptsFrom(events) {
  const byId=new Map()
  for(const event of events) {
    const usage=event.type==='usage'?event.payload:event.payload?.modelUsage
    if(!usage)continue
    const normalized=normalizeModelUsage(usage)
    if(!normalized)throw Error('Invalid model usage')
    if(byId.has(usage.attemptId) && digest(normalizeModelUsage(byId.get(usage.attemptId)))!==digest(normalized))throw Error('Frozen usage changed')
    byId.set(usage.attemptId,usage)
  }
  return [...byId.values()]
}
export function formatChecks(expected, answer) {
  const text=typeof answer==='string'?answer.trim():'',failures=[]
  if(!text)failures.push('EMPTY_ANSWER')
  const characters=[...text].length
  if(characters>expected.maxCharacters)failures.push('TOO_LONG')
  const items=(text.match(/^\s*(?:[-*+]\s+|\d+[.、)]\s*)/gm)||[]).length
  const boundaries=(text.match(/[。！？]|[.!?](?:\s|$)/g)||[]).length
  if(expected.format==='one_sentence' && (text.includes('\n') || items || /^\s*#|```|\|.*\|/m.test(text) || boundaries>1))failures.push('ONE_SENTENCE_FORMAT')
  if(expected.format==='two_items' && items!==2)failures.push('TWO_ITEMS_FORMAT')
  if(/sha256:|checksum|dataReleaseId|rulesetVersionId|pokeapi-reference-v1|casual-v1|pokemon-scarlet-violet|run-[a-f0-9-]{20,}|\b[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}\b/i.test(text))failures.push('INTERNAL_IDENTIFIER_LEAK')
  if(/Let me proceed with the final answer|我已经有足够资料|接下来(?:我将|给出)最终(?:回答|答案)/i.test(text))failures.push('KNOWN_PLANNING_LEAK')
  if(expected.requiredPattern && !new RegExp(expected.requiredPattern,'iu').test(text))failures.push('NECESSARY_VALUE_MISSING')
  return {pass:failures.length===0,failures,characters,items,sentenceBoundaries:boundaries,
    scope:'Finite formatting/known-pattern checks; not proof of semantic correctness or complete fact checking'}
}
const preferenceFields=['revision','responseStyle','trainingBudget','avoidLegendary','notes']
const preferenceView=raw=>raw && Object.fromEntries(preferenceFields.map(key=>[key,raw[key]]))
export function observationHash(record) {
  return digest({caseId:record.caseId,repetition:record.repetition,status:record.status,runId:record.runId,answer:record.answer,
    eventsSha256:record.eventsSha256,historySha256:record.historySha256,preferences:record.preferences})
}
export function generationFrom(attempts) {
  const modelCalls=attempts.reduce((sum,a)=>sum+a.modelCallCount,0)
  const generationKind=modelCalls>0?'MODEL':attempts.length && attempts.every(a=>a.coverage==='INSTRUMENTED'&&a.usageStatus==='COMPLETE'&&a.modelCallCount===0)?'LOCAL':'UNKNOWN'
  return {generationKind,modelCalls}
}
export function auditQuality(capture,readEvents,readHistory,reviews=[]) {
  if(capture.schemaVersion!=='answer-quality-capture-v1' || !capture.plan?.cases?.length || !Number.isInteger(capture.plan.repetitions)
      || capture.plan.repetitions<1 || capture.plan.repetitions>3 || capture.records?.length!==capture.plan.cases.length*capture.plan.repetitions)throw Error('Missing fixed denominator')
  if(new Set(capture.plan.cases.map(item=>item.id)).size!==capture.plan.cases.length || capture.plan.cases.some(item=>!item.prompt
      || !/^[A-Za-z0-9_-]{1,64}$/.test(item.id) || !['one_sentence','two_items'].includes(item.expected?.format)
      || !Number.isInteger(item.expected.maxCharacters) || item.expected.maxCharacters<1 || item.expected.maxCharacters>1000
      || !item.semanticCriteria?.length || item.semanticCriteria.some(text=>typeof text!=='string'||!text.trim())))throw Error('Invalid fixed plan')
  const reviewKeys=new Set();for(const review of reviews) {
    const key=`${review.caseId}:${review.repetition}`;if(reviewKeys.has(key))throw Error('Duplicate review');reviewKeys.add(key)
  }
  const rows=[]
  for(const item of capture.plan.cases)for(let repetition=1;repetition<=capture.plan.repetitions;repetition++) {
    const matching=capture.records.filter(r=>r.caseId===item.id&&r.repetition===repetition)
    if(matching.length!==1)throw Error('Missing or duplicate case')
    const record=matching[0],failures=[]
    let events=[],history=null
    try {events=readEvents(record);history=readHistory(record)}catch {failures.push('EVIDENCE_UNAVAILABLE')}
    const answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join('')
    if(answer!==record.answer)failures.push('ANSWER_CAPTURE_MISMATCH')
    if(record.status!=='COMPLETED' || !events.some(e=>e.type==='run.completed') || record.error || record.cleanupFailed)failures.push('RUN_NOT_COMPLETED')
    if(history?.answer!==answer || history?.prompt!==item.prompt)failures.push('HISTORY_MISMATCH')
    if(!history?.userPreferences || !history?.answerPreferences || digest(preferenceView(history.userPreferences))!==digest(record.preferences)
      || digest(preferenceView(history.answerPreferences))!==digest(record.preferences)
      || [history.userPreferences,history.answerPreferences].some(p=>p.schemaVersion!=='conversation-constraints-v1'||p.source!=='USER_CONFIRMED'))failures.push('PREFERENCE_SNAPSHOT_MISMATCH')
    const attempts=attemptsFrom(events)
    if(digest(attempts.map(normalizeModelUsage))!==digest((history?.attempts||[]).map(normalizeModelUsage)))failures.push('USAGE_HISTORY_MISMATCH')
    const tools=toolsFrom(events)
    if(item.expected.noTools && tools.length)failures.push('UNREQUESTED_TOOL_ATTEMPT')
    if(tools.some(t=>/previewTeamChanges|applyConfirmedTeamChanges|writeFile|downloadFile|terminal/i.test(t.toolName)))failures.push('FORBIDDEN_TOOL_ATTEMPT')
    if(item.expected.requiredToolAny && !tools.some(t=>item.expected.requiredToolAny.includes(t.toolName)&&['SUCCEEDED','EXECUTED','DEDUPLICATED'].includes(t.status)))failures.push('REQUIRED_TOOL_MISSING')
    const format=formatChecks(item.expected,answer);failures.push(...format.failures)
    const review=reviews.find(r=>r.caseId===item.id&&r.repetition===repetition)
    let reviewState='PENDING_REVIEW',humanPass=false
    if(review) {
      if(review.observationSha256!==observationHash(record) || !['human','ai-assisted'].includes(review.reviewerType) || !review.reviewer?.trim() || !review.reviewedAt || !review.note?.trim()
          || review.decisions?.length!==item.semanticCriteria.length || new Set(review.decisions.map(d=>d.criterion)).size!==item.semanticCriteria.length
          || review.decisions.some(d=>!Number.isInteger(d.criterion)||d.criterion<1||d.criterion>item.semanticCriteria.length||!['pass','fail','unknown'].includes(d.verdict)||!d.reason?.trim()))throw Error('Unbound or incomplete review')
      reviewState=review.reviewerType==='ai-assisted'?'ASSISTED_REVIEW_ONLY':review.decisions.some(d=>d.verdict==='fail')?'FAIL':review.decisions.every(d=>d.verdict==='pass')?'PASS':'PENDING_REVIEW'
      humanPass=reviewState==='PASS'
    }
    const {modelCalls,generationKind}=generationFrom(attempts)
    rows.push({caseId:item.id,repetition,generationKind,modelCalls,mechanicalPass:failures.length===0,failures:[...new Set(failures)],format,tools,reviewState,
      assistedFlagged:review?.reviewerType==='ai-assisted'&&review.decisions.some(d=>d.verdict==='fail'),
      taskOutcome:failures.length || reviewState==='FAIL'?'FAIL':humanPass?'PASS':'PENDING_REVIEW',observationSha256:observationHash(record)})
  }
  if(reviews.some(r=>!rows.some(row=>row.caseId===r.caseId&&row.repetition===r.repetition)))throw Error('Review without observation')
  return {schemaVersion:'answer-quality-report-v1',denominator:rows.length,mechanicalPass:rows.filter(r=>r.mechanicalPass).length,
    verifiedTaskPass:rows.filter(r=>r.taskOutcome==='PASS').length,fail:rows.filter(r=>r.taskOutcome==='FAIL').length,
    pendingReview:rows.filter(r=>r.taskOutcome==='PENDING_REVIEW').length,
    generationBreakdown:Object.fromEntries(['MODEL','LOCAL','UNKNOWN'].map(kind=>[kind,{runs:rows.filter(r=>r.generationKind===kind).length,
      mechanicalPass:rows.filter(r=>r.generationKind===kind&&r.mechanicalPass).length,modelCalls:rows.filter(r=>r.generationKind===kind).reduce((sum,r)=>sum+r.modelCalls,0)}])),rows,
    scope:'Development format and workflow regression. Only complete bound human reviews can produce task PASS; no general semantic accuracy claim.'}
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const [inputArg,outputArg,reviewsArg,...extra]=process.argv.slice(2)
  if(!inputArg || !outputArg || extra.length)throw Error('Usage: node tools/campus/answer-quality.mjs CAPTURE_JSON NEW_OUTPUT [REVIEWS_JSON]')
  const input=resolve(inputArg),output=resolve(outputArg),bytes=readFileSync(input),capture=JSON.parse(bytes)
  if(existsSync(output))throw Error('Never overwrite evidence')
  const planBytes=readFileSync(new URL('../../evaluation/campus/s4-quality/manifest.json',import.meta.url))
  if(capture.planSha256!==digest(planBytes) || digest(capture.plan)!==digest(JSON.parse(planBytes)))throw Error('Frozen plan mismatch')
  const readBound=(record,key)=>{
    const file=record[`${key}File`];if(!file || basename(file)!==file)throw Error('Invalid evidence path')
    const raw=readFileSync(resolve(dirname(input),file));if(digest(raw)!==record[`${key}Sha256`])throw Error('Evidence hash mismatch')
    return JSON.parse(raw)
  }
  const report=auditQuality(capture,r=>readBound(r,'events'),r=>readBound(r,'history'),reviewsArg?JSON.parse(readFileSync(resolve(reviewsArg))):[])
  report.captureSha256=digest(bytes);report.runtime=capture.runtime;report.analysisSha256=digest(readFileSync(fileURLToPath(import.meta.url)))
  mkdirSync(output,{recursive:true});writeFileSync(resolve(output,'quality-report.json'),JSON.stringify(report,null,2)+'\n')
  writeFileSync(resolve(output,'quality-report.md'),['# 当前请求与偏好边界回归','',
    `计划分母 ${report.denominator}；有限格式/工作流检查通过 ${report.mechanicalPass}；人工完整任务通过 ${report.verifiedTaskPass}；失败 ${report.fail}；待复核 ${report.pendingReview}。`,
    '',...Object.entries(report.generationBreakdown).map(([kind,r])=>`${kind}：${r.runs}次任务，有限检查通过${r.mechanicalPass}次，模型调用${r.modelCalls}次。`),
    '','| 案例 | 轮次 | 生成来源 | 有限检查 | 字符数 | 语义复核 | 原因 |','| --- | ---: | --- | --- | ---: | --- | --- |',
    ...report.rows.map(r=>`| ${r.caseId} | ${r.repetition} | ${r.generationKind} | ${r.mechanicalPass?'通过':'失败'} | ${r.format.characters} | ${r.reviewState} | ${r.failures.join(', ')||'人工语义复核单列'} |`),
    '','有限检查不证明事实正确、工具参数一致或所有偏好都遵守。AI辅助复查不能替代人工验收；未使用S2保留集，也没有准确率或统计提升结论。',''].join('\n'))
  writeFileSync(resolve(output,'review-template.json'),JSON.stringify(capture.records.map(r=>({caseId:r.caseId,repetition:r.repetition,observationSha256:observationHash(r),
    reviewerType:null,reviewer:'',reviewedAt:'',note:'',decisions:capture.plan.cases.find(c=>c.id===r.caseId).semanticCriteria.map((text,index)=>({criterion:index+1,text,verdict:'unknown',reason:''}))})),null,2)+'\n')
  console.log(JSON.stringify({denominator:report.denominator,mechanicalPass:report.mechanicalPass,verifiedTaskPass:report.verifiedTaskPass,fail:report.fail,pendingReview:report.pendingReview}))
  if(report.mechanicalPass!==report.denominator || report.fail)process.exitCode=1
}
