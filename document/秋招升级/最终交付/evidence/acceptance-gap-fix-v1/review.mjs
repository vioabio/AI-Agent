import {readFileSync,writeFileSync} from 'node:fs';
import {observationHash} from '../../../tools/campus/evaluation.mjs';
const base='artifacts/campus/acceptance-gap-fix-v1';
const report=JSON.parse(readFileSync(base+'/regression-v2/observations.json'));
const cases=JSON.parse(readFileSync('evaluation/campus/s2/cases.json'));
const notes={
'S2-FACTS-05':['冰/幽灵和70/80/70/80/70/110、合计480匹配捕获来源。','只展示来源事实和限制，未扩展机制或排名。'],
'S2-FACTS-06':['实际图鉴冰/幽灵及六项种族值匹配pokeapi-478。','没有攻略原文时明确不足，不再发布单打不能交换或未经核验的弱点/招式机制。'],
'S2-TEAM-03':['唯一预览为第一位moves由四招改为surf/protect；实际存储及其他字段检查保持。','WAITING_APPROVAL，未提交确认，取消后存储不变，不声称合法或最优。'],
'S2-TEAM-04':['已实际读取并生成仅第一位moves变更的预览；非学习途径查询结束。','等待页面确认，原队伍不变，未承诺配置合法/最优。'],
'S2-CLARIFICATION-03':['正文明确追问赛事/赛制、规则版本/赛季及日期，休闲上下文不代替正式规则。','无计算、预览或写入；没有直接判断合法或最优。'],
'S2-CLARIFICATION-04':['赛事名称、主办方、规则版本与日期完整列出，已提供条件可保留。','无确认修改，不把图鉴或通用攻略当赛事资格证明。']};
const reviews=report.records.map(r=>({caseId:r.caseId,repetition:r.repetition,observationSha256:observationHash(r),reviewerType:'ai-assisted',reviewer:'Codex source-linked regression review',reviewedAt:new Date().toISOString(),note:'已知失败及邻近题的回归，不是新的未见保留集。程序回复与预览不算模型自由生成准确率；不是人工签字。',decisions:cases.find(c=>c.id===r.caseId).expected.criteria.map((c,i)=>({criterionId:c.id,verdict:'pass',reason:notes[r.caseId][i],sourceIds:c.sourceIds}))}));
writeFileSync(base+'/reviews-v2.json',JSON.stringify(reviews,null,2)+'\n');
