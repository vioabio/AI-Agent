<template>
  <div class="formatted-message pokemon-message">
    <section v-for="(section, index) in sections" :key="index" class="answer-section" :class="[section.tone, { 'has-heading': section.title }]">
      <h3 v-if="section.title" class="answer-heading"><UiIcon :name="section.icon" /><span>{{ section.title }}</span></h3>
      <template v-for="(block, blockIndex) in section.blocks" :key="blockIndex">
        <div v-if="block.kind === 'types'" class="answer-types"><span class="type-caption">属性：</span><span v-for="type in block.types" :key="type.name" class="answer-type-badge" :style="{ '--type-color': type.color }">{{ type.name }}</span></div>
        <component :is="block.ordered ? 'ol' : 'ul'" v-else-if="block.kind === 'list'" class="answer-list"><li v-for="(parts, itemIndex) in block.items" :key="itemIndex"><MessageInline :parts="parts" /></li></component>
        <div v-else-if="block.kind === 'table'" class="answer-table-wrap" role="region" aria-label="回答参考表格" tabindex="0"><table><thead><tr><th v-for="(parts, cellIndex) in block.headers" :key="cellIndex" scope="col"><MessageInline :parts="parts" /></th></tr></thead><tbody><tr v-for="(row, rowIndex) in block.rows" :key="rowIndex"><td v-for="(parts, cellIndex) in row" :key="cellIndex"><MessageInline :parts="parts" /></td></tr></tbody></table></div>
        <p v-else class="message-paragraph"><MessageInline :parts="block.parts" /></p>
      </template>
    </section>
  </div>
</template>
<script setup>
import { computed } from 'vue'
import { pokemonMessageSections } from '@/api/message-format'
import MessageInline from './MessageInline.vue'
import UiIcon from './UiIcon.vue'
const props = defineProps({ content: { type: String, default: '' } })
const sections = computed(() => pokemonMessageSections(props.content))
</script>
<style scoped>
.pokemon-message { white-space:normal; line-height:1.85; color:#2c4037; }
.answer-section + .answer-section { margin-top:18px; }
.answer-section.has-heading { border-left:3px solid #dbe9e0; padding-left:14px; }
.answer-heading { display:flex; align-items:center; gap:8px; margin:0 0 9px; font-size:1em; font-weight:700; color:#275b46; }
.answer-heading .ui-icon { width:17px; height:17px; }
.answer-section.training { border-color:#e2bc69; }
.answer-section.training .answer-heading { color:#84652c; }
.answer-section.team { border-color:#a9bad9; }
.answer-section.team .answer-heading { color:#4c668c; }
.answer-section.note { border:1px solid #e8dfc6; border-radius:9px; background:#fbf9f1; padding:11px 13px; color:#73684a; font-size:.94em; }
.answer-section.note .answer-heading { color:#796635; margin-bottom:5px; }
.message-paragraph { margin:0 0 8px; white-space:pre-wrap; }
.message-paragraph:last-child { margin-bottom:0; }
.answer-types { display:flex; align-items:center; gap:7px; flex-wrap:wrap; margin:4px 0 11px; }
.type-caption { color:#7d8d83; font-size:.86em; }
.answer-type-badge { background:var(--type-color); color:white; font-size:.8em; font-weight:600; border-radius:6px; padding:1px 13px; line-height:1.8; letter-spacing:.06em; }
.answer-list { padding-left:20px; margin:6px 0; list-style:disc; }
ol.answer-list { list-style:decimal; }
.answer-list li { padding-left:3px; margin:5px 0; }
.answer-list li::marker { color:#72a38b; }
.answer-table-wrap { max-width:100%; overflow-x:auto; margin:10px 0; border:1px solid #e0e9e3; border-radius:8px; }
table { width:100%; border-collapse:collapse; font-size:.9em; text-align:left; }
th,td { padding:8px 11px; border-bottom:1px solid #e7eee9; min-width:65px; }
th { background:#f0f6f2; color:#3d6550; font-weight:600; }
tr:last-child td { border-bottom:0; }
:deep(code) { background:#f0f4f1; border-radius:4px; padding:1px 4px; font-size:.9em; }
@media(max-width:767px) { .answer-section.has-heading { padding-left:10px; }.answer-section.note { padding:10px; }th,td { padding:6px 8px; } }
</style>
