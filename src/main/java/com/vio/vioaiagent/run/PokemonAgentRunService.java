package com.vio.vioaiagent.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vio.vioaiagent.observability.AgentMetrics;
import com.vio.vioaiagent.rag.contract.AnswerContract;
import com.vio.vioaiagent.tools.governance.ToolExecutionContext;
import com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder;
import com.vio.vioaiagent.tools.governance.ToolGovernanceEvent;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.security.HitlAuditEvent;
import com.vio.vioaiagent.security.TokenBinding;
import com.vio.vioaiagent.security.PrincipalPolicy;
import com.vio.vioaiagent.security.AuthorizationSnapshot;
import com.vio.vioaiagent.security.AuthorizationSnapshotRepository;
import com.vio.vioaiagent.security.AuthorizationSnapshotValidator;
import com.vio.vioaiagent.security.AuthorizationRevocationRepository;
import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.DurableRunProperties;
import com.vio.vioaiagent.config.RunAdmissionProperties;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * M3 唯一 Run 生命周期服务。Controller 只创建、订阅和取消，模型/工具由 RunExecutor 执行。
 */
@Service
@Slf4j
public class PokemonAgentRunService {

    @org.springframework.beans.factory.annotation.Value("${vio.run.answer-streaming-enabled:false}")
    private boolean answerStreamingEnabled;

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
    private final RunAdmissionProperties admissionProperties;
    private final RunAdmissionController admission;
    private final ConcurrentMap<String, RunAdmissionController.Permit> reservations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledFuture<?>> deadlineTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledFuture<?>> queueTasks = new ConcurrentHashMap<>();
    /** 生产 profile 下由独立事务 Bean 提供事件+outbox 原子写入。 */
    private volatile RunEventOutboxAppender eventOutboxAppender;
    /** 可选的稳定答案投影；Spring 本地/生产分别装配内存/JDBC 实现。 */
    private volatile AnswerProjectionRepository answerProjectionRepository;
    /** Encrypted immutable output used by safe commit-answer recovery. */
    private volatile RunStepOutputRepository stepOutputRepository;
    private volatile ModelInvocationCacheCodec stepOutputCodec;
    private volatile AuthorizationSnapshotRepository authorizationSnapshotRepository;
    private volatile AuthorizationSnapshotValidator authorizationSnapshotValidator;
    private volatile AuthorizationRevocationRepository authorizationRevocationRepository;
    private final ThreadLocal<java.util.Set<String>> activeStepOutputWrites =
            ThreadLocal.withInitial(java.util.HashSet::new);
    private volatile ObjectMapper outputMapper = new ObjectMapper().findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    /** Immutable step artifacts are retained long enough for a bounded recovery window. */
    private static final Duration STEP_OUTPUT_RETENTION = Duration.ofHours(24);
    /** 可选的跨实例 HTTP 幂等记录；本地测试未装配时沿用 Run 唯一键。 */
    private volatile IdempotencyStore idempotencyStore;
    private final ConcurrentMap<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    /** 当前进程正在执行的聚合快照；避免超时/取消从 JDBC 重新加载出另一份对象。 */
    private final ConcurrentMap<String, AgentRun> activeRuns = new ConcurrentHashMap<>();
    private final ConcurrentMap<String,com.vio.vioaiagent.observability.ModelUsageLedger> modelUsageByRun=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,com.vio.vioaiagent.observability.TaskModelBudget> taskBudgets=new ConcurrentHashMap<>();
    private com.vio.vioaiagent.observability.ModelPriceCatalog modelPrices=com.vio.vioaiagent.observability.ModelPriceCatalog.disabled();
    @Autowired(required=false)
    public void setModelPriceCatalog(com.vio.vioaiagent.observability.ModelPriceCatalog prices){this.modelPrices=Objects.requireNonNull(prices);}
    private com.vio.vioaiagent.config.TaskBudgetProperties taskBudgetProperties=com.vio.vioaiagent.config.TaskBudgetProperties.defaults();
    @Autowired(required=false)
    public void setTaskBudgetProperties(com.vio.vioaiagent.config.TaskBudgetProperties properties){this.taskBudgetProperties=Objects.requireNonNull(properties);}
    private com.vio.vioaiagent.config.ContextBudgetProperties contextBudgetProperties=com.vio.vioaiagent.config.ContextBudgetProperties.defaults();
    @Autowired(required=false)
    public void setContextBudgetProperties(com.vio.vioaiagent.config.ContextBudgetProperties properties){this.contextBudgetProperties=Objects.requireNonNull(properties);}
    /** 仅保存当前进程活跃 Run 的令牌绑定引用；密钥不会写入 Run/事件数据库。 */
    private final ConcurrentMap<String, TokenBinding> tokenBindings = new ConcurrentHashMap<>();
    /** OIDC 主体/租户/角色/工具的短期不可变快照；不保存 JWT 或 Secret。 */
    private final ConcurrentMap<String, PrincipalPolicy> principalPolicies = new ConcurrentHashMap<>();
    /** Ownership belongs to one physical execution, never to a mutable runId lookup. */
    private final ThreadLocal<ExecutionLease> executionLease = new ThreadLocal<>();
    private final ThreadLocal<ExecutionLease> fencedLease = new ThreadLocal<>();
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

    @Autowired(required = false)
    public void setRunStepOutputRepository(RunStepOutputRepository repository) {
        this.stepOutputRepository = repository;
    }

    /** Uses the same controlled envelope codec as the model cache; no plaintext fallback. */
    @Autowired(required = false)
    public void setRunStepOutputCodec(ModelInvocationCacheCodec codec) {
        this.stepOutputCodec = codec;
    }

    @Autowired(required = false)
    public void setAuthorizationSnapshotRepository(AuthorizationSnapshotRepository repository) {
        this.authorizationSnapshotRepository = repository;
    }

    @Autowired(required = false)
    public void setAuthorizationSnapshotValidator(AuthorizationSnapshotValidator validator) {
        this.authorizationSnapshotValidator = validator;
    }

    @Autowired(required = false)
    public void setAuthorizationRevocationRepository(AuthorizationRevocationRepository repository) {
        this.authorizationRevocationRepository = repository;
    }

    @Autowired(required = false)
    public void setOutputMapper(ObjectMapper mapper) {
        if (mapper != null) {
            this.outputMapper = mapper.copy().findAndRegisterModules()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        }
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
                                  ObjectProvider<DurableRunProperties> durablePropertiesProvider,
                                  ObjectProvider<RunAdmissionProperties> admissionProvider) {
        this(runRepository, eventRepository, conversationRepository, messageRepository,
                executorProvider.getIfAvailable(DefaultRunExecutor::new),
                newWorker(admissionProperties(admissionProvider)), newScheduler(), metricsProvider.getIfAvailable(),
                hitlProvider == null ? null : hitlProvider.getIfAvailable(),
                outboxProvider == null ? null : outboxProvider.getIfAvailable(),
                checkpointProvider == null ? null : checkpointProvider.getIfAvailable(),
                durablePropertiesProvider == null ? new DurableRunProperties()
                        : durablePropertiesProvider.getIfAvailable(DurableRunProperties::new),
                admissionProperties(admissionProvider));
    }

    /** Compatibility for existing embedded provider-based construction. */
    public PokemonAgentRunService(RunRepository runs, EventRepository events,
                                  ConversationRepository conversations, ChatMessageRepository messages,
                                  ObjectProvider<RunExecutor> executor, ObjectProvider<AgentMetrics> metrics,
                                  ObjectProvider<HitlManager> hitl, ObjectProvider<RunOutboxRepository> outbox,
                                  ObjectProvider<CheckpointRepository> checkpoints,
                                  ObjectProvider<DurableRunProperties> durable) {
        this(runs, events, conversations, messages, executor, metrics, hitl, outbox, checkpoints, durable, null);
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
        this(runRepository, eventRepository, conversationRepository, messageRepository, executor,
                worker, scheduler, metrics, hitlManager, outboxRepository, checkpointRepository,
                durableRunProperties, new RunAdmissionProperties());
    }

    /** Deterministic tests may supply a bounded worker and small admission limits. */
    public PokemonAgentRunService(RunRepository runs, EventRepository events,
                                  ConversationRepository conversations, ChatMessageRepository messages,
                                  RunExecutor executor, ExecutorService worker, ScheduledExecutorService scheduler,
                                  RunAdmissionProperties admissionProperties) {
        this(runs, events, conversations, messages, executor, worker, scheduler,
                null, null, null, null, null, admissionProperties);
    }

    private PokemonAgentRunService(RunRepository runRepository, EventRepository eventRepository,
                                   ConversationRepository conversationRepository, ChatMessageRepository messageRepository,
                                   RunExecutor executor, ExecutorService worker, ScheduledExecutorService scheduler,
                                   AgentMetrics metrics, HitlManager hitlManager, RunOutboxRepository outboxRepository,
                                   CheckpointRepository checkpointRepository, DurableRunProperties durableRunProperties,
                                   RunAdmissionProperties admissionProperties) {
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
        if (this.durableRunProperties.durableResume() && checkpointRepository == null) {
            throw new IllegalArgumentException("启用耐久 Run 必须配置 CheckpointRepository，禁止绕过租约隔离");
        }
        this.admissionProperties = admissionProperties == null ? new RunAdmissionProperties() : admissionProperties;
        this.admission = new RunAdmissionController(this.admissionProperties);
        if (scheduler instanceof ScheduledThreadPoolExecutor scheduled) scheduled.setRemoveOnCancelPolicy(true);
    }

