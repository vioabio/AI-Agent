package com.vio.vioaiagent.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R09: PostgreSQL lease ownership, real row locks and fenced cross-repository rollback.
 * Run with {@code ./mvnw -Pintegration-tests verify}; requires Docker and the pgvector image.
 * A Docker-disabled skip is not evidence that these database guarantees passed.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@DisplayName("R09 PostgreSQL 租约 fencing 与原子回滚")
class PostgresRunLeaseFencingIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("vio_agent_fencing_it")
                    .withUsername("vio_it")
                    .withPassword("vio_it_password");

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    /** Separate DataSource identity prevents observer reads joining the test's transaction. */
    private static JdbcTemplate observer;
    private static JdbcRunRepository runs;
    private static JdbcConversationRepository conversations;
    private static JdbcDurableRunStateRepository durable;
    private static JdbcEventRepository events;
    private static JdbcChatMessageRepository messages;
    private static JdbcAnswerProjectionRepository projections;
    private static JdbcRunOutboxRepository outbox;
    private static JdbcRunEventOutboxAppender appender;
    private static PlatformTransactionManager transactionManager;

    @BeforeAll
    static void migrateAndWireTransactionProxies() {
        var result = Flyway.configure().dataSource(dataSource())
                .locations("classpath:db/migration").load().migrate();
        assertTrue(result.migrationsExecuted > 0, "The empty PostgreSQL database must run the real migrations");
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("prod");
        context.register(TransactionConfiguration.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        observer = new JdbcTemplate(dataSource());
        runs = context.getBean(JdbcRunRepository.class);
        conversations = context.getBean(JdbcConversationRepository.class);
        durable = context.getBean(JdbcDurableRunStateRepository.class);
        events = context.getBean(JdbcEventRepository.class);
        messages = context.getBean(JdbcChatMessageRepository.class);
        projections = context.getBean(JdbcAnswerProjectionRepository.class);
        outbox = context.getBean(JdbcRunOutboxRepository.class);
        appender = context.getBean(JdbcRunEventOutboxAppender.class);
        transactionManager = context.getBean(PlatformTransactionManager.class);
        assertTrue(AopUtils.isAopProxy(durable), "The test must invoke the real @Transactional fence proxy");
        assertTrue(AopUtils.isAopProxy(appender), "Event and outbox writes must use the production transaction proxy");
    }

    @AfterAll
    static void closeContext() {
        if (context != null) context.close();
    }

    @Test
    @DisplayName("实际数据库只允许匹配 owner/token 写入，替换后旧 owner 无权释放或续租")
    void liveLeaseRejectsWrongIdentityAndOldOwnerAfterReplacement() {
        AgentRun run = persistRunningRun("fence-it-identity");
        RunLease original = acquire(run, "owner-a", "token-a");
        AtomicInteger actions = new AtomicInteger();
        assertEquals(1, durable.withLease(run.id(), original.owner(), original.token(), actions::incrementAndGet));
        assertThrows(RunLeaseLostException.class,
                () -> durable.withLease(run.id(), "owner-b", original.token(), actions::incrementAndGet));
        assertThrows(RunLeaseLostException.class,
                () -> durable.withLease(run.id(), original.owner(), "token-b", actions::incrementAndGet));
        assertThrows(RunLeaseLostException.class,
                () -> durable.withLease("fence-it-missing", original.owner(), original.token(), actions::incrementAndGet));
        assertTrue(durable.acquireLease(run.id(), original.owner(), "token-b", Duration.ofMinutes(1)).isEmpty(),
                "An active owner cannot silently replace its physical execution token");
        assertTrue(durable.releaseLease(run.id(), original.owner(), original.token()));
        RunLease replacement = acquire(run, "owner-b", "token-b");
        assertThrows(RunLeaseLostException.class,
                () -> durable.withLease(run.id(), original.owner(), original.token(), actions::incrementAndGet));
        assertFalse(durable.releaseLease(run.id(), original.owner(), original.token()));
        assertTrue(durable.heartbeat(run.id(), original.owner(), original.token(), Duration.ofMinutes(1)).isEmpty());
        assertEquals(replacement, durable.currentLease(run.id()).orElseThrow());
        assertEquals(1, actions.get(), "Every rejected fence must leave its persistence callback untouched");
        assertEquals(2, durable.withLease(run.id(), replacement.owner(), replacement.token(), actions::incrementAndGet));
    }

    @Test
    @DisplayName("数据库时间判定过期，接管后旧 token 不能再次提交")
    void expiredLeaseAllowsTakeoverAndPermanentlyFencesOldToken() {
        AgentRun run = persistRunningRun("fence-it-expired");
        RunLease original = acquire(run, "owner-old", "token-old");
        // Advance this fixture to an expired state using the database clock, without a timing sleep.
        assertEquals(1, jdbc.update("""
                UPDATE agent_run_lease
                SET lease_expires_at = clock_timestamp() - interval '1 second'
                WHERE run_id = ?
                """, run.id()));
        AtomicInteger actions = new AtomicInteger();
        assertThrows(RunLeaseLostException.class,
                () -> durable.withLease(run.id(), original.owner(), original.token(), actions::incrementAndGet));
        assertTrue(durable.heartbeat(run.id(), original.owner(), original.token(), Duration.ofMinutes(1)).isEmpty());
        RunLease replacement = acquire(run, "owner-new", "token-new");
        assertTrue(replacement.version() > original.version());
        assertThrows(RunLeaseLostException.class,
                () -> durable.withLease(run.id(), original.owner(), original.token(), actions::incrementAndGet));
        assertEquals(0, actions.get());
        assertEquals(1, durable.withLease(run.id(), replacement.owner(), replacement.token(), actions::incrementAndGet));
    }

    @Test
    @DisplayName("同一 fence 内六类仓储全部写入后抛异常，已更新及新增事实一起回滚")
    void failedFenceRollsBackCheckpointRunProjectionMessageEventsAndOutbox() {
        AgentRun run = persistRunningRun("fence-it-rollback");
        RunLease lease = acquire(run, "rollback-owner", "rollback-token");
        appender.append(run, AgentEventType.RUN_STARTED, 1, Map.of("baseline", true));
        AgentCheckpoint beforeCheckpoint = durable.save(AgentCheckpoint.initial(run.id(), "it-executor",
                "it-context-hash", "execute-answer"));
        messages.save(new ChatMessage("baseline-message-" + run.id(), run.conversationId(), run.id(),
                run.userId(), "user", "baseline prompt", microsecondNow(), Map.of()));
        AgentRun.LifecycleSnapshot beforeRun = runs.findById(run.id()).orElseThrow().lifecycleSnapshot();
        List<AgentEvent> beforeEvents = events.findAfter(run.id(), 0);
        List<ChatMessage> beforeMessages = messages.findByConversationForUser(run.conversationId(), run.userId());
        RunOutboxMessage beforeOutbox = outbox.find(run.id() + ":1").orElseThrow();
        String answerId = "answer-" + run.id();
        String messageId = "tentative-message-" + run.id();
        IllegalStateException failure = new IllegalStateException("forced failure after all repositories wrote");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> durable.withLease(
                run.id(), lease.owner(), lease.token(), () -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    durable.save(beforeCheckpoint.withProgress(0, 1, "commit-answer", lease.owner(),
                            lease.expiresAt(), lease.heartbeatAt()));
                    assertTrue(run.complete());
                    runs.save(run);
                    projections.save(AnswerProjection.from(run, null, answerId, microsecondNow()));
                    messages.save(new ChatMessage(messageId, run.conversationId(), run.id(), run.userId(),
                            "assistant", "tentative answer", microsecondNow(), Map.of("answerId", answerId)));
                    appender.append(run, AgentEventType.ANSWER_COMPLETED, 2, Map.of("answerId", answerId));
                    AgentEvent terminal = appender.append(run, AgentEventType.RUN_COMPLETED, 3,
                            Map.of("answerId", answerId));
                    durable.save(AgentCheckpoint.initial(run.id(), "it-executor", "it-context-hash", "finished")
                            .withProgress(2, terminal.eventId(), "finished", lease.owner(),
                                    lease.expiresAt(), lease.heartbeatAt()));

                    // Prove the callback reached every write before causing rollback.
                    assertEquals(AgentRunStatus.COMPLETED, runs.findById(run.id()).orElseThrow().status());
                    assertEquals(beforeRun.version() + 1, run.version());
                    assertEquals(2, durable.findByRun(run.id()).size());
                    assertEquals("commit-answer", durable.findByRun(run.id()).getFirst().nextAction());
                    assertTrue(projections.findById(answerId).isPresent());
                    assertEquals(2, messages.findByConversationForUser(run.conversationId(), run.userId()).size());
                    assertEquals(3, events.findAfter(run.id(), 0).size());
                    assertEquals(3, outboxCount(run.id()));
                    throw failure;
                })));

        // These reads run after the proxy has returned and completed its rollback.
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(beforeRun, runs.findById(run.id()).orElseThrow().lifecycleSnapshot());
        assertEquals(List.of(beforeCheckpoint), durable.findByRun(run.id()));
        assertTrue(projections.findById(answerId).isEmpty());
        assertEquals(beforeMessages, messages.findByConversationForUser(run.conversationId(), run.userId()));
        assertEquals(beforeEvents, events.findAfter(run.id(), 0));
        assertFalse(events.hasTerminalEvent(run.id()));
        assertEquals(1, outboxCount(run.id()));
        assertEquals(beforeOutbox, outbox.find(run.id() + ":1").orElseThrow());
        assertTrue(outbox.find(run.id() + ":2").isEmpty());
        assertTrue(outbox.find(run.id() + ":3").isEmpty());
        assertTrue(durable.releaseLease(run.id(), lease.owner(), lease.token()),
                "A failed callback must also release the transaction's lease row lock");
    }

    @Test
    @DisplayName("fence 在外层事务中使用独立连接，提交及回滚均在返回调用方前完成")
    void fenceFinishesCommitAndRollbackBeforeResumingOuterTransaction() {
        AgentRun run = persistRunningRun("fence-it-outer");
        RunLease lease = acquire(run, "outer-owner", "outer-token");
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        String originalTitle = observer.queryForObject("SELECT title FROM conversation WHERE id = ?",
                String.class, run.conversationId());
        outer.executeWithoutResult(status -> {
            int outerPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            jdbc.update("UPDATE conversation SET title = ? WHERE id = ?", "outer-uncommitted", run.conversationId());
            durable.withLease(run.id(), lease.owner(), lease.token(), () -> {
                int fencePid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                assertNotEquals(outerPid, fencePid, "REQUIRES_NEW must suspend the outer database connection");
                durable.save(AgentCheckpoint.initial(run.id(), "it-executor", "it-context-hash", "dispatch"));
                return null;
            });
            assertEquals(1, observer.queryForObject("SELECT count(*) FROM agent_checkpoint WHERE run_id = ?",
                    Integer.class, run.id()), "An independent observer must see the fence commit before outer completion");
            assertEquals(originalTitle, observer.queryForObject("SELECT title FROM conversation WHERE id = ?",
                    String.class, run.conversationId()));
            assertEquals(outerPid, jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));

            IllegalStateException failure = new IllegalStateException("nested fence rollback");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> durable.withLease(
                    run.id(), lease.owner(), lease.token(), () -> {
                        durable.save(AgentCheckpoint.initial(run.id(), "it-executor", "it-context-hash", "finished")
                                .withProgress(2, 0, "finished", lease.owner(), lease.expiresAt(), lease.heartbeatAt()));
                        throw failure;
                    })));
            assertEquals(0, observer.queryForObject("""
                    SELECT count(*) FROM agent_checkpoint WHERE run_id = ? AND step_no = 2
                    """, Integer.class, run.id()));
            assertFalse(status.isRollbackOnly(), "The failed independent fence must not poison the resumed outer transaction");
            assertEquals(outerPid, jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            status.setRollbackOnly();
        });
        assertEquals(originalTitle, observer.queryForObject("SELECT title FROM conversation WHERE id = ?",
                String.class, run.conversationId()));
        assertEquals(1, durable.findByRun(run.id()).size(), "Outer rollback must preserve the already committed fence");
        assertEquals("dispatch", durable.findLatest(run.id()).orElseThrow().nextAction());
    }

    @Test
    @DisplayName("不同 PostgreSQL backend 的释放操作等待 fence 行锁，提交后可释放并接管")
    void concurrentConnectionWaitsForFenceCommitBeforeReleasingAndReplacingLease() throws Exception {
        AgentRun run = persistRunningRun("fence-it-lock");
        RunLease original = acquire(run, "lock-owner", "lock-token");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        AtomicInteger contenderPid = new AtomicInteger();
        try {
            Future<String> action = pool.submit(() -> durable.withLease(run.id(), original.owner(), original.token(), () -> {
                holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                entered.countDown();
                awaitLatch(finish);
                durable.save(AgentCheckpoint.initial(run.id(), "it-executor", "it-context-hash", "dispatch"));
                return "committed";
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "The first connection must enter its locked fence action");
            Future<Boolean> release = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                contenderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                return durable.releaseLease(run.id(), original.owner(), original.token());
            }));
            // Observe PostgreSQL's actual wait relation, not a JVM thread state or fixed sleep.
            await().pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(5)).until(() ->
                    contenderPid.get() > 0 && Boolean.TRUE.equals(observer.queryForObject(
                            "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, holderPid.get(), contenderPid.get())));
            assertNotEquals(holderPid.get(), contenderPid.get());
            assertFalse(release.isDone(), "A second connection cannot replace ownership during the fenced callback");
            finish.countDown();
            assertEquals("committed", action.get(5, TimeUnit.SECONDS));
            assertTrue(release.get(5, TimeUnit.SECONDS));
            assertEquals("dispatch", durable.findLatest(run.id()).orElseThrow().nextAction());
            RunLease replacement = acquire(run, "lock-replacement-owner", "lock-replacement-token");
            assertThrows(RunLeaseLostException.class,
                    () -> durable.withLease(run.id(), original.owner(), original.token(), () -> "late write"));
            assertEquals("new owner", durable.withLease(run.id(), replacement.owner(), replacement.token(), () -> "new owner"));
        } finally {
            finish.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Both database workers must exit after releasing the latch");
        }
    }

    private static AgentRun persistRunningRun(String runId) {
        Instant now = microsecondNow();
        String userId = "user-" + runId;
        String conversationId = "conversation-" + runId;
        conversations.save(new Conversation(conversationId, userId, "IT " + runId, now, now));
        AgentRun run = AgentRun.create(runId, "session-" + runId, conversationId, userId, "fencing test",
                "scarlet-violet", "casual-singles", "rules-1", "zh-CN", "dlc-1", "data-1",
                "knowledge-1", "index-1", "v1.1", "trace-" + runId, "idem-" + runId,
                now.plus(Duration.ofMinutes(10)));
        runs.save(run);
        assertTrue(run.transitionTo(AgentRunStatus.RUNNING));
        runs.save(run);
        // Use the database's persisted timestamp precision when comparing immutable projections.
        return runs.findById(run.id()).orElseThrow();
    }

    private static RunLease acquire(AgentRun run, String owner, String token) {
        return durable.acquireLease(run.id(), owner, token, Duration.ofMinutes(1)).orElseThrow();
    }

    private static int outboxCount(String runId) {
        return jdbc.queryForObject("SELECT count(*) FROM agent_event_outbox WHERE run_id = ?", Integer.class, runId);
    }

    private static Instant microsecondNow() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting to finish the fence action");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Fence action interrupted", ex);
        }
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
        @Bean DataSource postgresDataSource() { return dataSource(); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean JdbcRunRepository runs(JdbcTemplate jdbc) { return new JdbcRunRepository(jdbc); }
        @Bean JdbcConversationRepository conversations(JdbcTemplate jdbc) { return new JdbcConversationRepository(jdbc); }
        @Bean JdbcDurableRunStateRepository durable(JdbcTemplate jdbc, ObjectMapper mapper) {
            return new JdbcDurableRunStateRepository(jdbc, mapper);
        }
        @Bean JdbcEventRepository events(JdbcTemplate jdbc, ObjectMapper mapper) { return new JdbcEventRepository(jdbc, mapper); }
        @Bean JdbcChatMessageRepository messages(JdbcTemplate jdbc, ObjectMapper mapper) {
            return new JdbcChatMessageRepository(jdbc, mapper);
        }
        @Bean JdbcAnswerProjectionRepository projections(JdbcTemplate jdbc, ObjectMapper mapper) {
            return new JdbcAnswerProjectionRepository(jdbc, mapper);
        }
        @Bean JdbcRunOutboxRepository outbox(JdbcTemplate jdbc, ObjectMapper mapper) { return new JdbcRunOutboxRepository(jdbc, mapper); }
        @Bean JdbcRunEventOutboxAppender appender(JdbcEventRepository events, JdbcRunOutboxRepository outbox) {
            return new JdbcRunEventOutboxAppender(events, outbox);
        }
    }
}
