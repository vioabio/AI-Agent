<template>
  <template v-for="(part, index) in parts" :key="index"><strong v-if="part.bold">{{ part.text }}</strong><code v-else-if="part.code">{{ part.text }}</code><a v-else-if="part.href" :href="part.href" target="_blank" rel="noopener noreferrer">{{ part.text }}</a><template v-else>{{ part.text }}</template></template>
</template>
<script setup>
defineProps({ parts: { type: Array, default: () => [] } })
</script>
<style scoped>a { color:#256b53; text-decoration:underline; text-underline-offset:3px; overflow-wrap:anywhere; }a:hover { color:#174633; }</style>
