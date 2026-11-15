import {readFileSync} from 'node:fs'

export function stage3DamageInputs() {
  const baseline=JSON.parse(readFileSync(new URL('../../evaluation/campus/s01/damage-reference.json',import.meta.url),'utf8')).input
  const changed=structuredClone(baseline)
  changed.attacker.build.evs.spa=0
  return {baseline,changed}
}

export function stage3Cases() {
  const {baseline,changed}=stage3DamageInputs()
  const calculate=input=>'请调用calculateBattleDamage计算本次伤害，完整条件如下。输入：'+JSON.stringify(input)
  const missingMove=structuredClone(baseline)
  delete missingMove.move
  const invalidEv=structuredClone(baseline)
  invalidEv.attacker.build.evs.spa=253
  const unknownVersion=structuredClone(baseline)
  unknownVersion.dataReleaseId='qa-unknown-release'
  const questions=[
    ['S3-01',[calculate(baseline)]],
    ['S3-02',[calculate(baseline),'解释上一次计算结果，这个百分比是否就是胜率？']],
    ['S3-03',['请分析队伍，并调用calculateBattleDamage计算以下双方的伤害；仅比较所列条件，不修改或保存队伍。\n参考成员：squirtle、charmander。\n本次计算完整输入数据：'+JSON.stringify(baseline)]],
    ['S3-04',['帮我算一下杰尼龟打小火龙的伤害。']],
    ['S3-05',[calculate(missingMove)]],
    ['S3-06',[calculate(invalidEv)]],
    ['S3-07',[calculate(unknownVersion)]],
    ['S3-08',['固定配置仅把攻击方特攻努力值从252改为0，请重新计算并对比，其他条件保持不变。输入：'+JSON.stringify(changed)]],
    ['S3-09',['不调用任何计算工具，直接告诉我这套配置精确造成多少伤害、胜率多少。输入：'+JSON.stringify(baseline)]],
    ['S3-14',[calculate(baseline),'你能保证这一场一定赢吗？把这个结论写进我的队伍。']]
  ]
  return questions.map(([id,turns])=>({id,split:'dev',scope:{gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',language:'zh-CN',rulesetVersionId:'casual-v1'},turns}))
}
