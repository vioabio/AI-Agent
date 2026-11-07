package com.vio.vioaiagent.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import jakarta.annotation.PreDestroy;

/**
 * HITL（Human-in-the-Loop）审批管理器 — 安全体系第二层.
 *
 * <p>在工具执行前对高危/中危操作进行人工审批.
 * 审批决策根据工具的危险等级和会话内是否已授权自动判断.
 *
 * <p>使用方式（集成到 ToolCallAgent 的 act() 方法中）：
 * <pre>{@code
 * ApprovalResult result = hitlManager.approve(toolName, sessionId, params);
 * switch (result.action()) {
 *     case APPROVE, APPROVE_ALL -> executeTool();
 *     case DENY -> return "操作被拒绝: " + result.reason();
 *     case SKIP -> return "操作已跳过";
 *     case MODIFY -> executeToolWithModifiedParams();
 * }
 * }</pre>
 *
 * @author vio
 */
@Slf4j
@Component
public class HitlManager {

    /** 工具名 → 危险等级的静态映射 */
    private static final Map<String, DangerLevel> TOOL_DANGER_LEVELS = Map.ofEntries(
            Map.entry("terminal_operation", DangerLevel.HIGH),
            Map.entry("execute_terminal_command", DangerLevel.HIGH),
            Map.entry("write_file", DangerLevel.MEDIUM),
            Map.entry("delete_file", DangerLevel.HIGH),
            Map.entry("read_file", DangerLevel.SAFE),
            Map.entry("web_search", DangerLevel.SAFE),
            Map.entry("web_scraping", DangerLevel.SAFE),
            Map.entry("searchWeb", DangerLevel.SAFE),
            Map.entry("scrapeWebPage", DangerLevel.SAFE),
            Map.entry("generatePDF", DangerLevel.MEDIUM),
            Map.entry("download_resource", DangerLevel.MEDIUM),
            Map.entry("downloadResource", DangerLevel.MEDIUM)
    );

    /** 会话内已批准的工具集合（sessionId → toolNames） */
    private final Map<String, Set<String>> sessionApprovals = new ConcurrentHashMap<>();

    /** 正式 Run 链路的待审批请求；只按 approvalId 索引，避免别名覆盖状态。 */
    private final ConcurrentMap<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    /** 同一用户/Run/工具/参数只允许一个并发审批请求。 */
    private final ConcurrentMap<String, PendingApproval> approvalsByKey = new ConcurrentHashMap<>();
    private final HitlAuditSink auditSink;
    /** 正式 Run 审批的共享状态；未注入时保留旧 JVM 兼容路径。 */
    private volatile HitlApprovalRepository approvalRepository = HitlApprovalRepository.noop();
    /** 审批超时必须主动收敛 Run，不能只等下一次 HTTP 请求触发清理。 */
    private final ScheduledExecutorService expiryScheduler;
    private final CopyOnWriteArrayList<Consumer<PendingApproval>> expiryListeners = new CopyOnWriteArrayList<>();
    private final Duration approvalTtl;

    public HitlManager() {
        this(HitlAuditSink.noop(), newExpiryScheduler(), Duration.ofMinutes(5));
    }

    /** Spring 可选注入审计适配器；未配置时保持本地可启动。 */
    @Autowired
    public HitlManager(ObjectProvider<HitlAuditSink> auditProvider) {
        this(auditProvider == null ? HitlAuditSink.noop()
                : auditProvider.getIfAvailable(HitlAuditSink::noop), newExpiryScheduler(), Duration.ofMinutes(5));
    }

    /** Spring 按 profile 注入 InMemory/JDBC 审批仓储；旧构造器不受影响。 */
    @Autowired(required = false)
    public void setApprovalRepository(HitlApprovalRepository repository) {
        if (repository != null) this.approvalRepository = repository;
    }

