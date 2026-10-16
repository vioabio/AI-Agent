package com.vio.vioaiagent.tools.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.security.AuditEntry;
import com.vio.vioaiagent.security.AuditLogger;
import com.vio.vioaiagent.security.ToolAuditSink;
import com.vio.vioaiagent.security.CommandGuard;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.security.ParameterMasker;
import com.vio.vioaiagent.security.PathGuard;
import com.vio.vioaiagent.security.TokenBinding;
import com.vio.vioaiagent.security.TokenStore;
import com.vio.vioaiagent.security.PrincipalPolicy;
import com.vio.vioaiagent.config.OidcProperties;
import com.vio.vioaiagent.observability.AgentMetrics;
import com.vio.vioaiagent.run.EffectLedgerRepository;
import com.vio.vioaiagent.run.ToolEffectLedgerEntry;
import com.vio.vioaiagent.run.ToolEffectStatus;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一工具执行治理网关。
 *
 * <p>所有本地工具和显式登记的 MCP 工具都应通过此网关执行。顺序固定为：
 * 身份/Run → 白名单与角色 → Schema → 路径/命令/网络策略 → HITL → 幂等 →
 * 超时/有限重试 → 执行 → 脱敏 → 审计与事件。未知工具默认拒绝。</p>
 */
@Slf4j
@Service
public class ToolExecutionGateway {

    private static final long DEFAULT_TIMEOUT_MS = 5_000L;
    private static final int MAX_RETRIES = 1;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final HitlManager hitlManager;
    private final PathGuard pathGuard;
    private final CommandGuard commandGuard;
    private final ParameterMasker parameterMasker;
    private final AuditLogger auditLogger;
    /** 可选的结构化/持久化审计出口；生产优先使用 JDBC append-only sink。 */
    private final ToolAuditSink toolAuditSink;
    /** 令牌工具范围/频率治理；未启用认证时允许为空以保持本地兼容。 */
    private final TokenStore tokenStore;
    /** 可选共享副作用账本；生产装配后用于跨重启去重，未装配时保留本地缓存。 */
    private final EffectLedgerRepository effectLedger;
    /**
     * OIDC 开启时，任何工具调用都必须携带已经解析并绑定的 TokenBinding。
     *
     * <p>OIDC Resource Server 的 JWT 认证和 API-Key TokenBinding 是两条不同的
     * 认证适配路径；如果只看 Spring Security 的 authenticated 状态，异步 Run
     * worker 在切线程后会丢失该状态并重新落入“无 binding 放行”的旧分支。因此
     * 这里在 Spring 装配阶段锁定 OIDC 开关，并在网关授权/配额门禁处统一
     * fail-closed。程序化构造器默认关闭该门禁，保留无 Spring Security 的本地
     * 单测和嵌入式调用语义。</p>
     */
    private final boolean oidcBindingRequired;
    /** 可选指标依赖，保持程序化单元测试无需搭建 Micrometer。 */
    @Autowired(required = false)
    private AgentMetrics metrics;
    private final BoundedToolExecutor executor;

    private final Map<String, RegisteredTool> registry = new ConcurrentHashMap<>();
    private final Map<String, ToolGovernanceResult> idempotentResults = new ConcurrentHashMap<>();
    /** An uncertain write cannot become safe merely because its caller or worker has returned. */
    private final Set<String> unknownEffects = ConcurrentHashMap.newKeySet();
    /**
     * 幂等结果的读取、实际副作用和写回必须处于同一临界区。仅使用
     * {@code get + putIfAbsent} 仍会让两个并发请求同时执行一次写操作。
     * 锁对象在本次调用结束后移除，避免按用户输入无限增长。
     */
    private final Map<String, IdempotencyLock> idempotencyLocks = new ConcurrentHashMap<>();

    @Autowired
    public ToolExecutionGateway(ObjectProvider<HitlManager> hitlProvider,
                                ObjectProvider<PathGuard> pathProvider,
                                ObjectProvider<CommandGuard> commandProvider,
                                ObjectProvider<ParameterMasker> maskerProvider,
                                ObjectProvider<AuditLogger> auditProvider,
                                ObjectProvider<ToolAuditSink> toolAuditProvider,
                                ObjectProvider<TokenStore> tokenStoreProvider,
                                ObjectProvider<EffectLedgerRepository> effectProvider,
                                ObjectProvider<OidcProperties> oidcProvider,
                                Environment environment,
                                ToolExecutionProperties executionProperties) {
        this.hitlManager = hitlProvider.getIfAvailable(HitlManager::new);
        this.pathGuard = pathProvider.getIfAvailable(
                () -> new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")));
        this.commandGuard = commandProvider.getIfAvailable(() -> new CommandGuard(List.of()));
        this.parameterMasker = maskerProvider.getIfAvailable(ParameterMasker::new);
        this.auditLogger = auditProvider.getIfAvailable(() -> new AuditLogger(parameterMasker));
        ToolAuditSink configuredAuditSink = toolAuditProvider == null
                ? null : toolAuditProvider.getIfAvailable();
        boolean production = environment != null
                && java.util.Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "prod".equalsIgnoreCase(profile) || "demo".equalsIgnoreCase(profile));
        // Spring prod/demo 没有拿到 JDBC/合规 sink 时，不能退回本地 JSONL；使用
        // 明确失败的占位出口，让成功回调在网关层变成不可确认结果。
        this.toolAuditSink = configuredAuditSink != null
                ? configuredAuditSink
                : (production ? new com.vio.vioaiagent.security.FailClosedToolAuditSink() : null);
        this.tokenStore = tokenStoreProvider == null ? null : tokenStoreProvider.getIfAvailable();
        this.effectLedger = effectProvider == null ? null : effectProvider.getIfAvailable();
        this.oidcBindingRequired = oidcProvider != null
                && Optional.ofNullable(oidcProvider.getIfAvailable())
                .map(OidcProperties::enabled)
                .orElse(false);
        this.executor = new BoundedToolExecutor(executionProperties);
    }

