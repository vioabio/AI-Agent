import { computed, getCurrentInstance, onBeforeUnmount, reactive } from 'vue'
import {
  cancelRun as cancelRunRequest,
  createRun,
  fetchRunSnapshot,
  fetchRunEvents,
  subscribeRunEvents
} from '@/api'
import {
  createRunViewModel,
  compareCursorIds,
  normalizeCursorId,
  reduceRunEvent,
  RUN_STATUS,
  TERMINAL_STATUSES
} from '@/api/run-contract'
import {
  clearRunResume,
  loadRunResume,
  saveRunResume
} from '@/composables/runPersistence'

// 断线属于传输层故障，不能直接把业务 Run 标记为 cancelled。客户端最多
// 自动重连几次，之后保留 runId/游标交给用户手动继续；这样既避免无限请求，
// 也不会在网络异常时伪造答案或终态。
const MAX_AUTO_RECONNECT_ATTEMPTS = 6
const RECONNECT_BACKOFF_MS = 500
const NON_RETRYABLE_STREAM_STATUSES = new Set([400, 401, 403, 404, 409])

/**
 * Run 流式控制器。
 *
 * <p>每个 run 独立持有 AbortController、游标和 reducer 状态；同一 conversation
 * 存在未终态 Run 时拒绝再次发送（single-flight）。</p>
 */
