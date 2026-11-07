import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const apiPath = path.resolve(scriptDir, '../src/api/index.js')
const vitePath = path.resolve(scriptDir, '../vite.config.js')
const source = fs.readFileSync(apiPath, 'utf8')
const viteSource = fs.readFileSync(vitePath, 'utf8')
const failures = []

// 这里只检查会产生真实存储/传输行为的代码形态；文档注释可以说明禁止项。
if (/\blocalStorage\s*\./.test(source)) failures.push('API 层不得使用 localStorage')
if (/\bsessionStorage\s*\./.test(source)) failures.push('API 层不得使用 sessionStorage')
if (/[?&][^\n]{0,80}(?:api[_-]?key|apikey)\s*=/i.test(source)) {
  failures.push('API Key 不得拼接到 URL 查询参数')
}
if (/VITE_API_KEY/i.test(source)) failures.push('不得从 VITE_API_KEY 构建前端密钥')
if (/\bcors\s*:\s*true\b/.test(viteSource)) failures.push('Vite 开发服务器不得对任意站点开启 CORS')

const requiredSnippets = [
  ["credentials: 'include'", '现代请求必须携带同源 credentials'],
  ["'X-CSRF-TOKEN'", '写请求必须支持 CSRF Header'],
  ['const API_KEY_HEADER = \'X-API-Key\'', 'API Key 只能使用明确的 Header 名称'],
  ['headers[API_KEY_HEADER] = transientApiKey', '临时 API Key 必须注入 Header'],
  ['EventSource(fullUrl, { withCredentials: true })', '兼容 SSE 必须携带 Cookie']
]
for (const [snippet, message] of requiredSnippets) {
  if (!source.includes(snippet)) failures.push(message)
}

if (failures.length > 0) {
  console.error('Security contract FAILED')
  failures.forEach(failure => console.error(`- ${failure}`))
  process.exitCode = 1
} else {
  console.log('Security contract passed: no API key in browser storage/URL; Cookie/CSRF/Header paths present.')
}
