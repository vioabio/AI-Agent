<script setup>
import { computed } from 'vue'
import { parseDamageBlock } from '../api/damage-answer-block.js'
import EvidenceAnswerBlock from './EvidenceAnswerBlock.vue'
const props = defineProps({ block: { type: Object, required: true } })
const damage = computed(() => parseDamageBlock(props.block))
const labels = ['HP', '攻击', '防御', '特攻', '特防', '速度']
</script>

<template>
  <section v-if="damage" class="verified-damage" aria-label="已核验伤害卡片">
    <header><strong>伤害计算 · 已核验</strong><small>第九世代 · {{ damage.field.gameType === 'Singles' ? '单打' : '双打' }}</small></header>
    <p class="damage-matchup">{{ damage.attacker.species }} → {{ damage.defender.species }} · {{ damage.move }}</p>
    <p class="damage-range"><strong>{{ damage.minimum }}–{{ damage.maximum }}</strong><span>目标 HP {{ damage.defenderHp }} · 约 {{ damage.minimumPercent.toFixed(1) }}%–{{ damage.maximumPercent.toFixed(1) }}%</span></p>
    <details>
      <summary>查看实际计算条件</summary>
      <div class="damage-members"><div v-for="(member, index) in [damage.attacker, damage.defender]" :key="index">
        <h4>{{ index === 0 ? '攻击方' : '防御方' }} · {{ member.species }} · Lv.{{ member.level }}</h4>
        <p>性格 {{ member.nature }} · 特性 {{ member.ability }} · 道具 {{ member.item || '未携带' }}</p>
        <dl><div v-for="(label, stat) in labels" :key="label"><dt>{{ label }}</dt><dd>EV {{ member.evs[stat] }} / IV {{ member.ivs[stat] }}</dd></div></dl>
      </div></div>
      <p>天气：{{ damage.field.weather || '无' }} · 场地：{{ damage.field.terrain || '无' }}</p>
    </details>
    <EvidenceAnswerBlock v-if="damage.evidence" :evidence="damage.evidence" />
    <p v-else class="evidence-missing">本次结果未提供完整图鉴来源记录。</p>
    <footer>来源：本地 @smogon/calc。仅对应以上条件；不代表胜率或配置合法性，不包含未声明的战斗效果。</footer>
  </section>
</template>

<style scoped>
.verified-damage{padding:14px;border:1px solid #d6e5db;border-radius:12px;background:#f6faf7;color:#244337;overflow-wrap:anywhere}
header,.damage-range{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}
.damage-matchup{margin:10px 0}.damage-range strong{font-size:24px}.damage-range span,small,footer{font-size:12px}
.evidence-missing{font-size:12px;color:#675c40}
details{margin-top:12px}summary{cursor:pointer}details p{margin:8px 0;font-size:13px}.damage-members{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,220px),1fr));gap:14px;margin-top:10px}h4{font-size:13px}
dl{display:grid;gap:4px}dl div{display:flex;justify-content:space-between;gap:8px;font-size:12px}dt,dd{margin:0}footer{margin-top:12px;line-height:1.6}
</style>
