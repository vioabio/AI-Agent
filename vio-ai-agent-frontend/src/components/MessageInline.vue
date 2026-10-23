<template>
  <template v-for="(part, index) in parts" :key="index"><strong v-if="part.bold">{{ part.text }}</strong><code v-else-if="part.code">{{ part.text }}</code><template v-else>{{ part.text }}</template></template>
</template>
<script setup>
defineProps({ parts: { type: Array, default: () => [] } })
</script>
