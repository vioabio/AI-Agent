import fs from 'node:fs'
import path from 'node:path'
import {digest,observationHash} from '../../tools/campus/evaluation.mjs'
const directory='artifacts/campus/s2/facts-assisted-review-v1'
fs.mkdirSync(directory)
const sourceFile='artifacts/campus/s2/live-final/observations.json',report=JSON.parse(fs.readFileSync(sourceFile)),cases=JSON.parse(fs.readFileSync('evaluation/campus/s2/cases.json')),sources=JSON.parse(fs.readFileSync('evaluation/campus/s2/sources.json'))
const item=cases.find(c=>c.id==='S2-FACTS-01'),records=report.records.filter(r=>r.caseId===item.id)
if(records.length!==2)throw Error('Expected two fixed observations')
const reviews=[],evidence=[]
for(const record of records){
 const raw=fs.readFileSync(path.join('artifacts/campus/s2/live-final',record.eventsFile))
 if(digest(raw)!==record.eventsSha256||observationHash(record)!==record.observationSha256)throw Error('Historical evidence changed')
 const events=JSON.parse(raw),answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text??'').join('')
 if(answer!==record.answer)throw Error('Answer/events differ')
 fs.writeFileSync(path.join(directory,record.eventsFile),raw,{flag:'wx'})
 reviews.push({caseId:item.id,repetition:record.repetition,observationSha256:observationHash(record),reviewerType:'ai-assisted',reviewer:'Codex AI-assisted source review',reviewedAt:new Date().toISOString(),
  note:'AI source comparison only, not human acceptance. Numeric/source-boundary criteria checked; introductory durability/growth wording has no support in the supplied sources and remains a separate open wording concern.',
  decisions:[{criterionId:'C1',verdict:'pass',reason:'水属性与HP44、攻击48、防御65、特攻50、特防64、速度43逐项匹配pokeapi-7，合计314；明确称种族值，未标为50级实际能力。仅对该条目范围给辅助判断，开头耐久/成长措辞另列未支持风险。',sourceIds:['pokeapi-7']},
   {criterionId:'C2',verdict:'pass',reason:'正文明确本地参考快照不代表当前强度/使用率/赛制适配性，未将数据存在断言为排位合法或热门证据。',sourceIds:['reference-vs-rules-v1']}]})
 evidence.push({caseId:item.id,repetition:record.repetition,observationSha256:observationHash(record),eventsSha256:record.eventsSha256,answerMatchesEvents:true})
}
fs.writeFileSync(directory+'/reviews-ai-assisted.json',JSON.stringify(reviews,null,2)+'\n',{flag:'wx'})
fs.writeFileSync(directory+'/source-comparison.json',JSON.stringify({historicalCollection:report.startedAt,sourceReportSha256:digest(fs.readFileSync(sourceFile)),evidence,sources:sources.filter(s=>['pokeapi-7','reference-vs-rules-v1'].includes(s.id)),
 findings:[{code:'UNSUPPORTED_FACTS_INTRO',status:'NEEDS_HUMAN_REVIEW',appliesToRepetitions:[1,2],quote:'以高耐久基础和稳定成长性著称',scope:'The supplied selected-field snapshot and authored boundary guide do not support this qualitative introduction. This does not itself prove the statement false.',suggestion:'纯图鉴题直接展示属性、种族值与来源；定性策略判断需单独标注并给出依据。'}],
 humanReviewsCreated:0,newModelCalls:0,currentRuntimeQualityVerified:false},null,2)+'\n',{flag:'wx'})
fs.writeFileSync(directory+'/review-notes.md',['# 杰尼龟图鉴历史回答：AI辅助核对','', '范围：10月23日S2-FACTS-01的两次历史回答。不是当前版本的新回答，也不是人工结论。','',...records.flatMap(r=>[`## 轮次${r.repetition}`,'',r.answer,'','辅助核对：六项数值与水属性匹配，资料限制明确。开头耐久/成长表述无所附来源支持，待人工判断；不擅自改写冻结验收条目。',''])].join('\n'),{flag:'wx'})
console.log(directory)
