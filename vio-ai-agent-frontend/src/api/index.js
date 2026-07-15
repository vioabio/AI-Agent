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
 * @param {string} url    - API 路径（如 /ai/game_app/chat/sse）
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
 * 连接 AI 宝可梦大师（SSE 流式对话）
 *
 * @param {string} message - 用户消息
 * @param {string} chatId  - 会话 ID
 * @param {function} onMessage - 收到消息回调
 * @param {function} onError   - 出错回调
 * @returns {EventSource}
 */
export function chatWithGameApp(message, chatId, onMessage, onError) {
  return connectSSE(
    '/ai/game_app/chat/sse_emitter',
    { message, chatId },
    onMessage,
    onError
  )
}

/**
 * 连接 AI 超级智能体 VioManus（SSE 分步输出）
 *
 * @param {string} message - 用户任务
 * @param {function} onMessage - 收到消息回调 (data: string) => void
 * @param {function} onSession - 收到 sessionId 回调 (sessionId: string) => void
 * @param {function} onError   - 出错回调
 * @returns {EventSource}
 */
export function chatWithManus(message, onMessage, onSession, onError) {
  let sessionReceived = false
  return connectSSE(
    '/ai/manus/chat',
    { message },
    (data) => {
      // 拦截第一条消息提取 sessionId
      if (!sessionReceived && data.startsWith('[SESSION:')) {
        sessionReceived = true
        const sessionId = data.match(/\[SESSION:(.+?)\]/)?.[1]
        if (sessionId && onSession) onSession(sessionId)
        return  // 不显示 sessionId 给用户
      }
      onMessage(data)
    },
    onError
  )
}

/**
 * 手动停止 Agent
 * @param {string} sessionId
 * @returns {Promise<Response>}
 */
export function stopManus(sessionId) {
  return fetch(`${API_BASE_URL}/ai/manus/stop?sessionId=${encodeURIComponent(sessionId)}`)
}

export { connectSSE, API_BASE_URL }