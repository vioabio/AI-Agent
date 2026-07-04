/**
 * API 层 — 前后端通信桥梁
 *
 * 开发环境：前端 localhost:3000 → CORS → 后端 localhost:8123/api
 * 生产环境：前端 /api → Nginx 反向代理 → 后端服务
 */

const API_BASE_URL = import.meta.env.MODE === 'production'
  ? '/api'
  : 'http://localhost:8123/api'

/**
 * 通用 SSE 连接函数
 *
 * @param {string} url    - API 路径（如 /ai/love_app/chat/sse）
 * @param {object} params - URL 查询参数
 * @param {function} onMessage - 收到消息回调 (data: string) => void
 * @param {function} onError   - 出错回调 (error: Event) => void
 * @returns {EventSource} 可手动关闭连接
 */
function connectSSE(url, params, onMessage, onError) {
  const queryString = Object.entries(params)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&')
  const fullUrl = `${API_BASE_URL}${url}?${queryString}`

  const eventSource = new EventSource(fullUrl)

  eventSource.onmessage = (event) => {
    if (event.data === '[DONE]') {
      eventSource.close()
      return
    }
    onMessage(event.data)
  }

  eventSource.onerror = (error) => {
    eventSource.close()
    if (onError) onError(error)
  }

  return eventSource
}

/**
 * 连接 AI 恋爱大师（SSE 流式对话）
 *
 * @param {string} message - 用户消息
 * @param {string} chatId  - 会话 ID
 * @param {function} onMessage - 收到消息回调
 * @param {function} onError   - 出错回调
 * @returns {EventSource}
 */
export function chatWithLoveApp(message, chatId, onMessage, onError) {
  return connectSSE(
    '/ai/love_app/chat/sse',
    { message, chatId },
    onMessage,
    onError
  )
}

/**
 * 连接 AI 超级智能体 VioManus（SSE 分步输出）
 *
 * @param {string} message - 用户任务
 * @param {function} onMessage - 收到消息回调
 * @param {function} onError   - 出错回调
 * @returns {EventSource}
 */
export function chatWithManus(message, onMessage, onError) {
  return connectSSE(
    '/ai/manus/chat',
    { message },
    onMessage,
    onError
  )
}

export { connectSSE, API_BASE_URL }