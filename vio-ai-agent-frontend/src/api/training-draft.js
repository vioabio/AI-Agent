import { parseTrainingBlock } from './training-answer-block.js'
import { normalizeTransferTeam } from './team-transfer.js'

// A portable local draft has no saved-team ID, revision, ownership or approval capability.
export function trainingBlockToDraft(raw, context) {
  const block = parseTrainingBlock(raw)
  if (!block) throw new Error('配置卡片不完整或格式不受支持，未导入草稿')
  if (block.gameReleaseId !== context?.gameReleaseId || block.dataReleaseId !== context?.dataReleaseId || block.rulesetVersionId !== context?.rulesetVersionId) throw new Error('卡片与当前游戏、规则或数据版本不同，请切换到对应版本后再导入')
  return normalizeTransferTeam({ name:block.teamName, rulesetId:block.rulesetVersionId, dataReleaseId:block.dataReleaseId,
    members:block.members.map(member=>({formId:member.pokemon.formId,slot:member.slot,build:member.build})) })
}
