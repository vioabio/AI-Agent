/**
 * API 层 — 前后端通信桥梁
 *
 * 开发环境：前端 /api → Vite 同源代理 → 后端 localhost:8123/api
 * 生产环境：前端 /api → Nginx 反向代理 → 后端服务
 */

const API_BASE_URL = '/api'

const API_KEY_HEADER = 'X-API-Key'

/**
 * 仅在用户显式调用 setApiKey 时保留本次页面生命周期内的临时令牌。
 *
 * <p>生产浏览器默认不设置该值：推荐由同源 Session/CSRF 或 Nginx/边缘
 * 代理注入 X-API-Key。浏览器内存令牌只能用于本地/演示兼容，不能视为生产
 * 密钥存储，因为页面脚本/XSS 仍可能读取它。</p>
 */
let transientApiKey = ''
let authenticationFailureHandler = null
let authenticationEpoch = 0
const responseEpochs = new WeakMap()

/** Old account requests cannot expire a subsequently confirmed identity. */
export function invalidateAuthenticationRequests() { authenticationEpoch++ }

async function authenticatedFetch(url, options) {
  const epoch = authenticationEpoch
  const response = await globalThis.fetch(url, options)
  responseEpochs.set(response, epoch)
  return response
}

/** Only 401 invalidates the browser session; a permission/CSRF 403 does not. */
export function setAuthenticationFailureHandler(handler) {
  authenticationFailureHandler = typeof handler === 'function' ? handler : null
}

function notifyAuthenticationFailure(status, response) {
  if (Number(status) === 401 && responseEpochs.get(response) === authenticationEpoch) {
    authenticationFailureHandler?.()
  }
}

/** 同源 Cookie 认证；API Key 不写入 URL、localStorage 或浏览器历史。 */
function csrfToken() {
  if (typeof document === 'undefined') return ''
  const cookies = document.cookie.split(';').map(item => item.trim())
  const pair = cookies.find(item => item.startsWith('CSRF-TOKEN='))
    || cookies.find(item => item.startsWith('XSRF-TOKEN='))
  if (!pair) return ''
  try {
    return decodeURIComponent(pair.substring(pair.indexOf('=') + 1))
  } catch {
    // 畸形 Cookie 不应阻塞页面，也不应被当作有效 CSRF 凭据发送。
    return ''
  }
}

function requestHeaders(extra = {}) {
  const token = csrfToken()
  const headers = {
    Accept: 'application/json',
    ...(token ? { 'X-CSRF-TOKEN': token } : {}),
    ...extra
  }
  // 只把显式设置的临时令牌放进 Header；绝不退回到 URL 参数。
  if (transientApiKey && !headers[API_KEY_HEADER]) {
    headers[API_KEY_HEADER] = transientApiKey
  }
  return headers
}

function httpError(response, operation) {
  notifyAuthenticationFailure(response.status, response)
  const error = new Error(`${operation}（HTTP ${response.status}）`)
  error.status = response.status
  error.code = response.status === 401
    ? 'AUTH_REQUIRED'
    : response.status === 403
      ? 'CSRF_ORIGIN_REJECTED'
      : 'HTTP_ERROR'
  return error
}

function ensureOk(response, operation) {
  if (!response.ok) throw httpError(response, operation)
  return response
}

/**
 * 后端部分资源接口使用统一 Result<T> 包装，Run 接口则直接返回 DTO。
 * 资源 API 在这里做一次兼容解包，视图层只处理业务对象，避免把 code/data
 * 判断散落到各个组件中。
 */
function unwrapResult(payload, response) {
  const numericCode = payload && typeof payload === 'object' && !Array.isArray(payload)
    && Object.prototype.hasOwnProperty.call(payload, 'code')
    && ((typeof payload.code === 'number' && Number.isFinite(payload.code))
      || (typeof payload.code === 'string' && payload.code.trim() !== ''
        && Number.isFinite(Number(payload.code))))
    ? Number(payload.code) : NaN
  const hasNumericCode = Number.isInteger(numericCode)
  if (hasNumericCode
      // ResultEnvelope requires code/message/timestamp, while Result<Void>
      // intentionally omits nullable data.  Keep accepting the older minimal
      // `{ code, data }` shape used by local fixtures, but recognize a
      // data-less envelope by its numeric code plus message/timestamp marker.
      && (Object.prototype.hasOwnProperty.call(payload, 'data')
        || Object.prototype.hasOwnProperty.call(payload, 'message')
        || Object.prototype.hasOwnProperty.call(payload, 'timestamp'))) {
    // Resource/Calculator endpoints use the shared Result<T> envelope.  A
    // successful HTTP status alone is not enough: a proxy or legacy endpoint
    // may return an application error with HTTP 200.  Accept the two success
    // conventions used by this project (0 and any 2xx), but fail closed for a
    // numeric non-2xx business code instead of handing null data to the UI.
    if (Number.isFinite(numericCode)
        && numericCode !== 0 && (numericCode < 200 || numericCode >= 300)) {
      const error = new Error(payload.message || `业务请求失败（${payload.code}）`)
      error.code = `API_RESULT_${payload.code}`
      if (numericCode >= 400 && numericCode <= 599) error.status = numericCode
      notifyAuthenticationFailure(error.status, response)
      throw error
    }
    return payload.data
  }
  return payload
}

