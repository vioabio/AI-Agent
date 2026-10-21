import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const frontendRoot = path.resolve(scriptDir, '..')
const apiSource = fs.readFileSync(path.join(frontendRoot, 'src', 'api', 'index.js'), 'utf8')

async function loadApi() {
  // The API module is deliberately browser-oriented; replace only Vite's
  // compile-time mode expression so the contract tests stay offline.
  const executableSource = apiSource.replace('import.meta.env.MODE', "'test'")
  const moduleUrl = `data:text/javascript;base64,${Buffer.from(executableSource).toString('base64')}`
  return import(moduleUrl)
}

test('取消 Run 透传 CSRF、credentials 和稳定 Idempotency-Key，且拒绝空 runId', async () => {
  const api = await loadApi()
  const previousFetch = globalThis.fetch
  const previousDocument = globalThis.document
  const calls = []
  const controller = new AbortController()
  globalThis.document = { cookie: 'XSRF-TOKEN=csrf%20value' }
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    return { ok: true, status: 200, json: async () => ({ runId: 'run-1', status: 'cancelled' }) }
  }
  try {
    await assert.rejects(() => api.cancelRun('   '), /缺少 runId/)
    await api.cancelRun('run/1', 'user_cancelled', { idempotencyKey: 'cancel-1', signal: controller.signal })
    assert.equal(calls.length, 1)
    assert.equal(calls[0].url, '/api/chat/runs/run%2F1/cancel')
    assert.equal(calls[0].options.credentials, 'include')
    assert.equal(calls[0].options.headers['X-CSRF-TOKEN'], 'csrf value')
    assert.equal(calls[0].options.headers['Idempotency-Key'], 'cancel-1')
    assert.equal(calls[0].options.signal, controller.signal)
    assert.deepEqual(JSON.parse(calls[0].options.body), { reason: 'user_cancelled' })
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
    if (previousDocument === undefined) delete globalThis.document
    else globalThis.document = previousDocument
  }
})

test('旧版 stopManus 对 HTTP 失败显式 reject，不把未授权当作成功', async () => {
  const api = await loadApi()
  const previousFetch = globalThis.fetch
  globalThis.fetch = async () => ({ ok: false, status: 403, json: async () => ({}) })
  try {
    await assert.rejects(() => api.stopManus('session-1'), error =>
      error?.status === 403 && error?.code === 'CSRF_ORIGIN_REJECTED')
    await assert.rejects(() => api.stopManus(''), /缺少 sessionId/)
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
  }
})

test('Run 事件订阅把 AbortSignal 传给 fetch，便于路由卸载时取消请求', async () => {
  const api = await loadApi()
  const previousFetch = globalThis.fetch
  const controller = new AbortController()
  let call
  globalThis.fetch = async (url, options) => {
    call = { url, options }
    return {
      ok: true,
      status: 200,
      body: null,
      headers: { get: () => 'text/event-stream' }
    }
  }
  try {
    await api.subscribeRunEvents('run-1', { lastEventId: '7', signal: controller.signal })
    assert.equal(call.options.signal, controller.signal)
    assert.equal(call.options.headers['Last-Event-ID'], '7')
    assert.match(call.url, /afterEventId=7$/)
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
  }
})

test('Run 事件分页回放同时透传 query 和 Last-Event-ID 游标', async () => {
  const api = await loadApi()
  const previousFetch = globalThis.fetch
  let call
  globalThis.fetch = async (url, options) => {
    call = { url, options }
    return { ok: true, status: 200, json: async () => ({ events: [], nextEventId: 7, terminal: false }) }
  }
  try {
    const page = await api.fetchRunEvents('run-1', '7')
    assert.equal(page.nextEventId, 7)
    assert.equal(call.options.headers['Last-Event-ID'], '7')
    assert.match(call.url, /\/events\?afterEventId=7$/)
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
  }
})

