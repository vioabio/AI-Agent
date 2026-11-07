/**
 * Post-process the openapi-typescript-codegen output.
 *
 * Fixes applied:
 * 1. BASE URL — uses import.meta.env.MODE so dev and prod both work without manual edits.
 * 2. Adds a warning header so devs know the file is generated and shouldn't be hand-edited.
 */

import { readFileSync, writeFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const OPENAPI_TS = resolve(__dirname, '..', 'src', 'api', 'generated', 'core', 'OpenAPI.ts')

const oldBaseLine = `BASE: 'http://localhost:8123/api',`
const newBase = `BASE: (typeof import.meta !== 'undefined' && import.meta.env?.MODE === 'production')
    ? '/api'
    : 'http://localhost:8123/api',`

const headerComment = `/* ────────────────────────────────────────────────────────────
   GENERATED FILE — run "npm run openapi" to regenerate.
   Hand-written SSE wrappers live in: src/api/index.js
   ──────────────────────────────────────────────────────────── */`

let content = readFileSync(OPENAPI_TS, 'utf-8')

// Replace hardcoded BASE URL with env-aware version
if (content.includes(oldBaseLine)) {
  content = content.replace(oldBaseLine, newBase)
  console.log('✅  Patched BASE URL → env-aware (dev/prod auto-switch)')
} else {
  console.warn('⚠️  BASE URL pattern not found — may need manual update')
}

// Prepend header comment if not already there
if (!content.includes('GENERATED FILE')) {
  content = headerComment + '\n\n' + content
  console.log('✅  Added generated-file header comment')
}

writeFileSync(OPENAPI_TS, content, 'utf-8')
console.log('✅  Post-processing complete')