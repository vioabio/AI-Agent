import { mergeConfig } from 'vite'
import base from './vite.config.js'
import { liveWorkflowTargets } from './scripts/live-workflow-target.mjs'
const target = liveWorkflowTargets()
export default mergeConfig(base, {
  server: { host: target.frontendHost, port: Number(target.frontendPort), strictPort: true,
    proxy: { '/api': { target: target.apiOrigin, changeOrigin: false } } }
})