async function readJson(response, operation) {
  if (!response.ok && response.status === 404) {
    const error = httpError(response, operation)
    try {
      const payload = await response.json()
      if (typeof payload?.message === 'string') error.resourceMissing = /data_release/.test(payload.message)
    } catch { /* A proxy may return HTML; retain the HTTP error. */ }
    throw error
  }
  ensureOk(response, operation)
  if (response.status === 204) return null
  const body = await response.json()
  return unwrapResult(body, response)
}

function queryString(params = {}) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && String(value) !== '') {
      query.set(key, String(value))
    }
  })
  const encoded = query.toString()
  return encoded ? `?${encoded}` : ''
}

function requiredIdentifier(value, label) {
  const normalized = String(value ?? '').trim()
  if (!normalized) throw new Error(`缺少 ${label}`)
  return normalized
}

/**
 * 通用 SSE 连接函数
 *
 * @param {string} url    - API 路径（如 /ai/game_app/chat/sse）
 * @param {object} params - URL 查询参数
 * @param {function} onMessage - 收到消息回调 (data: string) => void
 * @param {function} onError   - 出错回调 (error: Event) => void
 * @returns {EventSource} 可手动关闭连接（旧端点兼容）
 */
function connectSSE(url, params, onMessage, onError) {
  const queryString = Object.entries(params)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&')
  const fullUrl = `${API_BASE_URL}${url}?${queryString}`

  // EventSource 不能设置自定义 Header，因此旧 SSE 只能依赖同源 Cookie/代理。
  // 绝不把 transientApiKey 拼到 URL；需要 API Key 时请使用现代 Run fetch 链路。
  const eventSource = new EventSource(fullUrl, { withCredentials: true })

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
      if (onError) {
        onError(error)
      }
    } else if (onError) {
      onError(error)
    }
  }

  return eventSource
}

/**
 * 设置页面生命周期内的临时 API Key（仅本地/演示兼容）。
 *
 * <p>调用方必须自行保证来源可信；该方法不会写入任何 Web Storage，刷新
 * 页面后令牌立即丢失。生产环境优先使用边缘代理注入或 Session/CSRF。</p>
 */
export function setApiKey(key) {
  transientApiKey = typeof key === 'string' ? key.trim() : ''
}

/** 清除页面内的临时 API Key。 */
export function clearApiKey() {
  transientApiKey = ''
}

/** Public bootstrap never sends a page's temporary API key or bearer token. */
export async function fetchAuthSession({ signal } = {}) {
  const response = await authenticatedFetch(`${API_BASE_URL}/auth/session`, {
    credentials: 'include', cache: 'no-store', signal,
    headers: { Accept: 'application/json' }
  })
  return readJson(response, '读取登录状态失败')
}

/** Local account forms never store passwords or recovery codes in browser storage. */
export async function localAccountRequest(action, body, method = 'POST') {
  const response = await authenticatedFetch(`${API_BASE_URL}/auth/local/${action}`, {
    method, credentials: 'include', cache: 'no-store',
    headers: requestHeaders(body ? { 'Content-Type': 'application/json' } : {}),
    ...(body ? { body: JSON.stringify(body) } : {})
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok) {
    if (!['login', 'register', 'recover'].includes(action)) notifyAuthenticationFailure(response.status, response)
    const error = new Error(payload?.message || `请求失败（HTTP ${response.status}），请重试`)
    error.status = response.status
    throw error
  }
  return payload
}

/** Refresh CSRF before logout, and only accept the documented no-content success. */
export async function logoutSession({ signal } = {}) {
  const session = await fetchAuthSession({ signal })
  if (!['oidc', 'local'].includes(session?.mode) || !session.csrf?.enabled || !session.csrf?.available || !csrfToken()) {
    throw new Error('缺少可用的退出校验，请重试')
  }
  const response = await authenticatedFetch(`${API_BASE_URL}/logout`, {
    method: 'POST', credentials: 'include', signal,
    headers: { Accept: 'application/json', 'X-CSRF-TOKEN': csrfToken() }
  })
  ensureOk(response, '退出登录失败')
  if (response.status !== 204) throw new Error('退出登录响应不符合契约')
  clearApiKey()
}

/** 是否已显式设置临时 API Key（不返回密钥本身）。 */
export function hasApiKey() {
  return Boolean(transientApiKey)
}

/**
 * 兼容旧调用方的读取接口；不建议在业务代码中使用。
 */
export function getApiKey() {
  return transientApiKey
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
  if (!sessionId || !String(sessionId).trim()) return Promise.reject(new Error('缺少 sessionId'))
  return authenticatedFetch(`${API_BASE_URL}/ai/manus/stop?sessionId=${encodeURIComponent(sessionId)}`, {
    credentials: 'include',
    headers: requestHeaders()
  }).then(response => ensureOk(response, '停止旧版 Agent 失败'))
}

/**
 * v1.0：创建独立 Agent Run。后端返回 202 + {runId, eventsUrl}。
 */
export async function createRun(payload, options = {}) {
  const idempotencyKey = options.idempotencyKey || payload?.idempotencyKey
  const response = await authenticatedFetch(`${API_BASE_URL}/chat/runs`, {
    method: 'POST',
    credentials: 'include',
    signal: options.signal,
    headers: requestHeaders({
      'Content-Type': 'application/json',
      ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {})
    }),
    body: JSON.stringify(payload)
  })
  return ensureOk(response, '创建 Run 失败').json()
}

