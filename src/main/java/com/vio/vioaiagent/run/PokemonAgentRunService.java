package com.vio.vioaiagent.run;

import com.vio.vioaiagent.observability.AgentMetrics;
import com.vio.vioaiagent.rag.contract.AnswerContract;
import com.vio.vioaiagent.tools.governance.ToolExecutionContext;
import com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder;
import com.vio.vioaiagent.tools.governance.ToolGovernanceEvent;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.security.HitlAuditEvent;
import com.vio.vioaiagent.security.TokenBinding;
import com.vio.vioaiagent.security.PrincipalPolicy;
import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.DurableRunProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M3 唯一 Run 生命周期服务。Controller 只创建、订阅和取消，模型/工具由 RunExecutor 执行。
 */
@Service
@Slf4j
public class PokemonAgentRunService {

    private final RunRepository runRepository;
    private final EventRepository eventRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final RunExecutor executor;
    private final ExecutorService worker;
    private final ScheduledExecutorService scheduler;
    private final AgentMetrics metrics;
    private final HitlManager hitlManager;
    /** 可选的事务 outbox；未装配时保持 v1.0 本地事件页语义。 */
    private final RunOutboxRepository outboxRepository;
    private final CheckpointRepository checkpointRepository;
    private final DurableRunProperties durableRunProperties;
    /** 生产 profile 下由独立事务 Bean 提供事件+outbox 原子写入。 */
    private volatile RunEventOutboxAppender eventOutboxAppender;
    /** 可选的稳定答案投影；Spring 本地/生产分别装配内存/JDBC 实现。 */
    private volatile AnswerProjectionRepository answerProjectionRepository;
    /** 可选的跨实例 HTTP 幂等记录；本地测试未装配时沿用 Run 唯一键。 */
    private volatile IdempotencyStore idempotencyStore;
    private final ConcurrentMap<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    /** 当前进程正在执行的聚合快照；避免超时/取消从 JDBC 重新加载出另一份对象。 */
    private final ConcurrentMap<String, AgentRun> activeRuns = new ConcurrentHashMap<>();
    /** 仅保存当前进程活跃 Run 的令牌绑定引用；密钥不会写入 Run/事件数据库。 */
    private final ConcurrentMap<String, TokenBinding> tokenBindings = new ConcurrentHashMap<>();
    /** OIDC 主体/租户/角色/工具的短期不可变快照；不保存 JWT 或 Secret。 */
    private final ConcurrentMap<String, PrincipalPolicy> principalPolicies = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> leaseTokens = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final Object idempotencyLock = new Object();
    /**
     * 恢复策略显式独立出来，避免在启动扫描里把“有 checkpoint”误当成
     * “可以安全重放”。当前执行器尚未提供 checkpoint-aware replay，因此
     * 策略默认只允许尚未开始执行的 dispatch 检查点继续。
     */
    private final RunRecoveryPolicy recoveryPolicy = new RunRecoveryPolicy();

    /** 由 Spring 注入；避免把幂等存储加入旧测试构造器的必需参数。 */
    @Autowired(required = false)
    public void setIdempotencyStore(IdempotencyStore store) {
        this.idempotencyStore = store;
    }

    @Autowired(required = false)
    public void setEventOutboxAppender(RunEventOutboxAppender appender) {
        this.eventOutboxAppender = appender;
    }

    /**
     * 答案投影是可选扩展点，保持旧测试/嵌入式构造器不变；正式 Spring 上下文
     * 会自动注入 profile 对应的仓储。投影写入失败由完成路径显式失败，避免
     * 发布一个没有可回读 answerId 的终态事件。
     */
    @Autowired(required = false)
    public void setAnswerProjectionRepository(AnswerProjectionRepository repository) {
        this.answerProjectionRepository = repository;
    }

    @Autowired
    public PokemonAgentRunService(RunRepository runRepository,
                                  EventRepository eventRepository,
                                  ConversationRepository conversationRepository,
                                  ChatMessageRepository messageRepository,
                                  ObjectProvider<RunExecutor> executorProvider,
                                  ObjectProvider<AgentMetrics> metricsProvider,
                                  ObjectProvider<HitlManager> hitlProvider,
                                  ObjectProvider<RunOutboxRepository> outboxProvider,
                                  ObjectProvider<CheckpointRepository> checkpointProvider,
                                  ObjectProvider<DurableRunProperties> durablePropertiesProvider) {
        this(runRepository, eventRepository, conversationRepository, messageRepository,
                executorProvider.getIfAvailable(DefaultRunExecutor::new),
                newWorker(), Executors.newScheduledThreadPool(1, runnable -> {
                    Thread thread = new Thread(runnable, "vio-run-timeout");
                    thread.setDaemon(true);
                    return thread;
                }), metricsProvider.getIfAvailable(),
                hitlProvider == null ? null : hitlProvider.getIfAvailable(),
                outboxProvider == null ? null : outboxProvider.getIfAvailable(),
                checkpointProvider == null ? null : checkpointProvider.getIfAvailable(),
                durablePropertiesProvider == null ? new DurableRunProperties()
                        : durablePropertiesProvider.getIfAvailable(DurableRunProperties::new));
    }

    /** 兼容只提供 outbox 的装配签名。 */
    public PokemonAgentRunService(RunRepository runRepository,
                                  EventRepository eventRepository,
                                  ConversationRepository conversationRepository,
                                  ChatMessageRepository messageRepository,
                                  ObjectProvider<RunExecutor> executorProvider,
                                  ObjectProvider<AgentMetrics> metricsProvider,
                                  ObjectProvider<HitlManager> hitlProvider,
                                  ObjectProvider<RunOutboxRepository> outboxProvider) {
        this(runRepository, eventRepository, conversationRepository, messageRepository,
                executorProvider, metricsProvider, hitlProvider, outboxProvider, null, null);
    }

    /** 兼容旧容器装配签名；outbox 未显式提供时保持关闭。 */
    public PokemonAgentRunService(RunRepository runRepository,
                                  EventRepository eventRepository,
                                  ConversationRepository conversationRepository,
                                  ChatMessageRepository messageRepository,
                                  ObjectProvider<RunExecutor> executorProvider,
                                  ObjectProvider<AgentMetrics> metricsProvider,
                                  ObjectProvider<HitlManager> hitlProvider) {
        this(runRepository, eventRepository, conversationRepository, messageRepository,
                executorProvider, metricsProvider, hitlProvider, null, null, null);
    }

    /** 兼容旧的 Spring/测试装配签名；正式容器使用上面的可选 HITL Provider。 */
    public PokemonAgentRunService(RunRepository runRepository,
                                  EventRepository eventRepository,
                                  ConversationRepository conversationRepository,
                                  ChatMessageRepository messageRepository,
                                  ObjectProvider<RunExecutor> executorProvider,
                                  ObjectProvider<AgentMetrics> metricsProvider) {
        this(runRepository, eventRepository, conversationRepository, messageRepository,
                executorProvider, metricsProvider, null);
    }

    /** 测试/嵌入式运行时构造器，可注入确定性执行器和时钟线程池。 */
    public PokemonAgentRunService(RunRepository runRepository,
                                  EventRepository eventRepository,
                                  ConversationRepository conversationRepository,
                                  ChatMessageRepository messageRepository,
                                  RunExecutor executor,
                                  ExecutorService worker,
                                  ScheduledExecutorService scheduler) {
        this(runRepository, eventRepository, conversationRepository, messageRepository,
                executor, worker, scheduler, null, null, null, null, null);
    }

    /**
     * 测试/嵌入式场景可显式注入 checkpoint 和耐久开关；旧构造器保持不变。
     * 生产 Spring 装配仍使用带 ObjectProvider 的构造器。
     */
    public PokemonAgentRunService(RunRepository runRepository,
                                  EventRepository eventRepository,
                                  ConversationRepository conversationRepository,
                                  ChatMessageRepository messageRepository,
                                  RunExecutor executor,
                                  ExecutorService worker,
                                  ScheduledExecutorService scheduler,
                                  CheckpointRepository checkpointRepository,
                                  DurableRunProperties durableRunProperties) {
        this(runRepository, eventRepository, conversationRepository, messageRepository,
                executor, worker, scheduler, null, null, null,
                checkpointRepository, durableRunProperties);
    }

