import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/Home.vue'),
    meta: { title: 'VIO AI Agent' }
  },
  {
    path: '/game-master',
    name: 'GameMaster',
    component: () => import('@/views/GameMaster.vue'),
    meta: { title: 'AI 宝可梦大师' }
  },
  {
    path: '/super-agent',
    name: 'SuperAgent',
    component: () => import('@/views/SuperAgent.vue'),
    meta: { title: 'AI 超级智能体' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫：动态设置页面标题
router.beforeEach((to) => {
  document.title = to.meta.title || 'VIO AI Agent'
})

export default router