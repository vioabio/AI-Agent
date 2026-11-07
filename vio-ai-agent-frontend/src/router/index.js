import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Home',
    redirect: to => ({ path: '/game-master', query: { ...to.query }, hash: to.hash })
  },
  {
    path: '/game-master',
    name: 'GameMaster',
    component: () => import('@/views/GameMaster.vue'),
    meta: { title: '宝可梦助手' }
  },
  {
    path: '/super-agent',
    name: 'SuperAgent',
    redirect: to => ({ path: '/game-master', query: { ...to.query, scope: 'super-agent' } })
  },
  {
    path: '/pokemon-workbench',
    name: 'PokemonWorkbench',
    redirect: to => ({ path: '/game-master', query: { ...to.query, panel: 'resources' } })
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫：动态设置页面标题
router.beforeEach((to) => {
  // Keep route resolution usable in SSR/unit-test contexts where `document`
  // is intentionally absent; the browser still receives the same title.
  if (typeof document !== 'undefined') {
    document.title = to.meta.title || 'VIO AI Agent'
  }
})

export default router
