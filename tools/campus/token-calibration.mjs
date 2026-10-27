import { readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { resolve, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { normalizeModelUsage } from '../../vio-ai-agent-frontend/src/api/model-usage.js'
import { digest } from './evaluation.mjs'
const percentile = (values, q) => values.length ? [...values].sort((a,b) => a-b)[Math.max(0, Math.ceil(values.length*q)-1)] : null
const round = number => Number(number.toFixed(6))

/** Observational only. Keep failed/missing calls in denominators; never tune policy on validation records. */
export function calibrationReport(records) {
  if (!Array.isArray(records) || !records.length) throw Error('Missing planned records')
  const rows=[], seen=new Set()
  for(const record of records) {
    const attempts=record.attempts || []
    if (!attempts.length) rows.push({caseId:record.caseId,split:record.split,status:'EXCLUDED',reason:'NO_ATTEMPT'})
    for(const raw of attempts) {
      const usage=normalizeModelUsage(raw)
      if(!usage || usage.modelCallCount!==usage.calls.length || usage.recordsTruncated) {
        rows.push({caseId:record.caseId,split:record.split,status:'EXCLUDED',reason:'INVALID_OR_TRUNCATED_ATTEMPT'});continue
      }
      if(!usage.modelCallCount) rows.push({caseId:record.caseId,split:record.split,status:'EXCLUDED',reason:'NO_MODEL_CALL'})
      for(const call of usage.calls) {
        const identity=`${usage.attemptId}:${call.callNo}`
        if(seen.has(identity)) throw Error('Duplicate physical model call across records')
        seen.add(identity)
        const pair=call.tokenEstimateComparison, context=call.contextBudget
        if(!pair || pair.status!=='PAIR_RECORDED') {
          rows.push({caseId:record.caseId,split:record.split,attemptId:usage.attemptId,callNo:call.callNo,status:'EXCLUDED',reason:pair?.reason||'COMPARISON_NOT_RECORDED'});continue
        }
        const baseRelativeError=pair.baseErrorTokens/pair.providerInputTokens,budgetRelativeError=pair.budgetErrorTokens/pair.providerInputTokens
        rows.push({caseId:record.caseId,split:record.split,attemptId:usage.attemptId,callNo:call.callNo,status:'PAIRED',
          requestedModelId:call.requestedModelId,responseModelId:call.responseModelId,configuredProvider:record.configuredProvider,
          providerUsageSource:raw.calls.find(c=>c.callNo===call.callNo)?.tokens?.source??null,mode:call.mode,estimator:context.estimator,
          safetyPercent:context.safetyPercent,profile:{toolDefinitions:context.baseEstimate.toolDefinitions>0,toolResults:context.baseEstimate.toolResults>0,history:context.baseEstimate.history>0},
          ...pair,status:'PAIRED',baseRelativeError:round(baseRelativeError),budgetRelativeError:round(budgetRelativeError)})
      }
    }
  }
  function summarize(items) {
    const paired=items.filter(row=>row.status==='PAIRED'), samples=paired.length
    const metric=key=>({mean:samples?round(paired.reduce((sum,row)=>sum+row[key],0)/samples):null,
      p50:percentile(paired.map(row=>row[key]),.5),p95:percentile(paired.map(row=>row[key]),.95)})
    return {observations:items.length,pairedCalls:samples,excluded:items.length-samples,
      baseUnderestimated:paired.filter(row=>row.baseErrorTokens<0).length,budgetUnderestimated:paired.filter(row=>!row.budgetCovered).length,
      coverageDenominator:'PAIRED_CALLS_ONLY_EXCLUSIONS_SEPARATE',
      budgetCoverageRate:samples?round(paired.filter(row=>row.budgetCovered).length/samples):null,
      baseRelativeError:metric('baseRelativeError'),budgetRelativeError:metric('budgetRelativeError'),
      baseAbsoluteRelativeError:metricFrom(paired.map(row=>Math.abs(row.baseRelativeError))),
      budgetAbsoluteRelativeError:metricFrom(paired.map(row=>Math.abs(row.budgetRelativeError)))}
  }
  function metricFrom(values) {return {mean:values.length?round(values.reduce((sum,v)=>sum+v,0)/values.length):null,p50:percentile(values,.5),p95:percentile(values,.95)}}
  const groups=new Map()
  for(const row of rows.filter(row=>row.status==='PAIRED')) {
    const key=JSON.stringify([row.split,row.configuredProvider,row.requestedModelId,row.responseModelId,row.providerUsageSource,row.mode,row.estimator,row.safetyPercent,row.profile])
    if(!groups.has(key))groups.set(key,[]);groups.get(key).push(row)
  }
  const declared=records.flatMap(record=>record.attempts||[]).map(attempt=>attempt.modelCallCount)
  const declaredModelCalls=declared.every(n=>Number.isSafeInteger(n)&&n>=0)?declared.reduce((sum,n)=>sum+n,0):null
  return {schemaVersion:'token-calibration-report-v1',plannedRuns:records.length,pairedRuns:records.filter(r=>rows.some(row=>row.caseId===r.caseId&&row.status==='PAIRED')).length,
    declaredModelCalls,pairedFractionOfDeclaredCalls:declaredModelCalls?round(rows.filter(r=>r.status==='PAIRED').length/declaredModelCalls):null,
    observedCalls:seen.size,overall:summarize(rows),bySplit:Object.fromEntries([...new Set(records.map(r=>r.split))].map(split=>[split,summarize(rows.filter(row=>row.split===split))])),
    groups:[...groups.values()].map(items=>({identity:JSON.parse(JSON.stringify({configuredProvider:items[0].configuredProvider,requestedModelId:items[0].requestedModelId,
      responseModelId:items[0].responseModelId,providerUsageSource:items[0].providerUsageSource,mode:items[0].mode,estimator:items[0].estimator,
      safetyPercent:items[0].safetyPercent,profile:items[0].profile,split:items[0].split})),...summarize(items)})),rows,
    policyChange:'NONE_OBSERVATIONAL_ONLY',estimatorCertification:'PROXY_UNCALIBRATED',
    scope:'Application ChatModel call pairs, SDK protocol overhead included in discrepancies. Not provider tokenizer equivalence, invoice, independent-call confidence interval or answer quality.'}
}

export function auditCapture(report, readEvents) {
  if(report.schemaVersion!=='token-calibration-capture-v1' || !Array.isArray(report.records) || !report.plan?.cases?.length
      || report.records.length!==report.plan.cases.length)throw Error('Missing fixed plan')
  const records=report.plan.cases.map(planned=>{
    const matching=report.records.filter(row=>row.caseId===planned.id)
    if(matching.length!==1)throw Error('Missing or duplicate planned case')
    const record=matching[0],events=readEvents(record),byId=new Map()
    for(const event of events) {
      const usage=event.type==='usage'?event.payload:event.payload?.modelUsage
      if(!usage)continue
      // usage events also carry outer Run lineage; compare the owned usage projection like the existing usage auditor.
      if(byId.has(usage.attemptId) && digest(normalizeModelUsage(byId.get(usage.attemptId)))!==digest(normalizeModelUsage(usage)))throw Error('Frozen attempt changed')
      byId.set(usage.attemptId,usage)
    }
    const attempts=[...byId.values()]
    if(digest(attempts)!==digest(record.attempts||[]))throw Error('Captured attempt mismatch')
    if(record.status==='COMPLETED' && digest(attempts.map(normalizeModelUsage))!==digest((record.historyAttempts||[]).map(normalizeModelUsage)))throw Error('History snapshot mismatch')
    return {caseId:planned.id,split:planned.split,configuredProvider:report.runtime?.configuredProvider,attempts}
  })
  return {...calibrationReport(records),completedRuns:report.records.filter(r=>r.status==='COMPLETED' && !r.error && !r.cleanupFailed).length}
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const [inputArg,outputArg,...extra]=process.argv.slice(2)
  if(!inputArg || !outputArg || extra.length)throw Error('Usage: node tools/campus/token-calibration.mjs CAPTURE_JSON NEW_OUTPUT')
  const input=resolve(inputArg),output=resolve(outputArg),bytes=readFileSync(input),report=JSON.parse(bytes)
  if(existsSync(output))throw Error('Never overwrite evidence')
  const frozen=readFileSync(new URL('../../evaluation/campus/s4-token/manifest.json',import.meta.url))
  if(report.planSha256!==digest(frozen) || digest(report.plan)!==digest(JSON.parse(frozen)))throw Error('Frozen calibration plan mismatch')
  const audit=auditCapture(report,record=>{
    if(!record.eventsFile || basename(record.eventsFile)!==record.eventsFile)throw Error('Invalid event path')
    const raw=readFileSync(resolve(dirname(input),record.eventsFile))
    if(digest(raw)!==record.eventsSha256)throw Error('Event identity mismatch')
    return JSON.parse(raw)
  })
  audit.captureSha256=digest(bytes);audit.planSha256=digest(report.plan);audit.runtime=report.runtime
  audit.analysisSha256=digest(readFileSync(fileURLToPath(import.meta.url)))
  mkdirSync(output,{recursive:true});writeFileSync(resolve(output,'calibration-report.json'),JSON.stringify(audit,null,2)+'\n')
  const percent=n=>n===null?'未知':`${(n*100).toFixed(2)}%`
  const lines=['# Token输入估算误差观测','',`计划 ${audit.plannedRuns} 个Run，完成 ${audit.completedRuns} 个；${audit.overall.pairedCalls} 条可比较调用，${audit.overall.excluded} 条排除记录。`,
    '','| 分组 | 可比较调用 | 基础估算平均绝对相对误差 | 预算估算平均绝对相对误差 | 预算低估次数 | 已知调用覆盖比例 |',
    '| --- | ---: | ---: | ---: | ---: | ---: |',
    ...Object.entries(audit.bySplit).map(([split,row])=>`| ${split} | ${row.pairedCalls} | ${percent(row.baseAbsoluteRelativeError.mean)} | ${percent(row.budgetAbsoluteRelativeError.mean)} | ${row.budgetUnderestimated} | ${percent(row.budgetCoverageRate)} |`),
    '','相对误差分母是供应商报告的输入Token，覆盖比例仅对可比较调用计算，排除项单列；不是完整任务成功率。',
    '基础值仍包含代理消息开销，不能当作供应商精确分词结果。当前策略未变更，仍为PROXY_UNCALIBRATED。',
    '调用共享同一Run/上下文，样本相关；本报告没有置信区间、响应提速、账单或回答质量结论。',
    '小样本即使全部覆盖，也不证明更长输入、其他模型或其他工具都安全。完整分组、逐条差值与排除原因见JSON。','']
  writeFileSync(resolve(output,'calibration-report.md'),lines.join('\n'))
  console.log(JSON.stringify({plannedRuns:audit.plannedRuns,pairedRuns:audit.pairedRuns,pairedCalls:audit.overall.pairedCalls,excluded:audit.overall.excluded,
    coverage:audit.overall.budgetCoverageRate,policyChange:audit.policyChange}))
  if(audit.completedRuns!==audit.plannedRuns || audit.pairedRuns!==audit.plannedRuns || audit.overall.excluded || audit.overall.budgetUnderestimated)process.exitCode=1
}