/** 查询版本化宝可梦事实。返回 FactQueryResult（或兼容的数组）。 */
export async function fetchPokemonFacts({
  dataReleaseId,
  slug,
  nationalNo,
  limit = 50,
  signal
} = {}) {
  if (!dataReleaseId || !String(dataReleaseId).trim()) {
    throw new Error('缺少 dataReleaseId，无法查询版本化事实')
  }
  const response = await authenticatedFetch(`${API_BASE_URL}/pokemon/facts${queryString({
    dataReleaseId: String(dataReleaseId).trim(),
    slug: slug?.trim?.() || undefined,
    nationalNo,
    limit
  })}`, {
    credentials: 'include',
    signal,
    headers: requestHeaders()
  })
  return readJson(response, '读取宝可梦事实失败')
}

/** 按 slug 查询单条版本化事实。 */
export async function fetchPokemonFact(slug, { dataReleaseId, signal } = {}) {
  if (!slug || !String(slug).trim()) throw new Error('缺少宝可梦 slug')
  if (!dataReleaseId || !String(dataReleaseId).trim()) throw new Error('缺少 dataReleaseId')
  const response = await authenticatedFetch(`${API_BASE_URL}/pokemon/facts/${encodeURIComponent(String(slug).trim())}${queryString({ dataReleaseId })}`, {
    credentials: 'include',
    signal,
    headers: requestHeaders()
  })
  return readJson(response, '读取宝可梦事实失败')
}

/**
 * 调用后端确定性属性克制计算器。
 *
 * <p>计算结果必须携带 dataReleaseId/calculationVersion；浏览器只负责
 * 组装显式输入和展示服务端结果，不在前端复制属性表或自行计算倍率。</p>
 */
export async function calculateTypeEffectiveness({
  dataReleaseId,
  attackType,
  defendTypes,
  signal
} = {}) {
  const release = normalizeCalculationRelease(dataReleaseId)
  const attack = String(attackType ?? '').trim()
  const defenses = Array.isArray(defendTypes)
    ? defendTypes.map(value => String(value ?? '').trim()).filter(Boolean)
    : []
  if (!attack) throw new Error('缺少攻击属性')
  if (defenses.length < 1 || defenses.length > 2) throw new Error('防御属性必须为 1–2 个')
  const response = await authenticatedFetch(`${API_BASE_URL}/pokemon/calculations/type-effectiveness`, {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: requestHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ dataReleaseId: release, attackType: attack, defendTypes: defenses })
  })
  return readJson(response, '属性克制计算失败')
}

function normalizeCalculationRelease(dataReleaseId) {
  const release = String(dataReleaseId ?? '').trim()
  if (!release) throw new Error('缺少 dataReleaseId，无法执行版本化计算')
  return release
}

function normalizeCalculationInteger(value, label, minimum, maximum) {
  const number = Number(value)
  if (!Number.isInteger(number) || number < minimum || (maximum !== undefined && number > maximum)) {
    const range = maximum === undefined ? `>= ${minimum}` : `${minimum}–${maximum}`
    throw new Error(`${label}必须为整数且范围为 ${range}`)
  }
  return number
}

function normalizeCalculationNumber(value, label, minimum, exclusiveMinimum = false) {
  const number = Number(value)
  const belowMinimum = exclusiveMinimum ? number <= minimum : number < minimum
  if (!Number.isFinite(number) || belowMinimum) {
    const qualifier = exclusiveMinimum ? '大于' : '不小于'
    throw new Error(`${label}必须为${qualifier} ${minimum} 的数字`)
  }
  return number
}