test('初始游标 0 也按 OpenAPI minimum=0 显式透传到 query 和 Header', async () => {
  const api = await loadApi()
  const previousFetch = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    return {
      ok: true,
      status: 200,
      json: async () => ({ events: [], nextEventId: 0, terminal: false }),
      body: null,
      headers: { get: () => 'application/json' }
    }
  }
  try {
    await api.fetchRunEvents('run-1', 0)
    await api.subscribeRunEvents('run-1', { lastEventId: 0 })
    for (const call of calls) {
      assert.match(call.url, /afterEventId=0$/)
      assert.equal(call.options.headers['Last-Event-ID'], '0')
    }
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
  }
})

test('Result<Void> 省略 data 时仍按统一响应解包，并保留业务失败门禁', async () => {
  const api = await loadApi()
  const previousFetch = globalThis.fetch
  const responses = [
    { code: 200, message: '操作成功', timestamp: 1 },
    { code: 403, message: '没有权限', timestamp: 2 },
    { code: 'not-a-number', message: '响应格式错误', timestamp: 3, data: { unsafe: true } }
  ]
  let index = 0
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    json: async () => responses[index++]
  })
  try {
    // DELETE /teams/{teamId} returns Result<Void>; Jackson omits nullable data.
    assert.equal(await api.deletePokemonTeam('42'), undefined)
    await assert.rejects(() => api.deletePokemonTeam('43'), error =>
      error?.status === 403 && error?.code === 'API_RESULT_403'
        && /没有权限/.test(error.message))
    // A malformed business envelope is not silently treated as a successful
    // Result<T>; leave it visible to the caller for an explicit protocol error.
    assert.deepEqual(await api.deletePokemonTeam('44'), responses[2])
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
  }
})

test('EventPage 的 nextCursor 兼容字段也会推进 Run 游标', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'composables', 'useRunStream.js'), 'utf8')
  assert.match(source, /page\?\.nextEventId \?\? page\?\.nextCursor/)
})

test('SSE 解析器支持分块、多 data 行和无尾换行的最后一帧', async () => {
  const api = await loadApi()
  const previousFetch = globalThis.fetch
  const encoder = new TextEncoder()
  const chunks = [
    encoder.encode('id: 11\nevent: answer.delta\ndata: {"text":"甲",\n'),
    encoder.encode('data: "kind":"delta"}\n\n'),
    encoder.encode('id: 12\nevent: run.completed\ndata: {"eventId":12,"type":"run.completed","payload":{"answer":"完成"}}')
  ]
  const events = []
  let index = 0
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    headers: { get: () => 'text/event-stream; charset=utf-8' },
    body: {
      getReader() {
        return {
          read: async () => index < chunks.length
            ? { value: chunks[index++], done: false }
            : { value: undefined, done: true }
        }
      }
    }
  })
  try {
    await api.subscribeRunEvents('run-1', { onEvent: event => events.push(event) })
    assert.equal(events.length, 2)
    assert.equal(events[0].id, '11')
    assert.equal(events[0].type, 'answer.delta')
    assert.equal(events[0].text, '甲')
    assert.equal(events[0].kind, 'delta')
    assert.equal(events[1].id, '12')
    assert.equal(events[1].type, 'run.completed')
    assert.deepEqual(events[1].payload, { answer: '完成' })
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
  }
})

test('SSE id/event 元数据优先于 data 内冲突字段，保持游标精确', async () => {
  const api = await loadApi()
  const previousFetch = globalThis.fetch
  const encoder = new TextEncoder()
  const events = []
  let done = false
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    headers: { get: () => 'text/event-stream' },
    body: {
      getReader() {
        return {
          read: async () => {
            if (done) return { value: undefined, done: true }
            done = true
            return {
              value: encoder.encode('id: 9007199254740993\nevent: run.completed\ndata: {"id":1,"type":"answer.delta","answer":"x"}\n\n'),
              done: false
            }
          }
        }
      }
    }
  })
  try {
    await api.subscribeRunEvents('run-1', { onEvent: event => events.push(event) })
    assert.equal(events.length, 1)
    assert.equal(events[0].id, '9007199254740993')
    assert.equal(events[0].type, 'run.completed')
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
  }
})

