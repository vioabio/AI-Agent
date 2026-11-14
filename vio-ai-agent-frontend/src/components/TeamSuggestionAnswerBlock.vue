<script setup>
import { computed } from 'vue'
import { parseTeamBlock } from '../api/team-answer-block.js'
import PokemonPortrait from './PokemonPortrait.vue'
const props = defineProps({ block: { type: Object, required: true } })
const emit = defineEmits(['open-pokemon'])
const team = computed(() => parseTeamBlock(props.block))
</script>

<template>
  <section v-if="team" class="team-reference" aria-label="队伍参考成员卡片">
    <header><strong>组队参考 · 图鉴事实</strong><span>{{ team.members.length }} 位参考成员 · 未保存</span></header>
    <ul class="members"><li v-for="member in team.members" :key="member.formId"><button type="button" :aria-label="`查看${member.name}图鉴`" @click="emit('open-pokemon', member)"><PokemonPortrait :form-id="member.formId" :name="member.name" class="member-portrait" /><strong>{{ member.name }}</strong><small>{{ member.types.join(' / ') }}</small></button></li></ul>
    <details><summary>配置与建议的边界</summary><ul><li v-for="item in team.missing" :key="item">{{ item }}</li></ul><p>策略建议见回答正文。要修改现有队伍，请明确选择队伍并核对变更预览，在审批区确认后执行。</p></details>
  </section>
</template>

<style scoped>
.team-reference{padding:14px;border:1px solid #d6e5db;border-radius:12px;background:#f6faf7;color:#244337;overflow-wrap:anywhere}header{display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap}header span,small{font-size:12px}.members{list-style:none;padding:0;display:grid;grid-template-columns:repeat(auto-fit,minmax(100px,1fr));gap:8px}.members button{width:100%;border:1px solid #d6e5db;border-radius:10px;background:white;color:inherit;padding:10px;display:flex;align-items:center;flex-direction:column;gap:5px;cursor:pointer}.member-portrait{width:48px;height:48px;object-fit:contain}button:focus-visible{outline:2px solid #357259;outline-offset:2px}details{font-size:13px;line-height:1.6}summary{cursor:pointer}
</style>