    /** Preserve embedded callers of the earlier Spring-provider constructor. */
    public ToolExecutionGateway(ObjectProvider<HitlManager> hitlProvider,
                                ObjectProvider<PathGuard> pathProvider,
                                ObjectProvider<CommandGuard> commandProvider,
                                ObjectProvider<ParameterMasker> maskerProvider,
                                ObjectProvider<AuditLogger> auditProvider,
                                ObjectProvider<ToolAuditSink> toolAuditProvider,
                                ObjectProvider<TokenStore> tokenStoreProvider,
                                ObjectProvider<EffectLedgerRepository> effectProvider,
                                ObjectProvider<OidcProperties> oidcProvider,
                                Environment environment) {
        this(hitlProvider, pathProvider, commandProvider, maskerProvider, auditProvider,
                toolAuditProvider, tokenStoreProvider, effectProvider, oidcProvider, environment,
                ToolExecutionProperties.defaults());
    }

    /** 兼容旧的 Spring/测试七参数装配签名。 */
    public ToolExecutionGateway(ObjectProvider<HitlManager> hitlProvider,
                                ObjectProvider<PathGuard> pathProvider,
                                ObjectProvider<CommandGuard> commandProvider,
                                ObjectProvider<ParameterMasker> maskerProvider,
                                ObjectProvider<AuditLogger> auditProvider,
                                ObjectProvider<TokenStore> tokenStoreProvider,
                                ObjectProvider<EffectLedgerRepository> effectProvider) {
        this(hitlProvider, pathProvider, commandProvider, maskerProvider, auditProvider,
                null, tokenStoreProvider, effectProvider, null, null);
    }

    /** 兼容旧的 Spring/测试六参数装配签名。 */
    public ToolExecutionGateway(ObjectProvider<HitlManager> hitlProvider,
                                ObjectProvider<PathGuard> pathProvider,
                                ObjectProvider<CommandGuard> commandProvider,
                                ObjectProvider<ParameterMasker> maskerProvider,
                                ObjectProvider<AuditLogger> auditProvider,
                                ObjectProvider<TokenStore> tokenStoreProvider) {
        this(hitlProvider, pathProvider, commandProvider, maskerProvider, auditProvider,
                tokenStoreProvider, null);
    }

    /** 兼容旧的 Spring/测试五参数装配签名。 */
    public ToolExecutionGateway(ObjectProvider<HitlManager> hitlProvider,
                                ObjectProvider<PathGuard> pathProvider,
                                ObjectProvider<CommandGuard> commandProvider,
                                ObjectProvider<ParameterMasker> maskerProvider,
                                ObjectProvider<AuditLogger> auditProvider) {
        this(hitlProvider, pathProvider, commandProvider, maskerProvider, auditProvider, null, null);
    }

    /** 便于单元测试和非 Spring 程序化使用的构造器。 */
    public ToolExecutionGateway(HitlManager hitlManager, PathGuard pathGuard,
                                CommandGuard commandGuard, ParameterMasker parameterMasker,
                                AuditLogger auditLogger) {
        this(hitlManager, pathGuard, commandGuard, parameterMasker, auditLogger, null);
    }

    public ToolExecutionGateway(HitlManager hitlManager, PathGuard pathGuard,
                                CommandGuard commandGuard, ParameterMasker parameterMasker,
                                AuditLogger auditLogger, TokenStore tokenStore) {
        this(hitlManager, pathGuard, commandGuard, parameterMasker, auditLogger,
                tokenStore, null);
    }

    /**
     * 便于嵌入式运行时/单元测试显式装配副作用账本。生产 Spring 路径使用上面的
     * {@link ObjectProvider} 构造器；这里保留直接注入入口，避免测试通过反射修改
     * final 依赖，也让“账本不可用时 fail-closed”的契约可以被真实覆盖。
     */
    public ToolExecutionGateway(HitlManager hitlManager, PathGuard pathGuard,
                                CommandGuard commandGuard, ParameterMasker parameterMasker,
                                AuditLogger auditLogger, TokenStore tokenStore,
                                EffectLedgerRepository effectLedger) {
        this(hitlManager, pathGuard, commandGuard, parameterMasker, auditLogger,
                tokenStore, effectLedger, false);
    }

    /**
     * 直接构造入口的显式 OIDC 门禁开关，供安全契约测试和嵌入式装配使用。
     * 默认构造器保持 false，避免无 Spring Security 的程序化调用被误判为 OIDC。
     */
    public ToolExecutionGateway(HitlManager hitlManager, PathGuard pathGuard,
                                CommandGuard commandGuard, ParameterMasker parameterMasker,
                                AuditLogger auditLogger, TokenStore tokenStore,
                                EffectLedgerRepository effectLedger,
                                boolean oidcBindingRequired) {
        this(hitlManager, pathGuard, commandGuard, parameterMasker,
                (ToolAuditSink) auditLogger, tokenStore, effectLedger, oidcBindingRequired);
    }

    /**
     * 允许嵌入式调用方显式提供 typed 审计出口；保留旧 AuditLogger 构造器，
     * 以便现有本地测试和兼容模块平滑迁移。
     */
    public ToolExecutionGateway(HitlManager hitlManager, PathGuard pathGuard,
                                CommandGuard commandGuard, ParameterMasker parameterMasker,
                                ToolAuditSink auditSink, TokenStore tokenStore,
                                EffectLedgerRepository effectLedger,
                                boolean oidcBindingRequired) {
        this(hitlManager, pathGuard, commandGuard, parameterMasker, auditSink, tokenStore,
                effectLedger, oidcBindingRequired, ToolExecutionProperties.defaults());
    }

    public ToolExecutionGateway(HitlManager hitlManager, PathGuard pathGuard,
                                CommandGuard commandGuard, ParameterMasker parameterMasker,
                                ToolAuditSink auditSink, TokenStore tokenStore,
                                EffectLedgerRepository effectLedger, boolean oidcBindingRequired,
                                ToolExecutionProperties executionProperties) {
        this.hitlManager = hitlManager == null ? new HitlManager() : hitlManager;
        this.pathGuard = pathGuard == null
                ? new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")) : pathGuard;
        this.commandGuard = commandGuard == null ? new CommandGuard(List.of()) : commandGuard;
        this.parameterMasker = parameterMasker == null ? new ParameterMasker() : parameterMasker;
        ToolAuditSink effectiveSink = auditSink == null ? new AuditLogger(this.parameterMasker) : auditSink;
        this.toolAuditSink = effectiveSink;
        this.auditLogger = effectiveSink instanceof AuditLogger logger ? logger : null;
        this.tokenStore = tokenStore;
        this.effectLedger = effectLedger;
        this.oidcBindingRequired = oidcBindingRequired;
        this.executor = new BoundedToolExecutor(executionProperties);
    }