test('反馈请求把原答案 trace 发送到专用 X-Answer-Trace-Id Header，并支持 AbortSignal', async () => {
  const api = await loadApi()
  const previousFetch = globalThis.fetch
  const controller = new AbortController()
  let call
  globalThis.fetch = async (url, options) => {
    call = { url, options }
    return { ok: true, status: 201, json: async () => ({ feedbackId: 'fb-1' }) }
  }
  try {
    await api.submitAnswerFeedback('answer-1', {
      traceId: 'trace-answer-1', signal: controller.signal
    })
    assert.equal(call.options.headers['X-Answer-Trace-Id'], 'trace-answer-1')
    assert.equal(call.options.signal, controller.signal)
    assert.doesNotMatch(call.url, /trace-answer-1/)
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
  }
})

test('反馈状态查询使用答案作用域和分页参数，拒绝空 answerId', async () => {
  const api = await loadApi()
  const previousFetch = globalThis.fetch
  let call
  globalThis.fetch = async (url, options) => {
    call = { url, options }
    return { ok: true, status: 200, json: async () => [{ feedbackId: 'fb-1', status: 'SUBMITTED' }] }
  }
  try {
    await assert.rejects(() => api.fetchAnswerFeedback(''), /缺少 answerId/)
    const result = await api.fetchAnswerFeedback('answer/1', { limit: 10 })
    assert.deepEqual(result, [{ feedbackId: 'fb-1', status: 'SUBMITTED' }])
    assert.match(call.url, /\/answers\/answer%2F1\/feedback\?limit=10$/)
    assert.equal(call.options.credentials, 'include')
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
  }
})

test('收藏 API 使用主体凭据、CSRF 和幂等键，并对资源 ID 做路径编码', async () => {
  const api = await loadApi()
  const previousFetch = globalThis.fetch
  const previousDocument = globalThis.document
  const calls = []
  globalThis.document = { cookie: 'XSRF-TOKEN=csrf%20favorite' }
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    return {
      ok: true,
      status: options.method === 'POST' ? 201 : 200,
      json: async () => !options.method || options.method === 'GET'
        ? { code: 0, data: { items: [{ id: 7 }], nextCursor: 'cursor-2' } }
        : options.method === 'POST'
          ? { id: 7, resourceType: 'pokemon', resourceKey: 'pikachu' }
          : undefined
    }
  }
  try {
    const page = await api.fetchFavorites({ limit: 10, cursor: 'cursor/1' })
    assert.deepEqual(page.items, [{ id: 7 }])
    assert.match(calls[0].url, /\/favorites\?limit=10&cursor=cursor%2F1$/)
    assert.equal(calls[0].options.credentials, 'include')
    assert.equal(calls[0].options.headers['X-CSRF-TOKEN'], 'csrf favorite')
    await api.createFavorite({ resourceType: 'pokemon', resourceKey: 'pikachu' }, { idempotencyKey: 'fav-1' })
    await api.deleteFavorite('favorite/7')
    assert.equal(calls[1].url, '/api/favorites')
    assert.equal(calls[1].options.credentials, 'include')
    assert.equal(calls[1].options.headers['X-CSRF-TOKEN'], 'csrf favorite')
    assert.equal(calls[1].options.headers['Idempotency-Key'], 'fav-1')
    assert.deepEqual(JSON.parse(calls[1].options.body), { resourceType: 'pokemon', resourceKey: 'pikachu' })
    assert.match(calls[2].url, /\/favorites\/favorite%2F7$/)
    assert.equal(calls[2].options.credentials, 'include')
    assert.equal(calls[2].options.headers['X-CSRF-TOKEN'], 'csrf favorite')
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
    if (previousDocument === undefined) delete globalThis.document
    else globalThis.document = previousDocument
  }
})

