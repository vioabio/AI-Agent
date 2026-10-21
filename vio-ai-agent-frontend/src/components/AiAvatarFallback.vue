<template>
  <div class="ai-avatar" :class="aiType">
    <VioMark />
  </div>
</template>

<script setup>
import VioMark from '@/components/VioMark.vue'

defineProps({
  aiType: { type: String, default: 'game' } // 'game' | 'super'
})

</script>
