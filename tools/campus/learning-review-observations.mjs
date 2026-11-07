import {buildLearningNotes,learningNotesVersion} from '../../vio-ai-agent-frontend/src/api/build-learning.js'
export function auditLearningNotes(answer,report,phrases=[]){
  const body=(answer||'').split('### 配招逐项核对')[1]?.split('### ')[0]||''
  const blocks=body.split(/\n- 招式\d+ · /).slice(1)
  const rows=report.moves.map((m,index)=>({slot:m.slot,pass:blocks[index]?.startsWith(m.supplied+'：')===true&&buildLearningNotes(m,report.request.build?.level).every(note=>blocks[index].includes('  - '+note))}))
  const checks={learningRowsAligned:blocks.length===report.moves.length&&rows.every(r=>r.pass),expectedLearningPhrases:phrases.every(p=>body.includes(p)),noLearningOrLegalityOverclaim:!/(?:0级即可学习|可以在0级学会|已通过合法性验证)/.test(body)}
  return {version:learningNotesVersion,checks,rows,pass:Object.values(checks).every(Boolean)}
}