/** 调用后端确定性速度线计算器；结果由 readJson 统一解包 Result<T>。 */
export async function calculateSpeed({
  dataReleaseId,
  base,
  level,
  iv,
  ev,
  natureMultiplier = 1,
  signal
} = {}) {
  const release = normalizeCalculationRelease(dataReleaseId)
  const normalizedBase = normalizeCalculationInteger(base, '基础速度', 1)
  const normalizedLevel = normalizeCalculationInteger(level, '等级', 1, 100)
  const normalizedIv = normalizeCalculationInteger(iv, '个体值', 0, 31)
  const normalizedEv = normalizeCalculationInteger(ev, '努力值', 0, 252)
  const normalizedNature = normalizeCalculationNumber(natureMultiplier, '性格倍率', 0, true)
  const response = await authenticatedFetch(`${API_BASE_URL}/pokemon/calculations/speed`, {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: requestHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({
      dataReleaseId: release,
      base: normalizedBase,
      level: normalizedLevel,
      iv: normalizedIv,
      ev: normalizedEv,
      natureMultiplier: normalizedNature
    })
  })
  return readJson(response, '速度线计算失败')
}

/** 调用后端确定性基础伤害区间计算器。modifier 必须显式表达外部规则修正。 */
export async function calculateDamage({
  dataReleaseId,
  level,
  power,
  attack,
  defense,
  modifier = 1,
  signal
} = {}) {
  const release = normalizeCalculationRelease(dataReleaseId)
  const normalizedLevel = normalizeCalculationInteger(level, '等级', 1, 100)
  const normalizedPower = normalizeCalculationInteger(power, '招式威力', 1)
  const normalizedAttack = normalizeCalculationInteger(attack, '攻击值', 1)
  const normalizedDefense = normalizeCalculationInteger(defense, '防御值', 1)
  const normalizedModifier = normalizeCalculationNumber(modifier, '修正倍率', 0)
  const response = await authenticatedFetch(`${API_BASE_URL}/pokemon/calculations/damage`, {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: requestHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({
      dataReleaseId: release,
      level: normalizedLevel,
      power: normalizedPower,
      attack: normalizedAttack,
      defense: normalizedDefense,
      modifier: normalizedModifier
    })
  })
  return readJson(response, '伤害计算失败')
}

/** 调用后端版本化队伍合法性检查器，不在浏览器复制规则。 */
export async function checkTeamLegality({
  dataReleaseId,
  rulesetVersionId,
  members,
  signal
} = {}) {
  const release = normalizeCalculationRelease(dataReleaseId)
  const ruleset = String(rulesetVersionId ?? '').trim()
  if (!ruleset) throw new Error('缺少 rulesetVersionId，无法校验队伍')
  if (!Array.isArray(members) || members.length < 1 || members.length > 6) {
    throw new Error('队伍成员必须为 1–6 名')
  }
  const normalizedMembers = members.map((member, index) => {
    if (!member || typeof member !== 'object' || Array.isArray(member)) {
      throw new Error(`第 ${index + 1} 名队伍成员格式非法`)
    }
    const formKey = String(member.formKey ?? '').trim()
    if (!formKey) throw new Error(`第 ${index + 1} 名队伍成员缺少 formKey`)
    if (!Array.isArray(member.moves) || member.moves.length > 4) {
      throw new Error(`第 ${index + 1} 名队伍成员招式必须为 0–4 个`)
    }
    const moves = member.moves.map((move, moveIndex) => {
      const normalizedMove = String(move ?? '').trim()
      if (!normalizedMove) throw new Error(`第 ${index + 1} 名队伍成员的第 ${moveIndex + 1} 个招式为空`)
      return normalizedMove
    })
    return { formKey, moves }
  })
  const response = await authenticatedFetch(`${API_BASE_URL}/pokemon/calculations/legality`, {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: requestHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({
      dataReleaseId: release,
      rulesetVersionId: ruleset,
      members: normalizedMembers
    })
  })
  return readJson(response, '队伍合法性校验失败')
}

/** 查询当前认证主体的队伍；后端可能返回数组或 ResourcePage。 */
export async function fetchPokemonTeams({ limit = 50, cursor, signal } = {}) {
  // /teams 是 v1.1 的规范资源入口，提供 ResourcePage/cursor 和主体隔离；
  // /pokemon/teams 仅保留给旧版兼容调用，不应成为工作台的默认路径。
  const response = await authenticatedFetch(`${API_BASE_URL}/teams${queryString({ limit, cursor })}`, {
    credentials: 'include',
    signal,
    headers: requestHeaders()
  })
  return readJson(response, '读取队伍失败')
}

/** 创建版本化队伍；幂等键由调用方提供时透传到 Header。 */
export async function createPokemonTeam(payload, { idempotencyKey, signal } = {}) {
  if (!payload || typeof payload !== 'object') throw new Error('队伍请求不能为空')
  const response = await authenticatedFetch(`${API_BASE_URL}/teams`, {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: requestHeaders({
      'Content-Type': 'application/json',
      ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {})
    }),
    body: JSON.stringify(payload)
  })
  return readJson(response, '创建队伍失败')
}

/** 修改当前认证主体的版本化队伍；幂等键用于安全重试。 */
export async function updatePokemonTeam(teamId, payload, { idempotencyKey, signal } = {}) {
  if (!teamId) throw new Error('缺少 teamId')
  if (!payload || typeof payload !== 'object') throw new Error('队伍请求不能为空')
  const response = await authenticatedFetch(`${API_BASE_URL}/teams/${encodeURIComponent(teamId)}`, {
    method: 'PATCH',
    credentials: 'include',
    signal,
    headers: requestHeaders({
      'Content-Type': 'application/json',
      ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {})
    }),
    body: JSON.stringify(payload)
  })
  return readJson(response, '修改队伍失败')
}