test('收藏列表遇到 HTTP/权限失败时显式 reject，不把空列表当作成功', async () => {
  const api = await loadApi()
  const previousFetch = globalThis.fetch
  globalThis.fetch = async () => ({ ok: false, status: 403, json: async () => ({ code: 403, message: 'forbidden' }) })
  try {
    await assert.rejects(() => api.fetchFavorites(), error =>
      error?.status === 403 && error?.code === 'CSRF_ORIGIN_REJECTED')
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
  }
})

test('收藏工作台接线覆盖列表、创建、删除、分页和明确状态/无障碍提示', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'views', 'PokemonWorkbench.vue'), 'utf8')
  assert.match(source, /fetchFavorites/)
  assert.match(source, /createFavorite/)
  assert.match(source, /deleteFavorite as deleteFavoriteResource/)
  assert.match(source, /loadFavorites\(\{ append = false \}/)
  assert.match(source, /cursor: append \? favoriteCursor\.value/)
  assert.match(source, /aria-labelledby="favorites-title"/)
  assert.match(source, /aria-label="收藏资源类型"/)
  assert.match(source, /当前主体还没有收藏资源/) 
  assert.match(source, /favoriteFormError.*role="alert"|role="alert"[^\n]*>\{\{ favoriteFormError/)
})

test('Run 时间线按 answerId 查询自己的反馈历史，终态/错误/权限均有显式状态', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'components', 'RunTimeline.vue'), 'utf8')
  assert.match(source, /fetchAnswerFeedback\(normalizedAnswerId, \{ limit: 50, signal: controller\.signal \}\)/)
  assert.match(source, /当前答案反馈历史/)
  assert.match(source, /feedbackHistoryState === 'permission'/)
  assert.match(source, /feedbackHistoryState === 'error'/)
  assert.match(source, /watch\(\s*\(\) => \[props\.run\?\.runId, props\.run\?\.answerId, props\.run\?\.status, props\.run\?\.feedbackSubmitted\]/s)
  assert.match(source, /onBeforeUnmount\(\s*\(\s*\)\s*=>/)
})

test('答案投影查询只读取服务端快照，不触发新的生成请求', async () => {
  const api = await loadApi()
  const previousFetch = globalThis.fetch
  let call
  globalThis.fetch = async (url, options) => {
    call = { url, options }
    return { ok: true, status: 200, json: async () => ({ answerId: 'answer-1', lineageStatus: 'COMPLETE' }) }
  }
  try {
    await assert.rejects(() => api.fetchAnswerProjection('  '), /缺少 answerId/)
    assert.deepEqual(await api.fetchAnswerProjection('answer/1'), { answerId: 'answer-1', lineageStatus: 'COMPLETE' })
    assert.match(call.url, /\/answers\/answer%2F1$/)
    assert.equal(call.options.method, undefined)
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
  }
})

test('前端引用链接拒绝 HTTP(S) userinfo，避免把凭据样式 URL 渲染成外链', () => {
  const workbenchSource = fs.readFileSync(path.join(frontendRoot, 'src', 'views', 'PokemonWorkbench.vue'), 'utf8')
  const timelineSource = fs.readFileSync(path.join(frontendRoot, 'src', 'components', 'RunTimeline.vue'), 'utf8')
  for (const source of [workbenchSource, timelineSource]) {
    assert.match(source, /!parsed\.username && !parsed\.password/)
    assert.match(source, /new URL\(raw\)/)
  }
})

test('Run 时间线明示 snapshot/polling 语义并展示反馈与限制卡片', () => {
  const timelineSource = fs.readFileSync(path.join(frontendRoot, 'src', 'components', 'RunTimeline.vue'), 'utf8')
  assert.match(timelineSource, /事件快照 · 游标可重连/)
  assert.match(timelineSource, /未登记事件，已忽略不展示/)
  assert.match(timelineSource, /run\.limitations\?\.length/)
  assert.match(timelineSource, /run\.cards\?\.feedback/)
  assert.match(timelineSource, /run\.context\.battleFormat \|\| run\.context\.battleFormatId/)
})

