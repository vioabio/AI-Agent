<template>
  <div class="home">
    <header class="home-header">
      <h1 class="home-title">VIO AI Agent</h1>
      <p class="home-subtitle">问答、资料与队伍分析，在一个工作区完成</p>
    </header>

    <div class="home-cards">
      <!-- 宝可梦大师卡片 -->
      <RouterLink class="home-card game-card" to="/game-master">
        <div class="card-icon"><PokemonPortrait form-id="7" name="杰尼龟" /></div>
        <h2 class="card-title">宝可梦助手</h2>
        <p class="card-desc">
          围绕朱／紫进行流式问答，查询版本化资料、组建队伍，并把成员资料交给 AI 分析。实际可查询范围取决于已加载的数据。
        </p>
        <span class="card-link">开始对话 →</span>
      </RouterLink>

    </div>

    <AppFooter />
  </div>
</template>

<script setup>
import AppFooter from '@/components/AppFooter.vue'
import PokemonPortrait from '@/components/PokemonPortrait.vue'
import { useHead } from '@unhead/vue'

useHead({
  title: 'VIO AI Agent',
  meta: [
    { name: 'description', content: '宝可梦助手：问答、资料、队伍分析与历史会话' }
  ]
})
</script>