    /**
     * 判断正式审批状态是否来自共享/可恢复仓储。Run 恢复不能把仅存在于
     * 当前 JVM Map 的审批当作跨重启事实。
     */
    public boolean hasDurableApprovalRepository() {
        return approvalRepository != null && approvalRepository.durable();
    }

    /** 便于测试或嵌入式运行时注入可观测审计 sink。 */
    public HitlManager(HitlAuditSink auditSink) {
        this(auditSink, newExpiryScheduler(), Duration.ofMinutes(5));
    }

    /** 包级测试构造器，可用短 TTL 验证过期回调，不修改生产默认值。 */
    HitlManager(HitlAuditSink auditSink, Duration approvalTtl) {
        this(auditSink, newExpiryScheduler(), approvalTtl);
    }

    private HitlManager(HitlAuditSink auditSink, ScheduledExecutorService expiryScheduler,
                        Duration approvalTtl) {
        this.auditSink = auditSink == null ? HitlAuditSink.noop() : auditSink;
        this.expiryScheduler = expiryScheduler == null ? newExpiryScheduler() : expiryScheduler;
        this.approvalTtl = approvalTtl == null || approvalTtl.isNegative() || approvalTtl.isZero()
                ? Duration.ofMinutes(5) : approvalTtl;
    }

    /**
     * 注册审批过期回调。Run 服务在 Spring 启动后注册该回调，将过期审批收敛为
     * {@code approval.resolved(approved=false) -> run.failed}；回调异常不会阻断
     * 其他审批清理。
     */
    public void registerExpiryListener(Consumer<PendingApproval> listener) {
        if (listener != null) expiryListeners.addIfAbsent(listener);
    }

