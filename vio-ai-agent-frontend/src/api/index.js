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
function connectSSE(url, params, onMessage, onError, onAuthError) {
  const queryString = Object.entries(params)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&')
  const fullUrl = `${API_BASE_URL}${url}?${queryString}`

  // 注入 API Key（从 localStorage 读取，支持认证）
  const apiKey = localStorage.getItem('vio-api-key')
  // SSE 不支持自定义 Header，通过 URL 参数传递
  const urlWithAuth = apiKey
    ? fullUrl + '&_apiKey=' + encodeURIComponent(apiKey)
    : fullUrl

  const eventSource = new EventSource(urlWithAuth)

  eventSource.onmessage = (event) => {
    if (event.data === '[DONE]') {
      eventSource.close()
      return
    }
    onMessage(event.data)
  }

  eventSource.onerror = (error) => {
    eventSource.close()
    // SSE 无法获取 HTTP 状态码，通过 eventSource.readyState 判断
    if (eventSource.readyState === EventSource.CLOSED) {
      if (onAuthError && !apiKey) {
        onAuthError('未配置 API Key，请在设置中配置。')
      } else if (onError) {
        onError(error)
      }
    } else if (onError) {
      onError(error)
    }
  }

  return eventSource
}

/**
 * 设置 API Key（存储到 localStorage）
 */
export function setApiKey(key) {
  localStorage.setItem('vio-api-key', key)
}

/**
 * 获取 API Key
 */
export function getApiKey() {
  return localStorage.getItem('vio-api-key') || ''
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