    /**
     * 测试/嵌入式场景显式注入正式 HITL 仓储，用于验证重启后 PENDING 审批
     * 的恢复边界；生产 Spring 装配仍使用上面的 ObjectProvider 构造器。
     */
    PokemonAgentRunService(RunRepository runRepository,
                           EventRepository eventRepository,
                           ConversationRepository conversationRepository,
                           ChatMessageRepository messageRepository,
                           RunExecutor executor,
                           ExecutorService worker,
                           ScheduledExecutorService scheduler,
                           HitlManager hitlManager,
                           CheckpointRepository checkpointRepository,
                           DurableRunProperties durableRunProperties) {
        this(runRepository, eventRepository, conversationRepository, messageRepository,
                executor, worker, scheduler, null, hitlManager, null,
                checkpointRepository, durableRunProperties);
    }

    private PokemonAgentRunService(RunRepository runRepository,
                                   EventRepository eventRepository,
                                   ConversationRepository conversationRepository,
                                   ChatMessageRepository messageRepository,
                                   RunExecutor executor,
                                   ExecutorService worker,
                                   ScheduledExecutorService scheduler,
                                   AgentMetrics metrics,
                                   HitlManager hitlManager,
                                   RunOutboxRepository outboxRepository,
                                   CheckpointRepository checkpointRepository,
                                   DurableRunProperties durableRunProperties) {
        if (runRepository == null || eventRepository == null || conversationRepository == null
                || messageRepository == null || executor == null || worker == null || scheduler == null) {
            throw new IllegalArgumentException("Run Service 依赖不能为空");
        }
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.executor = executor;
        this.worker = worker;
        this.scheduler = scheduler;
        this.metrics = metrics;
        this.hitlManager = hitlManager;
        this.outboxRepository = outboxRepository;
        this.checkpointRepository = checkpointRepository;
        this.durableRunProperties = durableRunProperties == null ? new DurableRunProperties() : durableRunProperties;
    }

    public RunView createRun(CreateRunCommand command) {
        if (command == null) throw new IllegalArgumentException("创建 Run 请求不能为空");
        synchronized (idempotencyLock) {
            String idemScope = createIdempotencyScope(command.userId());
            String requestHash = requestHash(command);
            IdempotencyStore.Claim idemClaim = claimIdempotency(idemScope, command.idempotencyKey(), requestHash);
            if (idemClaim.conflict()) throw new IdempotencyConflictException(idemScope, command.idempotencyKey());
            if (idemClaim.replay()) {
                Optional<AgentRun> replayRun = replayRun(idemClaim.record(), command);
                if (replayRun.isPresent()) return RunView.from(replayRun.get());
                // 102 + IN_PROGRESS 表示另一实例尚未提交最终响应；不要创建第二个 Run。
                throw new IdempotencyInProgressException(idemScope, command.idempotencyKey());
            }
            if (command.idempotencyKey() != null) {
                Optional<AgentRun> existing = runRepository.findByIdempotencyKey(
                        command.userId(), command.idempotencyKey());
                if (existing.isPresent()) {
                    RunView view = RunView.from(existing.get());
                    completeIdempotency(idemScope, command.idempotencyKey(), requestHash, view);
                    return view;
                }
            }
            String conversationId = ensureConversation(command);
            String runId = "run-" + UUID.randomUUID();
            String sessionId = valueOrUuid(command.sessionId());
            String traceId = valueOrUuid(command.traceId());
            Instant deadline = Instant.now().plus(command.timeout());
            AgentRun run = AgentRun.create(runId, sessionId, conversationId, command.userId(), command.prompt(),
                    command.gameReleaseId(), command.battleFormat(), command.rulesetVersionId(),
                    command.language(), command.dlcId(), command.dataReleaseId(), command.knowledgeReleaseId(),
                    command.indexReleaseId(), command.contractVersion(), traceId, command.idempotencyKey(), deadline);
            try {
                runRepository.save(run);
            } catch (RunConflictException conflict) {
                // 两个实例可能同时通过幂等查询；数据库唯一键 + CAS 只允许一个
                // 快照获胜，败者重新读取获胜 Run 并返回同一结果。
                if (command.idempotencyKey() != null) {
                    Optional<AgentRun> winner = runRepository.findByIdempotencyKey(
                            command.userId(), command.idempotencyKey());
                    if (winner.isPresent()) return RunView.from(winner.get());
                }
                throw conflict;
            }
            PrincipalPolicy policy = RequestContext.getPrincipalPolicy();
            if (policy != null) principalPolicies.put(run.id(), policy);
            TokenBinding binding = RequestContext.getTokenBinding();
            if (binding == null && policy != null) binding = policy.tokenBinding();
            if (binding != null) tokenBindings.put(run.id(), binding);
            // 先冻结授权快照再生成 checkpoint/event。只持久化 tenant、角色、
            // 版本、过期时间和 SHA-256 fingerprint；JWT/Secret/原始 binding token
            // 不进入 Run、事件或 checkpoint。
            saveCheckpoint(run, 0, "dispatch", null);
            activeRuns.put(run.id(), run);
            emit(run, AgentEventType.RUN_CREATED, runCreatedPayload(run, policy));
            messageRepository.save(new ChatMessage("message-" + UUID.randomUUID(), conversationId, runId,
                    command.userId(), "user", command.prompt(), Instant.now(), lineagePayload(run)));
            RunView view = RunView.from(run);
            // Submit before finalizing the idempotency response.  If the
            // idempotency store is temporarily unavailable, a persisted Run
            // must still have a worker (or a terminal failure) rather than
            // being stranded in CREATED with an IN_PROGRESS key.
            submit(run, command.timeout());
            completeIdempotency(idemScope, command.idempotencyKey(), requestHash, view);
            return view;
        }
    }

    public Optional<RunView> findRun(String runId, String userId) {
        AgentRun active = activeRuns.get(runId);
        if (active != null && userId != null && userId.equals(active.userId())) {
            // 生产库通过 conversation.deleted_at 软删除隐藏会话及其 Run；不能只
            // 相信进程内 activeRuns，否则删除后的一小段时间仍会暴露已删除资源。
            if (conversationVisible(active, userId)) {
                return Optional.of(RunView.from(active));
            }
            evictDetachedRun(active);
            // 不要再回退到 RunRepository 查询：内存仓储会移除会话，而生产软
            // 删除只隐藏会话；回退都会重新暴露已删除会话的 Run。
            return Optional.empty();
        }
        // 已完成 Run 也必须再次通过会话可见性检查，否则删除/软删除后仍可
        // 用 runId 读到历史资源。
        return runRepository.findByIdForUser(runId, userId)
                .filter(run -> conversationVisible(run, userId))
                .map(RunView::from);
    }

    /**
     * 按用户读取稳定答案投影，供反馈/纠错闭环回读 answerId→run→trace→release。
     * 未装配投影仓储时返回空，不伪造 unknown 血缘。
     */
    public Optional<AnswerProjection> findAnswer(String answerId, String userId) {
        AnswerProjectionRepository repository = answerProjectionRepository;
        if (repository == null || answerId == null || answerId.isBlank()
                || userId == null || userId.isBlank()) return Optional.empty();
        // Answer projection 是 append-only 证据，但不应绕过会话删除语义继续
        // 对原用户可见。先按 answer/user 取投影，再通过同一会话可见性门禁；
        // 这样内存和 JDBC 实现都一致，后台仍可用 repository.findById 做审计回读。
        return repository.findByIdForUser(answerId.trim(), userId.trim())
                .filter(answer -> conversationRepository
                        .findByIdForUser(answer.conversationId(), userId.trim())
                        .isPresent());
    }

    public EventPage events(String runId, String userId, long afterEventId) {
        AgentRun run = ownedRun(runId, userId);
        long retainedFrom = eventRepository.retainedFrom(runId);
        if (afterEventId < retainedFrom - 1) {
            throw new EventCursorExpiredException(retainedFrom);
        }
        List<AgentEvent> events = eventRepository.findAfter(runId, afterEventId);
        return new EventPage(events, eventRepository.lastEventId(runId), run.status().isTerminal());
    }

    public CancelResult cancel(String runId, String userId) {
        return cancel(runId, userId, null, null);
    }

    /** 带 Idempotency-Key 的取消；重复请求返回第一次的稳定结果。 */
    public CancelResult cancel(String runId, String userId, String idempotencyKey) {
        return cancel(runId, userId, idempotencyKey, null);
    }

