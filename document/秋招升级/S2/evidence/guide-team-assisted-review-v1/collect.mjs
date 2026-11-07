import fs from 'node:fs'
import {digest,observationHash,sameJson} from '../../../tools/campus/evaluation.mjs'
const base='artifacts/campus/s2/live-final/',out='artifacts/campus/s2-guide-team-review/'
const report=JSON.parse(fs.readFileSync(base+'observations.json')),reviews=[],binding=[]
for(const record of report.records.filter(r=>['S2-GUIDES-01','S2-TEAM-01'].includes(r.caseId))){
 const raw=fs.readFileSync(base+record.eventsFile),events=JSON.parse(raw)
 if(digest(raw)!==record.eventsSha256||observationHash(record)!==record.observationSha256||events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text??'').join('')!==record.answer)throw Error('Evidence mismatch')
 const guide=record.caseId==='S2-GUIDES-01'
 if(!guide&&!events.some(e=>e.type==='approval.required'&&sameJson(e.payload?.teamChange,record.preview)))throw Error('Preview mismatch')
 const sourceIds=[guide?'team-roles-v1':'team-contract']
 const reasons=guide?['两轮都涉及角色和缺口，但没有明确解释替换方向对应的牺牲与代价，未完整满足C1。','原文没有回答中列举的具体物种、招式搭配与机制示例。真实标题和作者不能支持这些扩展结论，C2辅助判为不通过。']:
 ['事件teamChange与记录preview一致，仅列第一位等级50→51；其他字段不变仍主要依赖采集器布尔检查，未独立重验完整存储快照，保留未知。','WAITING_APPROVAL与approval.required支持等待确认；存储不变及清理取消有采集记录，但本次未独立回读历史数据库，因此C2保留未知。']
 reviews.push({caseId:record.caseId,repetition:record.repetition,observationSha256:observationHash(record),reviewerType:'ai-assisted',reviewer:'Codex AI-assisted source and preview review',reviewedAt:new Date().toISOString(),note:'Historical source/event comparison only. Not human acceptance or current model reproduction.',decisions:reasons.map((reason,i)=>({criterionId:'C'+(i+1),verdict:guide?'fail':'unknown',reason,sourceIds}))})
 binding.push({caseId:record.caseId,repetition:record.repetition,eventsSha256:record.eventsSha256,answerMatchesEvents:true,previewMatchesApprovalEvent:guide?null:true})
 fs.writeFileSync(out+record.eventsFile,raw,{flag:'wx'})
}
if(reviews.length!==4)throw Error('Expected four observations')
const prior=JSON.parse(fs.readFileSync('artifacts/campus/s2/facts-assisted-review-v1/reviews-ai-assisted.json'))
fs.writeFileSync(out+'reviews-ai-assisted.json',JSON.stringify([...prior,...reviews],null,2)+'\n',{flag:'wx'})
fs.writeFileSync(out+'binding.json',JSON.stringify({historicalCollection:report.startedAt,bindings:binding,independentHistoricalDatabaseRead:false,currentModelReproduced:false,newHumanReviews:0,newModelCalls:0},null,2)+'\n',{flag:'wx'})
fs.writeFileSync(out+'answers.md',report.records.filter(r=>['S2-GUIDES-01','S2-TEAM-01'].includes(r.caseId)).map(r=>'## '+r.caseId+' / '+r.repetition+'\n\n'+(r.answer||JSON.stringify(r.preview,null,2))).join('\n\n'),{flag:'wx'})
