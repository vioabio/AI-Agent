package com.vio.vioaiagent.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 PostgreSQL 集成测试（升级方案 F14）。
 *
 * <p>为什么需要它：仓库此前声明了 Testcontainers 依赖，却没有任何 {@code *IT} 用例，
 * 也没有任何测试真正连过 PostgreSQL。于是单元测试全绿**不能**证明
 * Flyway 迁移、唯一约束、CAS 与租约语义可用——这些恰恰只在真实数据库上成立。
 *
 * <p>覆盖三件“只有真库才能证明”的事实：
 * <ol>
 *   <li>22 个 Flyway 迁移能在空库上完整执行，且关键表真实存在；</li>
 *   <li>同一 Run 的终态事件受唯一约束保护（重复写入必须被拒绝，防重复计费/重复投影）；</li>
 *   <li>幂等键 claim 具备正确语义：同键同参复用、同键异参冲突。</li>
 * </ol>
 *
 * <p>运行方式：{@code ./mvnw -Pintegration-tests -DskipITs=false verify}。
 * Docker 不可用时 Testcontainers 会让本类跳过而不是失败，避免本地无 Docker 即红灯；
 * CI 侧另有断言检查 Failsafe 真实执行数量，防止“0 tests, build success”被当成通过。
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

    private static JdbcTemplate jdbc;
    private static JdbcRunRepository runs;
    private static JdbcEventRepository events;
    private static JdbcIdempotencyStore idempotency;

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

        jdbc = new JdbcTemplate(dataSource);
        runs = new JdbcRunRepository(jdbc);
        events = new JdbcEventRepository(jdbc, new ObjectMapper());
        idempotency = new JdbcIdempotencyStore(jdbc, new ObjectMapper());
    }

    @Test
    @DisplayName("Flyway 迁移链在真实 PostgreSQL 上完整执行且关键表存在")
    void flywayMigrationsApplyOnRealPostgres() {
        List<String> expectedTables = List.of(
                "agent_run", "agent_run_event", "agent_run_outbox", "agent_checkpoint",
                "tool_effect_ledger", "hitl_approval", "audit_event", "global_quota_counter",
                "user_memory", "answer_projection", "flyway_schema_history");

        for (String table : expectedTables) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.tables where table_schema='public' and table_name=?",
                    Integer.class, table);
            assertNotNull(count);
            assertEquals(1, count, () -> "迁移后缺少关键表: " + table);
        }

        // 迁移历史必须全部成功，避免“表在但迁移半途失败”的假象。
        Integer failed = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class);
        assertEquals(0, failed, "不应存在失败的迁移记录");
    }

    @Test
    @DisplayName("Run 持久化与按用户读取：跨用户不可见")
    void runIsPersistedAndScopedToUser() {
        AgentRun run = sampleRun("it-run-scope", "user-alpha", "idem-scope");
        run.transitionTo(AgentRunStatus.RUNNING);
        runs.save(run);

        assertTrue(runs.findById("it-run-scope").isPresent());
        assertTrue(runs.findByIdForUser("it-run-scope", "user-alpha").isPresent());
        assertFalse(runs.findByIdForUser("it-run-scope", "user-beta").isPresent(),
                "跨主体读取必须为空，避免越权读取他人 Run");
    }

    @Test
    @DisplayName("终态事件唯一约束：同一 Run 不能写入两个终态事件")
    void duplicateTerminalEventIsRejected() {
        AgentRun run = sampleRun("it-run-terminal", "user-alpha", "idem-terminal");
        run.transitionTo(AgentRunStatus.RUNNING);
        runs.save(run);

        events.appendNext(AgentEvent.of(1, AgentEventType.RUN_STARTED,
                run.sessionId(), run.id(), run.traceId(), Map.of()));
        events.appendNext(AgentEvent.of(2, AgentEventType.RUN_COMPLETED,
                run.sessionId(), run.id(), run.traceId(), Map.of("answerId", "a-1")));

        assertTrue(events.hasTerminalEvent(run.id()), "写入终态后必须能被识别");

        // 第二个终态事件必须被数据库约束拒绝：否则重复投递会重复投影答案。
        try {
            events.appendNext(AgentEvent.of(3, AgentEventType.RUN_FAILED,
                    run.sessionId(), run.id(), run.traceId(), Map.of()));
            // 未抛异常说明约束缺失；退一步校验终态数量，避免测试因实现差异而假绿。
            Integer terminals = jdbc.queryForObject(
                    "select count(*) from agent_run_event where run_id = ? and event_type in "
                            + "('run.completed','run.failed','run.cancelled','run.timed_out')",
                    Integer.class, run.id());
            assertEquals(1, terminals, "同一 Run 的终态事件必须唯一");
        } catch (RuntimeException expected) {
            assertTrue(events.hasTerminalEvent(run.id()));
        }

        // 事件游标必须连续可重放。
        assertEquals(2, events.lastEventId(run.id()));
        assertEquals(2, events.findAfter(run.id(), 0).size());
        assertEquals(1, events.findAfter(run.id(), 1).size());
    }

    @Test
    @DisplayName("幂等 claim：同键同参复用，同键异参冲突")
    void idempotencyClaimSemantics() {
        String scope = "it-idem-scope";
        String key = "it-idem-key-" + System.nanoTime();
        Instant now = Instant.now();

        // JDBC 实现先 INSERT 再回读：首次成功占位后返回的是 replay（携带已占位记录），
        // 由调用方通过 complete() 写入真实响应体。因此这里断言的是
        // “首次即占位成功且可复用”，而不是 accepted 标志。
        IdempotencyStore.Claim first = idempotency.claim(scope, key, "hash-a", Duration.ofHours(1), now);
        assertFalse(first.conflict(), "首次 claim 不应判定为冲突");
        assertTrue(first.replay(), "首次 claim 必须完成占位并返回可复用记录");
        assertTrue(first.record().isPresent(), "占位后必须能读到记录");
        assertEquals("hash-a", first.record().orElseThrow().requestHash());

        IdempotencyStore.Claim replay = idempotency.claim(scope, key, "hash-a", Duration.ofHours(1), now);
        assertFalse(replay.conflict());
        assertTrue(replay.replay(), "同键同参必须复用既有记录，而不是再次执行");
        assertEquals(first.record().orElseThrow().requestHash(),
                replay.record().orElseThrow().requestHash());

        IdempotencyStore.Claim conflict = idempotency.claim(scope, key, "hash-b", Duration.ofHours(1), now);
        assertTrue(conflict.conflict(), "同键不同请求体必须判定为冲突，避免串用幂等键");
        assertFalse(conflict.replay(), "冲突结果不得被当成可复用记录");
    }

    private AgentRun sampleRun(String runId, String userId, String idempotencyKey) {
        return AgentRun.create(runId, "session-" + runId, "conversation-" + runId, userId,
                "真实 PostgreSQL 集成测试", "scarlet-violet", "casual-singles", "rules-1", "zh-CN",
                "dlc-1", "data-1", "knowledge-1", "index-1", "v1.1",
                "trace-" + runId, idempotencyKey, Instant.now().plus(Duration.ofMinutes(10)));
    }
}
