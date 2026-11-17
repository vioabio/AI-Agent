<script setup>
import { computed } from 'vue'
import { parsePokemonBlock } from '../api/answer-block.js'
const props = defineProps({ block: { type: Object, required: true } })
const emit = defineEmits(['open-pokemon'])
const fact = computed(() => parsePokemonBlock(props.block))
const labels = ['HP', '攻击', '防御', '特攻', '特防', '速度']
</script>

<template>
  <section v-if="fact" class="verified-pokemon" aria-label="已核验图鉴卡片">
    <header><strong>{{ fact.name }}</strong><span>资料事实 · {{ fact.types.join(' / ') }}</span></header>
    <dl><div v-for="(label, index) in labels" :key="label"><dt>{{ label }}</dt><dd>{{ fact.stats[index] }}</dd></div></dl>
    <footer><small>{{ fact.gameReleaseId }} · {{ fact.dataReleaseId }}</small>
      <button type="button" @click="emit('open-pokemon', { formId: fact.formId, gameReleaseId: fact.gameReleaseId, dataReleaseId: fact.dataReleaseId })">查看图鉴</button>
    </footer>
  </section>
</template>

<style scoped>
.verified-pokemon{padding:14px;border:1px solid #d6e5db;border-radius:12px;background:#f6faf7;color:#244337}
header,footer{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}
header span,small{font-size:12px}dl{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}dl div{display:flex;gap:8px}dt,dd{margin:0;font-size:13px}
button{border:1px solid #357259;border-radius:8px;padding:6px 12px;background:white;color:#28563f;cursor:pointer}button:focus-visible{outline:2px solid #357259;outline-offset:3px}
</style>