test('聊天工作区具备消息日志、输入标签和键盘可识别的发送按钮', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'components', 'ChatRoom.vue'), 'utf8')
  assert.match(source, /role="log"/) 
  assert.match(source, /aria-label="聊天消息"/)
  assert.match(source, /aria-label="输入消息"/)
  assert.match(source, /class="send-btn"[\s\S]*type="button"/)
  assert.match(source, /role="status"[\s\S]*aria-live="polite"/)
})

test('首页场景入口使用可键盘访问的 RouterLink，而不是仅依赖 click 的 div', () => {
  const homeSource = fs.readFileSync(path.join(frontendRoot, 'src', 'views', 'Home.vue'), 'utf8')
  const routerSource = fs.readFileSync(path.join(frontendRoot, 'src', 'router', 'index.js'), 'utf8')
  assert.equal((homeSource.match(/<RouterLink\b/g) || []).length, 1)
  assert.doesNotMatch(homeSource, /to="\/pokemon-workbench"/)
  assert.doesNotMatch(homeSource, /to="\/super-agent"/)
  assert.doesNotMatch(homeSource, /class="home-card[^>]*"\s+@click=/)
  assert.match(routerSource, /typeof document !== ['"]undefined['"]/) 
})

test('工作台会话历史消费 nextCursor 并提供加载更多入口', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'views', 'PokemonWorkbench.vue'), 'utf8')
  assert.match(source, /nextCursor/)
  assert.match(source, /loadConversations\(\{ append: true \}\)/)
  assert.match(source, /cursor: append \? conversationCursor\.value/)
})

