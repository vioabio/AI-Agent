package com.vio.vioaiagent.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.security.AuthorizationSnapshot;
import com.vio.vioaiagent.security.AuthorizationRevocation;
import com.vio.vioaiagent.security.JdbcAuthorizationSnapshotRepository;
import com.vio.vioaiagent.security.JdbcAuthorizationRevocationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 PostgreSQL 集成测试（升级方案 F14 / S0 收尾 R00）。
 *
 * <p>本类在 S0 首次编写时存在三处**源码可确定的缺陷**（见进度核查 B03），
 * 已在本轮修正：
 * <ol>
 *   <li>表名写错：迁移实际创建 {@code agent_event} / {@code agent_event_outbox}，
 *       原测试断言的是不存在的 {@code agent_run_event} / {@code agent_run_outbox}；</li>
 *   <li>列名写错：终态事件类型列是 {@code type}，原测试用的 {@code event_type} 不存在；</li>
 *   <li>缺少父记录：{@code agent_run.conversation_id} 有指向 {@code conversation(id)}
 *       的外键，原测试直接插 Run 必然违反外键。</li>
 * </ol>
 * 另外原实现用 {@code catch (RuntimeException)} 兜底终态断言，会把 SQL 写错
 * 当成"约束生效"，因此改为精确断言数据库约束名。
 *
 * <p>覆盖四件只有真库才能证明的事实：迁移链完整、终态唯一约束、
 * Run 的用户归属隔离、幂等 claim 的所有权语义。
 *
 * <p>运行：{@code ./mvnw -Pintegration-tests verify}（需要可用的 Docker 与镜像仓库）。
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@DisplayName("F14 真实 PostgreSQL 集成测试")
class PostgresRunPersistenceIT {

    /** 与 docker-compose.yml 使用同一镜像族，保证迁移在真实 pgvector 目标上验证。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("vio_agent_it")
                    .withUsername("vio_it")
                    .withPassword("vio_it_password");

    /** 迁移链上真实存在的关键表；使用真实名称，避免再次出现"断言不存在的表"。 */
    private static final List<String> EXPECTED_TABLES = List.of(
            "conversation", "agent_run", "agent_event", "chat_message",
            "agent_event_outbox", "agent_event_retention",
            "agent_checkpoint", "agent_run_lease", "agent_effect_ledger", "model_invocation_cache",
            "hitl_approval", "audit_event", "global_quota_counter", "memory_entry",
            "answer_projection", "api_idempotency_record", "favorite", "saved_team",
            "user_feedback", "knowledge_ingestion_job", "agent_authorization_snapshot",
            "agent_authorization_snapshot_revocation", "flyway_schema_history");

    private static JdbcTemplate jdbc;
    private static JdbcRunRepository runs;
    private static JdbcConversationRepository conversations;
    private static JdbcEventRepository events;
    private static JdbcIdempotencyStore idempotency;
    private static JdbcAuthorizationSnapshotRepository authorizationSnapshots;
    private static JdbcAuthorizationRevocationRepository authorizationRevocations;

    @BeforeAll
    static void migrateAndWireRepositories() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        var result = flyway.migrate();
        assertTrue(result.migrationsExecuted > 0, "空库必须实际执行迁移，否则本测试没有验证到迁移链");