    /** 包装一组工具；包装动作同时登记能力卡。 */
    public ToolCallback[] wrap(ToolCallback[] callbacks) {
        if (callbacks == null || callbacks.length == 0) {
            return new ToolCallback[0];
        }
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            wrapped[i] = wrap(callbacks[i]);
        }
        return wrapped;
    }

    /** 包装一个工具；未知工具仍可被发现，但实际调用会 fail-closed。 */
    public ToolCallback wrap(ToolCallback callback) {
        if (callback == null || callback.getToolDefinition() == null) {
            throw new IllegalArgumentException("工具回调及其定义不能为空");
        }
        register(callback, ToolCapabilityCatalog.find(callback.getToolDefinition().name())
                .orElse(null));
        return callback instanceof GovernedToolCallback
                ? callback : new GovernedToolCallback(callback, this);
    }

    /** 显式登记 MCP 工具能力卡后再包装，避免 MCP 运输层绕过治理。 */
    public ToolCallback wrapMcp(ToolCallback callback, ToolCapabilityCard card) {
        register(callback, card);
        return callback instanceof GovernedToolCallback
                ? callback : new GovernedToolCallback(callback, this);
    }

    public void register(ToolCallback callback, ToolCapabilityCard card) {
        if (callback == null || callback.getToolDefinition() == null) {
            throw new IllegalArgumentException("工具回调及其定义不能为空");
        }
        String name = callback.getToolDefinition().name();
        ToolCapabilityCard effective = card;
        if (effective != null && "{}".equals(effective.inputSchema())) {
            effective = new ToolCapabilityCard(effective.name(), effective.version(),
                    effective.riskLevel(), effective.sideEffect(), effective.idempotent(),
                    effective.timeoutMs(), effective.allowedRoles(),
                    callback.getToolDefinition().inputSchema(), effective.outputSchema());
        }
        registry.put(name, new RegisteredTool(callback, effective));
    }

    public ToolGovernanceResult execute(ToolCallback callback, String toolInput,
                                        ToolExecutionContext context) {
        if (callback == null || callback.getToolDefinition() == null) {
            return rejected("unknown", "工具定义缺失", context);
        }
        return execute(callback.getToolDefinition().name(), toolInput, context);
    }

    /** 按已登记工具名称执行。 */
    public ToolGovernanceResult execute(String toolName, String toolInput,
                                        ToolExecutionContext context) {
        ToolExecutionContext safeContext = context == null
                ? ToolExecutionContext.fromRequest() : context;
        RegisteredTool registered = registry.get(toolName);
        if (registered == null || registered.card() == null) {
            return rejected(toolName, "工具未登记能力卡，已按 fail-closed 拒绝", safeContext);
        }

        ToolCapabilityCard card = registered.card();
        String eventId = UUID.randomUUID().toString();
        Instant started = Instant.now();
        emit(safeContext, "tool.requested", toolName, eventId, Map.of("version", card.version()));

        Map<String, Object> arguments;
        try {
            arguments = parseAndValidate(toolInput, card.inputSchema());
            verifyRole(card, safeContext.roles());
            verifyPrincipalPolicy(card, toolName, safeContext);
            verifyPolicies(toolName, arguments);
            String bindingRejection = authorizeTokenScope(safeContext, toolName);
            if (bindingRejection != null) throw new SecurityException(bindingRejection);
        } catch (RuntimeException ex) {
            return rejected(toolName, ex.getMessage(), safeContext, eventId, started);
        }

        String parameterDigest = parameterDigest(arguments);
        String idemKey = safeContext.idempotencyKey();
        String resultKey = idempotencyKey(toolName, safeContext, idemKey);
        // Resolve uncertainty before HITL can expire and create another approval request.
        // The monitor below repeats this check for callers already waiting on the same key.
        if (card.isWriteLike() && resultKey != null && unknownEffects.contains(resultKey)) {
            return rejected(toolName, "副作用状态为 UNKNOWN，需人工核对后才能继续",
                    safeContext, eventId, started);
        }
        HitlManager.ApprovalDecision approval = hitlManager.evaluate(
                safeContext.userId(), safeContext.runId(), toolName, card.version(), parameterDigest,
                card.riskLevel() != ToolCapabilityCard.RiskLevel.LOW || card.isWriteLike());
        if (approval.waiting()) {
            emit(safeContext, "approval.required", toolName, eventId,
                    Map.of("approvalId", approval.approvalId(), "expiresAt", approval.expiresAt().toString()));
            if (metrics != null) metrics.recordApproval("waiting");
            return new ToolGovernanceResult(ToolGovernanceResult.Status.WAITING_APPROVAL,
                    toolName, approval.reason(), approval.approvalId(), eventId, 0,
                    elapsed(started));
        }
        if (!approval.allowed()) {
            if (metrics != null) metrics.recordApproval("denied");
            return rejected(toolName, approval.reason(), safeContext, eventId, started);
        }
        if (metrics != null && card.requiresApproval()) metrics.recordApproval("approved");

        if (card.isWriteLike() && (idemKey == null || idemKey.isBlank())) {
            return rejected(toolName, "写操作必须提供幂等键", safeContext, eventId, started);
        }
        if (resultKey != null) {
            // Count holders before entering the monitor.  Removing a plain
            // lock in the first caller's finally block has a small but real
            // gap: a waiter that already captured the old lock can overlap a
            // newcomer that creates a replacement lock.  The reference count
            // keeps the same monitor registered until every waiter leaves.
            IdempotencyLock lock = idempotencyLocks.compute(resultKey, (ignored, current) -> {
                IdempotencyLock value = current == null ? new IdempotencyLock() : current;
                value.references++;
                return value;
            });
            synchronized (lock) {
                try {
                    if (card.isWriteLike() && unknownEffects.contains(resultKey)) {
                        return rejected(toolName, "副作用状态为 UNKNOWN，需人工核对后才能继续",
                                safeContext, eventId, started);
                    }
                    ToolGovernanceResult previous = idempotentResults.get(resultKey);
                    if (previous != null) {
                        if (metrics != null) metrics.recordToolCall(true);
                        emit(safeContext, "tool.deduplicated", toolName, eventId, Map.of());
                        return new ToolGovernanceResult(ToolGovernanceResult.Status.DEDUPLICATED,
                                toolName, previous.output(), previous.approvalId(), eventId,
                                previous.retryCount(), elapsed(started));
                    }
                    String rateRejection = consumeTokenRate(safeContext);
                    if (rateRejection != null) {
                        return rejected(toolName, rateRejection, safeContext, eventId, started);
                    }
                    return executeRegistered(registered, card, toolInput, toolName, safeContext,
                            arguments, eventId, started, resultKey, parameterDigest, approval.approvalId());
                } finally {
                    idempotencyLocks.computeIfPresent(resultKey, (ignored, current) -> {
                        if (current != lock) return current;
                        current.references--;
                        return current.references == 0 ? null : current;
                    });
                }
            }
        }

        String rateRejection = consumeTokenRate(safeContext);
        if (rateRejection != null) {
            return rejected(toolName, rateRejection, safeContext, eventId, started);
        }
        return executeRegistered(registered, card, toolInput, toolName, safeContext,
                arguments, eventId, started, null, parameterDigest, approval.approvalId());
    }

    private ToolGovernanceResult executeRegistered(RegisteredTool registered,
                                                    ToolCapabilityCard card,
                                                    String toolInput,
                                                    String toolName,
                                                    ToolExecutionContext safeContext,
                                                    Map<String, Object> arguments,
                                                    String eventId,
                                                    Instant started,
                                                    String resultKey,
                                                    String parameterDigest,
                                                    String approvalId) {
        EffectReservation reservation;
        try {
            reservation = reserveEffect(card, toolName, safeContext, parameterDigest,
                    approvalId, started);
        } catch (EffectLedgerUnavailableException ex) {
            // 账本不可验证时绝不能继续调用外部写工具：否则进程重试可能产生
            // 不可追踪的重复副作用。对调用方只返回稳定的安全原因，不泄露 SQL/网络细节。
            return rejected(toolName, ex.reason(), safeContext, eventId, started);
        }
        ToolEffectLedgerEntry ledgerEntry = reservation.entry();
        if (ledgerEntry != null && !reservation.acquired()) {
            // 只有本次成功 reserve 的 STARTED 记录才拥有执行权。看到别的 worker
            // 留下的 STARTED/UNKNOWN 必须停住并人工核对，不能把“未结算”误判成可重放。
            if (ledgerEntry.status() == ToolEffectStatus.SUCCEEDED
                    || ledgerEntry.status() == ToolEffectStatus.FAILED) {
                // 跨进程重放已有终态时不再次调用外部工具；结果摘要本身不等于
                // 原始结果，因此调用方会收到可审计的 deduplicated 标记。
                emit(safeContext, "tool.deduplicated", toolName, eventId,
                        Map.of("effectStatus", ledgerEntry.status().name()));
                return new ToolGovernanceResult(ToolGovernanceResult.Status.DEDUPLICATED, toolName,
                        "副作用已由先前执行结算", ledgerEntry.approvalId(), eventId, 0, elapsed(started));
            }
            String reason = ledgerEntry.status() == ToolEffectStatus.WAITING_APPROVAL
                    ? "副作用仍在等待审批，禁止绕过审批重放"
                    : "副作用状态为 " + ledgerEntry.status().name() + "，需人工核对后才能继续";
            return rejected(toolName, reason, safeContext, eventId, started);
        }
        int retries = 0;
        String output = null;
        RuntimeException failure = null;
        int attempts = card.idempotent() && card.riskLevel() == ToolCapabilityCard.RiskLevel.LOW
                ? MAX_RETRIES + 1 : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                emit(safeContext, "tool.started", toolName, eventId,
                        Map.of("attempt", attempt + 1));
                output = callWithTimeout(registered.callback(), toolInput, card.timeoutMs(),
                        card, toolName, safeContext);
                failure = null;
                break;
            } catch (ToolAuthorizationException ex) {
                // Expiry/revocation is final for this attempt. A write reserved
                // in the effect ledger has not started its callback yet.
                completeEffect(ledgerEntry, ToolEffectStatus.FAILED, ex.getMessage(), approvalId);
                return rejected(toolName, ex.getMessage(), safeContext, eventId, started);
            } catch (BoundedToolExecutor.Unavailable ex) {
                completeEffect(ledgerEntry, ToolEffectStatus.FAILED, ex.getMessage(), approvalId);
                return rejected(toolName, ex.getMessage(), safeContext, eventId, started);
            } catch (BoundedToolExecutor.Stopped ex) {
                // Cancellation is cooperative: a callback may ignore interrupt.
                // Do not start a retry concurrently with that unconfirmed work.
                failure = ex;
                break;
            } catch (RuntimeException ex) {
                failure = ex;
                if (attempt + 1 < attempts) {
                    retries++;
                }
            }
        }

        long duration = elapsed(started);
        if (failure != null) {
            String message = safeMessage(failure);
            ToolEffectStatus effectStatus = failure instanceof BoundedToolExecutor.Stopped stopped
                    && stopped.callbackStarted() ? ToolEffectStatus.UNKNOWN : ToolEffectStatus.FAILED;
            if (effectStatus == ToolEffectStatus.UNKNOWN) rememberUnknownEffect(card, resultKey);
            emit(safeContext, "tool.failed", toolName, eventId, Map.of("error", message,
                    "traceId", safeContext.traceId(), "toolVersion", card.version(),
                    "parameterDigest", parameterDigest, "status", "FAILED"));
            emit(safeContext, "tool.finished", toolName, eventId, Map.of("status", "FAILED",
                    "durationMs", duration, "retryCount", retries, "toolVersion", card.version(),
                    "parameterDigest", parameterDigest, "approvalId", approvalId == null ? "" : approvalId));
            completeEffect(ledgerEntry, effectStatus, message, approvalId);
            audit(safeContext, toolName, arguments, "error", duration);
            if (metrics != null) metrics.recordToolCall(false);
            return new ToolGovernanceResult(ToolGovernanceResult.Status.FAILED, toolName,
                    message, null, eventId, retries, duration);
        }

        String safeOutput = redactOutput(output);
        // 外部回调已经完成，但在生产中审计事实尚未可靠落库时，不能把这次
        // 调用报告为 EXECUTED。对有副作用的工具写入 UNKNOWN，阻止后续自动重放；
        // 调用方得到稳定的 FAILED 结果，底层异常细节不会泄露。
        AuditResult auditResult = audit(safeContext, toolName, arguments, "allow", elapsed(started));
        if (!auditResult.success() && (auditResult.failClosed() || card.isWriteLike())) {
            rememberUnknownEffect(card, resultKey);
            emit(safeContext, "tool.audit_failed", toolName, eventId,
                    Map.of("status", "AUDIT_FAILED", "traceId", safeContext.traceId(),
                            "toolVersion", card.version(), "parameterDigest", parameterDigest));
            emit(safeContext, "tool.finished", toolName, eventId,
                    Map.of("status", "AUDIT_FAILED", "durationMs", elapsed(started),
                            "toolVersion", card.version(), "parameterDigest", parameterDigest,
                            "approvalId", approvalId == null ? "" : approvalId));
            completeEffect(ledgerEntry, ToolEffectStatus.UNKNOWN, safeOutput, approvalId);
            if (metrics != null) metrics.recordToolCall(false);
            return new ToolGovernanceResult(ToolGovernanceResult.Status.FAILED, toolName,
                    "工具结果无法安全确认：审计服务不可用", null, eventId, retries, elapsed(started));
        }
        ToolGovernanceResult result = new ToolGovernanceResult(
                ToolGovernanceResult.Status.EXECUTED, toolName, safeOutput, null,
                eventId, retries, duration);
        if (resultKey != null) {
            idempotentResults.putIfAbsent(resultKey, result);
        }
        emit(safeContext, "tool.completed", toolName, eventId,
                Map.of("durationMs", duration, "retryCount", retries, "traceId", safeContext.traceId(),
                        "toolVersion", card.version(), "parameterDigest", parameterDigest,
                        "approvalId", approvalId == null ? "" : approvalId, "status", "SUCCEEDED"));
        emit(safeContext, "tool.finished", toolName, eventId,
                Map.of("durationMs", duration, "retryCount", retries, "toolVersion", card.version(),
                        "parameterDigest", parameterDigest, "approvalId", approvalId == null ? "" : approvalId,
                        "status", "SUCCEEDED"));
        completeEffect(ledgerEntry, ToolEffectStatus.SUCCEEDED, safeOutput, approvalId);
        if (metrics != null) metrics.recordToolCall(true);
        return result;
    }

    private void rememberUnknownEffect(ToolCapabilityCard card, String resultKey) {
        // Retain these tombstones for the gateway's lifetime, including when no shared ledger
        // exists. Expiry or a late callback result cannot confirm an uncertain external write.
        if (card.isWriteLike() && resultKey != null) unknownEffects.add(resultKey);
    }

    public Collection<ToolCapabilityCard> registeredCapabilities() {
        List<ToolCapabilityCard> cards = new ArrayList<>();
        // 未登记能力卡的工具仍保留在 registry 以便 fail-closed；不把 null 泄露给诊断序列化。
        registry.values().forEach(tool -> {
            if (tool.card() != null) cards.add(tool.card());
        });
        return List.copyOf(cards);
    }

    private Map<String, Object> parseAndValidate(String input, String schema) {
        try {
            JsonNode node = input == null || input.isBlank() ? objectMapper.createObjectNode()
                    : objectMapper.readTree(input);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("工具参数必须是 JSON 对象");
            }
            if (schema != null && !schema.isBlank() && !"{}".equals(schema)) {
                JsonNode schemaNode = objectMapper.readTree(schema);
                validateSchemaNode("$", node, schemaNode);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.convertValue(node, Map.class);
            return result;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("工具参数 JSON 无效", ex);
        }
    }

    /**
     * 最小但递归的 JSON Schema 校验器。能力卡由配置驱动，不能只检查顶层
     * primitive 类型，否则嵌套对象/数组会成为策略绕过点。未知扩展关键字仍
     * 交给具体工具处理，保持与已有能力卡兼容。
     */
    private void validateSchemaNode(String path, JsonNode value, JsonNode schema) {
        if (value == null || value.isNull() || schema == null || schema.isMissingNode()) return;
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray()) {
            boolean matched = false;
            for (JsonNode candidate : enumValues) {
                if (candidate.equals(value)) { matched = true; break; }
            }
            if (!matched) throw new IllegalArgumentException("工具参数不满足 enum: " + path);
        }
        String type = schema.path("type").asText("");
        validateJsonType(path, value, type);
        switch (type) {
            case "object" -> {
                JsonNode properties = schema.get("properties");
                boolean allowAdditional = !schema.has("additionalProperties")
                        || schema.get("additionalProperties").asBoolean(true);
                JsonNode required = schema.get("required");
                if (required != null && required.isArray()) {
                    for (JsonNode field : required) {
                        String name = field.asText();
                        if (!value.has(name) || value.get(name).isNull()) {
                            throw new IllegalArgumentException("缺少工具参数: " + path + "." + name);
                        }
                    }
                }
                if (properties != null && properties.isObject()) {
                    var fields = value.fieldNames();
                    while (fields.hasNext()) {
                        String name = fields.next();
                        if (!allowAdditional && !properties.has(name)) {
                            throw new IllegalArgumentException("不允许的工具参数: " + path + "." + name);
                        }
                    }
                    var names = properties.fieldNames();
                    while (names.hasNext()) {
                        String name = names.next();
                        JsonNode child = value.get(name);
                        if (child != null && !child.isNull()) {
                            validateSchemaNode(path + "." + name, child, properties.get(name));
                        }
                    }
                }
            }
            case "array" -> {
                JsonNode minItems = schema.get("minItems");
                if (minItems != null && value.size() < minItems.asInt()) {
                    throw new IllegalArgumentException("工具参数数组过短: " + path);
                }
                JsonNode maxItems = schema.get("maxItems");
                if (maxItems != null && value.size() > maxItems.asInt()) {
                    throw new IllegalArgumentException("工具参数数组过长: " + path);
                }
                JsonNode items = schema.get("items");
                if (items != null) {
                    for (int i = 0; i < value.size(); i++) {
                        validateSchemaNode(path + "[" + i + "]", value.get(i), items);
                    }
                }
            }
            default -> { }
        }
        if (value.isTextual()) {
            JsonNode min = schema.get("minLength");
            if (min != null && value.textValue().length() < min.asInt()) {
                throw new IllegalArgumentException("工具参数字符串过短: " + path);
            }
            JsonNode max = schema.get("maxLength");
            if (max != null && value.textValue().length() > max.asInt()) {
                throw new IllegalArgumentException("工具参数字符串过长: " + path);
            }
            JsonNode pattern = schema.get("pattern");
            if (pattern != null && pattern.isTextual()
                    && !value.textValue().matches(pattern.textValue())) {
                throw new IllegalArgumentException("工具参数格式不匹配: " + path);
            }
        }
        if (value.isNumber()) {
            java.math.BigDecimal number = value.decimalValue();
            JsonNode min = schema.get("minimum");
            if (min != null && number.compareTo(min.decimalValue()) < 0) {
                throw new IllegalArgumentException("工具参数低于最小值: " + path);
            }
            JsonNode max = schema.get("maximum");
            if (max != null && number.compareTo(max.decimalValue()) > 0) {
                throw new IllegalArgumentException("工具参数超过最大值: " + path);
            }
        }
    }

    private void validateJsonType(String fieldName, JsonNode value, String type) {
        boolean valid = switch (type) {
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "", "null" -> true;
            default -> true; // 未知 JSON Schema 扩展交给具体工具处理
        };
        if (!valid) {
            throw new IllegalArgumentException("工具参数类型错误: " + fieldName + " 应为 " + type);
        }
    }

    private void verifyRole(ToolCapabilityCard card, Set<String> roles) {
        if (roles == null || card.allowedRoles().stream().noneMatch(roles::contains)) {
            throw new SecurityException("当前角色无权调用工具: " + card.name());
        }
    }

    private TokenBinding tokenBinding(ToolExecutionContext context) {
        TokenBinding binding = context.tokenBinding();
        if (binding == null) {
            binding = com.vio.vioaiagent.common.RequestContext.getTokenBinding();
        }
        if (binding == null && context.principalPolicy() != null) {
            // 异步 Run 不再依赖 ThreadLocal；策略快照携带的短期 binding
            // 仍会进入 TokenStore 的 scope/配额/撤销校验。
            binding = context.principalPolicy().tokenBinding();
        }
        return binding;
    }

    /**
     * OIDC policy 是 TokenBinding 之外的主体/租户/角色快照，但不能绕过
     * TokenStore：这里先验证快照与能力卡，再由 authorizeTokenScope 使用其
     * 短期 binding 执行令牌 scope、撤销和配额门禁。
     */
    private void verifyPrincipalPolicy(ToolCapabilityCard card, String toolName,
                                       ToolExecutionContext context) {
        PrincipalPolicy policy = context.principalPolicy();
        if (policy == null) return;
        if (!policy.isOidc() || policy.isExpired() || policy.tokenBinding() == null) {
            throw new SecurityException("OIDC 主体策略缺少有效短期绑定");
        }
        if (!policy.allowsTool(toolName)) {
            throw new SecurityException("主体策略未授权调用工具: " + toolName);
        }
        boolean roleMatches = card.allowedRoles().stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(policy.roles()::contains);
        if (!roleMatches) {
            throw new SecurityException("主体角色与工具能力卡不匹配");
        }
        TokenBinding contextBinding = context.tokenBinding();
        if (contextBinding != null && !policy.tokenBinding().equals(contextBinding)) {
            throw new SecurityException("OIDC 主体策略与令牌绑定不一致");
        }
    }

    private String authorizeTokenScope(ToolExecutionContext context, String toolName) {
        TokenBinding binding = tokenBinding(context);
        String missingBinding = missingBindingRejection(binding);
        if (missingBinding != null) return missingBinding;
        if (tokenStore == null) return null;
        return tokenStore.authorizeTool(binding, toolName);
    }

    private String consumeTokenRate(ToolExecutionContext context) {
        TokenBinding binding = tokenBinding(context);
        String missingBinding = missingBindingRejection(binding);
        if (missingBinding != null) return missingBinding;
        if (tokenStore == null) return null;
        return tokenStore.consumeRate(binding);
    }

    /**
     * OIDC JWT 认证与 TokenStore 的短期绑定必须同时存在。除了当前线程的
     * SecurityContext 检查外，Spring 装配时锁定的开关会覆盖异步 Run worker
     * 丢失 SecurityContext 的场景；这样恢复/重试不会回到旧的 fail-open 分支。
     */
    private String missingBindingRejection(TokenBinding binding) {
        if (binding != null) return null;
        if (oidcBindingRequired || oidcAuthenticated()) return oidcBindingRejection();
        return null;
    }

    private boolean oidcAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private String oidcBindingRejection() {
        return "OIDC 主体未绑定 TokenBinding 工具策略，已拒绝工具调用";
    }

    private void verifyPolicies(String toolName, Map<String, Object> arguments) {
        // 策略检查必须递归遍历嵌套对象/数组；只看顶层字段会让
        // {"options":{"url":"http://内网"}} 这类输入绕过 SSRF 围栏。
        verifyPolicyValues(arguments);
        // 对已知网络工具没有 URL 参数时仍允许其内部客户端工作；URL 工具经过上面的围栏。
        if (toolName == null || toolName.isBlank()) {
            throw new SecurityException("工具名称不能为空");
        }
    }

    @SuppressWarnings("unchecked")
    private void verifyPolicyValues(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey())
                        .toLowerCase(Locale.ROOT);
                Object nested = entry.getValue();
                if (nested instanceof Map<?, ?> || nested instanceof Collection<?>) {
                    // 对数组/对象中的每个叶子沿用当前字段名，保持路径、命令
                    // 和 URL 规则覆盖到任意嵌套层级。
                    verifyPolicyValue(key, nested);
                } else {
                    verifyPolicyLeaf(key, nested);
                }
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) verifyPolicyValues(item);
        }
    }

    private void verifyPolicyValue(String key, Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String nestedKey = entry.getKey() == null ? key : String.valueOf(entry.getKey())
                        .toLowerCase(Locale.ROOT);
                Object nested = entry.getValue();
                if (nested instanceof Map<?, ?> || nested instanceof Collection<?>) {
                    verifyPolicyValue(nestedKey, nested);
                } else {
                    verifyPolicyLeaf(nestedKey, nested);
                }
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) verifyPolicyValue(key, item);
        } else {
            verifyPolicyLeaf(key, value);
        }
    }

    private void verifyPolicyLeaf(String key, Object rawValue) {
        String value = rawValue == null ? "" : String.valueOf(rawValue);
        if (key.contains("command")) {
            commandGuard.validate(value);
        }
        if (key.contains("path") || key.equals("filename") || key.equals("file")) {
            pathGuard.validate(value);
        }
        if (isUrlKey(key)) {
            verifyNetworkTarget(value);
        }
    }

    private boolean isUrlKey(String key) {
        return key.equals("url") || key.equals("uri") || key.equals("urls") || key.equals("uris")
                || key.endsWith("url") || key.endsWith("uri")
                || key.endsWith("urls") || key.endsWith("uris");
    }

    private void verifyNetworkTarget(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new SecurityException("仅允许 HTTP(S) 出网");
            }
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || isPrivateHost(host)) {
                throw new SecurityException("禁止访问本机或内网地址");
            }
        } catch (IllegalArgumentException ex) {
            throw new SecurityException("URL 格式无效");
        } catch (UnknownHostException ex) {
            throw new SecurityException("无法解析 URL 主机，已按 fail-closed 拒绝");
        }
    }

    private boolean isPrivateHost(String host) throws UnknownHostException {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.equals("metadata.google.internal")) return true;
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) return true;
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                int first = bytes[0] & 0xff;
                int second = bytes[1] & 0xff;
                // RFC1918、CGNAT、链路本地/云元数据和保留地址。
                if (first == 10 || (first == 172 && second >= 16 && second <= 31)
                        || (first == 192 && second == 168) || (first == 100 && second >= 64 && second <= 127)
                        || (first == 169 && second == 254) || first == 127
                        || (first == 0)) return true;
            } else if (bytes.length == 16) {
                // IPv6 ULA fc00::/7、loopback ::1 和 link-local fe80::/10。
                int first = bytes[0] & 0xff;
                int second = bytes[1] & 0xff;
                if ((first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80)) return true;
                // IPv4-mapped IPv6（例如 ::ffff:127.0.0.1）在部分 JDK/平台上
                // 不会被 isLoopbackAddress/isSiteLocalAddress 识别，显式展开
                // 最后四字节后再次应用 IPv4 私网规则。
                boolean mapped = true;
                for (int i = 0; i < 10; i++) {
                    if (bytes[i] != 0) {
                        mapped = false;
                        break;
                    }
                }
                if (mapped && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
                    int firstV4 = bytes[12] & 0xff;
                    int secondV4 = bytes[13] & 0xff;
                    if (firstV4 == 10 || (firstV4 == 172 && secondV4 >= 16 && secondV4 <= 31)
                            || (firstV4 == 192 && secondV4 == 168)
                            || (firstV4 == 100 && secondV4 >= 64 && secondV4 <= 127)
                            || (firstV4 == 169 && secondV4 == 254) || firstV4 == 127
                            || firstV4 == 0) return true;
                }
            }
        }
        return false;
    }

    private String parameterDigest(Map<String, Object> arguments) {
        try {
            String canonical = objectMapper.writeValueAsString(arguments);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JRE 必备算法；不允许退回 String.hashCode()，否则幂等
            // 键可能因碰撞把不同参数误判为同一次工具调用。
            throw new IllegalStateException("JRE 不支持 SHA-256，无法生成工具参数摘要", ex);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("工具参数无法生成稳定摘要", ex);
        }
    }

    private EffectReservation reserveEffect(ToolCapabilityCard card, String toolName,
                                            ToolExecutionContext context, String parameterDigest,
                                            String approvalId, Instant started) {
        if (effectLedger == null || !card.isWriteLike() || context.idempotencyKey() == null
                || context.idempotencyKey().isBlank()) return EffectReservation.notRequired();
        int stepNo = Math.floorMod(context.idempotencyKey().hashCode(), Integer.MAX_VALUE);
        ToolEffectLedgerEntry candidate = new ToolEffectLedgerEntry(context.runId(), stepNo, toolName,
                card.version(), context.idempotencyKey(), parameterDigest, ToolEffectStatus.STARTED,
                null, null, approvalId, started, null, started);
        try {
            Optional<ToolEffectLedgerEntry> logicalExisting = effectLedger.findByIdempotencyKey(
                    candidate.runId(), candidate.stepNo(), candidate.toolName(), candidate.toolVersion(),
                    candidate.idempotencyKey());
            if (logicalExisting.isPresent()
                    && !logicalExisting.get().parameterHash().equals(candidate.parameterHash())) {
                throw new EffectLedgerUnavailableException("副作用幂等键已绑定其他参数");
            }
            Optional<ToolEffectLedgerEntry> existing = effectLedger.find(candidate.runId(), candidate.stepNo(),
                    candidate.toolName(), candidate.toolVersion(), candidate.idempotencyKey(), candidate.parameterHash());
            if (existing.isPresent()) return EffectReservation.replayed(existing.get());
            if (effectLedger.reserve(candidate)) return EffectReservation.acquired(candidate);
            // reserve=false 代表另一 worker 可能刚刚抢到同一键；必须重新读取并
            // 只接受已落库的事实。过去的 orElse(candidate) 会在读不到时假装拥有执行权。
            Optional<ToolEffectLedgerEntry> afterReserve = effectLedger.find(candidate.runId(), candidate.stepNo(),
                    candidate.toolName(), candidate.toolVersion(), candidate.idempotencyKey(), candidate.parameterHash());
            if (afterReserve.isPresent()) return EffectReservation.replayed(afterReserve.get());
            throw new EffectLedgerUnavailableException("副作用账本无法确认预留结果");
        } catch (RuntimeException ex) {
            log.warn("副作用账本不可用，拒绝写工具调用: tool={}, reason={}", toolName,
                    ex.getClass().getSimpleName());
            if (ex instanceof EffectLedgerUnavailableException unavailable) throw unavailable;
            throw new EffectLedgerUnavailableException("副作用账本不可用，已拒绝写操作", ex);
        }
    }

    private void completeEffect(ToolEffectLedgerEntry started, ToolEffectStatus status,
                                String result, String approvalId) {
        if (effectLedger == null || started == null || started.status() != ToolEffectStatus.STARTED) return;
        try {
            String digest = result == null ? null : parameterDigestText(result);
            effectLedger.complete(started.complete(status, digest, null, approvalId));
        } catch (RuntimeException ex) {
            // 结果已返回但账本结算失败必须留下告警；下一次重放会被
            // STARTED 状态识别为需要人工核对，而不是静默重复副作用。
            log.warn("副作用账本结算失败: tool={}, reason={}", started.toolName(),
                    ex.getClass().getSimpleName());
        }
    }

    private String parameterDigestText(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JRE 不支持 SHA-256", ex);
        }
    }

    /** 账本状态与本次 worker 是否取得执行权的不可变快照。 */
    private record EffectReservation(ToolEffectLedgerEntry entry, boolean acquired) {
        static EffectReservation notRequired() {
            return new EffectReservation(null, false);
        }

        static EffectReservation acquired(ToolEffectLedgerEntry entry) {
            return new EffectReservation(entry, true);
        }

        static EffectReservation replayed(ToolEffectLedgerEntry entry) {
            return new EffectReservation(entry, false);
        }
    }

    /** 对外部调用隐藏底层存储异常，同时让上层明确走 REJECTED 分支。 */
    private static final class EffectLedgerUnavailableException extends RuntimeException {
        private final String reason;

        private EffectLedgerUnavailableException(String reason) {
            super(reason);
            this.reason = reason;
        }

        private EffectLedgerUnavailableException(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }

        private String reason() {
            return reason;
        }
    }

    private String callWithTimeout(ToolCallback callback, String input, long timeoutMs,
                                   ToolCapabilityCard card, String toolName,
                                   ToolExecutionContext context) {
        return executor.call(() -> {
            // This runs in the executor immediately before every real callback,
            // including retries. Caller-side authorization can expire while a
            // task waits for scheduling, HITL, locks or the effect ledger.
            try {
                verifyPrincipalPolicy(card, toolName, context);
                String denial = authorizeTokenScope(context, toolName);
                if (denial != null) throw new SecurityException(denial);
            } catch (RuntimeException ex) {
                throw new ToolAuthorizationException(safeMessage(ex), ex);
            }
        }, () -> callback.call(input), timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS);
    }

    private static final class ToolAuthorizationException extends SecurityException {
        private ToolAuthorizationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private ToolGovernanceResult rejected(String toolName, String reason,
                                          ToolExecutionContext context) {
        return rejected(toolName, reason, context, UUID.randomUUID().toString(), Instant.now());
    }

    private ToolGovernanceResult rejected(String toolName, String reason,
                                          ToolExecutionContext context, String eventId,
                                          Instant started) {
        String message = reason == null || reason.isBlank() ? "安全策略拒绝" : reason;
        emit(context, "tool.rejected", toolName, eventId, Map.of("reason", message));
        audit(context, toolName, Map.of(), "deny", elapsed(started));
        if (metrics != null) metrics.recordToolCall(false);
        return new ToolGovernanceResult(ToolGovernanceResult.Status.REJECTED,
                toolName, message, null, eventId, 0, elapsed(started));
    }

    private AuditResult audit(ToolExecutionContext context, String toolName,
                              Map<String, Object> arguments, String outcome, long duration) {
        try {
            AuditEntry entry = AuditEntry.of(context.traceId(), context.runId(), context.userId(),
                    toolName, parameterMasker.mask(arguments), outcome, "gateway", duration);
            ToolAuditSink sink = toolAuditSink;
            if (sink != null) {
                sink.record(entry);
            } else if (auditLogger != null) {
                // 仅用于旧的程序化/本地构造器；Spring 生产路径会装配 typed sink
                // 或 FailClosedToolAuditSink，不会走到这里。
                auditLogger.record(entry);
            } else {
                throw new com.vio.vioaiagent.security.AuditWriteException("工具审计出口不可用");
            }
            return AuditResult.ok();
        } catch (RuntimeException ex) {
            log.warn("工具审计写入失败: tool={}, reason={}", toolName, ex.getMessage());
            boolean failClosed = toolAuditSink != null && toolAuditSink.failClosedOnError();
            return new AuditResult(false, failClosed);
        }
    }

    private record AuditResult(boolean success, boolean failClosed) {
        private static AuditResult ok() {
            return new AuditResult(true, false);
        }
    }

    private void emit(ToolExecutionContext context, String type, String toolName,
                      String eventId, Map<String, Object> attributes) {
        try {
            context.eventSink().accept(new ToolGovernanceEvent(type, context.runId(), toolName,
                    eventId, Instant.now(), attributes));
        } catch (RuntimeException ex) {
            log.debug("工具事件发布失败: type={}, reason={}", type, ex.getMessage());
        }
    }

    private String redactOutput(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }
        // 只做通用凭据模式脱敏，不截断结构化结果；长度限制由上层上下文策略决定。
        return output.replaceAll("(?i)(api[-_ ]?key|token|password|secret)(\\s*[:=]\\s*)[^,;\\s}]+",
                "$1$2" + ParameterMasker.REDACTED);
    }

    private String idempotencyKey(String toolName, ToolExecutionContext context, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return context.userId() + ":" + context.runId() + ":" + toolName + ":" + key;
    }

    private long elapsed(Instant started) {
        return Duration.between(started, Instant.now()).toMillis();
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? "未知执行错误" : message;
    }

    private record RegisteredTool(ToolCallback callback, ToolCapabilityCard card) {
    }

    /** Mutable only inside the per-key CHM compute callback. */
    private static final class IdempotencyLock {
        private int references;
    }

    @PreDestroy
    public void shutdown() {
        executor.close();
    }

    public BoundedToolExecutor.Snapshot executionSnapshot() { return executor.snapshot(); }
}
