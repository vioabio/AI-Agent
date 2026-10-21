<template>
  <div class="app-shell reference-layout" :class="{ 'in-workspace': route.path === '/game-master' }">
    <header v-if="canUseApplication" class="session-toolbar" :class="{ 'workspace-toolbar': route.path === '/game-master' }" data-testid="session-toolbar">
      <RouterLink to="/" class="session-brand">VIO AI Agent</RouterLink>
      <div v-if="['oidc', 'local'].includes(state.snapshot.mode)" class="session-account">
        <span class="session-principal">{{ state.snapshot.mode === 'local' ? '本地账户' : state.snapshot.principalId }}</span>
        <span class="session-tenant">{{ state.snapshot.tenantId }}</span>
        <button type="button" @click="signOut">退出登录</button>
      </div>
      <span v-else class="session-tenant">{{ state.snapshot.mode === 'api_key' ? '受限演示' : '开发模式' }}</span>
    </header>
    <router-view v-if="canUseApplication" :key="`${state.viewVersion}:${route.fullPath}`" />
    <LocalAuthPortal v-else-if="state.phase === 'ready' && state.snapshot?.mode === 'local'" />
    <main v-else class="auth-gate" data-testid="auth-gate" aria-live="polite">
      <div class="auth-card">
        <p class="auth-brand">VIO AI Agent</p>
        <h1>{{ gateTitle }}</h1>
        <p>{{ gateMessage }}</p>
        <button v-if="state.phase === 'ready' && state.snapshot?.mode === 'oidc'" type="button" @click="login(route.path)">
          {{ state.snapshot.accessDenied ? '切换账号' : '登录' }}
        </button>
        <button v-if="state.phase === 'error'" type="button" @click="state.reason === 'logout_failed' ? signOut() : refresh()">
          {{ state.reason === 'logout_failed' ? '重试退出' : '重试' }}
        </button>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, watch } from 'vue'
import LocalAuthPortal from '@/components/LocalAuthPortal.vue'
import { useRoute, useRouter } from 'vue-router'
import { consumeLoginReturnPath, initializeAuthSession, useAuthSession } from '@/composables/useAuthSession'

const route = useRoute()
const router = useRouter()
const { state, canUseApplication, refresh, signOut, login } = useAuthSession()
const gateTitle = computed(() => {
  if (state.phase === 'loading') return '正在确认登录状态'
  if (state.phase === 'logging_out') return '正在退出登录'
  if (state.phase === 'error') return state.reason === 'logout_failed' ? '退出未完成' : '登录状态暂时不可用'
  if (state.snapshot?.accessDenied) return '当前账号未获授权'
  return state.reason === 'expired' ? '登录已过期' : '登录后继续'
})
const gateMessage = computed(() => {
  if (state.phase === 'error') return state.error
  if (state.phase === 'loading') return '请稍候。'
  if (state.phase === 'logging_out') return '当前页面已停止读取账号数据。'
  if (state.snapshot?.accessDenied) return '请联系管理员配置访问权限，或切换账号。'
  if (state.reason === 'expired') return '请重新登录后继续。'
  return '使用企业账号访问会话和工作台。'
})
watch(() => state.snapshot?.authenticated, authenticated => {
  if (authenticated) {
    const path = consumeLoginReturnPath()
    if (path && path !== route.path) router.replace(path)
  }
})
initializeAuthSession()
</script>