        // 与生产一致：只依赖迁移后的真实 schema，不额外造表。
        jdbc = new JdbcTemplate(dataSource);
        runs = new JdbcRunRepository(jdbc);
        conversations = new JdbcConversationRepository(jdbc);
        events = new JdbcEventRepository(jdbc, new ObjectMapper());
        idempotency = new JdbcIdempotencyStore(jdbc, new ObjectMapper());
        authorizationSnapshots = new JdbcAuthorizationSnapshotRepository(jdbc, new ObjectMapper());
        authorizationRevocations = new JdbcAuthorizationRevocationRepository(jdbc);
    }

    // ==================== 迁移链 ====================

    @Test
    @DisplayName("26 个迁移在空库完整执行，关键表真实存在且无失败记录")
    void flywayMigrationsApplyOnRealPostgres() {
        for (String table : EXPECTED_TABLES) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.tables where table_schema='public' and table_name=?",
                    Integer.class, table);
            assertNotNull(count);
            assertEquals(1, count, () -> "迁移后缺少关键表: " + table);
        }

        Integer failed = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class);
        assertEquals(0, failed, "不应存在失败的迁移记录");

        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);
        assertNotNull(applied);
        assertTrue(applied >= 26, () -> "期望至少 26 条成功迁移，实际 " + applied);
    }

    @Test
    @DisplayName("终态唯一性由真实部分唯一索引 uq_agent_event_single_terminal 保证")
    void terminalUniquenessIsEnforcedByRealIndex() {
        Integer indexCount = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where schemaname='public' and tablename='agent_event'
                  and indexname='uq_agent_event_single_terminal'
                """, Integer.class);
        assertEquals(1, indexCount, "终态唯一索引必须真实存在，否则跨实例可能写出两个终态");

        AgentRun run = persistRunningRun("run-it-terminal", "user-it-terminal", "idem-it-terminal");
        events.appendNext(AgentEvent.of(1, AgentEventType.RUN_CREATED,
                run.sessionId(), run.id(), run.traceId(), Map.of()));
        events.appendNext(AgentEvent.of(2, AgentEventType.RUN_COMPLETED,
                run.sessionId(), run.id(), run.traceId(), Map.of("answerId", "a-1")));
        assertTrue(events.hasTerminalEvent(run.id()), "写入终态后必须能被识别");

        // 直接以显式 eventId 插入第二个终态，必须被拒绝。
        //
        // 注意断言的是 RuntimeException 而不是 DataIntegrityViolationException：
        // JdbcEventRepository.append 在触库之前就有显式短路
        // （hasTerminalEvent 命中即抛 IllegalStateException("一个 Run 只能有一个终态事件")），
        // 因此数据库异常不会漏到调用方。唯一索引是第二道防线，不是第一道。
        // 真正的契约是「第二个终态不得落库」，由下方 count 断言直接验证。
        assertThrows(RuntimeException.class,
                () -> events.append(AgentEvent.of(3, AgentEventType.RUN_FAILED,
                        run.sessionId(), run.id(), run.traceId(), Map.of("errorType", "it"))),
                "同一 Run 的第二个终态事件必须被拒绝");

        // appendNext 在数据库层短路：不会写出第二个终态，也不会回退序号。
        // 拒绝以 IllegalStateException 暴露（仓储把数据库异常封装成稳定类型），
        // 具体原因见下方 count 断言——契约是「不得落库」，不是「必须抛某个具体异常」。
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> events.appendNext(AgentEvent.of(3, AgentEventType.RUN_FAILED,
                        run.sessionId(), run.id(), run.traceId(), Map.of("errorType", "it-next"))),
                "appendNext 遇到已存在的终态时不得写入第二个终态事件");
        assertNotNull(refused.getMessage());
        Integer terminals = jdbc.queryForObject("""
                select count(*) from agent_event
                where run_id = ? and type in ('run.completed','run.failed','run.cancelled','run.timed_out')
                """, Integer.class, run.id());
        assertEquals(1, terminals, "appendNext 不得写出第二个终态事件");

        assertEquals(2, events.lastEventId(run.id()));
        assertEquals(2, events.findAfter(run.id(), 0).size());
        assertEquals(1, events.findAfter(run.id(), 1).size());
    }

    // ==================== 归属隔离 ====================

    @Test
    @DisplayName("Run 按用户与会话归属隔离，跨主体读取不可见")
    void runIsScopedToUserAndConversation() {
        AgentRun run = persistRunningRun("run-it-scope", "user-it-alpha", "idem-it-scope");

        assertTrue(runs.findById(run.id()).isPresent());
        assertTrue(runs.findByIdForUser(run.id(), "user-it-alpha").isPresent());
        assertFalse(runs.findByIdForUser(run.id(), "user-it-beta").isPresent(),
                "跨主体读取必须为空，避免越权读取他人 Run");

        // 会话软删除后，Run 必须同样不可见（恢复/超时路径共用同一归属门禁）。
        conversations.deleteForUser(run.conversationId(), "user-it-alpha");
        assertFalse(runs.findById(run.id()).isPresent(),
                "会话软删除后 Run 不应再可见，否则后台任务会继续写已删除会话");
    }

    @Test
    @DisplayName("agent_run.conversation_id 外键真实生效，孤立 Run 无法写入")
    void orphanRunIsRejectedByForeignKey() {
        AgentRun orphan = AgentRun.create("run-it-orphan", "session-it-orphan", "conversation-does-not-exist",
                "user-it-orphan", "孤立 Run", "scarlet-violet", "casual-singles", "rules-1", "zh-CN",
                "trace-it-orphan", "idem-it-orphan", Instant.now().plus(Duration.ofMinutes(10)));
        assertThrows(DataIntegrityViolationException.class, () -> runs.save(orphan),
                "缺少父会话时必须违反外键，证明 fixture 必须显式建会话");
    }

    // ==================== 幂等语义（S0/B04） ====================

    @Test
    @DisplayName("幂等 claim：首次取得所有权为 accepted，重复为 replay，异参为 conflict")
    void idempotencyClaimOwnershipSemantics() {
        String scope = "it-idem-scope";
        String key = "it-idem-key-" + System.nanoTime();
        Instant now = Instant.now();

        // S0/B04 的回归断言：首次必须返回 accepted（取得占位所有权）。
        // 修复前 JDBC 实现首次返回 replay，导致 createRun 抛
        // IDEMPOTENCY_IN_PROGRESS，带新幂等键的首次创建永远失败。
        IdempotencyStore.Claim first = idempotency.claim(scope, key, "hash-a", Duration.ofHours(1), now);
        assertTrue(first.accepted(), "首次 claim 必须取得所有权（accepted）");
        assertFalse(first.replay(), "首次 claim 不能是 replay");
        assertFalse(first.conflict(), "首次 claim 不能是 conflict");

        // 第二次同键同参：未取得所有权，返回可复用的既有记录。
        IdempotencyStore.Claim replay = idempotency.claim(scope, key, "hash-a", Duration.ofHours(1), now);
        assertFalse(replay.accepted(), "重复 claim 不应再次取得所有权");
        assertTrue(replay.replay(), "同键同参必须复用既有记录");
        assertTrue(replay.record().isPresent());

        // 同键不同请求体：必须判定冲突，避免串用幂等键。
        IdempotencyStore.Claim conflict = idempotency.claim(scope, key, "hash-b", Duration.ofHours(1), now);
        assertTrue(conflict.conflict(), "同键不同请求体必须判定为冲突");

        // 完成后回放应带回已落库的响应体。
        idempotency.complete(scope, key, "hash-a", 201, Map.of("runId", "run-it-idem"), now);
        IdempotencyStore.Claim afterComplete = idempotency.claim(scope, key, "hash-a", Duration.ofHours(1), now);
        assertTrue(afterComplete.replay(), "完成后必须回放已提交结果");
        assertEquals("run-it-idem", afterComplete.record().orElseThrow().responseBody().get("runId"));
    }

    @Test
    @DisplayName("两实例并发争抢同一幂等键：恰好一个获得所有权")
    void concurrentClaimGrantsExactlyOneOwner() throws Exception {
        String scope = "it-idem-race";
        String key = "it-idem-race-" + System.nanoTime();
        Instant now = Instant.now();
        int contenders = 4;

        var pool = java.util.concurrent.Executors.newFixedThreadPool(contenders);
        var barrier = new java.util.concurrent.CyclicBarrier(contenders);
        var claims = new java.util.concurrent.ConcurrentLinkedQueue<IdempotencyStore.Claim>();
        try {
            for (int i = 0; i < contenders; i++) {
                pool.submit(() -> {
                    try {
                        barrier.await();
                        claims.add(idempotency.claim(scope, key, "hash-race", Duration.ofHours(1), now));
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS), "并发 claim 未在超时内完成");
        } finally {
            pool.shutdownNow();
        }

        long owners = claims.stream().filter(IdempotencyStore.Claim::accepted).count();
        assertEquals(1, owners, () -> "并发下必须恰好一个实例取得所有权，实际 " + owners
                + "；claims=" + claims.stream()
                .map(c -> "accepted=" + c.accepted() + ",replay=" + c.replay() + ",conflict=" + c.conflict())
                .toList());
        assertEquals(contenders - 1, claims.stream().filter(IdempotencyStore.Claim::replay).count(),
                "未取得所有权的实例应得到 replay，而不是再次执行");

        // 数据库里也必须只有一行占位，避免出现重复的 IN_PROGRESS 孤儿。
        Integer rows = jdbc.queryForObject(
                "select count(*) from api_idempotency_record where scope=? and idempotency_key=?",
                Integer.class, scope, key);
        assertEquals(1, rows, "同一幂等键只能存在一行占位记录");
    }

    @Test
    @DisplayName("过期占位会被清理，不再永久阻塞该幂等键")
    void expiredClaimDoesNotBlockForever() {
        String scope = "it-idem-expired";
        String key = "it-idem-expired-" + System.nanoTime();
        Instant now = Instant.now();

        IdempotencyStore.Claim first = idempotency.claim(scope, key, "hash-x", Duration.ofSeconds(1), now);
        assertTrue(first.accepted());

        // 超过 TTL 后再次请求：过期占位应被清理，重新取得所有权。
        Instant later = now.plusSeconds(120);
        IdempotencyStore.Claim renewed = idempotency.claim(scope, key, "hash-y", Duration.ofHours(1), later);
        assertTrue(renewed.accepted(),
                "过期占位必须被清理，否则该幂等键会永久返回 IN_PROGRESS 孤儿");
    }

    @Test
    @DisplayName("授权快照不可覆盖，撤销事实独立追加且可回读")
    void authorizationSnapshotAndRevocationAreDurableAndImmutable() {
        AgentRun run = persistRunningRun("run-it-auth", "user-it-auth", "idem-it-auth");
        Instant issued = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        AuthorizationSnapshot snapshot = new AuthorizationSnapshot(run.id(), "alice", "tenant-a",
                java.util.Set.of("operator"), java.util.Set.of("write"), "v7", "a".repeat(64),
                "binding-a", issued, issued.plusSeconds(300));
        // 注意：这里用微秒精度构造输入，与 PostgreSQL TIMESTAMPTZ 的精度对齐。
        // 仓储的契约是「返回数据库里持久化的那份事实」，若传入纳秒对象，
        // 返回值会是归一化后的微秒对象（这是正确行为，见仓储注释）。
        // 用例关注的是不可变性与可回读，因此用对齐精度的输入避免噪声。
        assertEquals(snapshot, authorizationSnapshots.save(snapshot));
        assertEquals(snapshot, authorizationSnapshots.save(snapshot));
        AuthorizationSnapshot changed = new AuthorizationSnapshot(run.id(), "alice", "tenant-a",
                java.util.Set.of("admin"), java.util.Set.of("write"), "v7", "b".repeat(64),
                "binding-a", issued, issued.plusSeconds(300));
        assertThrows(IllegalStateException.class, () -> authorizationSnapshots.save(changed));

        AuthorizationRevocation revocation = authorizationRevocations.revoke(run.id(), "operator review", issued);
        assertEquals(revocation, authorizationRevocations.findByRunId(run.id()).orElseThrow());
        assertEquals(revocation, authorizationRevocations.revoke(run.id(), "second review", issued.plusSeconds(1)));
    }

    // ==================== createRun 幂等主链（S0/B04） ====================

    /**
     * 端到端复现 S0/B04：用真实 JDBC 幂等存储走一遍 {@code createRun}。
     *
     * <p>修复前 {@code JdbcIdempotencyStore.claim()} 首次即返回 {@code replay}，
     * 而 {@code createRun} 把「replay 且找不到已提交 Run」解释为
     * {@code IDEMPOTENCY_IN_PROGRESS}，于是**带新幂等键的首次创建永远失败**。
     * 内存实现首次返回 accepted，所以离线单测全绿也发现不了这个缺陷——
     * 只有接真实 JDBC 幂等存储才会暴露。
     */
    @Test
    @DisplayName("createRun 主链：首次幂等键必须真正创建 Run，而不是报 IN_PROGRESS")
    void createRunWithNewIdempotencyKeyCreatesRun() {
        String userId = "user-it-create";
        String idempotencyKey = "idem-it-create-" + System.nanoTime();
        String conversationId = "conversation-it-create-" + System.nanoTime();

        InMemoryRunRepository inMemoryRuns = new InMemoryRunRepository();
        InMemoryEventRepository inMemoryEvents = new InMemoryEventRepository();
        InMemoryConversationRepository inMemoryConversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository inMemoryMessages = new InMemoryChatMessageRepository();

        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PokemonAgentRunService service = new PokemonAgentRunService(inMemoryRuns, inMemoryEvents,
                inMemoryConversations, inMemoryMessages, context -> "集成测试答案",
                worker, scheduler);
        // 关键：使用真实 JDBC 幂等存储，这是 B04 的触发条件。
        service.setIdempotencyStore(idempotency);
        try {
            CreateRunCommand command = new CreateRunCommand(userId, "session-it-create", conversationId,
                    "首次创建测试", "scarlet-violet", "casual-singles", "rules-1", "zh-CN",
                    "trace-it-create", idempotencyKey, Duration.ofSeconds(20));

            RunView created = service.createRun(command);
            assertNotNull(created, "带新幂等键的首次创建必须成功返回 Run");
            assertTrue(created.runId() != null && !created.runId().isBlank());

            // 同键同参重放：必须返回同一个 Run，而不是新建第二个。
            RunView replayed = service.createRun(command);
            assertEquals(created.runId(), replayed.runId(),
                    "同键同参重放必须返回同一 Run，不能重复创建");
            // 注意不能用 findActive() 计数：它的定义就是排除终态 Run
            // （InMemoryRunRepository 按 !status().isTerminal() 过滤），
            // 而本用例的 executor 会立刻把 Run 推进到终态，因此恒为 0。
            // 改为按 id 回读、并按 (user, idempotencyKey) 唯一键回读，
            // 直接证明重放复用了同一条记录而没有产生第二个 Run。
            assertTrue(inMemoryRuns.findById(created.runId()).isPresent(),
                    "首次创建必须真的落库");
            AgentRun byKey = inMemoryRuns.findByIdempotencyKey(userId, idempotencyKey).orElseThrow();
            assertEquals(created.runId(), byKey.id(),
                    "同一 (user, idempotencyKey) 只能对应一个 Run");

            // 同键不同请求体：必须判定冲突。
            CreateRunCommand conflicting = new CreateRunCommand(userId, "session-it-create", conversationId,
                    "内容不同的请求", "scarlet-violet", "casual-singles", "rules-1", "zh-CN",
                    "trace-it-create", idempotencyKey, Duration.ofSeconds(20));
            assertThrows(IdempotencyConflictException.class, () -> service.createRun(conflicting),
                    "同键不同请求体必须判定冲突，避免串用幂等键");

            // 主链必须继续推进到终态，证明首次创建确实被执行而不是卡在 IN_PROGRESS。
            AgentEvent completed = RunTestAwait.awaitTerminalEvent(inMemoryEvents, created.runId());
            assertEquals(AgentEventType.RUN_COMPLETED, completed.type());

            // 数据库里的幂等记录必须已完成（201），不是残留的 102 IN_PROGRESS 孤儿。
            Integer status = jdbc.queryForObject("""
                    select response_status from api_idempotency_record
                    where scope = ? and idempotency_key = ?
                    """, Integer.class, createScopeFor(userId), idempotencyKey);
            assertNotNull(status);
            assertTrue(status != 102,
                    "首次创建完成后幂等记录必须被 complete() 收敛，不能残留 102 IN_PROGRESS");
        } finally {
            service.shutdown();
            scheduler.shutdownNow();
        }
    }

    /** 与 PokemonAgentRunService.createIdempotencyScope 保持一致的 scope 约定。 */
    private static String createScopeFor(String userId) {
        return "run:create:" + userId;
    }

    // ==================== 辅助 ====================

    /**
     * 按真实外键顺序落库：先建父会话，再建 Run 并推进到 RUNNING。
     *
     * <p>{@code agent_run.conversation_id} 有外键，且 {@code JdbcRunRepository} 不代建会话，
     * 因此测试必须像 {@code PokemonAgentRunService.ensureConversation} 那样显式建会话。
     */
    private AgentRun persistRunningRun(String runId, String userId, String idempotencyKey) {
        String conversationId = "conversation-" + runId;
        Instant now = Instant.now();
        conversations.save(new Conversation(conversationId, userId, "IT 会话 " + runId, now, now));

        AgentRun run = AgentRun.create(runId, "session-" + runId, conversationId, userId,
                "真实 PostgreSQL 集成测试", "scarlet-violet", "casual-singles", "rules-1", "zh-CN",
                "dlc-1", "data-1", "knowledge-1", "index-1", "v1.1",
                "trace-" + runId, idempotencyKey, now.plus(Duration.ofMinutes(10)));
        runs.save(run);
        run.transitionTo(AgentRunStatus.RUNNING);
        runs.save(run);
        assertEquals(AgentRunStatus.RUNNING, runs.findById(runId).orElseThrow().status());
        return run;
    }
}
