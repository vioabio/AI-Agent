<template>
  <div class="pokemon-portrait" :class="{ unavailable: failed }">
    <img v-if="validId && !failed" :key="src" :src="src" :alt="name || `宝可梦 #${formId}`" loading="lazy" decoding="async" referrerpolicy="no-referrer" @error="failed = true" />
    <div v-else class="portrait-placeholder" role="img" :aria-label="`${name || '宝可梦'}暂无插图`"><span></span></div>
  </div>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
const props = defineProps({ formId: { type: [String, Number], default: '' }, name: { type: String, default: '' } })
const failed = ref(false)
const validId = computed(() => Number.isSafeInteger(Number(props.formId)) && Number(props.formId) > 0 && Number(props.formId) <= 20000)
const src = computed(() => [1, 6, 7, 25, 445].includes(Number(props.formId))
  ? `/pokemon/${Number(props.formId)}.png`
  : `https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/${Number(props.formId)}.png`)
watch(src, () => { failed.value = false })
</script>
<style scoped>
.pokemon-portrait { display: grid; place-items: center; min-width: 0; min-height: 0; overflow: hidden; flex-shrink: 0; }
img { display: block; width: 100%; height: 100%; min-height: 0; max-height: 230px; object-fit: contain; filter: drop-shadow(0 9px 8px #254b3b10); }
.portrait-placeholder { width: 70px; height: 70px; border: 4px solid #b7c9bf; border-radius: 50%; background: linear-gradient(#e1ece5 45%,#b7c9bf 45%,#b7c9bf 53%,white 53%); display: grid; place-items: center; }
.portrait-placeholder span { width: 22px; height: 22px; border-radius: 50%; border: 4px solid #b7c9bf; background: white; }
</style>