    /**
     * 带规范化原因的取消。原因属于幂等请求语义的一部分：同一
     * Idempotency-Key 携带不同原因必须返回冲突，不能回放另一请求的结果。
     */
    public CancelResult cancel(String runId, String userId, String idempotencyKey, String reason) {
        AgentRun run = ownedRun(runId, userId);
        String scope = "run:cancel:" + safe(userId) + ":" + safe(runId);
        String normalizedReason = normalizeCancelReason(reason);
        String requestHash = hashValue(scope + "|" + safe(idempotencyKey) + "|" + normalizedReason);
        IdempotencyStore.Claim claim = claimIdempotency(scope, idempotencyKey, requestHash);
        if (claim.conflict()) {
            // 滚动升级兼容：v1.0 无 body 取消只把 scope/key 写入摘要。
            // 仅对本次原始输入为空且既有记录确实使用旧 hash、且已完成
            // 200 响应时回放；显式 reason 绝不能降级到旧语义。
            if (reason == null || reason.isBlank()) {
                String legacyHash = hashValue(scope + "|" + safe(idempotencyKey));
                Optional<IdempotencyRecord> previous = claim.record();
                if (previous.isPresent() && previous.get().requestHash().equals(legacyHash)
                        && previous.get().responseStatus() == 200
                        && previous.get().validAt(Instant.now())) {
                    Optional<CancelResult> replay = replayCancel(previous);
                    if (replay.isPresent()) return replay.get();
                }
            }
            throw new IdempotencyConflictException(scope, idempotencyKey);
        }
        if (claim.replay()) {
            return replayCancel(claim.record()).orElseThrow(() ->
                    new IdempotencyInProgressException(scope, idempotencyKey));
        }
        synchronized (run) {
            if (run.status().isTerminal()) {
                CancelResult result = new CancelResult(runId, run.status(), false, terminalEventId(runId));
                completeCancel(scope, idempotencyKey, requestHash, result);
                return result;
            }
            if (!run.cancel()) {
                CancelResult result = new CancelResult(runId, run.status(), false, terminalEventId(runId));
                completeCancel(scope, idempotencyKey, requestHash, result);
                return result;
            }
            try {
                runRepository.save(run);
            } catch (RunConflictException conflict) {
                refreshAfterConflict(run, conflict);
                throw conflict;
            }
            emitLocked(run, AgentEventType.RUN_CANCELLED, Map.of("reason", normalizedReason));
            FutureTask<Void> task = tasks.get(runId);
            if (task != null) task.cancel(true);
            CancelResult result = new CancelResult(runId, run.status(), true, eventRepository.lastEventId(runId));
            completeCancel(scope, idempotencyKey, requestHash, result);
            return result;
        }
    }

    /** 审批决定后的 Run 收敛：批准恢复执行，拒绝进入唯一 FAILED 终态。 */
    public RunView resolveApproval(String runId, String userId, boolean approved, String reason) {
        AgentRun run = ownedRun(runId, userId);
        synchronized (run) {
            if (run.status().isTerminal()) return RunView.from(run);
            if (run.status() != AgentRunStatus.WAITING_APPROVAL) {
                throw new IllegalStateException("Run 当前不在 WAITING_APPROVAL 状态");
            }
            // Keep the Run event boundary consistent with HITL audit records.
            // The controller accepts user supplied text; writing it verbatim
            // would allow CR/LF log injection and unbounded/credential-like
            // values to be persisted in the event stream.
            String safeReason = HitlAuditEvent.sanitizeReason(reason);
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("approved", approved);
            decision.put("reason", safeReason == null ? "" : safeReason);
            if (!approved) {
                run.fail();
                try {
                    runRepository.save(run);
                } catch (RunConflictException conflict) {
                    refreshAfterConflict(run, conflict);
                    throw conflict;
                }
                // 只有状态 CAS 成功后才发布 resolution，避免留下无法解释的幻影事件。
                // APPROVAL_RESOLVED 必须位于 RUN_FAILED 之前；emitLocked 对终态
                // 默认拒绝普通事件，因此这里显式允许该唯一的审批结论事件。
                emitLocked(run, AgentEventType.APPROVAL_RESOLVED, decision);
                emitLocked(run, AgentEventType.RUN_FAILED,
                        Map.of("errorType", "APPROVAL_DENIED", "reason",
                                safeReason == null || safeReason.isBlank()
                                        ? "用户拒绝工具调用" : safeReason));
                return RunView.from(run);
            }
            // 保持 WAITING_APPROVAL，交由恢复任务的 execute() 原子迁移到 RUNNING，
            // 避免在审批线程和 worker 线程之间产生重复 RUN_STARTED 或丢失执行。
            try {
                runRepository.save(run);
            } catch (RunConflictException conflict) {
                refreshAfterConflict(run, conflict);
                throw conflict;
            }
            emitLocked(run, AgentEventType.APPROVAL_RESOLVED, decision);
            resumeWhenIdle(run);
            return RunView.from(run);
        }
    }

    public List<ChatMessage> messages(String conversationId, String userId) {
        requireConversation(conversationId, userId);
        return messageRepository.findByConversationForUser(conversationId, userId);
    }

    public void deleteConversation(String conversationId, String userId) {
        // 与 createRun 共用同一把进程内锁，避免删除和新建 Run 交错，导致
        // 新 Run 在会话删除后又残留在 activeRuns 中。
        synchronized (idempotencyLock) {
            requireConversation(conversationId, userId);
            cancelAndDetachActiveRuns(conversationId, userId);
            messageRepository.deleteByConversation(conversationId);
            conversationRepository.deleteForUser(conversationId, userId);
        }
    }

    private String ensureConversation(CreateRunCommand command) {
        if (command.conversationId() != null) {
            Optional<Conversation> existing = conversationRepository.findByIdForUser(command.conversationId(), command.userId());
            if (existing.isPresent()) return existing.get().id();
            // 已存在但属于其他用户时统一按资源不存在处理，不能覆盖其会话。
            if (conversationRepository.findById(command.conversationId()).isPresent()) {
                throw new RunNotFoundException(command.conversationId());
            }
            Conversation created = new Conversation(command.conversationId(), command.userId(),
                    title(command.prompt()), Instant.now(), Instant.now());
            conversationRepository.save(created);
            // save 可能因并发的同 ID 创建而被 JDBC 的 ON CONFLICT DO NOTHING
            // 静默忽略；重新按用户读取，避免把其他用户的会话绑定到本次 Run。
            if (conversationRepository.findByIdForUser(created.id(), command.userId()).isEmpty()) {
                throw new RunConflictException(created.id(), 0L);
            }
            return created.id();
        }
        Conversation created = new Conversation("conversation-" + UUID.randomUUID(), command.userId(),
                title(command.prompt()), Instant.now(), Instant.now());
        conversationRepository.save(created);
        if (conversationRepository.findByIdForUser(created.id(), command.userId()).isEmpty()) {
            throw new RunConflictException(created.id(), 0L);
        }
        return created.id();
    }

    private void requireConversation(String conversationId, String userId) {
        if (conversationRepository.findByIdForUser(conversationId, userId).isEmpty()) {
            throw new RunNotFoundException(conversationId);
        }
    }

