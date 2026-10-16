import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 3000,
    // Vite 只服务本地静态资源，不需要对任意站点开放跨域。
    // 后端 API 的跨域策略由 CorsConfig 的显式 allowlist 负责。
    cors: false,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8123', changeOrigin: false }
    }
  }
})