/** 查询单个队伍。 */
export async function fetchPokemonFactByFormId(formId, { dataReleaseId, signal } = {}) {
  if (!Number.isSafeInteger(Number(formId)) || Number(formId) <= 0 || !dataReleaseId) {
    throw new Error('缺少有效的形态 ID 或数据版本')
  }
  const response = await authenticatedFetch(`${API_BASE_URL}/pokemon/facts/forms/${Number(formId)}${queryString({ dataReleaseId })}`, {
    credentials: 'include', signal, headers: requestHeaders()
  })
  return readJson(response, '读取队伍成员资料失败')
}

export async function fetchPokemonReferenceStatus({ signal } = {}) {
  return readJson(await authenticatedFetch(`${API_BASE_URL}/pokemon/reference/status`, {
    credentials: 'include', signal, headers: requestHeaders()
  }), '读取参考资料配置失败')
}

export async function fetchPokemonReference(query, { dataReleaseId, signal } = {}) {
  return readJson(await authenticatedFetch(`${API_BASE_URL}/pokemon/reference/pokemon/${encodeURIComponent(query)}${queryString({ dataReleaseId })}`, {
    credentials: 'include', signal, headers: requestHeaders()
  }), '读取宝可梦详细资料失败')
}

export async function calculateEngineDamage(payload, { signal } = {}) {
  return readJson(await authenticatedFetch(`${API_BASE_URL}/pokemon/calculations/engine-damage`, {
    method:'POST',credentials:'include',signal,headers:requestHeaders({'Content-Type':'application/json'}),body:JSON.stringify(payload)
  }), '本地伤害计算失败')
}

export async function checkPokemonBuild(payload, { signal } = {}) {
  return readJson(await authenticatedFetch(`${API_BASE_URL}/pokemon/reference/build-check`, {
    method:'POST',credentials:'include',signal,headers:requestHeaders({'Content-Type':'application/json'}),body:JSON.stringify(payload)
  }), '配招核对失败')
}

export async function checkPokemonTeamBuild(payload, {signal} = {}) {
  return readJson(await authenticatedFetch(`${API_BASE_URL}/pokemon/reference/team-build-check`, {
    method:'POST',credentials:'include',signal,headers:requestHeaders({'Content-Type':'application/json'}),body:JSON.stringify(payload)
  }), '整队配置核对失败')
}

export async function fetchPokemonMachine(learning, {signal} = {}) {
  return readJson(await authenticatedFetch(`${API_BASE_URL}/pokemon/reference/machines/${encodeURIComponent(learning.pokemonId)}/${encodeURIComponent(learning.moveSlug)}${queryString({dataReleaseId:learning.dataReleaseId,gameReleaseId:learning.gameReleaseId})}`, {
    credentials:'include',signal,headers:requestHeaders()
  }), '学习器编号读取失败')
}

export async function fetchMachineCraftingGuide(gameReleaseId, {signal} = {}) {
  return readJson(await authenticatedFetch(`${API_BASE_URL}/pokemon/reference/machines/crafting-guide${queryString({gameReleaseId})}`, {
    credentials:'include',signal,headers:requestHeaders()
  }), '学习器制作说明读取失败')
}

export async function fetchMachineRecipe(moveSlug, gameReleaseId, {signal} = {}) {
  return readJson(await authenticatedFetch(`${API_BASE_URL}/pokemon/reference/machines/recipes/${encodeURIComponent(moveSlug)}${queryString({gameReleaseId})}`, {
    credentials:'include',signal,headers:requestHeaders()
  }), '学习器配方参考读取失败')
}

export async function fetchMachineAcquisition(moveSlug, gameReleaseId, {signal} = {}) {
  return readJson(await authenticatedFetch(`${API_BASE_URL}/pokemon/reference/machines/acquisition/${encodeURIComponent(moveSlug)}${queryString({gameReleaseId})}`, {
    credentials:'include',signal,headers:requestHeaders()
  }), '学习器获取区域参考读取失败')
}

export async function searchPokemonGuides(query, gameReleaseId, { signal } = {}) {
  return readJson(await authenticatedFetch(`${API_BASE_URL}/pokemon/guides${queryString({query,gameReleaseId})}`, {
    credentials:'include',signal,headers:requestHeaders()
  }), '本地攻略检索失败')
}

export async function fetchPokemonTeam(teamId, { signal } = {}) {
  if (!teamId) throw new Error('缺少 teamId')
  const response = await authenticatedFetch(`${API_BASE_URL}/teams/${encodeURIComponent(teamId)}`, {
    credentials: 'include',
    signal,
    headers: requestHeaders()
  })
  return readJson(response, '读取队伍失败')
}