    /**
     * 注册并提交唯一 worker。审批接口可能被并发重放；同一 Run 在任意时刻
     * 只允许一个 FutureTask 占有执行权，避免同一个有副作用工具被执行两次。
     */
    private boolean submit(AgentRun run, Duration timeout) {
        AtomicReference<FutureTask<Void>> owner = new AtomicReference<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            execute(run, owner.get());
            return null;
        });
        owner.set(task);
        while (true) {
            FutureTask<Void> existing = tasks.get(run.id());
            if (existing != null && !existing.isDone()) return false;
            boolean registered = existing == null
                    ? tasks.putIfAbsent(run.id(), task) == null
                    : tasks.replace(run.id(), existing, task);
            if (registered) break;
        }
        activeRuns.putIfAbsent(run.id(), run);
        try {
            worker.execute(task);
        } catch (RuntimeException ex) {
            tasks.remove(run.id(), task);
            failIfActive(run, ex);
            throw ex;
        }
        scheduler.schedule(() -> timeout(run.id()),
                Math.max(1L, remainingTimeout(run).toMillis()), TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * 极快审批可能发生在原 worker 尚未从 WAITING_APPROVAL 返回时。等待旧
     * worker 释放所有权后再恢复；并发审批重放只会看到同一个已注册任务。
     */
    private void resumeWhenIdle(AgentRun run) {
        if (run.status().isTerminal() || run.status() != AgentRunStatus.WAITING_APPROVAL) return;
        if (submit(run, remainingTimeout(run))) return;
        scheduler.schedule(() -> {
            synchronized (run) {
                if (run.status() == AgentRunStatus.WAITING_APPROVAL) resumeWhenIdle(run);
            }
        }, 25L, TimeUnit.MILLISECONDS);
    }

    private void execute(AgentRun run, FutureTask<Void> ownerTask) {
        Instant startedAt = Instant.now();
        boolean metricsActive = false;
        String leaseOwner = "worker-" + UUID.randomUUID();
        String leaseToken = UUID.randomUUID().toString();
        boolean leaseAcquired = acquireLease(run, leaseOwner, leaseToken);
        if (!leaseAcquired) {
            log.info("Run 租约被其他 worker 持有，跳过本地执行: runId={}", run.id());
            return;
        }
        try {
            // 取消/超时可能先于 worker 获得执行权；仍需进入 finally 清理注册表。
            // 重启恢复可能拿到已经持久化为 RUNNING 的快照；此时不重复发起状态迁移。
            if (run.status() != AgentRunStatus.RUNNING
                    && !transition(run, AgentRunStatus.RUNNING, Map.of("status", "RUNNING"))) return;
            saveCheckpoint(run, 0, "execute-answer", leaseOwner);
            emit(run, AgentEventType.CONTEXT_CONFIRMED, contextPayload(run));
            // N5 的 ContextCard 是已登记的结构化事件；它允许空值，但不能
            // 缺键，前端据此稳定区分“未提供上下文”和“事件损坏”。
            emit(run, AgentEventType.CONTEXT_CARD, contextPayload(run));
            emit(run, AgentEventType.ROUTE_SELECTED, Map.of("route", "controlled-agent"));
            if (metrics != null) {
                metrics.recordAgentExecution();
                metrics.incrementActiveAgents();
                metricsActive = true;
            }
            PrincipalPolicy policy = principalPolicies.get(run.id());
            Set<String> roles = policy == null ? Set.of("trainer") : policy.roles();
            TokenBinding binding = tokenBindings.get(run.id());
            if (binding == null && policy != null) binding = policy.tokenBinding();
            ToolExecutionContext runContext = new ToolExecutionContext(
                    run.userId(), run.id(), roles, run.traceId(),
                    run.idempotencyKey(), event -> onToolEvent(run, event), binding, policy);
            ToolExecutionContextHolder.set(runContext);
            AnswerExecution execution = executeAnswer(run);
            String answer = execution.answer();
            saveCheckpoint(run, 1, "commit-answer", leaseOwner);
            // 当前 RunExecutor 以完整答案字符串返回；将答案首次可用时间记录为
            // TTFT 近似值，待真正 token streaming executor 接入后再替换为首 token 时间。
            if (metrics != null) {
                metrics.recordTimeToFirstToken(Duration.between(startedAt, Instant.now()).toMillis());
            }
            synchronized (run) {
                if (run.status().isTerminal()) return;
                // HITL 网关已发出 approval.required；当前执行器暂停，等待审批端点恢复。
                if (run.status() == AgentRunStatus.WAITING_APPROVAL) return;
                // answerId 按 Run 稳定派生，重试/恢复不会创建第二个答案。若已
                // 装配投影仓储，先保存不可变快照，再追加终态事件；仓储故障会让
                // 当前 Run 走 fail-closed，而不是产生无法追溯的完成结果。
                String answerId = answerId(run);
                AnswerLineage lineage = execution.contract() == null
                        ? AnswerLineage.empty()
                        : (executor instanceof ContractAwareRunExecutor contractExecutor
                        ? contractExecutor.answerLineage(new AgentRunContext(run), execution.contract())
                        : AnswerLineage.empty());
                AnswerProjection projection = AnswerProjection.from(run, execution.contract(), answerId,
                        Instant.now(), lineage);
                if (answerProjectionRepository != null) {
                    projection = answerProjectionRepository.save(projection);
                }
                // 先发布仍属于 RUNNING 阶段的答案/用量，再以 CAS 提交终态。
                // 这样事件流不会出现 answer.delta 位于 run.completed 之后；若
                // CAS 失败，另一实例的终态事件仍会排在这些已发布事件之后，旧
                // worker 不会再追加自己的终态。
                if (answer != null && !answer.isBlank()) {
                    Map<String, Object> answerDelta = new LinkedHashMap<>();
                    answerDelta.put("text", answer);
                    answerDelta.put("answerId", projection.answerId());
                    answerDelta.put("lineageStatus", projection.lineageStatus().name());
                    emitLocked(run, AgentEventType.ANSWER_DELTA, answerDelta);
                    messageRepository.save(new ChatMessage("message-" + UUID.randomUUID(), run.conversationId(),
                            run.id(), run.userId(), "assistant", answer, Instant.now(), lineagePayload(run)));
                }
                // ANSWER_COMPLETED 位于 RUN_COMPLETED 之前，保证非终态事件仍
                // 可追加；它携带投影的稳定 answerId 和完整/部分血缘状态。
                Map<String, Object> answerCompleted = new LinkedHashMap<>(projection.lineagePayload());
                answerCompleted.put("status", projection.status());
                answerCompleted.put("answerDigest", projection.answerDigest());
                emitLocked(run, AgentEventType.ANSWER_COMPLETED, answerCompleted);
                emitLocked(run, AgentEventType.USAGE, Map.of("executor", executor.getClass().getSimpleName()));
                run.complete();
                try {
                    runRepository.save(run);
                } catch (RunConflictException conflict) {
                    refreshAfterConflict(run, conflict);
                    throw conflict;
                }
                Map<String, Object> completed = new LinkedHashMap<>();
                completed.put("status", run.status().name());
                if (execution.contract() != null) {
                    completed.put("answer", execution.contract().answer());
                    completed.put("contractStatus", execution.contract().status().name());
                    completed.put("citationCount", execution.contract().citations().size());
                    completed.put("citations", execution.contract().citations().stream()
                            .map(this::citationPayload).toList());
                    completed.put("limitations", execution.contract().limitations());
                }
                completed.putAll(projection.lineagePayload());
                emitLocked(run, AgentEventType.RUN_COMPLETED, completed);
                saveCheckpoint(run, 2, "finished", leaseOwner);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            cancelIfActive(run, "interrupted");
        } catch (RunConflictException ex) {
            // 另一实例已经提交了更新；旧 worker 不得再用旧快照写 FAILED。
            log.info("Run 已由其他实例推进，停止旧 worker: runId={}, expectedVersion={}",
                    run.id(), run.version());
        } catch (Exception ex) {
            failIfActive(run, ex);
        } finally {
            ToolExecutionContextHolder.clear();
            if (metrics != null && metricsActive) {
                metrics.decrementActiveAgents();
                // WAITING_APPROVAL 不是终态；只在最终完成/失败/取消/超时时记录一次 Run。
                if (run.status().isTerminal()) {
                    metrics.recordRun(run.status().name(), Duration.between(startedAt, Instant.now()).toMillis());
                }
            }
            if (ownerTask == null) {
                tasks.remove(run.id());
            } else {
                tasks.remove(run.id(), ownerTask);
            }
            // 审批暂停期间保留 activeRuns，避免审批线程与旧 worker 的 finally
            // 竞态导致恢复任务失去当前快照；恢复/终态后再移除。
            if (run.status().isTerminal() || run.status() != AgentRunStatus.WAITING_APPROVAL) {
                activeRuns.remove(run.id(), run);
                tokenBindings.remove(run.id());
                principalPolicies.remove(run.id());
            }
            releaseLease(run, leaseOwner, leaseToken);
        }
    }

    /**
     * 在生命周期服务内统一发出检索/引用事件；旧文本执行器仍沿用原路径。
     */
    private AnswerExecution executeAnswer(AgentRun run) throws Exception {
        if (!(executor instanceof ContractAwareRunExecutor contractExecutor)) {
            return new AnswerExecution(executor.execute(new AgentRunContext(run)), null);
        }
        boolean grounded = contractExecutor.usesKnowledgeGrounding();
        if (grounded) {
            emit(run, AgentEventType.RETRIEVAL_STARTED,
                    Map.of("gameReleaseId", safe(run.gameReleaseId()),
                            "battleFormat", safe(run.battleFormat()),
                            "rulesetVersionId", safe(run.rulesetVersionId())));
        }
        AnswerContract contract = contractExecutor.executeContract(new AgentRunContext(run));
        if (contract == null) {
            throw new IllegalStateException("结构化执行器返回了空回答契约");
        }
        if (grounded) {
            if (metrics != null) {
                metrics.recordRetrieval(!contract.citations().isEmpty());
            }
            Map<String, Object> finished = new LinkedHashMap<>();
            finished.put("status", contract.status().name());
            finished.put("citationCount", contract.citations().size());
            finished.put("hasEvidence", !contract.citations().isEmpty());
            emit(run, AgentEventType.RETRIEVAL_FINISHED, finished);
            for (var citation : contract.citations()) {
                Map<String, Object> payload = citationPayload(citation);
                emit(run, AgentEventType.CITATION, payload);
                // 旧 citation 事件保留兼容性；只有来源、证据和三层 release
                // 都齐全时才追加 RetrievalCitationCard，避免结构化卡片把
                // 不可追溯/过期引用误报为可展示证据。
                if (completeCitation(payload, run)) {
                    emit(run, AgentEventType.RETRIEVAL_CITATION_CARD, payload);
                }
            }
        }
        return new AnswerExecution(contract.answer(), contract);
    }

    private record AnswerExecution(String answer, AnswerContract contract) { }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 取消原因是用户可控文本，会进入事件流和幂等摘要；在唯一入口统一做
     * 脱敏、控制字符清理、trim 与长度约束，避免不同调用方产生不同语义。
     */
    private String normalizeCancelReason(String reason) {
        if (reason == null || reason.isBlank()) return "user_requested";
        String sanitized = HitlAuditEvent.sanitizeReason(reason);
        if (sanitized == null) return "user_requested";
        StringBuilder cleaned = new StringBuilder(sanitized.length());
        sanitized.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)) cleaned.append(' ');
            else cleaned.appendCodePoint(codePoint);
        });
        String normalized = cleaned.toString().trim();
        if (normalized.isBlank()) return "user_requested";
        if (normalized.length() > 512) {
            throw new IllegalArgumentException("cancel reason 长度不能超过 512");
        }
        return normalized;
    }

    /** Stable, non-secret answer identifier derived from the owning Run. */
    private String answerId(AgentRun run) {
        return "answer-" + run.id();
    }

    private void putText(Map<String, Object> payload, String key, String value) {
        payload.put(key, value == null ? "" : value);
    }

    private Map<String, Object> citationPayload(com.vio.vioaiagent.rag.contract.Citation citation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (citation == null) return payload;
        putText(payload, "documentId", citation.documentId());
        putText(payload, "documentVersion", citation.documentVersion());
        putText(payload, "sourceTitle", citation.sourceTitle());
        putText(payload, "sourceUrl", citation.sourceUrl());
        putText(payload, "chunkId", citation.chunkId());
        putText(payload, "evidenceText", citation.evidenceText());
        payload.put("retrievalScore", citation.retrievalScore());
        return payload;
    }

    private boolean completeCitation(Map<String, Object> payload, AgentRun run) {
        if (payload == null || run == null) return false;
        for (String key : List.of("sourceUrl", "evidenceText", "documentVersion", "chunkId")) {
            Object value = payload.get(key);
            if (value == null || String.valueOf(value).isBlank()) return false;
        }
        if (!httpSource(String.valueOf(payload.get("sourceUrl")))) return false;
        return !isBlank(run.dataReleaseId()) && !isBlank(run.knowledgeReleaseId())
                && !isBlank(run.indexReleaseId());
    }

    private boolean httpSource(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim());
    }

    private void onToolEvent(AgentRun run, ToolGovernanceEvent event) {
        if (event == null) return;
        AgentEventType type = switch (event.eventType()) {
            case "approval.required" -> AgentEventType.APPROVAL_REQUIRED;
            case "tool.started" -> AgentEventType.TOOL_STARTED;
            case "tool.completed", "tool.finished", "tool.failed", "tool.rejected", "tool.deduplicated" -> AgentEventType.TOOL_FINISHED;
            default -> null;
        };
        if (type == null) return;
        synchronized (run) {
            if (run.status().isTerminal()) return;
            if (type == AgentEventType.APPROVAL_REQUIRED
                    && run.status() == AgentRunStatus.RUNNING) {
                run.transitionTo(AgentRunStatus.WAITING_APPROVAL);
                try {
                    runRepository.save(run);
                } catch (RunConflictException conflict) {
                    refreshAfterConflict(run, conflict);
                    return;
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolName", event.toolName());
            payload.put("governanceEventId", event.eventId());
            payload.putAll(event.attributes());
            emitLocked(run, type, payload);
        }
    }

    private void timeout(String runId) {
        AgentRun run = activeRuns.get(runId);
        if (run == null) run = runRepository.findById(runId).orElse(null);
        if (run == null) return;
        synchronized (run) {
            if (run.status().isTerminal() || !run.timeout()) return;
            try {
                runRepository.save(run);
            } catch (RunConflictException conflict) {
                refreshAfterConflict(run, conflict);
                return;
            }
            emitLocked(run, AgentEventType.RUN_TIMED_OUT, Map.of("reason", "deadline_exceeded"));
            FutureTask<Void> task = tasks.get(runId);
            if (task != null) task.cancel(true);
        }
    }

    private void cancelIfActive(AgentRun run, String reason) {
        synchronized (run) {
            if (run.status().isTerminal() || !run.cancel()) return;
            try {
                runRepository.save(run);
            } catch (RunConflictException conflict) {
                refreshAfterConflict(run, conflict);
                log.info("Run 取消被其他实例抢先处理: runId={}", run.id());
                return;
            }
            emitLocked(run, AgentEventType.RUN_CANCELLED, Map.of("reason", reason));
        }
    }

    private void failIfActive(AgentRun run, Exception ex) {
        synchronized (run) {
            if (run.status().isTerminal() || !run.fail()) return;
            try {
                runRepository.save(run);
            } catch (RunConflictException conflict) {
                refreshAfterConflict(run, conflict);
                log.info("Run 失败状态已由其他实例处理: runId={}", run.id());
                return;
            }
            emitLocked(run, AgentEventType.RUN_FAILED, Map.of("errorType", ex.getClass().getSimpleName()));
            if (metrics != null) metrics.recordError();
            log.warn("Run 执行失败 runId={}, errorType={}", run.id(), ex.getClass().getSimpleName());
        }
    }

    private boolean transition(AgentRun run, AgentRunStatus target, Map<String, Object> payload) {
        synchronized (run) {
            if (!run.transitionTo(target)) return false;
            try {
                runRepository.save(run);
            } catch (RunConflictException conflict) {
                refreshAfterConflict(run, conflict);
                return false;
            }
            if (target == AgentRunStatus.RUNNING) emitLocked(run, AgentEventType.RUN_STARTED, payload);
            return true;
        }
    }

    private void emit(AgentRun run, AgentEventType type, Map<String, Object> payload) {
        synchronized (run) { emitLocked(run, type, payload); }
    }

    private void emitLocked(AgentRun run, AgentEventType type, Map<String, Object> payload) {
        // 取消/超时一旦把本地快照推进到终态，旧 worker 不得再追加普通事件。
        // 终态事件本身由仓储的唯一约束保证单例，重复调用直接短路。
        if (run.status().isTerminal() && !type.isTerminal()
                && type != AgentEventType.APPROVAL_RESOLVED) return;
        if (type.isTerminal() && eventRepository.hasTerminalEvent(run.id())) return;
        // 事件是跨服务回放/审计的主要载体；无论调用方是否显式提供上下文，
        // 都附带 Run 创建时冻结的版本键。调用方 payload 优先，允许完成事件
        // 使用执行器实际回读的 release 覆盖 Run 创建时的空值，但不会写入 unknown。
        Map<String, Object> enrichedPayload = new LinkedHashMap<>(lineagePayload(run));
        if (payload != null) enrichedPayload.putAll(payload);
        // 新增领域卡片事件必须通过机器契约校验；在补入 Run 冻结上下文后
        // 再校验，允许旧执行器只提供业务字段而由 Run 补齐版本键。
        AgentEventContractRegistry.validate(type, enrichedPayload);
        long next = eventRepository.lastEventId(run.id()) + 1;
        if (eventOutboxAppender != null) {
            // JdbcRunEventOutboxAppender 会在一个数据库事务中重新分配 eventId，
            // 并同步写入 outbox；这里的 next 只作为内存/测试实现的提示。
            eventOutboxAppender.append(run, type, next, enrichedPayload);
            return;
        }
        // next 仅作为内存仓储的快速路径提示；生产 JDBC 会在 agent_run 行锁内
        // 重新分配序号，避免多个实例同时读取 MAX 后写入同一 event_id。
        AgentEvent appended = eventRepository.appendNext(
                AgentEvent.of(next, type, run.sessionId(), run.id(), run.traceId(), enrichedPayload));
        if (outboxRepository != null) {
            // outboxId 由 run/event 组成，重复发布只会命中同一条记录。生产 JDBC
            // 仓储应在同一事务中调用 event + outbox；当前服务保留该边界并让失败显式冒泡。
            String outboxId = run.id() + ":" + appended.eventId();
            outboxRepository.enqueue(RunOutboxMessage.pending(outboxId, run.id(), appended.eventId(),
                    appended.type().wireName(), appended.payload()));
        }
    }

    private long terminalEventId(String runId) {
        return eventRepository.hasTerminalEvent(runId) ? eventRepository.lastEventId(runId) : 0;
    }

    private AgentRun ownedRun(String runId, String userId) {
        AgentRun active = activeRuns.get(runId);
        if (active != null && userId != null && userId.equals(active.userId())) {
            if (conversationVisible(active, userId)) return active;
            // 删除会话后，活动快照只是本地孤儿；先撤销本地可见性，再按
            // 统一的 404 语义返回，避免泄露 Run 是否仍存在。
            evictDetachedRun(active);
            throw new RunNotFoundException(runId);
        }
        AgentRun persisted = runRepository.findByIdForUser(runId, userId)
                .orElseThrow(() -> new RunNotFoundException(runId));
        if (!conversationVisible(persisted, userId)) {
            throw new RunNotFoundException(runId);
        }
        return persisted;
    }

    /**
     * 取消并移除指定会话下的本进程活动 Run。无论内存仓储物理移除还是生产
     * 软删除隐藏，都必须先停止本地 worker，避免删除后继续写入消息或事件。
     */
    private void cancelAndDetachActiveRuns(String conversationId, String userId) {
        for (AgentRun run : List.copyOf(activeRuns.values())) {
            if (!conversationId.equals(run.conversationId()) || !userId.equals(run.userId())) continue;
            synchronized (run) {
                if (!run.status().isTerminal() && run.cancel()) {
                    try {
                        runRepository.save(run);
                        emitLocked(run, AgentEventType.RUN_CANCELLED,
                                Map.of("reason", "conversation_deleted"));
                    } catch (RunConflictException conflict) {
                        // 其他实例可能已经推进了 Run；删除会话仍应继续，
                        // 但绝不能把旧快照重新写回数据库。
                        refreshAfterConflict(run, conflict);
                    }
                }
                FutureTask<Void> task = tasks.remove(run.id());
                if (task != null) task.cancel(true);
                // 即使冲突刷新过另一份快照，删除会话后该 Run 也不再属于
                // 可见资源；无条件清掉本进程索引，避免保留孤儿快照。
                activeRuns.remove(run.id());
                tokenBindings.remove(run.id());
                principalPolicies.remove(run.id());
            }
        }
    }

    private boolean conversationVisible(AgentRun run, String userId) {
        return run != null && userId != null
                && conversationRepository.findByIdForUser(run.conversationId(), userId).isPresent();
    }

    private void evictDetachedRun(AgentRun run) {
        if (run == null) return;
        FutureTask<Void> task = tasks.remove(run.id());
        if (task != null) task.cancel(true);
        activeRuns.remove(run.id(), run);
        tokenBindings.remove(run.id());
        principalPolicies.remove(run.id());
    }

    private Duration remainingTimeout(AgentRun run) {
        if (run.deadlineAt() == null) return Duration.ofMinutes(2);
        long millis = Duration.between(Instant.now(), run.deadlineAt()).toMillis();
        return Duration.ofMillis(Math.max(1L, millis));
    }

    private boolean acquireLease(AgentRun run, String owner, String token) {
        if (checkpointRepository == null || !durableRunProperties.durableResume()) return true;
        Duration ttl = Duration.ofSeconds(durableRunProperties.leaseTtlSeconds());
        Optional<RunLease> lease = checkpointRepository.acquireLease(run.id(), owner, token, ttl);
        if (lease.isEmpty()) return false;
        leaseTokens.put(run.id(), token);
        long interval = Math.max(1L, Math.min(durableRunProperties.heartbeatSeconds(),
                Math.max(1L, durableRunProperties.leaseTtlSeconds() / 2)));
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                () -> heartbeat(run, owner, token), interval, interval, TimeUnit.SECONDS);
        heartbeatTasks.put(run.id(), heartbeat);
        return true;
    }

    private void heartbeat(AgentRun run, String owner, String token) {
        if (run.status().isTerminal() || checkpointRepository == null) return;
        try {
            if (checkpointRepository.heartbeat(run.id(), owner, token,
                    Duration.ofSeconds(durableRunProperties.leaseTtlSeconds())).isEmpty()) {
                log.warn("Run 租约心跳失败，停止本地 worker: runId={}", run.id());
                FutureTask<Void> task = tasks.get(run.id());
                if (task != null) task.cancel(true);
            }
        } catch (RuntimeException ex) {
            log.warn("Run 租约心跳异常，停止本地 worker: runId={}, reason={}", run.id(), ex.getMessage());
            FutureTask<Void> task = tasks.get(run.id());
            if (task != null) task.cancel(true);
        }
    }

    private void releaseLease(AgentRun run, String owner, String token) {
        ScheduledFuture<?> heartbeat = heartbeatTasks.remove(run.id());
        if (heartbeat != null) heartbeat.cancel(false);
        leaseTokens.remove(run.id(), token);
        if (checkpointRepository != null && durableRunProperties.durableResume()) {
            try {
                checkpointRepository.releaseLease(run.id(), owner, token);
            } catch (RuntimeException ex) {
                log.warn("释放 Run 租约失败（将由过期回收处理）: runId={}, reason={}", run.id(), ex.getMessage());
            }
        }
    }

    /** 保存一个新版本 checkpoint；失败时在启用耐久恢复的环境中直接阻止继续执行。 */
    private void saveCheckpoint(AgentRun run, int stepNo, String nextAction, String leaseOwner) {
        if (checkpointRepository == null) return;
        try {
            AgentCheckpoint latest = checkpointRepository.findLatest(run.id()).orElse(null);
            long version = latest == null ? 0L : latest.version();
            AgentCheckpoint checkpoint = new AgentCheckpoint(run.id(), stepNo,
                    executor.getClass().getName(), contextHash(run), eventRepository.lastEventId(run.id()),
                    nextAction, leaseOwner,
                    leaseOwner == null ? null : Instant.now().plusSeconds(durableRunProperties.leaseTtlSeconds()),
                    leaseOwner == null ? null : Instant.now(), Instant.now(), version);
            checkpointRepository.save(checkpoint);
        } catch (RuntimeException ex) {
            if (durableRunProperties.durableResume()) {
                throw new IllegalStateException("耐久 checkpoint 写入失败，安全停止 Run", ex);
            }
            log.debug("本地 checkpoint 写入失败，保持 v1.0 兼容路径: runId={}, reason={}", run.id(), ex.getMessage());
        }
    }

    private String contextHash(AgentRun run) {
        PrincipalPolicy policy = principalPolicies.get(run.id());
        String value = String.join("|", safe(run.gameReleaseId()), safe(run.battleFormat()),
                safe(run.rulesetVersionId()), safe(run.language()), safe(run.dlcId()),
                safe(run.dataReleaseId()), safe(run.knowledgeReleaseId()), safe(run.indexReleaseId()),
                safe(run.contractVersion()), run.prompt(), policy == null ? "" : policy.fingerprint());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    private IdempotencyStore.Claim claimIdempotency(String scope, String key, String requestHash) {
        if (idempotencyStore == null || key == null || key.isBlank()) {
            return IdempotencyStore.Claim.acceptedClaim();
        }
        String normalized = key.trim();
        if (normalized.length() > 256) throw new IllegalArgumentException("Idempotency-Key 长度不能超过 256");
        return idempotencyStore.claim(scope, normalized, requestHash, Duration.ofHours(24), Instant.now());
    }

    private Optional<AgentRun> replayRun(Optional<IdempotencyRecord> record, CreateRunCommand command) {
        if (record == null || record.isEmpty()) return Optional.empty();
        Map<String, Object> body = record.get().responseBody();
        Object runId = body.get("runId");
        if (runId != null && !runId.toString().isBlank()) {
            Optional<AgentRun> stored = runRepository.findByIdForUser(runId.toString(), command.userId());
            if (stored.isPresent()) return stored;
        }
        // 兼容旧版本只写 Run 唯一键、尚未写幂等响应的记录。
        return command.idempotencyKey() == null
                ? Optional.empty()
                : runRepository.findByIdempotencyKey(command.userId(), command.idempotencyKey());
    }

    private void completeIdempotency(String scope, String key, String requestHash, RunView view) {
        if (idempotencyStore == null || key == null || key.isBlank() || view == null) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", view.runId());
        body.put("sessionId", view.sessionId());
        body.put("conversationId", view.conversationId());
        body.put("status", view.status().name());
        body.put("eventsUrl", view.eventsUrl());
        body.put("traceId", view.traceId());
        body.put("gameReleaseId", safe(view.gameReleaseId()));
        body.put("language", safe(view.language()));
        body.put("battleFormatId", safe(view.battleFormatId()));
        body.put("rulesetVersionId", safe(view.rulesetVersionId()));
        body.put("dlcId", safe(view.dlcId()));
        body.put("dataReleaseId", safe(view.dataReleaseId()));
        body.put("knowledgeReleaseId", safe(view.knowledgeReleaseId()));
        body.put("indexReleaseId", safe(view.indexReleaseId()));
        body.put("contractVersion", safe(view.contractVersion()));
        body.put("lineageStatus", safe(view.lineageStatus()));
        idempotencyStore.complete(scope, key.trim(), requestHash, 202, body, Instant.now());
    }

    private Optional<CancelResult> replayCancel(Optional<IdempotencyRecord> record) {
        if (record == null || record.isEmpty()) return Optional.empty();
        Map<String, Object> body = record.get().responseBody();
        Object id = body.get("runId");
        Object status = body.get("status");
        Object changed = body.get("changed");
        Object eventId = body.get("terminalEventId");
        if (id == null || status == null || changed == null || eventId == null) return Optional.empty();
        try {
            return Optional.of(new CancelResult(id.toString(), AgentRunStatus.valueOf(status.toString()),
                    Boolean.parseBoolean(changed.toString()), Long.parseLong(eventId.toString())));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private void completeCancel(String scope, String key, String requestHash, CancelResult result) {
        if (idempotencyStore == null || key == null || key.isBlank() || result == null) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", result.runId());
        body.put("status", result.status().name());
        body.put("changed", result.changed());
        body.put("terminalEventId", result.terminalEventId());
        idempotencyStore.complete(scope, key.trim(), requestHash, 200, body, Instant.now());
    }

    private String createIdempotencyScope(String userId) {
        return "run:create:" + safe(userId);
    }

    private String requestHash(CreateRunCommand command) {
        String canonical = String.join("\u001f", safe(command.userId()), safe(command.sessionId()),
                safe(command.conversationId()), safe(command.prompt()), safe(command.gameReleaseId()),
                safe(command.battleFormat()), safe(command.rulesetVersionId()), safe(command.language()),
                safe(command.dlcId()), safe(command.dataReleaseId()), safe(command.knowledgeReleaseId()),
                safe(command.indexReleaseId()), safe(command.contractVersion()),
                safe(command.traceId()), String.valueOf(command.timeout().toMillis()));
        return hashValue(canonical);
    }

    private String hashValue(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /**
     * 进程重启后的恢复入口。
     *
     * <p>只有 {@code step=0,nextAction=dispatch} 表示尚未进入模型/工具执行，
     * 才允许重新提交 worker。其余 checkpoint 即使存在，也没有统一的响应
     * artifact/effect-ledger replay 协议，必须 fail-closed；不能把“checkpoint
     * 存在”误写成“重复执行是幂等的”。</p>
     */
    @PostConstruct
    void recoverInFlightRuns() {
        if (hitlManager != null) {
            hitlManager.registerExpiryListener(pending -> {
                resolveExpiredApproval(pending, 0);
            });
        }
        for (AgentRun run : runRepository.findActive()) {
            // WAITING_APPROVAL 不是普通的“worker 中断”状态。只有审批事实
            // 在共享仓储中仍为同一 user/run 绑定的 PENDING，才能保留等待；
            // 缺失、已决定或已过期都必须安全失败，不能伪造可继续执行的状态。
            if (run.status() == AgentRunStatus.WAITING_APPROVAL) {
                Optional<AgentCheckpoint> checkpoint = latestCheckpoint(run.id());
                if (recoverWaitingApproval(run)) continue;
                failAfterUnsafeRecovery(run, checkpoint.orElse(null), "WAITING_APPROVAL_NOT_PENDING");
                continue;
            }
            if (durableRunProperties.durableResume() && checkpointRepository != null) {
                Optional<AgentCheckpoint> checkpoint = checkpointRepository.findLatest(run.id());
                if (checkpoint.isPresent() && (run.status() == AgentRunStatus.CREATED
                        || run.status() == AgentRunStatus.RUNNING)) {
                    AgentCheckpoint saved = checkpoint.get();
                    RunRecoveryPolicy.Decision decision = recoveryDecision(run, saved);
                    if (decision.canResume()) {
                        activeRuns.putIfAbsent(run.id(), run);
                        try {
                            submit(run, remainingTimeout(run));
                            log.info("Run 已依据 dispatch checkpoint 重新提交恢复: runId={}, stepNo={}, nextAction={}",
                                    run.id(), saved.stepNo(), saved.nextAction());
                            continue;
                        } catch (RuntimeException ex) {
                            log.warn("Run dispatch checkpoint 恢复提交失败，转为安全失败: runId={}, reason={}",
                                    run.id(), ex.getMessage());
                            failAfterUnsafeRecovery(run, saved, "DISPATCH_SUBMIT_FAILED");
                            continue;
                        }
                    }
                    // 对已进入 execute-answer/commit-answer 的 Run 不再盲目
                    // 调用模型或工具；先把原因和 checkpoint 摘要写入唯一终态。
                    failAfterUnsafeRecovery(run, saved, decision.reason());
                    continue;
                }
            }
            synchronized (run) {
                if (run.status().isTerminal() || !run.fail()) continue;
                try {
                    runRepository.save(run);
                } catch (RunConflictException conflict) {
                    refreshAfterConflict(run, conflict);
                    continue;
                }
                if (!eventRepository.hasTerminalEvent(run.id())) {
                    emitLocked(run, AgentEventType.RUN_FAILED, Map.of("errorType", "SERVICE_RESTARTED"));
                }
            }
        }
    }

    /** 读取最新 checkpoint；恢复扫描不能因单条损坏记录阻断其他 Run。 */
    private Optional<AgentCheckpoint> latestCheckpoint(String runId) {
        if (checkpointRepository == null) return Optional.empty();
        try {
            return checkpointRepository.findLatest(runId);
        } catch (RuntimeException ex) {
            log.warn("读取 Run checkpoint 失败，按安全失败处理: runId={}, reason={}",
                    runId, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 恢复一个仍等待人工审批的 Run。这里不重放模型/工具，只恢复两个定时器：
     * 审批到期清理和 Run 总截止时间；审批通过后仍由显式 resolveApproval 触发
     * 新 worker，避免重启扫描直接执行有副作用的工具。
     */
    private boolean recoverWaitingApproval(AgentRun run) {
        if (run == null || run.status() != AgentRunStatus.WAITING_APPROVAL
                || hitlManager == null || !hitlManager.hasDurableApprovalRepository()) {
            return false;
        }
        Optional<String> approvalId = latestApprovalId(run.id());
        if (approvalId.isEmpty()) return false;
        HitlManager.PendingApproval pending;
        try {
            pending = hitlManager.getApproval(approvalId.get());
        } catch (RuntimeException ex) {
            log.warn("读取持久 HITL 审批失败，按安全失败处理: runId={}, reason={}",
                    run.id(), ex.getClass().getSimpleName());
            return false;
        }
        Instant now = Instant.now();
        if (pending == null || pending.status() != HitlManager.ApprovalStatus.PENDING
                || !run.id().equals(pending.runId()) || !run.userId().equals(pending.userId())
                || pending.expiresAt() == null || !pending.expiresAt().isAfter(now)) {
            return false;
        }
        synchronized (run) {
            // getApproval() 可能在清理过期记录时同步触发回调；重新确认状态，
            // 避免把已经 FAILED/CANCELLED 的 Run 放回活动表。
            if (run.status() != AgentRunStatus.WAITING_APPROVAL) return false;
            try {
                activeRuns.putIfAbsent(run.id(), run);
                hitlManager.rearmExpiry(pending);
                scheduler.schedule(() -> timeout(run.id()),
                        Math.max(1L, remainingTimeout(run).toMillis()), TimeUnit.MILLISECONDS);
            } catch (RuntimeException ex) {
                activeRuns.remove(run.id(), run);
                log.warn("恢复 WAITING_APPROVAL 定时器失败，按安全失败处理: runId={}, reason={}",
                        run.id(), ex.getClass().getSimpleName());
                return false;
            }
        }
        log.info("Run 已恢复为等待审批: runId={}, approvalId={}, expiresAt={}",
                run.id(), approvalId.get(), pending.expiresAt());
        return true;
    }

    /**
     * 从事件流提取最近一次 approval.required 的 ID。只接受简单标量并且不把
     * payload 原文写入日志；任何查询/解析异常都返回空，让调用方 fail-closed。
     */
    private Optional<String> latestApprovalId(String runId) {
        try {
            List<AgentEvent> stream = eventRepository.findAfter(runId, 0);
            String latest = null;
            for (AgentEvent event : stream) {
                if (event == null || event.type() != AgentEventType.APPROVAL_REQUIRED
                        || event.payload() == null) continue;
                Object value = event.payload().get("approvalId");
                // 部分旧事件/数据库驱动使用 snake_case；接受同义键，但只接受
                // 简单标量，拒绝对象/数组，避免把任意 payload 误当作审批 ID。
                if (value == null) value = event.payload().get("approval_id");
                String candidate = scalarApprovalId(value);
                // 相关事件存在但 ID 缺失/损坏时清空此前候选；若这是流中最后一条
                // approval.required，调用方会安全失败，而不会恢复到旧审批。
                latest = candidate == null || candidate.isBlank() || candidate.length() > 128
                        ? null : candidate;
            }
            return latest == null ? Optional.empty() : Optional.of(latest);
        } catch (RuntimeException ex) {
            log.warn("读取 approval.required 事件失败，按安全失败处理: runId={}, reason={}",
                    runId, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 从事件 payload 提取兼容的审批 ID 标量，不展开/记录任意对象内容。 */
    private String scalarApprovalId(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?>
                || value.getClass().isArray()) return null;
        if (!(value instanceof CharSequence) && !(value instanceof Number)
                && !(value instanceof UUID)) return null;
        return value.toString().trim();
    }

    /**
     * 恢复前校验 checkpoint 是否仍属于当前 Run/执行器上下文。
     *
     * <p>策略故意要求完整 fingerprint 匹配。重启后如果 OIDC 策略快照没有
     * 被持久化，{@link #contextHash(AgentRun)} 会与原 checkpoint 不同，此时
     * 宁可失败也不在未知权限下重放；这也是 N3 持久化 policy snapshot 尚未
     * 完成时的显式边界。</p>
     */
    private RunRecoveryPolicy.Decision recoveryDecision(AgentRun run, AgentCheckpoint checkpoint) {
        if (run == null || checkpoint == null || !run.id().equals(checkpoint.runId())) {
            return RunRecoveryPolicy.Decision.fail("CHECKPOINT_RUN_MISMATCH");
        }
        if (!executor.getClass().getName().equals(checkpoint.executorVersion())) {
            return RunRecoveryPolicy.Decision.fail("CHECKPOINT_EXECUTOR_VERSION_MISMATCH");
        }
        if (!contextHash(run).equals(checkpoint.contextHash())) {
            return RunRecoveryPolicy.Decision.fail("CHECKPOINT_CONTEXT_MISMATCH");
        }
        long currentEventId;
        try {
            currentEventId = eventRepository.lastEventId(run.id());
        } catch (RuntimeException ex) {
            return RunRecoveryPolicy.Decision.fail("CHECKPOINT_EVENT_LOOKUP_FAILED");
        }
        if (checkpoint.lastEventId() > currentEventId) {
            return RunRecoveryPolicy.Decision.fail("CHECKPOINT_EVENT_AHEAD");
        }
        return recoveryPolicy.decide(checkpoint);
    }

    /**
     * 把不可安全重放的 Run 收敛为唯一 FAILED 事实。payload 只包含步骤、动作
     * 和错误分类，不包含 checkpoint contextHash、提示词或任何凭据。
     */
    private void failAfterUnsafeRecovery(AgentRun run, AgentCheckpoint checkpoint, String reason) {
        if (run == null) return;
        synchronized (run) {
            // 终态事件是跨实例事实；先检查它，避免把本地快照改成 FAILED
            // 却留下一个已经发布的 COMPLETED/CANCELLED 终态。
            if (run.status().isTerminal() || eventRepository.hasTerminalEvent(run.id())) return;
            if (!run.fail()) return;
            try {
                runRepository.save(run);
            } catch (RunConflictException conflict) {
                refreshAfterConflict(run, conflict);
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("errorType", "RECOVERY_UNSAFE");
            payload.put("recoveryReason", safe(reason));
            if (checkpoint != null) {
                payload.put("checkpointStepNo", checkpoint.stepNo());
                payload.put("checkpointNextAction", safe(checkpoint.nextAction()));
                payload.put("checkpointLastEventId", checkpoint.lastEventId());
            }
            emitLocked(run, AgentEventType.RUN_FAILED, payload);
        }
    }

    /**
     * 过期回调位于内存审批表之外，数据库瞬时故障时做有限重试；最终仍需
     * 持久化 outbox/审批表接管跨进程可靠投递，这里不能假装已经具备该能力。
     */
    private void resolveExpiredApproval(HitlManager.PendingApproval pending, int attempt) {
        if (pending == null) return;
        try {
            // 过期审批与显式拒绝使用同一条收敛路径，保证事件顺序和
            // 唯一终态约束一致；若 Run 已被取消/删除则安全忽略。
            resolveApproval(pending.runId(), pending.userId(), false, "审批已过期");
        } catch (RunNotFoundException | IllegalStateException ex) {
            log.debug("过期审批对应 Run 已不可恢复: approvalId={}, runId={}, reason={}",
                    pending.approvalId(), pending.runId(), ex.getMessage());
        } catch (RuntimeException ex) {
            if (attempt < 3 && !scheduler.isShutdown()) {
                long delay = 100L * (attempt + 1);
                scheduler.schedule(() -> resolveExpiredApproval(pending, attempt + 1),
                        delay, TimeUnit.MILLISECONDS);
            } else {
                log.warn("过期审批收敛 Run 失败（重试耗尽）: approvalId={}, runId={}, reason={}",
                        pending.approvalId(), pending.runId(), ex.getMessage());
            }
        }
    }

    /**
     * CAS 失败时把本地旧快照从活动表移出并换成数据库最新快照；调用方决定是否
     * 继续返回冲突（HTTP）或静默停止旧 worker（后台线程）。
     */
    private void refreshAfterConflict(AgentRun stale, RunConflictException conflict) {
        try {
            runRepository.findById(stale.id()).ifPresentOrElse(
                    latest -> activeRuns.put(stale.id(), latest),
                    () -> activeRuns.remove(stale.id(), stale));
            log.info("Run CAS 冲突，已刷新本地快照: runId={}, expectedVersion={}",
                    stale.id(), stale.version());
        } catch (RuntimeException refreshFailure) {
            // 补读也可能遇到瞬时数据库故障；后台调度线程不能因为刷新失败
            // 再次冒泡，移除旧快照并等待后续查询/恢复任务重试。
            activeRuns.remove(stale.id(), stale);
            log.warn("Run CAS 冲突后刷新失败: runId={}, conflict={}, reason={}",
                    stale.id(), conflict.getMessage(), refreshFailure.getMessage());
        }
    }

    private String valueOrUuid(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    private String title(String prompt) {
        return prompt.length() <= 40 ? prompt : prompt.substring(0, 40);
    }

    private Map<String, Object> contextPayload(AgentRun run) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("language", safe(run.language()));
        payload.put("gameReleaseId", safe(run.gameReleaseId()));
        payload.put("battleFormat", safe(run.battleFormat()));
        payload.put("rulesetVersionId", safe(run.rulesetVersionId()));
        payload.putAll(lineagePayload(run));
        return Map.copyOf(payload);
    }

    private Map<String, Object> runCreatedPayload(AgentRun run, PrincipalPolicy policy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", run.status().name());
        if (policy != null) {
            payload.put("tenantId", policy.tenantId());
            payload.put("roles", policy.roles().stream().sorted().toList());
            payload.put("policyVersion", policy.policyVersion());
            payload.put("policyFingerprint", policy.fingerprint());
            payload.put("policyExpiresAt", policy.expiresAt() == null ? "" : policy.expiresAt().toString());
        }
        return Map.copyOf(payload);
    }

    /**
     * 每条 Run 事件和会话消息共享的版本血缘摘要。缺失字段使用空字符串表示
     * “尚未绑定”，不写入 unknown，便于下游按字段判断是否可追溯。
     */
    private Map<String, Object> lineagePayload(AgentRun run) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("gameReleaseId", safe(run.gameReleaseId()));
        payload.put("battleFormatId", safe(run.battleFormat()));
        payload.put("rulesetVersionId", safe(run.rulesetVersionId()));
        payload.put("language", safe(run.language()));
        payload.put("dlcId", safe(run.dlcId()));
        payload.put("dataReleaseId", safe(run.dataReleaseId()));
        payload.put("knowledgeReleaseId", safe(run.knowledgeReleaseId()));
        payload.put("indexReleaseId", safe(run.indexReleaseId()));
        payload.put("contractVersion", safe(run.contractVersion()));
        return Map.copyOf(payload);
    }

    private static ExecutorService newWorker() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "vio-agent-run");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
        scheduler.shutdownNow();
    }
}
