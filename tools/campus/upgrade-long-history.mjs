// Development scenario. This fixture is separate from the frozen 40-case suite.
export function longHistoryCases() {
  const filler = [
    '基础属性关系下，冰招式打草/钢是多少倍？',
    '基础属性关系下，火招式打虫/钢是多少？',
    '基础属性关系下，幽灵招式打一般/超能是多少？',
    '不考虑特性道具太晶，电属性招式打水/飞行的倍率是多少？'
  ]
  return [{id:'U3-LONG-HISTORY',category:'conversation',split:'dev',
    scope:{gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',language:'zh-CN',rulesetVersionId:'casual-v1'},
    turns:[
      '请按参考等级顺序列出朱紫馆主，并说明不是强制挑战顺序。',
      '看刚才表格第四位馆主的初战配置。',
      ...filler,...filler,
      '这个馆主的太晶成员是哪只？',
      '想围绕一个核心组队，怎样检查功能角色是否重叠？',
      '现在改问奇树，请只查询她的初战队伍。',
      '这个馆主的太晶成员是哪只？',
      '现在只聊通用组队，如何检查功能角色是否重叠？'
    ],
    expected:{rubric:'承接海岱与好胜毛蟹太晶水，长历史后通用组队只使用角色原文，切换奇树后回答梦妖魔太晶电；核对真实历史裁剪事件、工具和正文。',
      relevantDocumentIds:['team-roles-v1'],reviewMethod:'manual-or-ai-assisted-not-auto-pass'}
  }]
}
