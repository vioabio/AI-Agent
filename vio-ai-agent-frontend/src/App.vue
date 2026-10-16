<template>
  <div class="app-shell">
    <header v-if="canUseApplication" class="session-toolbar" data-testid="session-toolbar">
      <RouterLink to="/" class="session-brand">VIO AI Agent</RouterLink>
      <div v-if="state.snapshot.mode === 'oidc'" class="session-account">
        <span class="session-principal">{{ state.snapshot.principalId }}</span>
        <span class="session-tenant">{{ state.snapshot.tenantId }}</span>
        <button type="button" @click="signOut">退出登录</button>
      </div>
      <span v-else class="session-tenant">{{ state.snapshot.mode === 'api_key' ? '受限演示' : '开发模式' }}</span>
    </header>
    <router-view v-if="canUseApplication" :key="state.viewVersion" />
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

<style>
/* ===== 全局 CSS 重置 ===== */
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body, #app {
  height: 100%;
  width: 100%;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
    'Helvetica Neue', Arial, 'Noto Sans SC', sans-serif;
  background-color: #0f0f1a;
  color: #e0e0e0;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

/* ===== 自定义滚动条 ===== */
::-webkit-scrollbar {
  width: 6px;
}

::-webkit-scrollbar-track {
  background: transparent;
}

::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.15);
  border-radius: 3px;
}

::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.25);
}
</style>

<style scoped>
.session-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: .8rem 1.2rem; border-bottom: 1px solid #ffffff1a; background: #131525; }
.session-brand, .auth-brand { color: #cb98ff; font-weight: 700; }
.session-account { display: flex; align-items: center; flex-wrap: wrap; gap: .8rem; }
.session-principal { max-width: 16rem; overflow-wrap: anywhere; }
.session-tenant { color: #a5a7bd; font-size: .85rem; }
.auth-gate { min-height: 100vh; display: grid; place-items: center; padding: 1.5rem; background: radial-gradient(circle at top, #282047, #0f0f1a 65%); }
.auth-card { width: min(100%, 28rem); padding: 2rem; border: 1px solid #ffffff20; border-radius: 1rem; background: #171827; }
.auth-card h1 { margin: 1.3rem 0 .8rem; font-size: 1.5rem; }
.auth-card p { line-height: 1.7; color: #c3c4d5; }
button { padding: .65rem 1rem; border-radius: .5rem; color: #fff; background: #6842a8; }
.auth-card button { margin-top: 1.3rem; }
button:focus-visible, a:focus-visible { outline: 2px solid #e3c8ff; outline-offset: 3px; }
@media (max-width: 600px) { .session-toolbar { align-items: flex-start; } .session-account { justify-content: flex-end; } }
</style>
