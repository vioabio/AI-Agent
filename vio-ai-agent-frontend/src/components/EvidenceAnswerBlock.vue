<script setup>
defineProps({ evidence: { type: Object, required: true } })
</script>

<template>
  <details class="damage-evidence">
    <summary>查看核验依据</summary>
    <p>计算引擎：本地 @smogon/calc {{ evidence.engineVersion }}</p>
    <ul><li v-for="source in evidence.sources" :key="source.role"><a :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.role === 'attacker' ? '攻击方图鉴参考' : '防御方图鉴参考' }} ↗</a></li></ul>
    <p>来源记录与本次请求、计算条件及数值绑定。公开图鉴参考和本地计算不代表当前可获得性、赛制合法性或胜率。</p>
  </details>
</template>

<style scoped>
.damage-evidence{margin-top:12px;font-size:13px;line-height:1.6}summary{cursor:pointer}p{margin:8px 0}a{color:#25664e;text-decoration:underline}ul{padding-left:20px}
</style>
