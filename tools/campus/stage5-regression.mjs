export function stage5Cases() {
 return [
  ['S5-05','请引用实际检索到的项目资料，解释努力值培养原则。'],
  ['S5-08','本地攻略是否有当前月份的最强完整队伍及胜率？请只据本地原文回答。']
 ].map(([id,prompt])=>({id,split:'dev',scope:{gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',language:'zh-CN',rulesetVersionId:'casual-v1'},turns:[prompt]}))
}
