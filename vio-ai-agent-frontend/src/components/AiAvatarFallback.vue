<template>
  <div class="ai-avatar" :class="aiType">
    {{ emoji }}
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  aiType: { type: String, default: 'love' } // 'love' | 'super'
})

const emoji = computed(() => (props.aiType === 'love' ? '💕' : '🤖'))
</script>

<style scoped>
.ai-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  flex-shrink: 0;
}

.ai-avatar.love {
  background: linear-gradient(135deg, rgba(255, 107, 157, 0.25), rgba(196, 77, 255, 0.2));
}

.ai-avatar.super {
  background: linear-gradient(135deg, rgba(0, 204, 255, 0.25), rgba(102, 51, 255, 0.2));
}
</style>