/** 删除当前认证主体的队伍。 */
export async function deletePokemonTeam(teamId, { signal } = {}) {
  if (!teamId) throw new Error('缺少 teamId')
  const response = await authenticatedFetch(`${API_BASE_URL}/teams/${encodeURIComponent(teamId)}`, {
    method: 'DELETE',
    credentials: 'include',
    signal,
    headers: requestHeaders()
  })
  return readJson(response, '删除队伍失败')
}

/** 查询当前主体收藏；后端可能返回数组或 ResourcePage。 */
export async function fetchFavorites({ limit = 50, cursor, signal } = {}) {
  const response = await authenticatedFetch(`${API_BASE_URL}/favorites${queryString({ limit, cursor })}`, {
    credentials: 'include',
    signal,
    headers: requestHeaders()
  })
  return readJson(response, '读取收藏失败')
}

/** 创建收藏；resourceType/resourceKey 由服务端校验并作为不可变业务身份。 */
export async function createFavorite(payload, { idempotencyKey, signal } = {}) {
  if (!payload || typeof payload !== 'object') throw new Error('收藏请求不能为空')
  const response = await authenticatedFetch(`${API_BASE_URL}/favorites`, {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: requestHeaders({
      'Content-Type': 'application/json',
      ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {})
    }),
    body: JSON.stringify(payload)
  })
  return readJson(response, '创建收藏失败')
}

/** 修改收藏展示字段；resourceType/resourceKey 不允许通过 PATCH 改写。 */
export async function updateFavorite(favoriteId, payload, { signal } = {}) {
  if (!favoriteId) throw new Error('缺少 favoriteId')
  if (!payload || typeof payload !== 'object') throw new Error('收藏更新请求不能为空')
  const response = await authenticatedFetch(`${API_BASE_URL}/favorites/${encodeURIComponent(favoriteId)}`, {
    method: 'PATCH',
    credentials: 'include',
    signal,
    headers: requestHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(payload)
  })
  return readJson(response, '修改收藏失败')
}

/** 删除当前认证主体的收藏。 */
export async function deleteFavorite(favoriteId, { signal } = {}) {
  if (!favoriteId) throw new Error('缺少 favoriteId')
  const response = await authenticatedFetch(`${API_BASE_URL}/favorites/${encodeURIComponent(favoriteId)}`, {
    method: 'DELETE',
    credentials: 'include',
    signal,
    headers: requestHeaders()
  })
  return readJson(response, '删除收藏失败')
}

/** 查询带来源、有效期和 freshness 门禁的活动资讯。 */
export async function fetchPokemonActivities({
  gameReleaseId,
  dlcId,
  language,
  region,
  limit = 50,
  signal
} = {}) {
  if (!gameReleaseId || !String(gameReleaseId).trim()) throw new Error('缺少 gameReleaseId')
  if (!language || !String(language).trim()) throw new Error('缺少 language')
  const response = await authenticatedFetch(`${API_BASE_URL}/pokemon/activities${queryString({
    gameReleaseId: String(gameReleaseId).trim(), dlcId, language: String(language).trim(), region, limit
  })}`, {
    credentials: 'include',
    signal,
    headers: requestHeaders()
  })
  return readJson(response, '读取活动资讯失败')
}

/** 查询单条活动资讯；过期/未审核内容由后端以 404/410 fail-closed。 */
export async function fetchPokemonActivity(noticeId, { signal } = {}) {
  if (!noticeId) throw new Error('缺少 noticeId')
  const response = await authenticatedFetch(`${API_BASE_URL}/pokemon/activities/${encodeURIComponent(noticeId)}`, {
    credentials: 'include',
    signal,
    headers: requestHeaders()
  })
  return readJson(response, '读取活动资讯失败')
}

/** 查询当前认证主体的会话列表；兼容 ResourcePage 和数组响应。 */
export async function fetchConversations({ limit = 50, cursor, signal } = {}) {
  const response = await authenticatedFetch(`${API_BASE_URL}/conversations${queryString({ limit, cursor })}`, {
    credentials: 'include',
    signal,
    headers: requestHeaders()
  })
  return readJson(response, '读取会话历史失败')
}

/** 读取当前账号显式保存的会话偏好。 */
export async function fetchConversationConstraints(conversationId, { signal } = {}) {
  const id = requiredIdentifier(conversationId, 'conversationId')
  const response = await authenticatedFetch(`${API_BASE_URL}/chat/conversations/${encodeURIComponent(id)}/constraints`, {
    credentials: 'include', signal, headers: requestHeaders()
  })
  return readJson(response, '读取对话偏好失败')
}

export async function saveConversationConstraints(conversationId, payload, { signal } = {}) {
  const id = requiredIdentifier(conversationId, 'conversationId')
  const response = await authenticatedFetch(`${API_BASE_URL}/chat/conversations/${encodeURIComponent(id)}/constraints`, {
    method: 'PUT', credentials: 'include', signal, headers: requestHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(payload)
  })
  return readJson(response, '保存对话偏好失败')
}

