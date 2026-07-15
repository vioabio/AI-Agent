<template>
  <div class="home">
    <header class="home-header">
      <h1 class="home-title">VIO AI Agent</h1>
      <p class="home-subtitle">AI 宝可梦大师 & AI 超级智能体应用平台</p>
    </header>

    <div class="home-cards">
      <!-- 宝可梦大师卡片 -->
      <div class="home-card game-card" @click="$router.push('/game-master')">
        <div class="card-icon">🎮</div>
        <h2 class="card-title">AI 宝可梦大师</h2>
        <p class="card-desc">
          全世代宝可梦智能顾问，从GBA红蓝宝石到Switch朱紫/ZA，图鉴查询/对战配招/版本入坑/养成攻略一站式解答。
        </p>
        <span class="card-link">开始对话 →</span>
      </div>

      <!-- 超级智能体卡片 -->
      <div class="home-card agent-card" @click="$router.push('/super-agent')">
        <div class="card-icon">🤖</div>
        <h2 class="card-title">AI 超级智能体</h2>
        <p class="card-desc">
          拥有自主规划能力的 AI 智能体，能够搜索网络、读写文件、生成 PDF，自动分解并完成复杂任务。
        </p>
        <span class="card-link">开始对话 →</span>
      </div>
    </div>

    <AppFooter />
  </div>
</template>

<script setup>
import AppFooter from '@/components/AppFooter.vue'
import { useHead } from '@vueuse/head'

useHead({
  title: 'VIO AI Agent',
  meta: [
    { name: 'description', content: 'AI 宝可梦大师 & AI 超级智能体应用平台' }
  ]
})
</script>

<style scoped>
.home {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  background: linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 50%, #16213e 100%);
}

.home-header {
  text-align: center;
  margin-bottom: 60px;
}

.home-title {
  font-size: 48px;
  font-weight: 700;
  background: linear-gradient(135deg, #ff6b9d, #c44dff);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  margin-bottom: 12px;
}

.home-subtitle {
  font-size: 18px;
  color: rgba(255, 255, 255, 0.6);
  letter-spacing: 2px;
}

.home-cards {
  display: flex;
  gap: 32px;
  flex-wrap: wrap;
  justify-content: center;
  max-width: 800px;
  margin-bottom: 60px;
}

.home-card {
  flex: 1;
  min-width: 300px;
  max-width: 380px;
  padding: 40px 32px;
  border-radius: 20px;
  cursor: pointer;
  transition: all 0.3s ease;
  border: 1px solid rgba(255, 255, 255, 0.08);
  position: relative;
  overflow: hidden;
}

.home-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  border-radius: 20px;
  opacity: 0;
  transition: opacity 0.3s ease;
}

.game-card {
  background: linear-gradient(135deg, rgba(255, 107, 157, 0.1), rgba(196, 77, 255, 0.05));
}

.game-card::before {
  background: linear-gradient(135deg, rgba(255, 107, 157, 0.15), rgba(196, 77, 255, 0.1));
}

.agent-card {
  background: linear-gradient(135deg, rgba(0, 204, 255, 0.1), rgba(102, 51, 255, 0.05));
}

.agent-card::before {
  background: linear-gradient(135deg, rgba(0, 204, 255, 0.15), rgba(102, 51, 255, 0.1));
}

.home-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.3);
  border-color: rgba(255, 255, 255, 0.15);
}

.home-card:hover::before {
  opacity: 1;
}

.card-icon {
  font-size: 48px;
  margin-bottom: 20px;
}

.card-title {
  font-size: 24px;
  font-weight: 600;
  margin-bottom: 12px;
  color: #fff;
  position: relative;
}

.card-desc {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.55);
  line-height: 1.8;
  margin-bottom: 20px;
  position: relative;
}

.card-link {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.7);
  position: relative;
  transition: color 0.3s;
}

.home-card:hover .card-link {
  color: #fff;
}

@media (max-width: 700px) {
  .home-title {
    font-size: 36px;
  }
  .home-cards {
    flex-direction: column;
    align-items: center;
  }
  .home-card {
    min-width: unset;
    max-width: 100%;
  }
}
</style>