import { defineConfig, devices } from '@playwright/test'
import path from 'node:path'
import fs from 'node:fs'
import { liveWorkflowTargets } from './scripts/live-workflow-target.mjs'
const target = liveWorkflowTargets(), output = process.env.VIO_WORKFLOW_LIVE_OUTPUT
if (!output || fs.existsSync(path.join(output, 'playwright.json'))) throw new Error('Choose a new VIO_WORKFLOW_LIVE_OUTPUT directory')
export default defineConfig({
  testDir: './e2e-live', timeout: 180000, expect: { timeout: 15000 }, workers: 1, retries: 0, fullyParallel: false,
  outputDir: path.join(output, 'browser'),
  reporter: [['list'], ['json', { outputFile: path.join(output, 'playwright.json') }]],
  use: { ...devices['Desktop Chrome'], baseURL: target.frontendOrigin,
    channel: process.env.N5_E2E_BROWSER_CHANNEL || undefined,
    // Real credentials remain in memory: no HAR, tracing, video or failure screenshots of login.
    trace: 'off', video: 'off', screenshot: 'off', serviceWorkers: 'block' },
  webServer: { command: `node ./node_modules/vite/bin/vite.js --config vite.workflow-live.config.js --host ${target.frontendHost} --port ${target.frontendPort} --strictPort`,
    url: target.frontendOrigin, reuseExistingServer: false, timeout: 30000 }
})