/** 查询会话完整消息；该接口返回 raw ChatMessage[]，不伪造历史内容。 */
export async function fetchConversationMessages(conversationId, { signal } = {}) {
  if (!conversationId) throw new Error('缺少 conversationId')
  const response = await authenticatedFetch(`${API_BASE_URL}/chat/conversations/${encodeURIComponent(conversationId)}/messages`, {
    credentials: 'include',
    signal,
    headers: requestHeaders()
  })
  return readJson(response, '读取会话消息失败')
}

/** 获取服务端 Run 快照；用于终态事件缺失/事件页为空时确认真实状态。 */
export async function fetchRunSnapshot(runId, options = {}) {
  const id = requiredIdentifier(runId, 'runId')
  const response = await authenticatedFetch(`${API_BASE_URL}/chat/runs/${encodeURIComponent(id)}`, {
    credentials: 'include',
    signal: options.signal,
    headers: requestHeaders()
  })
  return ensureOk(response, '读取 Run 快照失败').json()
}

/** 获取持久化事件页，支持用 lastEventId 断线恢复。 */
export async function fetchRunEvents(runId, lastEventId = null, options = {}) {
  const id = requiredIdentifier(runId, 'runId')
  // 0 is a valid protocol cursor (the stream's initial position); test for
  // presence rather than truthiness so an explicit zero is not silently lost.
  const hasCursor = lastEventId !== null && lastEventId !== undefined
    && String(lastEventId) !== ''
  const params = hasCursor ? `?afterEventId=${encodeURIComponent(lastEventId)}` : ''
  // OpenAPI/后端同时支持 query 和 Last-Event-ID；发送同一游标到两处，
  // 兼容仅转发 SSE 头的网关，并让服务端的“Header 优先”规则保持一致。
  const cursorHeader = hasCursor ? { 'Last-Event-ID': String(lastEventId) } : {}
  const response = await authenticatedFetch(`${API_BASE_URL}/chat/runs/${encodeURIComponent(id)}/events${params}`, {
    credentials: 'include',
    signal: options.signal,
    headers: requestHeaders(cursorHeader)
  })
  return ensureOk(response, '读取 Run 事件失败').json()
}

/** 显式取消 Run；网络断开不会自动调用此接口。 */
export async function cancelRun(runId, reason = 'user_cancelled', options = {}) {
  const id = requiredIdentifier(runId, 'runId')
  const idempotencyKey = options?.idempotencyKey
  const response = await authenticatedFetch(`${API_BASE_URL}/chat/runs/${encodeURIComponent(id)}/cancel`, {
    method: 'POST',
    credentials: 'include',
    signal: options?.signal,
    headers: requestHeaders({
      'Content-Type': 'application/json',
      ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {})
    }),
    body: JSON.stringify({ reason })
  })
  return ensureOk(response, '取消 Run 失败').json().catch(() => ({}))
}

/** 提交答案反馈；不从浏览器发送 userId，身份由后端认证上下文确定。 */
export async function submitAnswerFeedback(answerId, {
  feedbackType = 'CORRECTION',
  errorType = 'GENERAL',
  evidence = {},
  traceId,
  dataReleaseId,
  knowledgeReleaseId,
  indexReleaseId,
  signal
} = {}) {
  if (!answerId) throw new Error('缺少 answerId，无法提交可追溯反馈')
  const lineage = {
    ...(traceId ? { traceId } : {}),
    ...(dataReleaseId ? { dataReleaseId } : {}),
    ...(knowledgeReleaseId ? { knowledgeReleaseId } : {}),
    ...(indexReleaseId ? { indexReleaseId } : {})
  }
  const response = await authenticatedFetch(`${API_BASE_URL}/answers/${encodeURIComponent(answerId)}/feedback`, {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: requestHeaders({
      'Content-Type': 'application/json',
      // 后端将 X-Answer-Trace-Id 视为原答案血缘的专用边界；不要把本次
      // HTTP 请求的通用 trace header 误当成答案 trace。
      ...(traceId ? { 'X-Answer-Trace-Id': traceId } : {})
    }),
    body: JSON.stringify({ feedbackType, errorType, evidence, ...lineage })
  })
  return ensureOk(response, '提交反馈失败').json()
}

/** 查询当前主体对答案的反馈及纠错状态；不把其他主体的记录缓存到前端。 */
export async function fetchAnswerFeedback(answerId, { limit = 50, signal } = {}) {
  if (!answerId || !String(answerId).trim()) throw new Error('缺少 answerId，无法读取反馈')
  const response = await authenticatedFetch(`${API_BASE_URL}/answers/${encodeURIComponent(String(answerId).trim())}/feedback${queryString({ limit })}`, {
    credentials: 'include',
    signal,
    headers: requestHeaders()
  })
  return readJson(response, '读取反馈状态失败')
}