test('队伍工作台使用 v1.1 规范 /teams 资源入口，保留分页与幂等能力', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'api', 'index.js'), 'utf8')
  assert.match(source, /API_BASE_URL}\/teams\$\{queryString\(/)
  assert.match(source, /API_BASE_URL}\/teams`/)
  assert.doesNotMatch(source, /API_BASE_URL}\/pokemon\/teams/)
})

test('工作台资源请求支持 AbortController，并拒绝旧会话响应覆盖当前会话', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'views', 'PokemonWorkbench.vue'), 'utf8')
  assert.match(source, /new AbortController\(\)/)
  assert.match(source, /signal: controller\.signal/)
  assert.match(source, /selectedConversation\.value\?\.id.*conversationId/)
  assert.match(source, /onBeforeUnmount\(/)
})

test('Run 创建在网络请求前预占会话，关闭慢请求下的 single-flight 竞态窗口', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'composables', 'useRunStream.js'), 'utf8')
  const start = source.indexOf('async function startRun')
  const guard = source.indexOf('pendingConversations.has(conversationId)', start)
  const reserve = source.indexOf('pendingConversations.add(conversationId)', start)
  // startRun now forwards AbortSignal/options as a second argument; anchor on
  // the call prefix rather than the old one-argument formatting.
  const requestOffset = source.slice(start).search(/await createRun\(payload\s*[,)]/m)
  const request = requestOffset < 0 ? -1 : start + requestOffset
  const release = source.indexOf('pendingConversations.delete(conversationId)', request)
  assert.ok(start >= 0 && guard > start)
  assert.ok(reserve > guard && reserve < request)
  assert.ok(release > request)
  assert.match(source.slice(request, release), /finally/)

  const restore = source.indexOf('async function restoreConversation')
  const restoreGuard = source.indexOf('pendingConversations.has(conversationId)', restore)
  const restoreReserve = source.indexOf('pendingConversations.add(conversationId)', restore)
  const restoreRequestOffset = source.slice(restore).search(/await fetchRunSnapshot\(saved\.runId\s*[,)]/m)
  const restoreRequest = restoreRequestOffset < 0 ? -1 : restore + restoreRequestOffset
  const restoreRelease = source.indexOf('pendingConversations.delete(conversationId)', restoreRequest)
  assert.ok(restore >= 0 && restoreGuard > restore)
  assert.ok(restoreReserve > restoreGuard && restoreReserve < restoreRequest)
  assert.ok(restoreRelease > restoreRequest)
  assert.match(source.slice(restoreRequest, restoreRelease), /finally/)
})

test('事件游标过期时清理对应恢复元数据，不在刷新后重复恢复坏游标', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'composables', 'useRunStream.js'), 'utf8')
  const expired = source.indexOf("error?.status === 410")
  assert.ok(expired >= 0)
  const branch = source.slice(expired, source.indexOf('break', expired) + 5)
  assert.match(branch, /streamError = 'EVENT_CURSOR_EXPIRED'/)
  assert.match(branch, /clearRunResume\(model\.conversationId, runId\)/)
})

test('刷新恢复遇到 410 时也 fail-closed，并清理该 Run 的坏游标', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'composables', 'useRunStream.js'), 'utf8')
  const restore = source.indexOf('async function restoreConversation')
  const recoveryCatch = source.lastIndexOf('if (error?.status === 404 || error?.status === 410')
  assert.ok(restore >= 0 && recoveryCatch > restore)
  const branch = source.slice(recoveryCatch, source.indexOf('} else {', recoveryCatch))
  assert.match(branch, /model\.status = RUN_STATUS\.FAILED/)
  assert.match(branch, /EVENT_CURSOR_EXPIRED/)
  assert.match(branch, /clearRunResume\(conversationId, saved\.runId\)/)
  assert.doesNotMatch(branch, /consume\(saved\.runId\)/)
})

test('刷新恢复先校验服务端 run/conversation 身份，拒绝错绑快照', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'composables', 'useRunStream.js'), 'utf8')
  const restore = source.indexOf('async function restoreConversation')
  const identityGate = source.indexOf('const snapshotRunId =', restore)
  const modelConstruction = source.indexOf('const model = reactive(createRunViewModel', identityGate)
  assert.ok(restore >= 0 && identityGate > restore && modelConstruction > identityGate)
  const branch = source.slice(identityGate, modelConstruction)
  assert.match(branch, /snapshotRunId/) 
  assert.match(branch, /snapshotConversationId/) 
  assert.match(branch, /clearRunResume\(conversationId, saved\.runId\)/)
  assert.match(branch, /return null/)
})

test('Run 流把创建/恢复请求绑定到组件生命周期，卸载时保存游标并中止连接', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'composables', 'useRunStream.js'), 'utf8')
  assert.match(source, /onBeforeUnmount\(cleanupOnUnmount\)/)
  assert.match(source, /signal: options\.signal \|\| lifecycleController\?\.signal/)
  assert.match(source, /fetchRunSnapshot\(saved\.runId, \{[\s\S]*signal: lifecycleController\?\.signal/)
  assert.match(source, /lifecycleController\?\.abort\(\)/)
  assert.match(source, /for \(const controller of controllers\.values\(\)\) controller\.abort\(\)/)
})

test('Run 流对认证/参数/冲突 HTTP 状态停止自动重试，429 仍走退避', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'composables', 'useRunStream.js'), 'utf8')
  assert.match(source, /NON_RETRYABLE_STREAM_STATUSES = new Set\(\[400, 401, 403, 404, 409\]\)/)
  assert.match(source, /NON_RETRYABLE_STREAM_STATUSES\.has\(Number\(error\?\.status\)\)/)
  assert.match(source, /status === 401 \|\| status === 403/)
  assert.match(source, /model\.reconnectAttempts = MAX_AUTO_RECONNECT_ATTEMPTS \+ 1/)
  assert.match(source, /model\.status = RUN_STATUS\.FAILED/)
})

test('自动重连达到上限前固化最新恢复游标，刷新后仍可手动继续', () => {
  const source = fs.readFileSync(path.join(frontendRoot, 'src', 'composables', 'useRunStream.js'), 'utf8')
  const marker = 'if (model.reconnectAttempts > MAX_AUTO_RECONNECT_ATTEMPTS)'
  const start = source.indexOf(marker)
  const end = source.indexOf('\n          }', start)
  assert.ok(start >= 0 && end > start)
  const branch = source.slice(start, end)
  assert.match(branch, /isCurrentConversationModel\(model\)/)
  assert.match(branch, /saveRunResume\(model\)/)
})