    public RunView createRun(CreateRunCommand command) {
        if (command == null) throw new IllegalArgumentException("创建 Run 请求不能为空");
        synchronized (idempotencyLock) {
            String idemScope = createIdempotencyScope(command.userId());
            String requestHash = requestHash(command);
            if (command.idempotencyKey() != null) {
                if (command.idempotencyKey().length() > 256) throw new IllegalArgumentException("Idempotency-Key 长度不能超过 256");
                if (idempotencyStore != null) {
                    Optional<IdempotencyRecord> record = idempotencyStore.find(idemScope, command.idempotencyKey())
                            .filter(value -> value.validAt(Instant.now()));
                    if (record.isPresent()) {
                        Optional<AgentRun> replay = replayRun(record, command);
                        if (!matchesCreateRequest(record.get(), command, requestHash, replay)) throw new IdempotencyConflictException(idemScope, command.idempotencyKey());
                        if (replay.isPresent()) return RunView.from(replay.get());
                        throw new IdempotencyInProgressException(idemScope, command.idempotencyKey());
                    }
                } else {
                    Optional<AgentRun> replay = runRepository.findByIdempotencyKey(command.userId(), command.idempotencyKey());
                    if (replay.isPresent()) return RunView.from(replay.get());
                }
            }
            if (worker.isShutdown() || scheduler.isShutdown()) throw unavailable();
            RunAdmissionController.Permit permit = admission.acquire(trustedTenant(), command.userId());
            AgentRun admitted = null;
            boolean submitted = false;
            try {
                IdempotencyStore.Claim idemClaim = claimIdempotency(idemScope, command.idempotencyKey(), requestHash);
                if (idemClaim.conflict()) {
                    Optional<AgentRun> replay = replayRun(idemClaim.record(), command);
                    if (idemClaim.record().isPresent() && replay.isPresent()
                            && matchesCreateRequest(idemClaim.record().get(), command, requestHash, replay)) return RunView.from(replay.get());
                    throw new IdempotencyConflictException(idemScope, command.idempotencyKey());
                }
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
                var constraints = conversationRepository.constraintsForUser(conversationId, command.userId())
                        .orElseGet(ConversationConstraints::defaults);
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
                admitted = run;
                taskBudgets.put(run.id(),new com.vio.vioaiagent.observability.TaskModelBudget(taskBudgetProperties));
                reservations.put(run.id(), permit);
                PrincipalPolicy policy = RequestContext.getPrincipalPolicy();
                if (policy != null) principalPolicies.put(run.id(), policy);
                TokenBinding binding = RequestContext.getTokenBinding();
                if (binding == null && policy != null) binding = policy.tokenBinding();
                if (binding != null) tokenBindings.put(run.id(), binding);
                if (policy != null && authorizationSnapshotRepository != null) {
                    // Persist only secret-free authorization facts before the
                    // first checkpoint. A durable Run must never outlive an
                    // authorization snapshot that failed to be recorded.
                    authorizationSnapshotRepository.save(
                            AuthorizationSnapshot.from(run.id(), policy, Instant.now()));
                }
                // 先冻结授权快照再生成 checkpoint/event。只持久化 tenant、角色、
                // 版本、过期时间和 SHA-256 fingerprint；JWT/Secret/原始 binding token
                // 不进入 Run、事件或 checkpoint。
                Map<String,Object> userMetadata = new LinkedHashMap<>(lineagePayload(run));
                userMetadata.put("conversationConstraints", constraints.snapshot());
                messageRepository.save(new ChatMessage("message-" + UUID.randomUUID(), conversationId, runId,
                        command.userId(), "user", command.prompt(), Instant.now(), userMetadata));
                saveCheckpoint(run, 0, "dispatch", null);
                activeRuns.put(run.id(), run);
                emit(run, AgentEventType.RUN_CREATED, runCreatedPayload(run, policy));
                RunView view = RunView.from(run);
                // Submit before finalizing the idempotency response.  If the
                // idempotency store is temporarily unavailable, a persisted Run
                // must still have a worker (or a terminal failure) rather than
                // being stranded in CREATED with an IN_PROGRESS key.
                submitted = submit(run, command.timeout());
                completeIdempotency(idemScope, command.idempotencyKey(), requestHash, view);
                return view;
            } catch (RuntimeException ex) {
                if (admitted != null && !submitted) {
                    try {
                        failIfActive(admitted, ex);
                        completeIdempotency(idemScope, command.idempotencyKey(), requestHash, RunView.from(admitted));
                    } finally {
                        cancelTask(admitted.id());
                        cleanIdleRun(admitted);
                    }
                }
                throw ex;
            } finally {
                if (admitted == null || reservations.get(admitted.id()) != permit) permit.close();
            }
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
                cleanIdleRun(run);
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
                try {
                    runRepository.save(run);
                } catch (RunConflictException conflict) {
                    refreshAfterConflict(run, conflict);
                    throw conflict;
                }
                emitLocked(run, AgentEventType.RUN_CANCELLED, Map.of("reason", normalizedReason));
                CancelResult result = new CancelResult(runId, run.status(), true, eventRepository.lastEventId(runId));
                completeCancel(scope, idempotencyKey, requestHash, result);
                return result;
            } finally {
                cancelTask(runId);
                cleanIdleRun(run);
            }
        }
    }

    /** 审批决定后的 Run 收敛：批准恢复执行，拒绝进入唯一 FAILED 终态。 */
    public RunView resolveApproval(String runId, String userId, boolean approved, String reason) {
        AgentRun run = ownedRun(runId, userId);
        synchronized (run) {
            if (run.status().isTerminal()) { cleanIdleRun(run); return RunView.from(run); }
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
                } finally {
                    cancelTask(run.id());
                    cleanIdleRun(run);
                }
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

    public ConversationConstraints conversationConstraints(String id, String userId) {
        requireConversation(id, userId);
        return conversationRepository.constraintsForUser(id, userId).orElseGet(ConversationConstraints::defaults);
    }

    public ConversationConstraints saveConversationConstraints(String id, String userId, ConversationConstraints expected) {
        if (id == null || !id.matches("[A-Za-z0-9._:-]{1,64}") || expected == null) throw new IllegalArgumentException("对话偏好请求无效");
        synchronized (idempotencyLock) {
            if (conversationRepository.findByIdForUser(id, userId).isEmpty()) {
                if (conversationRepository.findById(id).isPresent()) throw new RunNotFoundException(id);
                if (expected.revision() != 0) throw new RunConflictException(id, expected.revision());
                conversationRepository.save(new Conversation(id, userId, "宝可梦训练家会话", Instant.now(), Instant.now()));
                requireConversation(id, userId);
            }
            if (!conversationRepository.replaceConstraintsForUser(id, userId, expected)) throw new RunConflictException(id, expected.revision());
            return expected.next();
        }
    }

    private ConversationConstraints constraintsForRun(AgentRun run) {
        var messages = messageRepository.findByConversationForUser(run.conversationId(), run.userId()).stream()
                .filter(m -> run.id().equals(m.runId()) && "user".equals(m.role())).toList();
        if (messages.size() > 1) throw new IllegalStateException("任务存在冲突的用户偏好快照");
        if (messages.isEmpty() || !messages.getFirst().metadata().containsKey("conversationConstraints")) return ConversationConstraints.defaults();
        return ConversationConstraints.fromSnapshot(messages.getFirst().metadata().get("conversationConstraints"));
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
        synchronized (run) {
            if (tasks.containsKey(run.id())) return false;
            reserveRecoveredRun(run);
            AtomicReference<FutureTask<Void>> owner = new AtomicReference<>();
            ManagedRunTask task = new ManagedRunTask(run, owner);
            owner.set(task);
            if (tasks.putIfAbsent(run.id(), task) != null) return false;
            activeRuns.putIfAbsent(run.id(), run);
            try {
                armDeadline(run);
                ScheduledFuture<?> queued = scheduler.schedule(() -> expireQueued(run, task),
                        admissionProperties.queueWaitMs(), TimeUnit.MILLISECONDS);
                task.queueTimer = queued;
                queueTasks.put(run.id(), queued);
                if (task.isCancelled() || run.status().isTerminal()) {
                    task.cancelQueueTimer();
                } else {
                    worker.execute(task);
                    if (task.isCancelled() && worker instanceof ThreadPoolExecutor pool) pool.remove(task);
                }
            } catch (RuntimeException ex) {
                try { failIfActive(run, new RunDispatchException()); }
                finally { cancelTask(run.id()); cleanIdleRun(run); }
                throw unavailable();
            }
            return true;
        }
    }

    private void armDeadline(AgentRun run) {
        if (deadlineTasks.containsKey(run.id())) return;
        ScheduledFuture<?> future = scheduler.schedule(() -> timeout(run.id()),
                Math.max(1L, remainingTimeout(run).toMillis()), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> existing = deadlineTasks.putIfAbsent(run.id(), future);
        if (existing != null) future.cancel(false);
        // The deadline may fire before its handle is registered.
        if (run.status().isTerminal()) cancelTimer(deadlineTasks, run.id());
    }

    private void expireQueued(AgentRun run, ManagedRunTask task) {
        synchronized (run) {
            if (tasks.get(run.id()) != task || task.started() || run.status().isTerminal()) return;
            try { failIfActive(run, new RunQueueTimeoutException()); }
            finally { cancelTask(run.id()); cleanIdleRun(run); }
        }
    }

    private final class ManagedRunTask extends FutureTask<Void> {
        private final AgentRun run;
        private final long queuedAtNanos = System.nanoTime();
        private volatile long workerEnteredAtNanos;
        private boolean queueExpiredAtStart;
        private volatile boolean ownershipLost;
        /** Bound by execute() to the physical lease owned by this task. */
        private volatile ExecutionLease executionLease;
        private volatile ScheduledFuture<?> queueTimer;
        private boolean entered;
        ManagedRunTask(AgentRun run, AtomicReference<FutureTask<Void>> owner) {
            super(() -> { execute(run, owner.get()); return null; });
            this.run = run;
        }
        synchronized boolean started() { return entered; }
        void bindExecutionLease(ExecutionLease lease) { this.executionLease = lease; }
        void revokeExecutionLease() {
            ExecutionLease lease = executionLease;
            if (lease != null) lease.loseOwnership();
        }
        void cancelQueueTimer() {
            ScheduledFuture<?> timer = queueTimer;
            if (timer != null) {
                queueTasks.remove(run.id(), timer);
                timer.cancel(false);
            }
        }
        @Override public void run() {
            workerEnteredAtNanos=System.nanoTime();
            synchronized (run) {
                synchronized (this) { entered = true; }
                queueExpiredAtStart = System.nanoTime() - queuedAtNanos
                        >= TimeUnit.MILLISECONDS.toNanos(admissionProperties.queueWaitMs());
                cancelQueueTimer();
            }
            try { super.run(); }
            finally { finishTask(run, this); }
        }
        @Override protected void done() {
            if (!started()) finishTask(run, this);
        }
    }

    private void finishTask(AgentRun run, FutureTask<Void> task) {
        if (worker instanceof ThreadPoolExecutor pool) pool.remove(task);
        synchronized (run) {
            if (task instanceof ManagedRunTask managed) managed.cancelQueueTimer();
            if (!tasks.remove(run.id(), task)) return;
            if (run.status() != AgentRunStatus.WAITING_APPROVAL || worker.isShutdown()
                    || (task instanceof ManagedRunTask managed && managed.ownershipLost)) cleanIdleRun(run);
        }
    }

    private void cancelTask(String runId) {
        FutureTask<Void> task = tasks.get(runId);
        if (task instanceof ManagedRunTask managed) managed.revokeExecutionLease();
        if (task != null) task.cancel(true);
    }

    private void cleanIdleRun(AgentRun run) {
        synchronized (run) {
            if (tasks.containsKey(run.id())) return; // A canceled running callback may still be physically executing.
            activeRuns.remove(run.id());
            var taskBudget=taskBudgets.remove(run.id());
            if(taskBudget!=null)taskBudget.close();
            tokenBindings.remove(run.id());
            principalPolicies.remove(run.id());
            cancelTimer(deadlineTasks, run.id());
            cancelTimer(queueTasks, run.id());
            RunAdmissionController.Permit permit = reservations.remove(run.id());
            if (permit != null) permit.close();
        }
    }

    private static void cancelTimer(ConcurrentMap<String, ScheduledFuture<?>> timers, String runId) {
        ScheduledFuture<?> timer = timers.remove(runId);
        if (timer != null) timer.cancel(false);
    }

    private String trustedTenant() {
        PrincipalPolicy policy = RequestContext.getPrincipalPolicy();
        if (policy != null) return policy.tenantId();
        TokenBinding binding = RequestContext.getTokenBinding();
        if (binding != null && binding.tenantId() != null && !binding.tenantId().isBlank()) return binding.tenantId();
        return "local";
    }

    private void reserveRecoveredRun(AgentRun run) {
        if (reservations.containsKey(run.id())) return;
        // Recovery has no request identity. Use a bounded server-owned legacy scope.
        RunAdmissionController.Permit permit = admission.acquire("recovery", run.userId());
        RunAdmissionController.Permit existing = reservations.putIfAbsent(run.id(), permit);
        if (existing != null) permit.close();
    }

    private RunAdmissionException unavailable() {
        return new RunAdmissionException("RUN_EXECUTION_UNAVAILABLE", admissionProperties.retryAfterSeconds(), true);
    }
    private static final class RunQueueTimeoutException extends RuntimeException { }
    private static final class RunDispatchException extends RuntimeException { }
    private static final class RunAuthorizationExpiredException extends RuntimeException { }

    private static final class ExecutionLease {
        private final String runId;
        private final String owner = "worker-" + UUID.randomUUID();
        private final String token = UUID.randomUUID().toString();
        private final FutureTask<Void> task;
        private final AtomicBoolean revoked = new AtomicBoolean();

        private ExecutionLease(String runId, FutureTask<Void> task) {
            this.runId = runId;
            this.task = task;
        }

        private void requireActive() {
            if (revoked.get()) throw new RunLeaseLostException();
        }

        private void loseOwnership() {
            revoked.set(true);
            if (task instanceof ManagedRunTask managed) managed.ownershipLost = true;
        }
    }

    /**
     * Worker Run lifecycle persistence takes the JVM monitor before the database lease lock.
     * JDBC actions share the fence transaction; model and tool calls stay outside it.
     */
    private <T> T workerWrite(AgentRun run, Supplier<T> action) {
        synchronized (run) {
            ExecutionLease lease = executionLease.get();
            if (lease == null) return action.get(); // Independent user/deadline control intent.
            if (!run.id().equals(lease.runId)) throw new RunLeaseLostException();
            lease.requireActive();
            if (fencedLease.get() == lease) {
                T result = action.get();
                lease.requireActive();
                return result;
            }
            if (checkpointRepository == null || !durableRunProperties.durableResume()) {
                try {
                    T result = action.get();
                    lease.requireActive();
                    return result;
                }
                catch (RunConflictException conflict) {
                    refreshAfterConflict(run, conflict);
                    throw conflict;
                }
            }
            AgentRun.LifecycleSnapshot before = run.lifecycleSnapshot();
            try {
                return checkpointRepository.withLease(run.id(), lease.owner, lease.token, () -> {
                    lease.requireActive();
                    ExecutionLease previousFence = fencedLease.get();
                    fencedLease.set(lease);
                    try {
                        T result = action.get();
                        lease.requireActive();
                        return result;
                    } finally {
                        if (previousFence == null) fencedLease.remove();
                        else fencedLease.set(previousFence);
                    }
                });
            } catch (RuntimeException | Error ex) {
                // The transaction proxy has completed rollback before this catch runs.
                run.restoreLifecycle(before);
                if (ex instanceof RunLeaseLostException) lease.loseOwnership();
                if (ex instanceof RunConflictException conflict) refreshAfterConflict(run, conflict);
                throw ex;
            }
        }
    }

    private void capturedToolEvent(AgentRun run, ExecutionLease captured, ToolGovernanceEvent event) {
        ExecutionLease previous = executionLease.get();
        executionLease.set(captured);
        try { onToolEvent(run, event); }
        finally {
            if (previous == null) executionLease.remove();
            else executionLease.set(previous);
        }
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
        var modelUsage=new com.vio.vioaiagent.observability.ModelUsageLedger(taskBudgets.computeIfAbsent(run.id(),
                ignored->com.vio.vioaiagent.observability.TaskModelBudget.unavailable(taskBudgetProperties)),modelPrices);
        if(ownerTask instanceof ManagedRunTask managed)modelUsage.responseTiming(new com.vio.vioaiagent.observability.RunAttemptTiming(managed.queuedAtNanos,managed.workerEnteredAtNanos));
        boolean metricsActive = false;
        ExecutionLease lease = new ExecutionLease(run.id(), ownerTask);
        if (ownerTask instanceof ManagedRunTask task) task.bindExecutionLease(lease);
        try {
            if (ownerTask.isCancelled()) {
                lease.loseOwnership();
                return;
            }
            lease.requireActive();
            if (run.deadlineAt() != null && !Instant.now().isBefore(run.deadlineAt())) { timeout(run.id()); return; }
            if (ownerTask instanceof ManagedRunTask task && task.queueExpiredAtStart) {
                failIfActive(run, new RunQueueTimeoutException());
                return;
            }
            executionLease.set(lease);
            lease.requireActive();
            boolean leaseAcquired = acquireLease(run, lease);
            if (!leaseAcquired) {
                log.info("Run 租约被其他 worker 持有，跳过本地执行: runId={}", run.id());
                return;
            }
            PrincipalPolicy capturedPolicy = principalPolicies.get(run.id());
            if (capturedPolicy != null && capturedPolicy.isExpired()) throw new RunAuthorizationExpiredException();
            // 取消/超时可能先于 worker 获得执行权；仍需进入 finally 清理注册表。
            // 重启恢复可能拿到已经持久化为 RUNNING 的快照；此时不重复发起状态迁移。
            if (run.status() != AgentRunStatus.RUNNING
                    && !transition(run, AgentRunStatus.RUNNING, Map.of("status", "RUNNING"))) return;
            saveCheckpoint(run, 0, "execute-answer", lease.owner);
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
                    run.idempotencyKey(), event -> capturedToolEvent(run, lease, event), binding, policy);
            ToolExecutionContextHolder.set(runContext);
            if (run.deadlineAt() != null && !Instant.now().isBefore(run.deadlineAt())) {
                timeout(run.id());
                return;
            }
            workerWrite(run,()->{
                if(run.status()!=AgentRunStatus.RUNNING)throw new java.util.concurrent.CancellationException("Run stopped");
                modelUsageByRun.put(run.id(),modelUsage);return null;
            });
            AnswerExecution execution = executeAnswer(run,modelUsage);
            saveCheckpoint(run, 1, "commit-answer", lease.owner);
            if(!execution.streamed() && run.status()!=AgentRunStatus.WAITING_APPROVAL
                    && execution.answer()!=null && !execution.answer().isBlank())recordAnswerReady(modelUsage,"BUFFERED");
            if (run.status().isTerminal() || run.status() == AgentRunStatus.WAITING_APPROVAL) return;
            // Lineage may call an executor extension; compute it before opening a DB transaction.
            AnswerLineage lineage = execution.contract() == null
                    ? AnswerLineage.empty()
                    : (executor instanceof ContractAwareRunExecutor contractExecutor
                    ? contractExecutor.answerLineage(new AgentRunContext(run), execution.contract())
                    : AnswerLineage.empty());
            // Persist the opaque, encrypted answer artifact in its own fenced
            // transaction after the step-1 checkpoint. This leaves a durable
            // reconciliation fact even if the later projection/event CAS fails.
            saveStepOutput(run, 1, execution, lineage);
            String answerId = answerId(run);
            AnswerProjection projection = AnswerProjection.from(run, execution.contract(), answerId,
                    Instant.now(), lineage);
            // Decode and reconcile before opening the final persistence fence;
            // KMS-backed codecs must never turn the lifecycle transaction into
            // an external network call boundary.
            verifyStepOutputConsistency(run, execution, lineage, projection);
            workerWrite(run, () -> {
                if (run.status().isTerminal()) return null;
                if (run.deadlineAt() != null && !Instant.now().isBefore(run.deadlineAt())) {
                    timeout(run.id());
                    return null;
                }
                // HITL 网关已发出 approval.required；当前执行器暂停，等待审批端点恢复。
                if (run.status() == AgentRunStatus.WAITING_APPROVAL) return null;
                // answerId 按 Run 稳定派生，重试/恢复不会创建第二个答案。若已
                // 装配投影仓储，先保存不可变快照，再追加终态事件；仓储故障会让
                // 当前 Run 走 fail-closed，而不是产生无法追溯的完成结果。
                AnswerProjection committedProjection = projection;
                if (answerProjectionRepository != null) {
                    committedProjection = answerProjectionRepository.save(committedProjection);
                }
                // Keep event order while committing projection, message, events, Run CAS and
                // checkpoint in the same JDBC fence transaction. A losing CAS rolls all back.
                String answer = execution.answer();
                if (answer != null && !answer.isBlank()) {
                    Map<String, Object> answerDelta = new LinkedHashMap<>();
                    answerDelta.put("text", answer);
                    answerDelta.put("answerId", committedProjection.answerId());
                    answerDelta.put("lineageStatus", committedProjection.lineageStatus().name());
                    if (!execution.streamed()) emitLocked(run, AgentEventType.ANSWER_DELTA, answerDelta);
                    Map<String,Object> historyMetadata=new LinkedHashMap<>(lineagePayload(run));
                    historyMetadata.put("modelUsageAttempts",usageHistory(run,modelUsage));
                    historyMetadata.put("conversationConstraints", constraintsForRun(run).snapshot());
                    messageRepository.save(new ChatMessage("message-" + UUID.randomUUID(), run.conversationId(),
                            run.id(), run.userId(), "assistant", answer, Instant.now(), historyMetadata));
                }
                // ANSWER_COMPLETED 位于 RUN_COMPLETED 之前，保证非终态事件仍
                // 可追加；它携带投影的稳定 answerId 和完整/部分血缘状态。
                Map<String, Object> answerCompleted = new LinkedHashMap<>(committedProjection.lineagePayload());
                answerCompleted.put("status", committedProjection.status());
                answerCompleted.put("answerDigest", committedProjection.answerDigest());
                emitLocked(run, AgentEventType.ANSWER_COMPLETED, answerCompleted);
                emitLocked(run, AgentEventType.USAGE, modelUsage.freeze());
                run.complete();
                runRepository.save(run);
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
                completed.putAll(committedProjection.lineagePayload());
                emitLocked(run, AgentEventType.RUN_COMPLETED, completed);
                saveCheckpoint(run, 2, "finished", lease.owner);
                return null;
            });
        } catch (RunLeaseLostException ex) {
            lease.loseOwnership();
            log.info("Run 执行所有权已失效，停止本地提交: runId={}", run.id());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            // Shutdown preserves pending approval facts even when its old worker cooperates
            // with interruption. User cancellation has already transitioned the Run itself.
            if (run.status() != AgentRunStatus.WAITING_APPROVAL || admission.snapshot().accepting()) {
                try { cancelIfActive(run, "interrupted"); }
                catch (RunLeaseLostException lost) { log.info("失租约 worker 不提交取消状态: runId={}", run.id()); }
                catch (RunConflictException conflict) { log.info("Run 已由其他实例推进: runId={}", run.id()); }
            }
        } catch (RunConflictException ex) {
            // 另一实例已经提交了更新；旧 worker 不得再用旧快照写 FAILED。
            log.info("Run 已由其他实例推进，停止旧 worker: runId={}, expectedVersion={}",
                    run.id(), run.version());
        } catch (Exception ex) {
            try { failIfActive(run, ex); }
            catch (RunLeaseLostException lost) { log.info("失租约 worker 不提交失败状态: runId={}", run.id()); }
            catch (RunConflictException conflict) { log.info("Run 已由其他实例推进: runId={}", run.id()); }
        } finally {
            ToolExecutionContextHolder.clear();
            if(modelUsageByRun.get(run.id())==modelUsage) {
                try {
                    if(run.status()==AgentRunStatus.WAITING_APPROVAL)workerWrite(run,()->{
                        if(run.status()==AgentRunStatus.WAITING_APPROVAL)emitLocked(run,AgentEventType.USAGE,modelUsage.freeze());
                        return null;
                    });
                } catch(RuntimeException observationFailure) {
                    log.warn("模型用量快照未提交: runId={}, errorType={}",run.id(),observationFailure.getClass().getSimpleName());
                } finally {modelUsage.freeze();modelUsageByRun.remove(run.id(),modelUsage);}
            }
            try {
                if (metrics != null && metricsActive) {
                    metrics.decrementActiveAgents();
                    // WAITING_APPROVAL 不是终态；只在最终完成/失败/取消/超时时记录一次 Run。
                    if (run.status().isTerminal()) {
                        metrics.recordRun(run.status().name(), Duration.between(startedAt, Instant.now()).toMillis());
                    }
                }
            } finally {
                // ManagedRunTask retains ownership until lease cleanup and physical exit finish.
                lease.revoked.set(true);
                try { releaseLease(run, lease); }
                finally {
                    executionLease.remove();
                    fencedLease.remove();
                }
            }
        }
    }

    /**
     * 在生命周期服务内统一发出检索/引用事件；旧文本执行器仍沿用原路径。
     */
    private AnswerExecution executeAnswer(AgentRun run,com.vio.vioaiagent.observability.ModelUsageLedger modelUsage) throws Exception {
        StringBuilder streamed = new StringBuilder();
        boolean allowStreaming = answerStreamingEnabled && !durableRunProperties.durableResume()
                && executor instanceof ContractAwareRunExecutor aware && !aware.usesKnowledgeGrounding();
        var storedHistory=messageRepository.findByConversationForUser(run.conversationId(),run.userId());
        AgentRunContext context = new AgentRunContext(run, allowStreaming ? delta -> {
            if (delta == null || delta.isEmpty()) return;
            workerWrite(run, () -> {
                if (run.status() != AgentRunStatus.RUNNING || Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("Run no longer running");
                }
                if (streamed.length() + delta.length() > 131072) {
                    throw new IllegalStateException("模型回答超过本地长度上限");
                }
                if(!delta.isBlank())recordAnswerReady(modelUsage,"DELTA");
                emitLocked(run, AgentEventType.ANSWER_DELTA, Map.of("text", delta));
                streamed.append(delta);
                return null;
            });
        } : null, historyWindow(run,modelUsage,storedHistory), new RunMutationBoundary() {
            public void check() {
                ExecutionLease lease = executionLease.get();
                if (lease == null) throw new RunLeaseLostException();
                lease.requireActive();
                if (!run.id().equals(lease.runId) || run.status()!=AgentRunStatus.RUNNING
                        || Thread.currentThread().isInterrupted() || !Instant.now().isBefore(run.deadlineAt()))
                    throw new java.util.concurrent.CancellationException("Run 已停止或超过写入期限");
            }
            public <T> T write(Supplier<T> action) { return workerWrite(run, () -> { check(); return action.get(); }); }
        },modelUsage,constraintsForRun(run),ConversationHistory.dialogueState(run,storedHistory));
        if (!(executor instanceof ContractAwareRunExecutor contractExecutor)) {
            return new AnswerExecution(executor.execute(context), null);
        }
        boolean grounded = contractExecutor.usesKnowledgeGrounding();
        if (grounded) {
            emit(run, AgentEventType.RETRIEVAL_STARTED,
                    Map.of("gameReleaseId", safe(run.gameReleaseId()),
                            "battleFormat", safe(run.battleFormat()),
                            "rulesetVersionId", safe(run.rulesetVersionId())));
        }
        AnswerContract contract = contractExecutor.executeContract(context);
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
        if (!streamed.isEmpty() && !streamed.toString().equals(contract.answer())) {
            throw new IllegalStateException("流式内容与最终回答不一致，拒绝提交完成事实");
        }
        return new AnswerExecution(contract.answer(), contract, !streamed.isEmpty());
    }

    /**
     * Save the immutable output produced by step 1. The artifact is deliberately
     * opaque at rest: only the controlled codec sees its plaintext JSON. A
     * configured durable repository without a codec is a hard error, because
     * falling back to plaintext would make recovery less safe than no artifact.
     */
    private void saveStepOutput(AgentRun run, int stepNo, AnswerExecution execution,
                                AnswerLineage lineage) {
        String key = (run == null ? "<null>" : run.id()) + "#" + stepNo;
        java.util.Set<String> active = activeStepOutputWrites.get();
        if (!active.add(key)) {
            // A reentrant repository callback is used by tests and can also
            // occur in an instrumented store. Reconcile identical plaintext
            // output; reject a different logical result instead of allowing
            // the outer write to hide the conflict.
            try {
                if (run != null && execution != null && durableRunProperties.durableResume()
                        && stepOutputRepository != null && stepOutputCodec != null) {
                    String canonical = canonicalStepOutput(run, stepNo, execution, lineage);
                    if (reuseExistingStepOutput(run, stepNo, executor.getClass().getName(),
                            contextHash(run), hashValue(canonical), canonical, stepOutputCodec)) {
                        return;
                    }
                }
            } catch (RuntimeException ignored) {
                // Fall through to the explicit reentrant conflict below.
            }
            throw new RunStepOutputConflictException(true);
        }
        try {
            saveStepOutputInternal(run, stepNo, execution, lineage);
        } finally {
            active.remove(key);
            if (active.isEmpty()) activeStepOutputWrites.remove();
        }
    }

    private void saveStepOutputInternal(AgentRun run, int stepNo, AnswerExecution execution,
                                AnswerLineage lineage) {
        if (!durableRunProperties.durableResume() || stepOutputRepository == null) return;
        if (run == null || execution == null) {
            throw new RunStepOutputException("Run 步骤输出上下文缺失");
        }
        ModelInvocationCacheCodec codec = stepOutputCodec;
        if (codec == null) {
            throw new RunStepOutputException("Run 步骤输出已启用但未配置受控 codec");
        }
        String canonicalPayload = canonicalStepOutput(run, stepNo, execution, lineage);
        String payloadDigest = hashValue(canonicalPayload);
        String expectedContextHash = contextHash(run);
        String expectedExecutorVersion = executor.getClass().getName();
        // A KMS/envelope codec normally uses a fresh nonce for every encode. Reuse
        // an already persisted, valid artifact before asking the codec to encrypt
        // again; otherwise a replay of the same logical output would look like a
        // conflicting immutable row merely because its ciphertext differs.
        if (reuseExistingStepOutput(run, stepNo, expectedExecutorVersion, expectedContextHash,
                payloadDigest, canonicalPayload, stepOutputCodec)) {
            return;
        }
        final String encryptedPayload;
        final String keyVersion;
        try {
            encryptedPayload = codec.encode(canonicalPayload);
            keyVersion = normalizeStepOutputKeyVersion(codec.keyVersion());
        } catch (RuntimeException ex) {
            throw new RunStepOutputException("Run 步骤输出加密失败", ex);
        }
        if (encryptedPayload == null || encryptedPayload.isBlank()) {
            throw new RunStepOutputException("Run 步骤输出密文为空");
        }
        Instant createdAt = Instant.now();
        RunStepOutput candidate;
        try {
            candidate = new RunStepOutput(run.id(), stepNo, expectedExecutorVersion,
                    expectedContextHash, payloadDigest, encryptedPayload.trim(), keyVersion,
                    createdAt, createdAt.plus(STEP_OUTPUT_RETENTION));
        } catch (RuntimeException ex) {
            throw new RunStepOutputException("Run 步骤输出元数据无效", ex);
        }
        try {
            workerWrite(run, () -> {
                if (run.status().isTerminal() || run.status() == AgentRunStatus.WAITING_APPROVAL) return null;
                Optional<RunStepOutput> existing = stepOutputRepository.find(run.id(), stepNo);
                if (existing.isPresent()) {
                    if (sameImmutableOutput(existing.get(), candidate)) return null;
                    throw new RunStepOutputConflictException();
                }
                stepOutputRepository.save(candidate);
                return null;
            });
        } catch (RunLeaseLostException | RunConflictException | RunStepOutputConflictException ex) {
            if (ex instanceof RunStepOutputConflictException conflict && conflict.reentrant()) {
                throw ex;
            }
            // A concurrent writer may have won the immutable key between the
            // read above and INSERT. Reconcile that row outside the fence,
            // where a KMS decode is allowed, and reuse it only if its canonical
            // plaintext and metadata match this execution.
            if (ex instanceof RunStepOutputConflictException
                    && reuseExistingStepOutput(run, stepNo, expectedExecutorVersion,
                    expectedContextHash, payloadDigest, canonicalPayload, stepOutputCodec)) {
                return;
            }
            throw ex;
        } catch (RuntimeException ex) {
            throw new RunStepOutputException("Run 步骤输出持久化失败", ex);
        }
    }

    /**
     * Reconcile an existing immutable row without performing KMS work inside a
     * lease transaction. A missing row returns false; a corrupt or semantically
     * different row fails closed.
     */
    private boolean reuseExistingStepOutput(AgentRun run, int stepNo, String expectedExecutorVersion,
                                            String expectedContextHash, String expectedPayloadDigest,
                                            String canonicalPayload, ModelInvocationCacheCodec codec) {
        final Optional<RunStepOutput> existing;
        try {
            existing = stepOutputRepository.find(run.id(), stepNo);
        } catch (RuntimeException ex) {
            throw new RunStepOutputException("读取既有 Run 步骤输出失败", ex);
        }
        if (existing.isEmpty()) return false;
        RunStepOutput output = existing.get();
        if (output.expired(Instant.now())) {
            throw new RunStepOutputException("既有 Run 步骤输出已过期");
        }
        if (!run.id().equals(output.runId()) || stepNo != output.stepNo()
                || !expectedExecutorVersion.equals(output.executorVersion())
                || !expectedContextHash.equalsIgnoreCase(output.contextHash())
                || !expectedPayloadDigest.equalsIgnoreCase(output.payloadDigest())) {
            throw new RunStepOutputConflictException();
        }
        if (codec == null) throw new RunStepOutputException("Run 步骤输出 codec 不可用");
        final String decoded;
        try {
            decoded = codec.decode(output.encryptedPayload(), output.keyVersion());
        } catch (RuntimeException ex) {
            throw new RunStepOutputException("既有 Run 步骤输出解密失败", ex);
        }
        if (decoded == null || !canonicalPayload.equals(decoded)
                || !hashValue(decoded).equalsIgnoreCase(output.payloadDigest())) {
            throw new RunStepOutputConflictException();
        }
        return true;
    }

    /** Verify the persisted artifact before publishing the answer projection. */
    private void verifyStepOutputConsistency(AgentRun run, AnswerExecution execution,
                                             AnswerLineage lineage, AnswerProjection projection) {
        if (!durableRunProperties.durableResume() || stepOutputRepository == null) return;
        RunStepOutput output;
        try {
            output = stepOutputRepository.find(run.id(), 1).orElse(null);
        } catch (RuntimeException ex) {
            throw new RunStepOutputException("读取 Run 步骤输出失败", ex);
        }
        if (output == null || output.expired(Instant.now())) {
            throw new RunStepOutputException("Run 步骤输出缺失或已过期");
        }
        if (!run.id().equals(output.runId()) || output.stepNo() != 1
                || !executor.getClass().getName().equals(output.executorVersion())
                || output.keyVersion() == null || output.keyVersion().isBlank()) {
            throw new RunStepOutputException("Run 步骤输出元数据不一致");
        }
        String canonicalPayload = canonicalStepOutput(run, 1, execution, lineage);
        if (!contextHash(run).equalsIgnoreCase(output.contextHash())
                || !hashValue(canonicalPayload).equalsIgnoreCase(output.payloadDigest())) {
            throw new RunStepOutputException("Run 步骤输出与答案投影不一致");
        }
        ModelInvocationCacheCodec codec = stepOutputCodec;
        if (codec == null) throw new RunStepOutputException("Run 步骤输出 codec 不可用");
        final String decoded;
        try {
            decoded = codec.decode(output.encryptedPayload(), output.keyVersion());
        } catch (RuntimeException ex) {
            throw new RunStepOutputException("Run 步骤输出解密失败", ex);
        }
        if (decoded == null || !canonicalPayload.equals(decoded)
                || !hashValue(decoded).equalsIgnoreCase(output.payloadDigest())) {
            throw new RunStepOutputException("Run 步骤输出密文摘要校验失败");
        }
        verifyArtifactProjectionFields(decoded, projection);
        String answerDigest = hashValue(execution.answer() == null ? "" : execution.answer());
        // Legacy text executors do not populate AnswerProjection.answer when no
        // AnswerContract exists; structured executors must match the projection.
        if (execution.contract() != null && !answerDigest.equalsIgnoreCase(projection.answerDigest())) {
            throw new RunStepOutputException("Run 步骤输出答案摘要不一致");
        }
    }

    private void verifyArtifactProjectionFields(String decoded, AnswerProjection projection) {
        try {
            var payload = outputMapper.readTree(decoded);
            if (!answerIdValue(payload, "answerId").equals(safe(projection.answerId()))
                    || !answerIdValue(payload, "status").equals(safe(projection.status()))
                    || !answerIdValue(payload, "gameVersion").equals(safe(projection.gameReleaseId()))
                    || !answerIdValue(payload, "battleFormat").equals(safe(projection.battleFormatId()))
                    || !answerIdValue(payload, "rulesetVersion").equals(safe(projection.rulesetVersionId()))
                    || !answerIdValue(payload, "traceId").equals(safe(projection.traceId()))) {
                throw new RunStepOutputException("Run 步骤输出与答案投影字段不一致");
            }
            var lineage = payload.path("lineage");
            if (!answerIdValue(lineage, "dlcId").equals(safe(projection.dlcId()))
                    || !answerIdValue(lineage, "dataReleaseId").equals(safe(projection.dataReleaseId()))
                    || !answerIdValue(lineage, "knowledgeReleaseId").equals(safe(projection.knowledgeReleaseId()))
                    || !answerIdValue(lineage, "indexReleaseId").equals(safe(projection.indexReleaseId()))
                    || !answerIdValue(lineage, "contractVersion").equals(safe(projection.contractVersion()))) {
                throw new RunStepOutputException("Run 步骤输出血缘与答案投影不一致");
            }
        } catch (RunStepOutputException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            throw new RunStepOutputException("Run 步骤输出结构无效", ex);
        } catch (RuntimeException ex) {
            throw new RunStepOutputException("Run 步骤输出结构无效", ex);
        }
    }

    private String answerIdValue(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node == null || node.path(field).isMissingNode() || node.path(field).isNull()
                ? "" : node.path(field).asText("");
    }

    private boolean sameImmutableOutput(RunStepOutput left, RunStepOutput right) {
        return left != null && right != null
                && left.runId().equals(right.runId())
                && left.stepNo() == right.stepNo()
                && left.executorVersion().equals(right.executorVersion())
                && left.contextHash().equalsIgnoreCase(right.contextHash())
                && left.payloadDigest().equalsIgnoreCase(right.payloadDigest())
                && left.encryptedPayload().equals(right.encryptedPayload())
                && left.keyVersion().equals(right.keyVersion());
    }

    /** Produce deterministic JSON before encryption; no prompt or authorization data is included. */
    private String canonicalStepOutput(AgentRun run, int stepNo, AnswerExecution execution,
                                      AnswerLineage lineage) {
        AnswerContract contract = execution.contract();
        lineage = lineage == null ? AnswerLineage.empty() : lineage;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "run-step-output.v1");
        payload.put("runId", run.id());
        payload.put("stepNo", stepNo);
        payload.put("answerId", answerId(run));
        payload.put("answer", execution.answer() == null ? "" : execution.answer());
        payload.put("status", contract == null ? AgentRunStatus.COMPLETED.name() : contract.status().name());
        payload.put("gameVersion", firstNonBlank(contract == null ? null : contract.applicableGameVersion(),
                run.gameReleaseId()));
        payload.put("battleFormat", firstNonBlank(contract == null ? null : contract.battleFormat(),
                run.battleFormat()));
        payload.put("rulesetVersion", firstNonBlank(contract == null ? null : contract.rulesetVersion(),
                run.rulesetVersionId()));
        List<Map<String, Object>> citations = new java.util.ArrayList<>();
        if (contract != null && contract.citations() != null) {
            for (var citation : contract.citations()) {
                if (citation != null) citations.add(citationPayload(citation));
            }
        }
        payload.put("citations", citations);
        payload.put("limitations", contract == null ? List.of() : contract.limitations());
        payload.put("traceId", firstNonBlank(contract == null ? null : contract.traceId(), run.traceId()));
        Map<String, Object> lineagePayload = new LinkedHashMap<>();
        lineagePayload.put("dlcId", firstNonBlank(lineage.dlcId(), run.dlcId()));
        lineagePayload.put("dataReleaseId", firstNonBlank(lineage.dataReleaseId(), run.dataReleaseId()));
        lineagePayload.put("knowledgeReleaseId", firstNonBlank(lineage.knowledgeReleaseId(), run.knowledgeReleaseId()));
        lineagePayload.put("indexReleaseId", firstNonBlank(lineage.indexReleaseId(), run.indexReleaseId()));
        // AnswerProjection gives an extension lineage contract version precedence
        // only when all release IDs are present; keep the artifact byte-for-byte
        // aligned with that resolution rule.
        String contractVersion = lineage.hasReleaseLineage()
                ? firstNonBlank(lineage.contractVersion(), run.contractVersion())
                : firstNonBlank(run.contractVersion(), lineage.contractVersion());
        lineagePayload.put("contractVersion", contractVersion);
        payload.put("lineage", lineagePayload);
        try {
            return outputMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new RunStepOutputException("Run 步骤输出规范化失败", ex);
        } catch (RuntimeException ex) {
            throw new RunStepOutputException("Run 步骤输出规范化失败", ex);
        }
    }

    private String normalizeStepOutputKeyVersion(String value) {
        return value == null || value.isBlank() ? "managed" : value.trim();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() || "unknown".equalsIgnoreCase(first.trim())
                ? (second == null || second.isBlank() || "unknown".equalsIgnoreCase(second.trim())
                ? null : second.trim()) : first.trim();
    }

    private record AnswerExecution(String answer, AnswerContract contract, boolean streamed) {
        private AnswerExecution(String answer, AnswerContract contract) { this(answer, contract, false); }
    }

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
            case "agent.delegation.started", "agent.delegation.completed", "agent.delegation.fallback" -> AgentEventType.ROUTE_SELECTED;
            default -> null;
        };
        if (type == null) return;
        workerWrite(run, () -> {
            if (run.status().isTerminal()) return null;
            if (type == AgentEventType.APPROVAL_REQUIRED
                    && run.status() == AgentRunStatus.RUNNING) {
                run.transitionTo(AgentRunStatus.WAITING_APPROVAL);
                runRepository.save(run);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolName", event.toolName());
            payload.put("governanceEventId", event.eventId());
            if(event.eventType().startsWith("agent.delegation."))payload.put("delegationEvent",event.eventType());
            payload.putAll(event.attributes());
            // A cache hit need not emit tool.started. Keep its terminal outcome explicit.
            if (!payload.containsKey("status")) {
                switch (event.eventType()) {
                    case "tool.deduplicated" -> payload.put("status", "FAILED".equals(payload.get("effectStatus")) ? "FAILED" : "DEDUPLICATED");
                    case "tool.rejected" -> payload.put("status", "REJECTED");
                    case "tool.failed" -> payload.put("status", "FAILED");
                    default -> { }
                }
            }
            emitLocked(run, type, payload);
            if(type==AgentEventType.TOOL_FINISHED) {
                var usage=modelUsageByRun.get(run.id());
                if(usage!=null)usage.toolTiming(event.eventId(),event.toolName(),payload.get("timing"));
            }
            return null;
        });
    }

    private void recordAnswerReady(com.vio.vioaiagent.observability.ModelUsageLedger usage,String path) {
        Long millis=usage.answerReady(path);
        if(millis!=null && metrics!=null)metrics.recordPublicAnswerReady(millis,path);
    }

    private void timeout(String runId) {
        AgentRun run = activeRuns.get(runId);
        if (run == null) run = runRepository.findById(runId).orElse(null);
        if (run == null) return;
        AgentRun timeoutRun = run;
        boolean nestedInWorkerFence = fencedLease.get() != null;
        try {
            workerWrite(timeoutRun, () -> {
                if (timeoutRun.status().isTerminal()) return null;
                if (!timeoutRun.timeout()) return null;
                if (!saveLifecycle(timeoutRun)) return null;
                emitLocked(timeoutRun, AgentEventType.RUN_TIMED_OUT, Map.of("reason", "deadline_exceeded"));
                return null;
            });
        } finally {
            // Revoke only after the timeout fact has left its lifecycle fence;
            // revoking inside the transaction would make workerWrite roll back
            // the very terminal state it just committed.
            if (!nestedInWorkerFence) {
                cancelTask(runId);
                cleanIdleRun(timeoutRun);
            }
        }
    }

    private void cancelIfActive(AgentRun run, String reason) {
        try {
            workerWrite(run, () -> {
                if (run.status().isTerminal() || !run.cancel()) return null;
                if (!saveLifecycle(run)) return null;
                emitLocked(run, AgentEventType.RUN_CANCELLED, Map.of("reason", reason));
                return null;
            });
        } finally {
            // The lease must remain active until workerWrite has returned so a
            // terminal cancellation cannot roll back its own fenced transaction.
            cancelTask(run.id());
            cleanIdleRun(run);
        }
    }

    private void failIfActive(AgentRun run, Exception ex) {
        workerWrite(run, () -> {
            if (run.status().isTerminal() || !run.fail()) return null;
            if (!saveLifecycle(run)) return null;
            String errorType = ex instanceof com.vio.vioaiagent.observability.TaskBudgetExceededException taskFailure ? taskFailure.errorType()
                    : ex instanceof com.vio.vioaiagent.observability.ContextBudgetExceededException ? "RUN_CONTEXT_BUDGET_EXCEEDED"
                    : ex instanceof RunQueueTimeoutException ? "RUN_QUEUE_TIMEOUT"
                    : ex instanceof RunDispatchException ? "RUN_EXECUTION_UNAVAILABLE"
                    : ex instanceof RunAuthorizationExpiredException ? "RUN_AUTHORIZATION_EXPIRED"
                    : ex.getClass().getSimpleName();
            if (ex instanceof com.vio.vioaiagent.agent.DamageCalculationFailedException) {
                emitLocked(run, AgentEventType.RUN_FAILED, Map.of("errorType", "DAMAGE_CALCULATION_FAILED", "message", ex.getMessage()));
            } else emitLocked(run, AgentEventType.RUN_FAILED, Map.of("errorType", errorType));
            if (metrics != null) metrics.recordError();
            log.warn("Run 执行失败 runId={}, errorType={}", run.id(), ex.getClass().getSimpleName());
            return null;
        });
    }

    private boolean transition(AgentRun run, AgentRunStatus target, Map<String, Object> payload) {
        return workerWrite(run, () -> {
            if (!run.transitionTo(target)) return false;
            if (!saveLifecycle(run)) return false;
            if (target == AgentRunStatus.RUNNING) emitLocked(run, AgentEventType.RUN_STARTED, payload);
            return true;
        });
    }

    private boolean saveLifecycle(AgentRun run) {
        try {
            runRepository.save(run);
            return true;
        } catch (RunConflictException conflict) {
            // Let the worker fence roll back first. Control paths retain their existing CAS behavior.
            if (executionLease.get() != null) throw conflict;
            refreshAfterConflict(run, conflict);
            return false;
        }
    }

    private void emit(AgentRun run, AgentEventType type, Map<String, Object> payload) {
        workerWrite(run, () -> { emitLocked(run, type, payload); return null; });
    }

    /** Persist completed attempt snapshots with the owner-scoped answer, including pre-approval model work. */
    private List<org.springframework.ai.chat.messages.Message> historyWindow(AgentRun run,com.vio.vioaiagent.observability.ModelUsageLedger usage,List<ChatMessage> storedHistory) {
        var window=ConversationHistory.select(run,storedHistory,contextBudgetProperties);
        usage.historySource(window.summary());return window.messages();
    }

    private List<Map<String,Object>> usageHistory(AgentRun run,com.vio.vioaiagent.observability.ModelUsageLedger current) {
        Map<String,Map<String,Object>> attempts=new LinkedHashMap<>();
        for(var event:eventRepository.findAfter(run.id(),0)) {
            var payload=event.payload();
            if(event.type()==AgentEventType.USAGE && "model-usage-v1".equals(payload.get("schemaVersion"))
                    && payload.get("attemptId") instanceof String id)attempts.put(id,payload);
        }
        var snapshot=current.freeze();attempts.put((String)snapshot.get("attemptId"),snapshot);
        return List.copyOf(attempts.values());
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
        if(type.isTerminal()) {
            var taskBudget=taskBudgets.get(run.id());
            if(taskBudget!=null)taskBudget.close();
            var usage=modelUsageByRun.get(run.id());
            if(usage!=null)enrichedPayload.put("modelUsage",usage.freeze());
        }
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
                try {
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
                } finally {
                    cancelTask(run.id());
                    // 即使冲突刷新过另一份快照，删除会话后该 Run 也不再属于
                    // 可见资源；无条件清掉本进程索引，避免保留孤儿快照。
                    activeRuns.remove(run.id());
                    tokenBindings.remove(run.id());
                    principalPolicies.remove(run.id());
                    cleanIdleRun(run);
                }
            }
        }
    }

    private boolean conversationVisible(AgentRun run, String userId) {
        return run != null && userId != null
                && conversationRepository.findByIdForUser(run.conversationId(), userId).isPresent();
    }

    private void evictDetachedRun(AgentRun run) {
        if (run == null) return;
        cancelTask(run.id());
        activeRuns.remove(run.id(), run);
        tokenBindings.remove(run.id());
        principalPolicies.remove(run.id());
        cleanIdleRun(run);
    }

    private Duration remainingTimeout(AgentRun run) {
        if (run.deadlineAt() == null) return Duration.ofMinutes(2);
        long millis = Duration.between(Instant.now(), run.deadlineAt()).toMillis();
        return Duration.ofMillis(Math.max(1L, millis));
    }

    private boolean acquireLease(AgentRun run, ExecutionLease execution) {
        execution.requireActive();
        if (checkpointRepository == null || !durableRunProperties.durableResume()) return true;
        Duration ttl = Duration.ofSeconds(durableRunProperties.leaseTtlSeconds());
        Optional<RunLease> lease;
        try { lease = checkpointRepository.acquireLease(run.id(), execution.owner, execution.token, ttl); }
        catch (RuntimeException ex) {
            execution.loseOwnership();
            throw new RunLeaseLostException();
        }
        execution.requireActive();
        if (lease.isEmpty()) { execution.loseOwnership(); return false; }
        long interval = Math.max(1L, Math.min(durableRunProperties.heartbeatSeconds(),
                Math.max(1L, durableRunProperties.leaseTtlSeconds() / 2)));
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                () -> heartbeat(run, execution), interval, interval, TimeUnit.SECONDS);
        heartbeatTasks.put(run.id(), heartbeat);
        return true;
    }

    private void heartbeat(AgentRun run, ExecutionLease execution) {
        if (execution.revoked.get() || run.status().isTerminal() || checkpointRepository == null) return;
        try {
            if (checkpointRepository.heartbeat(run.id(), execution.owner, execution.token,
                    Duration.ofSeconds(durableRunProperties.leaseTtlSeconds())).isEmpty()) {
                log.warn("Run 租约心跳失败，停止本地 worker: runId={}", run.id());
                execution.loseOwnership();
                execution.task.cancel(true);
            }
        } catch (RuntimeException ex) {
            log.warn("Run 租约心跳异常，停止本地 worker: runId={}", run.id());
            execution.loseOwnership();
            execution.task.cancel(true);
        }
    }

    private void releaseLease(AgentRun run, ExecutionLease execution) {
        ScheduledFuture<?> heartbeat = heartbeatTasks.remove(run.id());
        if (heartbeat != null) heartbeat.cancel(false);
        if (checkpointRepository != null && durableRunProperties.durableResume()) {
            try {
                checkpointRepository.releaseLease(run.id(), execution.owner, execution.token);
            } catch (RuntimeException ex) {
                log.warn("释放 Run 租约失败（将由过期回收处理）: runId={}", run.id());
            }
        }
    }

    /** 保存一个新版本 checkpoint；失败时在启用耐久恢复的环境中直接阻止继续执行。 */
    private void saveCheckpoint(AgentRun run, int stepNo, String nextAction, String leaseOwner) {
        if (checkpointRepository == null) return;
        try {
            workerWrite(run, () -> {
                // A physical worker may ignore interrupt and return after an
                // external cancel/timeout. Terminal runs must never receive a
                // stale step checkpoint; the sole terminal checkpoint is the
                // successful step-2/finished fact written after COMPLETED.
                boolean finished = stepNo == 2
                        && run.status() == AgentRunStatus.COMPLETED
                        && "finished".equals(nextAction);
                if (run.status().isTerminal() && !finished) return null;
                if (stepNo == 2 && !finished) return null;
                // CAS belongs to this step, not the highest step from a previous execution.
                long version = checkpointRepository.findByRun(run.id()).stream()
                        .filter(checkpoint -> checkpoint.stepNo() == stepNo)
                        .findFirst().map(AgentCheckpoint::version).orElse(0L);
                AgentCheckpoint checkpoint = new AgentCheckpoint(run.id(), stepNo,
                        executor.getClass().getName(), contextHash(run), eventRepository.lastEventId(run.id()),
                        nextAction, leaseOwner,
                        leaseOwner == null ? null : Instant.now().plusSeconds(durableRunProperties.leaseTtlSeconds()),
                        leaseOwner == null ? null : Instant.now(), Instant.now(), version);
                checkpointRepository.save(checkpoint);
                return null;
            });
        } catch (RunLeaseLostException | RunConflictException ex) {
            throw ex;
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
        var constraints = constraintsForRun(run);
        if (constraints.revision() > 0) value += "|constraints:" + hashValue(String.join("\u001f",
                "conversation-constraints-v1", Long.toString(constraints.revision()), constraints.responseStyle().name(),
                constraints.trainingBudget().name(), Boolean.toString(constraints.avoidLegendary()), constraints.notes()));
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
        return requestHash(command, command.traceId());
    }

    /** Preserve the persisted hash format; trace-only retries may reuse a proven owned Run. */
    private boolean matchesCreateRequest(IdempotencyRecord record, CreateRunCommand command,
                                         String incomingHash, Optional<AgentRun> replay) {
        if (record.requestHash().equals(incomingHash)) return true;
        if (replay.isEmpty()) return false;
        // Legacy commands may have omitted traceId before the Run generated its trace.
        return record.requestHash().equals(requestHash(command, replay.get().traceId()))
                || record.requestHash().equals(requestHash(command, null));
    }

    private String requestHash(CreateRunCommand command, String originalTraceId) {
        String canonical = String.join("\u001f", safe(command.userId()), safe(command.sessionId()),
                safe(command.conversationId()), safe(command.prompt()), safe(command.gameReleaseId()),
                safe(command.battleFormat()), safe(command.rulesetVersionId()), safe(command.language()),
                safe(command.dlcId()), safe(command.dataReleaseId()), safe(command.knowledgeReleaseId()),
                safe(command.indexReleaseId()), safe(command.contractVersion()),
                safe(originalTraceId), String.valueOf(command.timeout().toMillis()));
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
                    restoreValidatedAuthorizationSnapshot(run);
                    AgentCheckpoint saved = checkpoint.get();
                    RunRecoveryPolicy.Decision decision = recoveryDecision(run, saved);
                    if (decision.canResume()) {
                        // dispatch is the only checkpoint that proves no model/tool work started.
                        taskBudgets.putIfAbsent(run.id(),new com.vio.vioaiagent.observability.TaskModelBudget(taskBudgetProperties));
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

    /** Restore policy only after current policy configuration accepts the snapshot. */
    private void restoreValidatedAuthorizationSnapshot(AgentRun run) {
        if (run == null || authorizationSnapshotRepository == null || authorizationSnapshotValidator == null) return;
        try {
            Optional<com.vio.vioaiagent.security.AuthorizationSnapshot> found =
                    authorizationSnapshotRepository.findByRunId(run.id());
            if (found.isEmpty() || !authorizationSnapshotValidator.validate(found.get()).allowed()) return;
            if (authorizationRevocationRepository != null
                    && authorizationRevocationRepository.findByRunId(run.id()).isPresent()) return;
            var snapshot = found.get();
            Instant expires = snapshot.expiresAt();
            TokenBinding binding = TokenBinding.oidc(snapshot.principalId(), snapshot.tenantId(), snapshot.roles(),
                    snapshot.allowedTools(), snapshot.policyVersion(), 0, expires);
            PrincipalPolicy policy = new PrincipalPolicy(snapshot.principalId(), snapshot.tenantId(),
                    snapshot.roles(), snapshot.allowedTools(), snapshot.policyVersion(), expires, binding);
            if (!policy.fingerprint().equalsIgnoreCase(snapshot.policyFingerprint())) return;
            principalPolicies.put(run.id(), policy);
            tokenBindings.put(run.id(), binding);
        } catch (RuntimeException ex) {
            principalPolicies.remove(run.id());
            tokenBindings.remove(run.id());
            log.warn("授权快照恢复校验失败，按安全失败处理: runId={}, errorType={}",
                    run.id(), ex.getClass().getSimpleName());
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
                reserveRecoveredRun(run);
                taskBudgets.putIfAbsent(run.id(),restoreWaitingTaskBudget(run));
                activeRuns.putIfAbsent(run.id(), run);
                hitlManager.rearmExpiry(pending);
                armDeadline(run);
            } catch (RuntimeException ex) {
                activeRuns.remove(run.id(), run);
                cleanIdleRun(run);
                log.warn("恢复 WAITING_APPROVAL 定时器失败，按安全失败处理: runId={}, reason={}",
                        run.id(), ex.getClass().getSimpleName());
                return false;
            }
        }
        log.info("Run 已恢复为等待审批: runId={}, approvalId={}, expiresAt={}",
                run.id(), approvalId.get(), pending.expiresAt());
        return true;
    }

    /** A frozen usage event must follow the latest approval, otherwise remaining credit is unknown. */
    private com.vio.vioaiagent.observability.TaskModelBudget restoreWaitingTaskBudget(AgentRun run) {
        try {
            long approvalEvent=0;
            AgentEvent latestUsage=null;
            for(var event:eventRepository.findAfter(run.id(),0)) {
                if(event.type()==AgentEventType.APPROVAL_REQUIRED)approvalEvent=event.eventId();
                if(event.type()==AgentEventType.USAGE)latestUsage=event;
            }
            if(approvalEvent>0 && latestUsage!=null && latestUsage.eventId()>approvalEvent
                    && "model-usage-v1".equals(latestUsage.payload().get("schemaVersion"))
                    && "INSTRUMENTED".equals(latestUsage.payload().get("coverage"))
                    && "EXECUTION_ATTEMPT".equals(latestUsage.payload().get("scope"))
                    && !Boolean.TRUE.equals(latestUsage.payload().get("recordsTruncated"))
                    && latestUsage.payload().get("calls") instanceof List<?> calls
                    && latestUsage.payload().get("modelCallCount") instanceof Number count
                    && count.longValue()==calls.size()
                    && calls.stream().allMatch(row->row instanceof Map<?,?> call
                        && Set.of("RETURNED","FAILED","CANCELLED").contains(call.get("outcome")))
                    && latestUsage.payload().get("taskBudget") instanceof Map<?,?> state
                    && state.get("modelCalls") instanceof Number cumulative
                    && cumulative.longValue()>=count.longValue()) {
                return com.vio.vioaiagent.observability.TaskModelBudget.restore(state,taskBudgetProperties);
            }
        } catch(RuntimeException invalidSnapshot) {
            log.warn("任务额度恢复不可核实: runId={}, errorType={}",run.id(),invalidSnapshot.getClass().getSimpleName());
        }
        return com.vio.vioaiagent.observability.TaskModelBudget.unavailable(taskBudgetProperties);
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
            try {
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
            } finally {
                cleanIdleRun(run);
            }
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
            payload.put("principalId", policy.principalId());
            payload.put("tenantId", policy.tenantId());
            payload.put("roles", policy.roles().stream().sorted().toList());
            payload.put("allowedTools", policy.allowedTools().stream().sorted().toList());
            payload.put("policyVersion", policy.policyVersion());
            payload.put("policyFingerprint", policy.fingerprint());
            payload.put("bindingId", policy.tokenBinding() == null ? "" : policy.tokenBinding().tokenId());
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

    private static RunAdmissionProperties admissionProperties(ObjectProvider<RunAdmissionProperties> provider) {
        return provider == null ? new RunAdmissionProperties() : provider.getIfAvailable(RunAdmissionProperties::new);
    }

    static ThreadPoolExecutor newWorker(RunAdmissionProperties properties) {
        return new ThreadPoolExecutor(properties.threadCount(), properties.threadCount(), 0, TimeUnit.MILLISECONDS,
                properties.queueCapacity() == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(properties.queueCapacity()), runnable -> {
            Thread thread = new Thread(runnable, "vio-agent-run");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    private static ScheduledExecutorService newScheduler() {
        ScheduledThreadPoolExecutor scheduled = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "vio-run-timeout");
            thread.setDaemon(true);
            return thread;
        });
        scheduled.setRemoveOnCancelPolicy(true);
        return scheduled;
    }

    /** Aggregate counts only: no account, tenant, prompt or credential identifiers. */
    public RuntimeSnapshot runtimeSnapshot() {
        RunAdmissionController.Snapshot limits = admission.snapshot();
        ThreadPoolExecutor pool = worker instanceof ThreadPoolExecutor value ? value : null;
        return new RuntimeSnapshot(limits.reserved(), limits.capacity(), tasks.size(),
                pool == null ? null : pool.getActiveCount(), pool == null ? null : pool.getQueue().size(),
                deadlineTasks.size(), queueTasks.size(), limits.principalScopes(), limits.tenantScopes(), limits.accepting());
    }
    public record RuntimeSnapshot(int reserved, int capacity, int tasks, Integer executing, Integer queued,
                                  int deadlineTimers, int queueTimers, int principalScopes, int tenantScopes,
                                  boolean accepting) { }

    @PreDestroy
    public void shutdown() {
        synchronized (idempotencyLock) {
            admission.close();
            for (AgentRun run : List.copyOf(activeRuns.values())) {
                if (run.status() != AgentRunStatus.WAITING_APPROVAL) {
                    try { cancelIfActive(run, "service_shutdown"); }
                    catch (RuntimeException ex) { log.warn("Run shutdown state unavailable: errorType={}", ex.getClass().getSimpleName()); }
                }
            }
            for (FutureTask<Void> task : List.copyOf(tasks.values())) {
                if (task instanceof ManagedRunTask managed) managed.revokeExecutionLease();
                task.cancel(true);
            }
            for (Runnable queued : worker.shutdownNow()) if (queued instanceof FutureTask<?> task) task.cancel(true);
            for (AgentRun run : List.copyOf(activeRuns.values())) cleanIdleRun(run);
            deadlineTasks.values().forEach(timer -> timer.cancel(false));
            queueTasks.values().forEach(timer -> timer.cancel(false));
            deadlineTasks.clear();
            queueTasks.clear();
            scheduler.shutdownNow();
        }
    }
}
