import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.N5_E2E_BASE_URL || 'http://127.0.0.1:3000'
const jsonOutput = process.env.N5_E2E_JSON_REPORT || 'artifacts/v1.1/test/n5-frontend-e2e-results.json'
const target = String(process.env.N5_E2E_TARGET || 'local').trim().toLowerCase()
const externalTarget = target === 'staging'
const parsedBaseURL = new URL(baseURL)
const localHostname = parsedBaseURL.hostname.replace(/^\[|\]$/g, '').toLowerCase()
const localHosts = new Set(['localhost', '127.0.0.1', '::1'])

if (!externalTarget && (parsedBaseURL.protocol !== 'http:' || !localHosts.has(localHostname))) {
  throw new Error('Local Playwright runs require an HTTP loopback N5_E2E_BASE_URL')
}

const localPort = parsedBaseURL.port || '80'
const viteHost = localHostname === '::1' ? '::1' : localHostname
const localServer = {
  // Launch Vite directly instead of through npm.  On Windows the npm.cmd
  // wrapper can retain a child process after Playwright finishes, leaving the
  // quality gate hung even though every test has reported a result.
  command: `node ./node_modules/vite/bin/vite.js --host ${viteHost} --port ${localPort} --strictPort`,
  url: parsedBaseURL.origin,
  // CI owns the server by default.  A constrained local validator may start
  // the loopback server itself and opt into reuse so Windows process-tree
  // limitations do not strand Playwright's captured output pipe.
  reuseExistingServer: process.env.N5_E2E_REUSE_EXISTING_SERVER === 'true' || !process.env.CI,
  timeout: 30_000
}

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [
    ['list'],
    ['json', { outputFile: jsonOutput }]
  ],
  use: {
    baseURL,
    ...devices['Desktop Chrome'],
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    serviceWorkers: 'block'
  },
  // Local runs start Vite on the exact loopback host/port selected by the
  // wrapper. Staging runs must pass an explicit base URL and never start a
  // local server.
  webServer: externalTarget ? undefined : localServer
})
