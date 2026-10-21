<template>
  <div class="formatted-message"><template v-for="(line, index) in lines" :key="index"><component :is="line.heading ? 'h3' : 'div'" :class="{ 'message-paragraph': !line.heading }"><template v-for="(part, partIndex) in line.parts" :key="partIndex"><strong v-if="part.bold">{{ part.text }}</strong><code v-else-if="part.code">{{ part.text }}</code><template v-else>{{ part.text }}</template></template><br v-if="!line.parts.length" /></component></template></div>
</template>
<script setup>
import { computed } from 'vue'
const props=defineProps({content:{type:String,default:''}})
// Render a small, safe formatting subset through Vue text nodes, never model-provided HTML.
const lines=computed(()=>props.content.split('\n').map(raw=>{
  const heading=/^#{1,3}\s+(.+)$/.exec(raw) || /^\*\*([^*]+)\*\*$/.exec(raw)
  const text=heading?heading[1]:raw
  const parts=text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).filter(Boolean).map(part=>({
    text:part.startsWith('**')?part.slice(2,-2):part.startsWith('`')?part.slice(1,-1):part,
    bold:part.startsWith('**'),code:part.startsWith('`')
  }))
  return {heading:!!heading,parts}
}))
</script>
