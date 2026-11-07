import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest,canonical,sameJson} from './evaluation.mjs'
import {auditTeamPlanning} from './team-planning.mjs'
import {auditTeamPlanningWithReferences} from './team-planning-reference.mjs'
import {fixedNaturalInputs} from './team-natural-preflight.mjs'
export const TEAM_REVIEW_CRITERIA=[
 {id:'members',text:'回答是否完整覆盖用户成员及位置，保留明确顺序，没有混入保存队伍？'},
 {id:'missing',text:'未填写字段是否保持缺失，没有替用户默认填写培养配置？'},
 {id:'evidence',text:'事实与核对结论是否受到所展示参考报告支持，未将工程匹配当成策略正确？'},
 {id:'limits',text:'是否明确资料/联合合法性限制，并直接回答问题，没有编造强势搭配或完成状态？'}]
function seal(value){return {...value,bundleSha256:digest(JSON.stringify(canonical(value)))}}
export function buildTeamReview(directory){
 const planBytes=fs.readFileSync('evaluation/campus/team-natural-v1/cases.json')
 const {plan,protocol}=fixedNaturalInputs(planBytes.toString(),fs.readFileSync('evaluation/campus/team-planning-reference-v2/protocol.json','utf8'))
 const source=fs.readFileSync(path.join(directory,'observations.json')),r=JSON.parse(source)
 if(r.planSha256!==digest(planBytes)||!Array.isArray(r.records)||!r.records.length)throw Error('Actual fixed-plan captured records required')
 const seen=new Set(),rows=r.records.map(row=>{
  if(typeof row.caseId!=='string'||seen.has(row.caseId))throw Error('Invalid or duplicate case');seen.add(row.caseId)
  const item=plan.cases.find(c=>c.id===row.caseId);if(!item||row.prompt!==item.prompt||!sameJson(row.request,item.expectedRequest))throw Error('Changed fixed question or request')
  if(typeof row.eventsFile!=='string'||path.basename(row.eventsFile)!==row.eventsFile)throw Error('Invalid event path')
  const bytes=fs.readFileSync(path.join(directory,row.eventsFile));if(digest(bytes)!==row.eventsSha256)throw Error('Changed event evidence')
  const events=JSON.parse(bytes),answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join('')
  if(answer!==row.answer||typeof row.prompt!=='string'||!sameJson(row.httpBefore?.request,row.request))throw Error('Unbound answer or reference request')
  const expected={request:row.request,reportSha256:row.httpBefore.reportSha256,mode:row.executionMode||'MODEL_TOOL_SELECTION'}
  const v1=auditTeamPlanning(events,expected),v2=auditTeamPlanningWithReferences(events,expected,protocol.queriesByPokemonId)
  const captureIntegrity=Boolean(row.httpAfter&&r.teamBefore&&r.teamAfter&&r.runtime?.implementationSha256&&r.runtimeAfter?.implementationSha256)
   &&sameJson(row.httpBefore,row.httpAfter)&&sameJson(r.teamBefore,r.teamAfter)&&sameJson(r.runtime.implementationSha256,r.runtimeAfter.implementationSha256)
  return {id:row.caseId,question:row.prompt,answer,answerSha256:digest(answer),request:row.request,reference:row.httpBefore,
   engineering:{status:v1.checks.completed?'COMPLETED':'NOT_COMPLETED',captureIntegrity,v1Pass:v1.pass&&captureIntegrity,v2Pass:v2.pass&&captureIntegrity},eventsSha256:row.eventsSha256}
 })
 return seal({schemaVersion:'team-answer-review-bundle-v1',observationsSha256:digest(source),criteria:TEAM_REVIEW_CRITERIA,rows,
  scope:'Development answers only. HTTP report is reference evidence, not independent factual truth. Reviewer identity is self-reported; AI is not human acceptance.'})
}
export function compileTeamReviews(bundle,submission){
 const {bundleSha256,...content}=bundle;if(seal(content).bundleSha256!==bundleSha256)throw Error('Changed bundle')
 if(submission?.bundleSha256!==bundleSha256||!Array.isArray(submission.rows))throw Error('Wrong review binding')
 const byId=new Map(),out=[]
 for(const row of submission.rows){
  const original=bundle.rows.find(r=>r.id===row.id)
  if(!original||byId.has(row.id)||row.answerSha256!==original.answerSha256)throw Error('Unknown duplicate or changed answer')
  if(!['human','ai-assisted'].includes(row.reviewerType)||typeof row.reviewer!=='string'||!row.reviewer.trim()||row.reviewer.length>80||row.attested!==true||typeof row.reviewedAt!=='string'||!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/.test(row.reviewedAt)||!Number.isFinite(Date.parse(row.reviewedAt))||new Date(row.reviewedAt).toISOString()!==row.reviewedAt)throw Error('Explicit reviewer and attestation required')
  if(!Array.isArray(row.criteria)||row.criteria.length!==bundle.criteria.length)throw Error('Complete criteria required')
  const ids=new Set()
  for(const item of row.criteria){
   if(ids.has(item.id)||!bundle.criteria.some(c=>c.id===item.id)||!['pass','fail','unknown'].includes(item.judgment)||typeof item.reason!=='string'||!item.reason.trim()||item.reason.length>2000)throw Error('Invalid judgment or missing reason');ids.add(item.id)
   if(item.judgment==='pass'&&(typeof item.answerQuote!=='string'||!item.answerQuote.trim()||!original.answer.includes(item.answerQuote)))throw Error('Passing judgment requires an actual answer quote')
  }
  byId.set(row.id,row);out.push(row)
 }
 const results=bundle.rows.map(row=>{const review=byId.get(row.id);const semantic=review?.criteria.some(c=>c.judgment==='fail')?'FAIL':review?.criteria.every(c=>c.judgment==='pass')?'PASS':'PENDING'
  return {id:row.id,engineering:row.engineering,reviewType:review?.reviewerType||'unreviewed',semantic,
   verifiedTaskPass:semantic==='PASS'&&review?.reviewerType==='human'&&row.engineering.status==='COMPLETED'&&row.engineering.v2Pass}
 })
 return {schemaVersion:'team-answer-review-report-v1',bundleSha256,denominator:bundle.rows.length,humanReviewed:out.filter(r=>r.reviewerType==='human').length,assistedReviewed:out.filter(r=>r.reviewerType==='ai-assisted').length,verifiedTaskPass:results.filter(r=>r.verifiedTaskPass).length,pending:results.filter(r=>r.semantic==='PENDING').length,rows:results,identityAssurance:'SELF_REPORTED_NOT_AUTHENTICATED',formalAdmissions:0}
}
export function teamReviewMarkdown(bundle){return ['# 整队回答质量待审核包','','这是开发样本的质量审核材料。参考报告不等于独立事实真值，审核者身份为自述。完整原始数据保存在同目录bundle.json。','','四项均需实际核对；未知保持待审核。AI辅助不得填为人工验收。','',...bundle.rows.flatMap(row=>[
 `## ${row.id}`,'','### 原问题','',row.question,'','### 实际回答（原文）','',row.answer,'',
 '### 工程状态与参考（不等于事实已验收）','',`原严格协议：${row.engineering.v1Pass?'通过':'未通过'}；受约束图鉴协议：${row.engineering.v2Pass?'通过':'未通过'}。`,'',
 '| 原顺序位置 | 参考名称 | 用户培养配置 | 逐项参考状态 |','| --- | --- | --- | --- |',
 ...row.request.members.map((m,i)=>`| ${m.slot} | ${row.reference.members?.[i]?.report?.source?.nameZh||row.reference.members?.[i]?.report?.source?.slug||'需对照原始参考'} | ${m.build===null?'未填写':'已提供，详见原始数据'} | ${row.reference.members?.[i]?.report?.status||'未知'} |`),'',
 '### 人工审核项','',...bundle.criteria.map(c=>`- [ ] ${c.id}：${c.text}`),''])].join('\n')}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [command,directory,...args]=process.argv.slice(2);let bundle,submission,output
 if(command==='build'&&args.length===1){bundle=buildTeamReview(directory);output=args[0];submission={bundleSha256:bundle.bundleSha256,rows:[]}}
 else if(command==='report'&&args.length===3){bundle=JSON.parse(fs.readFileSync(args[0]));if(!sameJson(bundle,buildTeamReview(directory)))throw Error('Bundle no longer matches original capture');submission=JSON.parse(fs.readFileSync(args[1]));output=args[2]}
 else throw Error('build CAPTURE_DIR NEW_OUTPUT | report CAPTURE_DIR BUNDLE REVIEWS NEW_OUTPUT')
 if(fs.existsSync(output))throw Error('Never overwrite review evidence')
 const report=compileTeamReviews(bundle,submission);fs.mkdirSync(output,{recursive:true})
 const draft={bundleSha256:bundle.bundleSha256,rows:bundle.rows.map(r=>({id:r.id,answerSha256:r.answerSha256,reviewer:'',reviewerType:'human',attested:false,reviewedAt:'',criteria:bundle.criteria.map(c=>({id:c.id,judgment:'unknown',reason:'',answerQuote:''}))}))}
 for(const [name,value]of Object.entries({'bundle.json':bundle,'reviews.json':submission,'draft.json':draft,'report.json':report}))fs.writeFileSync(path.join(output,name),JSON.stringify(value,null,2)+'\n',{flag:'wx'})
 fs.writeFileSync(path.join(output,'review.md'),teamReviewMarkdown(bundle),{flag:'wx'})
 console.log(JSON.stringify({output,denominator:report.denominator,humanReviewed:report.humanReviewed,pending:report.pending,verifiedTaskPass:report.verifiedTaskPass}))
}