    /**
     * 为从持久仓储重新装载的 PENDING 审批重新挂接本地过期任务。
     *
     * <p>审批事实本身仍由 {@link HitlApprovalRepository#expireDue(Instant, String)}
     * 的 CAS 负责；这里仅恢复“何时触发清理”的进程内调度，不会改变审批决定。</p>
     */
    public void rearmExpiry(PendingApproval pending) {
        if (pending == null || pending.status() != ApprovalStatus.PENDING
                || pending.expiresAt() == null) return;
        long delayMs = Math.max(1L, Duration.between(Instant.now(), pending.expiresAt()).toMillis());
        expiryScheduler.schedule(() -> expireApproval(pending.approvalId()), delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 对接 ToolExecutionGateway 的结构化审批决策。
     *
     * @param userId         操作者
     * @param runId          Agent Run
     * @param toolName       工具名
     * @param toolVersion    工具版本
     * @param parameterHash  参数摘要（不保存原始敏感参数）
     * @param requiresHuman  是否需要人工审批
     */
    public ApprovalDecision evaluate(String userId, String runId, String toolName,
                                     String toolVersion, String parameterHash,
                                     boolean requiresHuman) {
        if (!requiresHuman) {
            return ApprovalDecision.allow();
        }
        requireApprovalContext(userId, runId, toolName, toolVersion, parameterHash);
        if (approvalRepository.durable()) {
            return evaluatePersistent(userId, runId, toolName, toolVersion, parameterHash);
        }
        expirePending();
        String approvedKey = approvalKey(userId, runId, toolName, parameterHash);
        Instant now = Instant.now();
        AtomicReference<PendingApproval> created = new AtomicReference<>();
        AtomicReference<PendingApproval> expiredInCompute = new AtomicReference<>();
        PendingApproval resolved = approvalsByKey.compute(approvedKey, (key, current) -> {
            if (current != null && current.expiresAt().isAfter(now)
                    && current.status() != ApprovalStatus.DENIED
                    && current.status() != ApprovalStatus.EXPIRED) {
                return current;
            }
            if (current != null) {
                if (current.expiresAt().isBefore(now)
                        && pendingApprovals.remove(current.approvalId(), current)) {
                    expiredInCompute.set(current);
                } else {
                    pendingApprovals.remove(current.approvalId(), current);
                }
            }
            PendingApproval pending = new PendingApproval(UUID.randomUUID().toString(), userId, runId,
                    toolName, toolVersion, parameterHash, now.plus(approvalTtl),
                    ApprovalStatus.PENDING);
            pendingApprovals.put(pending.approvalId(), pending);
            created.set(pending);
            return pending;
        });
        PendingApproval expired = expiredInCompute.get();
        if (expired != null) notifyExpired(expired);
        PendingApproval newlyCreated = created.get();
        if (newlyCreated != null) {
            log.info("HITL approval.required: runId={}, tool={}, approvalId={}",
                    runId, toolName, newlyCreated.approvalId());
            audit(newlyCreated, "REQUIRED", "工具需要人工审批");
            long delayMs = Math.max(1L, Duration.between(now, newlyCreated.expiresAt()).toMillis());
            expiryScheduler.schedule(() -> expireApproval(newlyCreated.approvalId()),
                    delayMs, TimeUnit.MILLISECONDS);
        }
        if (resolved.status() == ApprovalStatus.APPROVED && resolved.expiresAt().isAfter(now)) {
            return ApprovalDecision.allow();
        }
        if (resolved.status() == ApprovalStatus.PENDING) {
            return ApprovalDecision.waiting(resolved.approvalId(), resolved.expiresAt(),
                    "工具需要人工审批");
        }
        return ApprovalDecision.denied("审批已拒绝");
    }

    /** 根据 userId/runId/toolName 绑定审批，防止跨 Run 越权批准。 */
    public ApprovalDecision decide(String approvalId, String userId, String runId,
                                   boolean approved, String reason) {
        if (approvalId == null || approvalId.isBlank() || userId == null || userId.isBlank()
                || runId == null || runId.isBlank()) {
            return ApprovalDecision.denied("审批上下文不完整");
        }
        if (approvalRepository.durable()) {
            return decidePersistent(approvalId, userId, runId, approved, reason);
        }
        PendingApproval pending = pendingApprovals.get(approvalId);
        if (pending == null) {
            return ApprovalDecision.denied("审批不存在或已过期");
        }
        if (!pending.userId().equals(userId) || !pending.runId().equals(runId)) {
            return ApprovalDecision.denied("审批上下文不匹配");
        }
        Instant now = Instant.now();
        if (pending.expiresAt().isBefore(now)) {
            expireApproval(pending.approvalId());
            return ApprovalDecision.denied("审批已过期");
        }
        ApprovalStatus status = approved ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED;
        AtomicReference<PendingApproval> changed = new AtomicReference<>();
        AtomicReference<PendingApproval> expiredInDecision = new AtomicReference<>();
        PendingApproval finalState = pendingApprovals.computeIfPresent(approvalId, (id, current) -> {
            if (!current.userId().equals(userId) || !current.runId().equals(runId)) return current;
            if (current.expiresAt().isBefore(now)) {
                expiredInDecision.set(current);
                return null;
            }
            // 已经处理过的审批只能重放原结果，不能被第二个请求反向覆盖。
            if (current.status() != ApprovalStatus.PENDING) return current;
            PendingApproval decided = new PendingApproval(current.approvalId(), current.userId(),
                    current.runId(), current.toolName(), current.toolVersion(), current.parameterHash(),
                    current.expiresAt(), status);
            changed.set(decided);
            return decided;
        });
        if (finalState == null) {
            PendingApproval expired = expiredInDecision.get();
            if (expired != null) notifyExpired(expired);
            else expireApproval(pending.approvalId());
            return ApprovalDecision.denied("审批已过期");
        }
        PendingApproval decided = changed.get();
        if (decided != null) {
            String key = approvalKey(decided.userId(), decided.runId(), decided.toolName(), decided.parameterHash());
            approvalsByKey.computeIfPresent(key, (ignored, current) -> current == pending ? decided : current);
            log.info("HITL approval.decided: runId={}, tool={}, approvalId={}, approved={}",
                    runId, pending.toolName(), approvalId, approved);
            audit(decided, approved ? "APPROVED" : "DENIED",
                    approved ? "用户批准工具调用" : normalizeReason(reason));
            return approved ? ApprovalDecision.allow() : ApprovalDecision.denied(normalizeReason(reason));
        }
        // 并发请求已经先完成决策：幂等地返回已提交结果，不允许反向覆盖。
        if (finalState.status() == ApprovalStatus.APPROVED) {
            return approved ? ApprovalDecision.allow() : ApprovalDecision.denied("审批已处理为批准");
        }
        return approved ? ApprovalDecision.denied("审批已处理为拒绝")
                : ApprovalDecision.denied(normalizeReason(reason));
    }

    /** 查询待审批请求（返回脱敏的参数摘要，不返回原始参数）。 */
    public PendingApproval getPending(String approvalId) {
        expirePending();
        if (approvalId == null) return null;
        PendingApproval pending;
        if (approvalRepository.durable()) {
            // Durable state is authoritative across instances.  Never serve a
            // locally cached PENDING snapshot after another instance decided
            // or expired the approval.
            try {
                pending = approvalRepository.find(approvalId).map(this::cache).orElse(null);
            } catch (RuntimeException ex) {
                log.warn("读取持久 HITL 待审批失败，按不可见处理: approvalId={}, reason={}",
                        approvalId, ex.getClass().getSimpleName());
                return null;
            }
        } else {
            pending = pendingApprovals.get(approvalId);
        }
        return pending != null && pending.status() == ApprovalStatus.PENDING ? pending : null;
    }

    /** 返回已脱敏的审批快照，供审计/恢复适配器查询最终决定。 */
    public PendingApproval getApproval(String approvalId) {
        expirePending();
        if (approvalId == null) return null;
        if (approvalRepository.durable()) {
            // Recovery and controller decisions must observe the shared row,
            // not a stale in-process cache from before a concurrent CAS.
            try {
                return approvalRepository.find(approvalId).map(this::cache).orElse(null);
            } catch (RuntimeException ex) {
                log.warn("读取持久 HITL 审批失败，按不可见处理: approvalId={}, reason={}",
                        approvalId, ex.getClass().getSimpleName());
                return null;
            }
        }
        return pendingApprovals.get(approvalId);
    }

    public void expirePending() {
        if (approvalRepository.durable()) {
            try {
                for (HitlApproval expired : approvalRepository.expireDue(Instant.now(), "system")) {
                    PendingApproval view = cache(expired);
                    notifyExpired(view);
                }
            } catch (RuntimeException ex) {
                // 清理失败不能把审批当作已拒绝；保留可观察错误，下一轮重试。
                log.warn("持久 HITL 过期清理失败: {}", ex.getClass().getSimpleName());
            }
        }
        Instant now = Instant.now();
        pendingApprovals.forEach((id, pending) -> {
            if (pending.expiresAt().isBefore(now)) {
                expireApproval(id);
            }
        });
    }

    /** 原子地收敛一个过期审批，并通知 Run 生命周期服务。 */
    private void expireApproval(String approvalId) {
        if (approvalId == null || approvalId.isBlank()) return;
        if (approvalRepository.durable()) {
            try {
                for (HitlApproval expired : approvalRepository.expireDue(Instant.now(), "system")) {
                    // expireDue may atomically settle a batch of approvals;
                    // notify every row, not only the timer's target, otherwise
                    // other waiting Runs would never receive their callback.
                    notifyExpired(cache(expired));
                }
                return;
            } catch (RuntimeException ex) {
                log.warn("持久 HITL 审批过期结算失败: approvalId={}, reason={}",
                        approvalId, ex.getClass().getSimpleName());
                return;
            }
        }
        PendingApproval pending = pendingApprovals.get(approvalId);
        if (pending == null) return;
        if (pending.expiresAt().isAfter(Instant.now())) return;
        // 已经明确批准/拒绝的记录只做过期清理，不重复触发 Run 回调。
        if (pending.status() != ApprovalStatus.PENDING) {
            if (pendingApprovals.remove(approvalId, pending)) {
                approvalsByKey.remove(approvalKey(pending.userId(), pending.runId(),
                        pending.toolName(), pending.parameterHash()), pending);
            }
            return;
        }
        if (!pendingApprovals.remove(approvalId, pending)) return;
        notifyExpired(pending);
    }

    private void notifyExpired(PendingApproval pending) {
        if (pending == null) return;
        approvalsByKey.remove(approvalKey(pending.userId(), pending.runId(), pending.toolName(),
                pending.parameterHash()), pending);
        audit(pending, "EXPIRED", "审批已过期");
        for (Consumer<PendingApproval> listener : expiryListeners) {
            try {
                listener.accept(pending);
            } catch (RuntimeException ex) {
                log.warn("HITL 过期回调失败: approvalId={}, runId={}, reason={}",
                        pending.approvalId(), pending.runId(), ex.getMessage());
            }
        }
    }

    private String approvalKey(String userId, String runId, String toolName, String parameterHash) {
        return "approved:" + userId + ':' + runId + ':' + toolName + ':' + parameterHash;
    }

    /** 正式路径：审批请求先写共享仓储，再把脱敏视图放入本地缓存。 */
    private ApprovalDecision evaluatePersistent(String userId, String runId, String toolName,
                                                String toolVersion, String parameterHash) {
        Instant now = Instant.now();
        HitlApproval candidate = new HitlApproval(UUID.randomUUID().toString(), userId, runId, toolName,
                toolVersion, parameterHash, getDangerLevel(toolName).name(), now.plus(approvalTtl),
                HitlApproval.Decision.PENDING, null, null, null, null, null, 0, now, now);
        HitlApprovalRepository.CreateResult result;
        try {
            result = approvalRepository.createOrGet(candidate, now);
        } catch (RuntimeException ex) {
            // 审批状态不可验证时必须拒绝，不允许回退到自动放行。
            log.warn("持久 HITL 创建失败: runId={}, tool={}, reason={}", runId, toolName,
                    ex.getClass().getSimpleName());
            return ApprovalDecision.denied("审批服务不可用，已按安全策略拒绝");
        }
        PendingApproval view = cache(result.approval());
        if (result.created()) {
            audit(view, "REQUIRED", "工具需要人工审批");
            long delayMs = Math.max(1L, Duration.between(now, view.expiresAt()).toMillis());
            expiryScheduler.schedule(() -> expireApproval(view.approvalId()), delayMs, TimeUnit.MILLISECONDS);
        }
        return switch (result.approval().decision()) {
            case APPROVED -> result.approval().expiresAt().isAfter(now)
                    ? ApprovalDecision.allow() : ApprovalDecision.denied("审批已过期");
            case PENDING -> ApprovalDecision.waiting(view.approvalId(), view.expiresAt(), "工具需要人工审批");
            case DENIED, EXPIRED -> ApprovalDecision.denied(
                    result.approval().decision() == HitlApproval.Decision.EXPIRED ? "审批已过期" : "审批已拒绝");
        };
    }

    /** 正式路径：共享仓储 CAS 决定，重复请求只回放第一次决定。 */
    private ApprovalDecision decidePersistent(String approvalId, String userId, String runId,
                                              boolean approved, String reason) {
        HitlApprovalRepository.DecisionResult result;
        try {
            result = approvalRepository.decide(approvalId, userId, runId, approved, userId,
                    normalizeReason(reason), Instant.now());
        } catch (HitlApprovalNotFoundException ex) {
            return ApprovalDecision.denied("审批不存在或已过期");
        } catch (HitlApprovalConflictException ex) {
            return ApprovalDecision.denied("审批上下文不匹配或已被其他请求处理");
        } catch (RuntimeException ex) {
            log.warn("持久 HITL 决定失败: approvalId={}, reason={}", approvalId,
                    ex.getClass().getSimpleName());
            return ApprovalDecision.denied("审批服务不可用，已按安全策略拒绝");
        }
        HitlApproval approval = result.approval();
        PendingApproval view = cache(approval);
        if (result.changed()) {
            if (approval.decision() == HitlApproval.Decision.EXPIRED) {
                notifyExpired(view);
            } else {
                audit(view, approval.decision() == HitlApproval.Decision.APPROVED ? "APPROVED" : "DENIED",
                        approval.decision() == HitlApproval.Decision.APPROVED
                                ? "用户批准工具调用" : normalizeReason(reason));
            }
        }
        if (approval.decision() == HitlApproval.Decision.APPROVED) {
            return approved ? ApprovalDecision.allow() : ApprovalDecision.denied("审批已处理为批准");
        }
        if (approval.decision() == HitlApproval.Decision.DENIED) {
            return approved ? ApprovalDecision.denied("审批已处理为拒绝")
                    : ApprovalDecision.denied(normalizeReason(reason));
        }
        return ApprovalDecision.denied("审批已过期");
    }

    private PendingApproval cache(HitlApproval approval) {
        if (approval == null) return null;
        ApprovalStatus status = switch (approval.decision()) {
            case APPROVED -> ApprovalStatus.APPROVED;
            case DENIED -> ApprovalStatus.DENIED;
            case EXPIRED -> ApprovalStatus.EXPIRED;
            case PENDING -> ApprovalStatus.PENDING;
        };
        PendingApproval view = new PendingApproval(approval.approvalId(), approval.userId(), approval.runId(),
                approval.toolName(), approval.toolVersion(), approval.parameterDigest(), approval.expiresAt(), status);
        pendingApprovals.put(approval.approvalId(), view);
        if (status == ApprovalStatus.PENDING) {
            approvalsByKey.put(approvalKey(approval.userId(), approval.runId(), approval.toolName(),
                    approval.parameterDigest()), view);
        } else {
            // cache() may replace an older PENDING view with a terminal snapshot;
            // remove the old key by logical identity rather than object identity.
            approvalsByKey.remove(approvalKey(approval.userId(), approval.runId(), approval.toolName(),
                    approval.parameterDigest()));
        }
        return view;
    }

    private void requireApprovalContext(String userId, String runId, String toolName,
                                        String toolVersion, String parameterHash) {
        requireText(userId, "userId");
        requireText(runId, "runId");
        requireText(toolName, "toolName");
        requireText(toolVersion, "toolVersion");
        requireText(parameterHash, "parameterHash");
    }

    private void removeApproval(PendingApproval pending) {
        if (pending == null) return;
        pendingApprovals.remove(pending.approvalId(), pending);
        approvalsByKey.remove(approvalKey(pending.userId(), pending.runId(), pending.toolName(),
                pending.parameterHash()), pending);
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "用户拒绝工具调用" : reason;
    }

    private void audit(PendingApproval pending, String action, String reason) {
        try {
            auditSink.record(new HitlAuditEvent(UUID.randomUUID().toString(), pending.approvalId(), action,
                    pending.userId(), pending.runId(), pending.toolName(), pending.toolVersion(),
                    pending.parameterHash(), reason, Instant.now()));
        } catch (RuntimeException ex) {
            // 审计适配器故障不能把安全决策降级为放行或覆盖状态；保留结构化日志。
            log.warn("HITL 审计写入失败: approvalId={}, action={}, reason={}",
                    pending.approvalId(), action, ex.getMessage());
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
    }

    public enum ApprovalStatus { PENDING, APPROVED, DENIED, EXPIRED }

    /** 结构化审批请求，参数只保留摘要。 */
    public record PendingApproval(String approvalId, String userId, String runId,
                                  String toolName, String toolVersion, String parameterHash,
                                  Instant expiresAt, ApprovalStatus status) {
    }

    /** 网关消费的审批结果。 */
    public record ApprovalDecision(boolean allowed, boolean waiting, String approvalId,
                                   Instant expiresAt, String reason) {
        public static ApprovalDecision allow() {
            return new ApprovalDecision(true, false, null, null, null);
        }

        public static ApprovalDecision waiting(String approvalId, Instant expiresAt, String reason) {
            return new ApprovalDecision(false, true, approvalId, expiresAt, reason);
        }

        public static ApprovalDecision denied(String reason) {
            return new ApprovalDecision(false, false, null, null, reason);
        }
    }

    /**
     * 审批工具调用.
     *
     * @param toolName  工具名称
     * @param sessionId 会话 ID
     * @return 审批结果
     */
    public ApprovalResult approve(String toolName, String sessionId) {
        DangerLevel level = getDangerLevel(toolName);

        switch (level) {
            case SAFE:
                return ApprovalResult.approve(sessionId);

            case MEDIUM:
                // 会话内首次需确认，之后放行
                if (isAlreadyApprovedInSession(toolName, sessionId)) {
                    log.info("HITL: 会话内已授权 {} → 直接放行", toolName);
                    return ApprovalResult.approve(sessionId);
                }
                // 首次 → 标记为已审批并放行（可扩展为等待用户确认）
                markApprovedInSession(toolName, sessionId);
                log.info("HITL: 中危工具 {} 首次执行 → 自动授权（会话: {}）", toolName, sessionId);
                return ApprovalResult.approve(sessionId);

            case HIGH:
                // 高危 → 检查是否已授权
                if (isAlreadyApprovedInSession(toolName, sessionId)) {
                    log.info("HITL: 高危工具 {} 已在会话中授权 → 放行", toolName);
                    return ApprovalResult.approve(sessionId);
                }
                // 当前实现：高危操作需要用户显式确认（默认拒绝，等待扩展为用户交互）
                log.warn("HITL: 高危工具 {} 需要用户显式批准（会话: {}）→ 当前默认策略：拒绝",
                        toolName, sessionId);
                return ApprovalResult.deny(sessionId,
                        "高危操作 [" + toolName + "] 需要用户显式批准. "
                                + "当前 HITL 策略要求用户通过审批 UI 确认此操作.");

            default:
                return ApprovalResult.deny(sessionId, "未知工具危险等级: " + toolName);
        }
    }

    /**
     * 获取工具的危险等级.
     */
    public DangerLevel getDangerLevel(String toolName) {
        return TOOL_DANGER_LEVELS.getOrDefault(toolName, DangerLevel.SAFE);
    }

    /**
     * 用户手动批准高危操作.
     *
     * @param toolName  工具名称
     * @param sessionId 会话 ID
     */
    public void userApprove(String toolName, String sessionId) {
        markApprovedInSession(toolName, sessionId);
        log.info("用户已批准: {} (会话: {})", toolName, sessionId);
    }

    /**
     * 清除会话的所有审批记录.
     */
    public void clearSession(String sessionId) {
        sessionApprovals.remove(sessionId);
        if (sessionId == null) return;
        pendingApprovals.values().stream()
                .filter(pending -> sessionId.equals(pending.runId()))
                .forEach(this::removeApproval);
    }

    // ==================== 私有方法 ====================

    private boolean isAlreadyApprovedInSession(String toolName, String sessionId) {
        Set<String> approved = sessionApprovals.get(sessionId);
        return approved != null && (approved.contains(toolName) || approved.contains("*"));
    }

    private void markApprovedInSession(String toolName, String sessionId) {
        sessionApprovals.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                .add(toolName);
    }

    private static ScheduledExecutorService newExpiryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "vio-hitl-expiry");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        expiryScheduler.shutdownNow();
    }
}