/** 查询不可变答案投影；用于恢复/反馈详情，不重新调用模型生成答案。 */
export async function fetchAnswerProjection(answerId, { signal } = {}) {
  const id = requiredIdentifier(answerId, 'answerId')
  const response = await authenticatedFetch(`${API_BASE_URL}/answers/${encodeURIComponent(id)}`, {
    credentials: 'include',
    signal,
    headers: requestHeaders()
  })
  return readJson(response, '读取答案投影失败')
}

/** 处理绑定 userId/runId 的正式审批请求。 */
export async function decideRunApproval(approvalId, { runId, approved, reason = '' } = {}) {
  const approval = requiredIdentifier(approvalId, 'approvalId')
  const run = requiredIdentifier(runId, 'runId')
  const params = new URLSearchParams({
    runId: run,
    approved: String(Boolean(approved))
  })
  if (reason) params.set('reason', reason)
  const response = await authenticatedFetch(`${API_BASE_URL}/hitl/approvals/${encodeURIComponent(approval)}?${params}`, {
    method: 'POST',
    credentials: 'include',
    headers: requestHeaders({ 'Content-Type': 'application/json' })
  })
  return ensureOk(response, response.status === 410 ? '审批已过期，请重新生成预览'
    : response.status === 409 ? '审批已处理或状态冲突，请刷新任务' : '审批处理失败').json()
}

/**
 * 订阅结构化事件流。后端可返回 SSE，也可返回 JSON EventPage；两者都归一化为 onEvent。
 */
export async function subscribeRunEvents(runId, { lastEventId = null, onEvent, signal } = {}) {
  const id = requiredIdentifier(runId, 'runId')
  // Keep cursor 0 explicit for parity with the OpenAPI minimum=0 contract.
  const hasCursor = lastEventId !== null && lastEventId !== undefined
    && String(lastEventId) !== ''
  const query = hasCursor ? `?afterEventId=${encodeURIComponent(lastEventId)}` : ''
  const response = await authenticatedFetch(`${API_BASE_URL}/chat/runs/${encodeURIComponent(id)}/events/stream${query}`, {
    credentials: 'include',
    signal,
    headers: requestHeaders({
      Accept: 'text/event-stream, application/json',
      ...(hasCursor ? { 'Last-Event-ID': String(lastEventId) } : {})
    })
  })
  ensureOk(response, '订阅 Run 事件失败')
  if (!response.body) return

  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    const page = await response.json()
    const events = Array.isArray(page) ? page : (page.events || [])
    events.forEach(event => onEvent?.(event))
    return page
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let eventId = null
  let eventName = null
  let dataLines = []
  const flush = () => {
    if (!dataLines.length) return
    const data = dataLines.join('\n')
    let payload = data
    try { payload = JSON.parse(data) } catch { /* 允许纯文本增量 */ }
    if (payload && typeof payload === 'object') {
      // SSE metadata is the transport-level cursor/type authority.  Prefer it
      // when present so a rounded or conflicting JSON field cannot overwrite
      // the exact `id:` header used for resume/deduplication.
      payload = {
        ...payload,
        id: eventId ?? payload.id,
        type: eventName ?? payload.type
      }
    } else {
      payload = { id: eventId, type: eventName || 'answer.delta', data: payload }
    }
    onEvent?.(payload)
    eventId = null
    eventName = null
    dataLines = []
  }

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop() || ''
    for (const line of lines) {
      if (line === '') { flush(); continue }
      if (line.startsWith('id:')) eventId = line.substring(3).trim()
      else if (line.startsWith('event:')) eventName = line.substring(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.substring(5).trimStart())
    }
    if (done) break
  }
  // 某些代理在关闭连接前不会发送最后一个换行；不要丢掉尾部 data。
  if (buffer.trim()) {
    const line = buffer.replace(/\r$/, '')
    if (line.startsWith('id:')) eventId = line.substring(3).trim()
    else if (line.startsWith('event:')) eventName = line.substring(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.substring(5).trimStart())
  }
  flush()
}

export { connectSSE, API_BASE_URL, requestHeaders, API_KEY_HEADER }

export async function fetchRunTeamChange(runId, { signal } = {}) {
  if (!runId) throw new Error('缺少 Run ID')
  return readJson(await authenticatedFetch(`${API_BASE_URL}/chat/runs/${encodeURIComponent(runId)}/team-change`, {
    method: 'GET', signal, headers: requestHeaders()
  }), '读取队伍修改结果失败')
}

export async function decideTeamChange(runId, approvalId, approved) {
  const response = await authenticatedFetch(`${API_BASE_URL}/chat/runs/${encodeURIComponent(requiredIdentifier(runId, 'runId'))}/team-change/decision`, {
    method: 'POST', credentials: 'include', headers: requestHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ approvalId: requiredIdentifier(approvalId, 'approvalId'), approved: Boolean(approved) })
  })
  return readJson(response, response.status === 410 ? '审批已过期，请重新生成预览'
    : response.status === 409 ? '审批已处理或状态冲突，请刷新任务' : '队伍确认失败')
}