export function useRunStream() {
  const runs = reactive(new Map())
  const controllers = new Map()
  const conversationRuns = new Map()
  // createRun 的网络请求返回前还没有 runId，单靠 conversationRuns 无法
  // 阻止同一会话的第二个 POST 穿过检查。预占集合覆盖这个窗口，并在
  // 创建失败时释放，避免慢网络/快速重复发送产生两个并发 Run。
  const pendingConversations = new Set()
  // The owning view can unmount while a create/restore request is awaiting
  // the network. Keep a lifecycle signal so those requests cannot attach a
  // new stream after the view has gone away.
  const lifecycleController = typeof AbortController === 'function'
    ? new AbortController() : null
  let disposed = false

  function getRun(runId) {
    return runs.get(runId) || null
  }

  // A stream callback can arrive after pause()/dispose() has aborted its
  // controller (the fetch reader is allowed to finish one buffered frame).
  // Mutations and resume writes must be scoped to the model still registered
  // for this run; otherwise an old stream can overwrite a newer run in the
  // same conversation.
  function isCurrentModel(model) {
    return Boolean(model && runs.get(model.runId) === model)
  }

  function isCurrentConversationModel(model) {
    return isCurrentModel(model)
      && conversationRuns.get(model.conversationId) === model.runId
  }

  function activeRunFor(conversationId) {
    const runId = conversationRuns.get(conversationId)
    const model = runId ? runs.get(runId) : null
    return model && !TERMINAL_STATUSES.has(model.status) ? model : null
  }

  function lifecycleAborted() {
    return disposed || Boolean(lifecycleController?.signal.aborted)
  }

  function abortError() {
    return typeof DOMException === 'function'
      ? new DOMException('Run lifecycle ended', 'AbortError')
      : Object.assign(new Error('Run lifecycle ended'), { name: 'AbortError' })
  }

  async function startRun(payload, options = {}) {
    const conversationId = payload.conversationId || 'default'
    if (lifecycleAborted()) throw abortError()
    const active = activeRunFor(conversationId)
    if (active || pendingConversations.has(conversationId)) {
      const error = new Error('当前会话已有任务执行中，请等待终态后再发送')
      error.code = 'RUN_SINGLE_FLIGHT'
      throw error
    }

    pendingConversations.add(conversationId)
    try {
      const created = await createRun(payload, {
        ...options,
        signal: options.signal || lifecycleController?.signal
      })
      const body = created?.data || created
      const runId = body?.runId
      if (!runId) throw new Error('后端未返回 runId')
      const model = reactive(createRunViewModel({
        ...body,
        conversationId,
        status: RUN_STATUS.CREATING
      }))
      // The POST can be accepted by the server just before the component is
      // destroyed. Preserve only its safe resume pointer, but never return a
      // live model or start a stream for a destroyed view.
      if (lifecycleAborted()) {
        saveRunResume(model)
        throw abortError()
      }
      runs.set(runId, model)
      conversationRuns.set(conversationId, runId)
      saveRunResume(model)
      // 流式消费异步运行；调用方立即拿到可响应的 RunViewModel。
      consume(runId).catch(error => {
        // consume 已将可恢复网络错误保留为 reconnecting；这里只处理不应
        // 被吞掉的编程错误，避免 Promise rejection 静默丢失。
        if (!isCurrentModel(model) || TERMINAL_STATUSES.has(model.status)) return
        model.status = RUN_STATUS.FAILED
        model.error = error.message || 'Run 流处理失败'
        model.finishedAt = model.finishedAt || new Date().toISOString()
        if (conversationRuns.get(conversationId) === runId) {
          conversationRuns.delete(conversationId)
          clearRunResume(conversationId, runId)
        }
      })
      return model
    } finally {
      pendingConversations.delete(conversationId)
    }
  }

  async function consume(runId) {
    if (lifecycleAborted()) return
    const model = runs.get(runId)
    if (!model) return
    const existingController = controllers.get(runId)
    if (existingController && !existingController.signal.aborted) return model
    const controller = new AbortController()
    controllers.set(runId, controller)
    try {
      if (model.status === RUN_STATUS.CREATING || model.status === RUN_STATUS.RECONNECTING) {
        model.status = RUN_STATUS.RUNNING
      }
      // 后端 SSE 是可恢复的快照流：连接结束不代表 Run 结束，持续补读直到终态。
      // 每次异常都从 lastEventId 继续，成功收到一批事件后重置退避计数。
      while (!TERMINAL_STATUSES.has(model.status)
        && model.status !== RUN_STATUS.WAITING_APPROVAL
        && !controller.signal.aborted) {
        try {
          const page = await subscribeRunEvents(runId, {
            lastEventId: model.lastEventId,
            signal: controller.signal,
            onEvent: event => applyEvent(model, event)
          })
          advanceCursor(model, page?.nextEventId ?? page?.nextCursor)
          if (TERMINAL_STATUSES.has(model.status)
            || model.status === RUN_STATUS.WAITING_APPROVAL
            || controller.signal.aborted) break
          await replayPage(model, controller.signal)
          if (TERMINAL_STATUSES.has(model.status)
            || model.status === RUN_STATUS.WAITING_APPROVAL
            || controller.signal.aborted) break
          model.reconnectAttempts = 0
          await delay(500, controller.signal)
        } catch (error) {
          if (controller.signal.aborted) return
          if (!isCurrentModel(model)) return
          if (error?.status === 410 || error?.code === 'EVENT_CURSOR_EXPIRED') {
            // 服务端已清理游标对应的事件；不能从未知位置拼接答案。
            model.status = RUN_STATUS.FAILED
            model.error = '事件游标已过期，请重新打开会话'
            model.streamError = 'EVENT_CURSOR_EXPIRED'
            model.finishedAt = model.finishedAt || new Date().toISOString()
            if (conversationRuns.get(model.conversationId) === runId) {
              conversationRuns.delete(model.conversationId)
            }
            // 过期游标无法安全恢复；带 runId 清理避免误删同会话后来
            // 创建的新 Run 的 resume 条目。
            clearRunResume(model.conversationId, runId)
            break
          }
          if (NON_RETRYABLE_STREAM_STATUSES.has(Number(error?.status))) {
            const status = Number(error.status)
            model.error = error?.message || `Run 事件流请求失败（HTTP ${status}）`
            model.streamError = error?.code || `HTTP_${status}`
            if (status === 401 || status === 403) {
              // 身份/CSRF 失败不是网络抖动；停在可手动重试的 reconnecting，
              // 等用户完成登录或修正来源后再继续，不自动刷屏式重试。
              model.status = RUN_STATUS.RECONNECTING
              model.reconnectAttempts = MAX_AUTO_RECONNECT_ATTEMPTS + 1
              saveRunResume(model)
            } else {
              // 参数错误、Run 不存在和状态冲突无法靠重复请求修复；将其
              // 作为本地 fail-closed 终态，清理坏游标并保留明确错误。
              model.status = RUN_STATUS.FAILED
              model.finishedAt = model.finishedAt || new Date().toISOString()
              if (conversationRuns.get(model.conversationId) === runId) {
                conversationRuns.delete(model.conversationId)
              }
              clearRunResume(model.conversationId, runId)
            }
            break
          }
          model.status = RUN_STATUS.RECONNECTING
          model.error = error?.message || '连接暂时中断'
          model.reconnectAttempts += 1
          if (model.reconnectAttempts > MAX_AUTO_RECONNECT_ATTEMPTS) {
            // 保留 reconnecting 以便 UI 提供“继续连接”，不伪造业务失败。
            // 此次请求可能已经推进了服务端游标但随后在回放/连接阶段失败；
            // 在停止自动重试前固化最新安全指针，刷新后才能从正确位置继续。
            if (isCurrentConversationModel(model)) saveRunResume(model)
            break
          }
          await delay(RECONNECT_BACKOFF_MS * Math.min(model.reconnectAttempts, 6), controller.signal)
          if (!TERMINAL_STATUSES.has(model.status)) model.status = RUN_STATUS.RUNNING
        }
      }
    } finally {
      // 防止一个旧连接的 finally 删除随后建立的新连接控制器。
      if (controllers.get(runId) === controller) controllers.delete(runId)
    }
  }

  function delay(milliseconds, signal) {
    return new Promise((resolve, reject) => {
      let settled = false
      let timer
      const cleanup = () => {
        if (timer !== undefined) clearTimeout(timer)
        signal?.removeEventListener('abort', onAbort)
      }
      const onAbort = () => {
        if (settled) return
        settled = true
        cleanup()
        reject(new DOMException('Run stream aborted', 'AbortError'))
      }
      const onTimeout = () => {
        if (settled) return
        settled = true
        cleanup()
        resolve()
      }
      if (signal?.aborted) {
        onAbort()
        return
      }
      timer = setTimeout(onTimeout, milliseconds)
      signal?.addEventListener('abort', onAbort, { once: true })
    })
  }

  async function replayPage(model, signal) {
    if (!isCurrentConversationModel(model)) return null
    const page = await fetchRunEvents(model.runId, model.lastEventId, { signal })
    if (!isCurrentConversationModel(model)) return page
    const events = Array.isArray(page) ? page : (page?.events || [])
    events.forEach(event => applyEvent(model, event))
    advanceCursor(model, page?.nextEventId ?? page?.nextCursor)
    // 事件页可能因为代理重放/保留策略为空，但服务端仍明确知道 Run 已
    // 结束。读取快照确认具体状态，不凭空猜测 completed/failed。
    if (page?.terminal && !TERMINAL_STATUSES.has(model.status)) {
      const snapshot = await fetchRunSnapshot(model.runId, { signal })
      if (!isCurrentConversationModel(model)) return page
      const status = String(snapshot?.status || '').toLowerCase()
      if (status === RUN_STATUS.WAITING_APPROVAL) {
        model.status = RUN_STATUS.WAITING_APPROVAL
      } else if (TERMINAL_STATUSES.has(status)) {
        model.status = status
        model.finishedAt = snapshot.finishedAt || model.finishedAt || new Date().toISOString()
      }
    }
    saveOrClearResume(model)
    return page
  }

  function advanceCursor(model, candidate) {
    if (!isCurrentModel(model)) return
    if (candidate === null || candidate === undefined || candidate === '') return
    const next = normalizeCursorId(candidate, { allowZero: true })
    if (next === null) {
      model.streamError = model.streamError || 'INVALID_EVENT_CURSOR'
      return
    }
    const order = compareCursorIds(next, model.lastEventId)
    if (model.lastEventId === null || model.lastEventId === undefined || model.lastEventId === ''
      || order > 0) {
      model.lastEventId = next
    }
  }

  function applyEvent(model, event) {
    if (!isCurrentModel(model)) return
    const next = reduceRunEvent(model, event)
    Object.assign(model, next)
    if (TERMINAL_STATUSES.has(model.status)) {
      if (conversationRuns.get(model.conversationId) === model.runId) {
        conversationRuns.delete(model.conversationId)
        clearRunResume(model.conversationId, model.runId)
      }
    } else if (isCurrentConversationModel(model)) {
      saveRunResume(model)
    }
    // approval.required 后停止当前快照轮询；审批决定是显式用户动作，
    // handleApproveRun/reconnect 会重新打开连接，避免等待期间高频空请求。
    if (model.status === RUN_STATUS.WAITING_APPROVAL) {
      controllers.get(model.runId)?.abort()
    }
  }

  async function cancelRun(runId, reason = 'user_cancelled') {
    const model = runs.get(runId)
    if (!model || TERMINAL_STATUSES.has(model.status)) return model
    // 取消是可重试的写操作；同一 Run 使用稳定幂等键，避免双击/网络重试
    // 生成多个取消记录或把竞态误报为新的业务动作。
    const response = await cancelRunRequest(runId, reason, {
      idempotencyKey: `run-cancel-${encodeURIComponent(String(runId))}`,
      signal: lifecycleController?.signal
    })
    const body = response?.data || response || {}
    // 取消接口会先持久化终态事件；优先重放服务端事实，避免网络延迟时
    // 用前端本地事件伪造“已取消”。
    try {
      await replayPage(model, lifecycleController?.signal)
    } catch (error) {
      if (error?.name !== 'AbortError' && isCurrentModel(model)) model.error = error.message
    }
    const serverStatus = String(body.status || '').toLowerCase()
    if (serverStatus === RUN_STATUS.CANCELLED && body.terminalEventId !== null
      && body.terminalEventId !== undefined && !TERMINAL_STATUSES.has(model.status)) {
      // 仅作为极端竞态的兜底；正常路径应由 replayPage 消费 run.cancelled。
      applyEvent(model, {
        type: 'run.cancelled',
        id: body.terminalEventId ? String(body.terminalEventId) : null
      })
    }
    if (TERMINAL_STATUSES.has(model.status)) controllers.get(runId)?.abort()
    return model
  }

  async function reconnect(runId) {
    const model = runs.get(runId)
    if (!model || TERMINAL_STATUSES.has(model.status)) return model
    // 手动重试是用户明确动作，重新开启有限自动重连窗口。
    const existingController = controllers.get(runId)
    if (existingController && !existingController.signal.aborted) {
      existingController.abort()
    }
    model.reconnectAttempts = 0
    model.streamError = null
    model.status = RUN_STATUS.RECONNECTING
    await consume(runId)
    return model
  }

  /**
   * 刷新/重新挂载页面后恢复该标签页最近一次 Run。
   * 服务端快照和事件游标是唯一事实来源；Storage 只提供 runId，不直接
   * 恢复答案，避免把过期或跨用户的浏览器数据当成可信内容。
   */
  async function restoreConversation(conversationId) {
    if (lifecycleAborted()) return null
    const existing = activeRunFor(conversationId)
    if (existing) return existing
    // 恢复会先访问服务端再写入 conversationRuns；此期间新建请求若穿过
    // 会造成旧 Run 覆盖新 Run。与 startRun 共用预占集合，已有创建/恢复
    // 正在进行时不启动第二条恢复链。
    if (pendingConversations.has(conversationId)) return null
    const saved = loadRunResume(conversationId)
    if (!saved) return null

    pendingConversations.add(conversationId)
    try {
      let snapshot
      try {
        snapshot = await fetchRunSnapshot(saved.runId, {
          signal: lifecycleController?.signal
        })
      } catch (error) {
        // 资源已删除/过期时清掉标签页元数据；其他网络错误保留它，
        // 让用户稍后重试而不是静默丢失可恢复游标。
        if (error?.status === 404 || error?.status === 410) {
          clearRunResume(conversationId, saved.runId)
          return null
        }
        throw error
      }

      // Storage only provides a candidate runId; the server snapshot remains
      // the authority for resource ownership.  Never attach a snapshot from a
      // different run/conversation to the requested conversation, even if a
      // manually edited sessionStorage entry or a buggy proxy returns 200.
      const snapshotRunId = snapshot?.runId === undefined || snapshot?.runId === null
        ? '' : String(snapshot.runId).trim()
      const snapshotConversationId = snapshot?.conversationId === undefined
        || snapshot?.conversationId === null ? '' : String(snapshot.conversationId).trim()
      if ((snapshotRunId && snapshotRunId !== String(saved.runId))
        || (snapshotConversationId && snapshotConversationId !== String(conversationId))) {
        clearRunResume(conversationId, saved.runId)
        return null
      }

      const model = reactive(createRunViewModel({
        ...snapshot,
        runId: saved.runId,
        conversationId,
        // 先用 reconnecting 允许重放事件；具体终态由事件/快照确认。
        status: RUN_STATUS.RECONNECTING,
        // 刷新后不能只从 saved.lastEventId 读取，否则此前已显示的答案会在
        // 新页面消失。先从保留事件的起点完整重建 ViewModel，随后连接会从
        // 重建出的最新游标继续；若起点已过期，明确提示重新打开会话。
        lastEventId: null,
        resumeCursor: saved.lastEventId
      }))
      if (lifecycleAborted()) return null
      runs.set(saved.runId, model)
      conversationRuns.set(conversationId, saved.runId)
      try {
        await replayPage(model, lifecycleController?.signal)
        if (!lifecycleAborted() && !TERMINAL_STATUSES.has(model.status)) {
          consume(saved.runId).catch(error => {
            if (!isCurrentModel(model) || TERMINAL_STATUSES.has(model.status)) return
            model.status = RUN_STATUS.FAILED
            model.error = error.message || '恢复 Run 失败'
            model.finishedAt = model.finishedAt || new Date().toISOString()
            if (conversationRuns.get(conversationId) === saved.runId) {
              conversationRuns.delete(conversationId)
              clearRunResume(conversationId, saved.runId)
            }
          })
        }
      } catch (error) {
        if (lifecycleAborted() || error?.name === 'AbortError') {
          // cleanupOnUnmount persists the pointer before aborting. Remove the
          // detached in-memory model so a late onMounted continuation cannot
          // expose it after the component has already been destroyed.
          if (conversationRuns.get(conversationId) === saved.runId) {
            conversationRuns.delete(conversationId)
          }
          if (runs.get(saved.runId) === model) runs.delete(saved.runId)
          return null
        }
        if (!isCurrentModel(model)) return model
        if (error?.status === 404 || error?.status === 410
          || error?.code === 'EVENT_CURSOR_EXPIRED') {
          // A refresh rebuilds the view from the retained event stream.  If
          // that stream has already been compacted (or the Run was deleted),
          // retrying from cursor 0 would keep producing the same incomplete /
          // ambiguous answer. Treat it as a local terminal recovery failure
          // and remove only this run's resume record; a newer Run in the same
          // conversation must survive.
          model.status = RUN_STATUS.FAILED
          model.streamError = error?.status === 404 ? 'RUN_NOT_FOUND' : 'EVENT_CURSOR_EXPIRED'
          model.error = error?.status === 404
            ? 'Run 已不存在，请重新打开会话'
            : '事件游标已过期，请重新打开会话'
          model.finishedAt = model.finishedAt || new Date().toISOString()
          if (conversationRuns.get(conversationId) === saved.runId) {
            conversationRuns.delete(conversationId)
          }
          clearRunResume(conversationId, saved.runId)
        } else {
          model.status = RUN_STATUS.RECONNECTING
          model.error = error.message || '恢复 Run 失败'
        }
      }
      return model
    } finally {
      pendingConversations.delete(conversationId)
    }
  }

  /** 页面卸载时只暂停传输连接，保留服务端 Run 和恢复游标。 */
  function pause(runId) {
    controllers.get(runId)?.abort()
    const model = runs.get(runId)
    if (model && isCurrentConversationModel(model) && !TERMINAL_STATUSES.has(model.status)) {
      saveRunResume(model)
    }
    controllers.delete(runId)
    runs.delete(runId)
    if (model && conversationRuns.get(model.conversationId) === model.runId) {
      conversationRuns.delete(model.conversationId)
    }
  }

  function dispose(runId) {
    controllers.get(runId)?.abort()
    const model = runs.get(runId)
    if (model && conversationRuns.get(model.conversationId) === model.runId) {
      conversationRuns.delete(model.conversationId)
      clearRunResume(model.conversationId, model.runId)
    }
    runs.delete(runId)
  }

  /**
   * Abort in-flight requests and streams when the owning view is destroyed.
   * Non-terminal Runs remain resumable through sessionStorage; no answer or
   * prompt is persisted here. Explicit pause/dispose calls in the views are
   * still safe and simply remove the already-aborted model.
   */
  function cleanupOnUnmount() {
    if (disposed) return
    for (const model of runs.values()) {
      if (isCurrentConversationModel(model) && !TERMINAL_STATUSES.has(model.status)) {
        saveRunResume(model)
      }
    }
    disposed = true
    lifecycleController?.abort()
    for (const controller of controllers.values()) controller.abort()
    controllers.clear()
    pendingConversations.clear()
  }

  // Register only when called from a component setup; utility/test callers can
  // still use the composable without triggering a Vue lifecycle warning.
  if (getCurrentInstance()) onBeforeUnmount(cleanupOnUnmount)

  function saveOrClearResume(model) {
    if (!isCurrentConversationModel(model)) return
    if (TERMINAL_STATUSES.has(model.status)) {
      clearRunResume(model.conversationId, model.runId)
    } else {
      saveRunResume(model)
    }
  }

  return {
    runs,
    getRun,
    activeRunFor,
    startRun,
    cancelRun,
    reconnect,
    restoreConversation,
    pause,
    dispose,
    hasActiveRun: conversationId => computed(() => Boolean(activeRunFor(conversationId)))
  }
}
