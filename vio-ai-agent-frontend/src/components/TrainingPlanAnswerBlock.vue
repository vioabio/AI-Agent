<script setup>
import { computed } from 'vue'
import { parseTrainingBlock } from '../api/training-answer-block.js'
import { NATURE_LABELS } from '../api/pokemon-natures.js'
import { TERA_LABELS } from '../api/team-transfer.js'
const props = defineProps({ block:{type:Object,required:true}, busy:{type:Boolean,default:false} })
const emit = defineEmits(['open-pokemon','import-draft'])
const plan = computed(()=>parseTrainingBlock(props.block))
const stats = [['hp','HP'],['atk','攻击'],['def','防御'],['spa','特攻'],['spd','特防'],['spe','速度']]
</script>

<template>
  <section v-if="plan" class="saved-training" aria-label="已保存培养配置卡片">
    <header><strong>{{ plan.teamName }} · 培养配置</strong><small>读取时快照 · 可能已变更</small></header>
    <p class="notice">以下是保存值，可能包含创建时默认值；不等于游戏内实际状态，不代表合法性或最优培养建议。</p>
    <details v-for="member in plan.members" :key="member.slot">
      <summary>第 {{ member.slot }} 位 · {{ member.pokemon.name }} · Lv.{{ member.build.level }} · {{ NATURE_LABELS[member.build.nature] }}</summary>
      <p>招式：{{ member.build.moves.length ? member.build.moves.join(' / ') : '未配置' }}</p>
      <p>特性：{{ member.build.ability || '未配置' }} · 道具：{{ member.build.item || '未指定（保存为空）' }} · 太晶：{{ TERA_LABELS[member.build.teraType] || '未指定' }}</p>
      <dl><div v-for="[key,label] in stats" :key="key"><dt>{{ label }}</dt><dd>EV {{ member.build.evs[key] }} / IV {{ member.build.ivs[key] }}</dd></div></dl>
      <button type="button" @click="emit('open-pokemon',member.pokemon)">查看{{ member.pokemon.name }}图鉴</button>
    </details>
    <footer>可复制读取时的配置为新草稿，核对后手动保存；不会更新原队伍，也不代表最优培养方案。</footer>
    <button type="button" :disabled="busy" @click="emit('import-draft',block)">导入为新草稿</button>
  </section>
</template>

<style scoped>
.saved-training{padding:14px;border:1px solid #d6e5db;border-radius:12px;background:#f6faf7;color:#244337;overflow-wrap:anywhere}header{display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap}small,.notice,footer{font-size:12px;line-height:1.6}details{margin:10px 0;border-top:1px solid #d6e5db;padding-top:10px;font-size:13px}summary{cursor:pointer}dl{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:6px}dl div{display:flex;gap:8px}dt,dd{margin:0}button{padding:6px 10px;border:1px solid #357259;border-radius:8px;background:white;color:inherit;cursor:pointer}button:focus-visible{outline:2px solid #357259;outline-offset:2px}
</style>
