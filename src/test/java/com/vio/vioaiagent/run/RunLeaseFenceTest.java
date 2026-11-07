package com.vio.vioaiagent.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Repository fencing tests; real PostgreSQL row-lock behavior still requires the integration suite. */
class RunLeaseFenceTest {
    private static final String RUN = "fence-run";
    private static final String OWNER = "fence-owner";
    private static final String TOKEN = "fence-token";
    private static final Instant DATABASE_TIME = Instant.EPOCH;

    @Test
    void memoryFenceExecutesOnlyForMatchingLiveOwnerAndToken() {
        InMemoryDurableRunStateRepository repository = new InMemoryDurableRunStateRepository();
        repository.acquireLease(RUN, OWNER, TOKEN, Duration.ofMinutes(1)).orElseThrow();
        assertEquals("saved", repository.withLease(RUN, OWNER, TOKEN, () -> "saved"));

        AtomicInteger writes = new AtomicInteger();
        assertThrows(RunLeaseLostException.class,
                () -> repository.withLease(RUN, "other-owner", TOKEN, writes::incrementAndGet));
        assertThrows(RunLeaseLostException.class,
                () -> repository.withLease(RUN, OWNER, "other-token", writes::incrementAndGet));
        assertThrows(RunLeaseLostException.class,
                () -> repository.withLease("missing-run", OWNER, TOKEN, writes::incrementAndGet));
        assertEquals(0, writes.get());
    }

    @Test
    void memoryFenceRejectsExpiredLeaseAndAllowsNewOwnerTakeover() {
        InMemoryDurableRunStateRepository repository = new InMemoryDurableRunStateRepository();
        RunLease lease = repository.acquireLease(RUN, OWNER, TOKEN, Duration.ofMillis(20)).orElseThrow();
        await().atMost(Duration.ofSeconds(2)).until(() -> !lease.activeAt(Instant.now()));

        AtomicInteger writes = new AtomicInteger();
        assertThrows(RunLeaseLostException.class,
                () -> repository.withLease(RUN, OWNER, TOKEN, writes::incrementAndGet));
        repository.acquireLease(RUN, "new-owner", "new-token", Duration.ofMinutes(1)).orElseThrow();
        assertThrows(RunLeaseLostException.class,
                () -> repository.withLease(RUN, OWNER, TOKEN, writes::incrementAndGet));
        int committed = repository.withLease(RUN, "new-owner", "new-token", writes::incrementAndGet);
        assertEquals(1, committed);
    }

    @Test
    void sameOwnerCannotReplaceAnActiveToken() {
        InMemoryDurableRunStateRepository repository = new InMemoryDurableRunStateRepository();
        repository.acquireLease(RUN, OWNER, TOKEN, Duration.ofMinutes(1)).orElseThrow();

        assertTrue(repository.acquireLease(RUN, OWNER, "replacement", Duration.ofMinutes(1)).isEmpty());
        assertTrue(repository.acquireLease(RUN, OWNER, TOKEN, Duration.ofMinutes(1)).isPresent());
        assertEquals(TOKEN, repository.currentLease(RUN).orElseThrow().token());
    }

    @Test
    void memoryFenceRetainsOwnershipLockThroughAction() throws Exception {
        InMemoryDurableRunStateRepository repository = new InMemoryDurableRunStateRepository();
        repository.acquireLease(RUN, OWNER, TOKEN, Duration.ofMinutes(1)).orElseThrow();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicReference<Thread> contenderThread = new AtomicReference<>();
        try {
            Future<String> action = pool.submit(() -> repository.withLease(RUN, OWNER, TOKEN, () -> {
                entered.countDown();
                try {
                    if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fence action timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
                return "committed";
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<Boolean> release = pool.submit(() -> {
                contenderThread.set(Thread.currentThread());
                return repository.releaseLease(RUN, OWNER, TOKEN);
            });
            await().atMost(Duration.ofSeconds(2)).until(() -> contenderThread.get() != null
                    && contenderThread.get().getState() == Thread.State.BLOCKED);
            assertFalse(release.isDone(), "Lease replacement must wait for the persistence action");
            finish.countDown();
            assertEquals("committed", action.get(2, TimeUnit.SECONDS));
            assertTrue(release.get(2, TimeUnit.SECONDS));
            assertThrows(RunLeaseLostException.class, () -> repository.withLease(RUN, OWNER, TOKEN, () -> "late"));
        } finally {
            finish.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void memoryActionFailurePropagatesAndReleasesMonitor() {
        InMemoryDurableRunStateRepository repository = new InMemoryDurableRunStateRepository();
        repository.acquireLease(RUN, OWNER, TOKEN, Duration.ofMinutes(1)).orElseThrow();
        IllegalStateException failure = new IllegalStateException("action failure");

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> repository.withLease(RUN, OWNER, TOKEN, () -> { throw failure; })));
        assertTrue(repository.releaseLease(RUN, OWNER, TOKEN));
    }

    @Test
    void unsupportedFenceDefaultsToSafeDenialWithoutExecutingAction() {
        CheckpointRepository repository = mock(CheckpointRepository.class, CALLS_REAL_METHODS);
        AtomicInteger writes = new AtomicInteger();

        RunLeaseLostException denial = assertThrows(RunLeaseLostException.class,
                () -> repository.withLease(RUN, OWNER, TOKEN, writes::incrementAndGet));

        assertEquals(0, writes.get());
        assertSafe(denial);
    }

    @Test
    void jdbcFenceWithoutTransactionProxyFailsBeforeQueriesOrAction() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcDurableRunStateRepository repository = new JdbcDurableRunStateRepository(jdbc, new ObjectMapper());
        AtomicInteger writes = new AtomicInteger();

        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertSafe(assertThrows(RunLeaseLostException.class,
                () -> repository.withLease(RUN, OWNER, TOKEN, writes::incrementAndGet)));
        assertEquals(0, writes.get());
        assertTrue(jdbc.statements.isEmpty());
    }

    @Test
    void springTransactionProxyLocksThenReadsClockAndCommitsOrRollsBackAction() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            JdbcDurableRunStateRepository repository = context.getBean(JdbcDurableRunStateRepository.class);
            RecordingJdbcTemplate jdbc = context.getBean(RecordingJdbcTemplate.class);
            Connection connection = context.getBean(Connection.class);
            assertTrue(AopUtils.isAopProxy(repository), "This must exercise the actual @Transactional proxy");

            String result = repository.withLease(RUN, OWNER, TOKEN, () -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(2, jdbc.statements.size());
                assertTrue(jdbc.statements.get(0).contains("FOR UPDATE"));
                assertEquals("SELECT clock_timestamp()", jdbc.statements.get(1));
                jdbc.statements.add("ACTION");
                return "saved";
            });
            assertEquals("saved", result);
            verify(connection).commit();

            DataAccessResourceFailureException actionFailure = new DataAccessResourceFailureException("action failed");
            assertSame(actionFailure, assertThrows(DataAccessResourceFailureException.class,
                    () -> repository.withLease(RUN, OWNER, TOKEN, () -> { throw actionFailure; })));
            verify(connection).rollback();
            var order = inOrder(connection);
            order.verify(connection).setAutoCommit(false);
            order.verify(connection).commit();
            order.verify(connection).setAutoCommit(true);
            order.verify(connection).setAutoCommit(false);
            order.verify(connection).rollback();
            order.verify(connection).setAutoCommit(true);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        });
    }

    @Test
    void fenceCommitsBeforeReturningEvenWhenOuterTransactionLaterRollsBack() throws SQLException {
        runner().run(context -> {
            JdbcDurableRunStateRepository repository = context.getBean(JdbcDurableRunStateRepository.class);
            DataSource dataSource = context.getBean(DataSource.class);
            Connection outerConnection = context.getBean(Connection.class);
            Connection fenceConnection = mock(Connection.class);
            when(fenceConnection.getAutoCommit()).thenReturn(true);
            when(dataSource.getConnection()).thenReturn(outerConnection, fenceConnection);
            TransactionTemplate outer = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            IllegalStateException outerFailure = new IllegalStateException("outer failure");

            assertSame(outerFailure, assertThrows(IllegalStateException.class, () -> outer.execute(status -> {
                assertSame(outerConnection, DataSourceUtils.getConnection(dataSource));
                assertEquals("committed", repository.withLease(RUN, OWNER, TOKEN, () -> {
                    assertSame(fenceConnection, DataSourceUtils.getConnection(dataSource));
                    return "committed";
                }));
                assertDoesNotThrow(() -> {
                    verify(fenceConnection).commit();
                    verify(outerConnection, never()).commit();
                    verify(outerConnection, never()).rollback();
                });
                assertSame(outerConnection, DataSourceUtils.getConnection(dataSource),
                        "The outer transaction must be resumed after the fenced commit");
                throw outerFailure;
            })));

            verify(outerConnection).rollback();
            verify(fenceConnection, never()).rollback();
            var order = inOrder(fenceConnection, outerConnection);
            order.verify(fenceConnection).commit();
            order.verify(outerConnection).rollback();
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        });
    }

    @Test
    void fenceRollsBackBeforeFailureReachesOuterTransaction() throws SQLException {
        runner().run(context -> {
            JdbcDurableRunStateRepository repository = context.getBean(JdbcDurableRunStateRepository.class);
            DataSource dataSource = context.getBean(DataSource.class);
            Connection outerConnection = context.getBean(Connection.class);
            Connection fenceConnection = mock(Connection.class);
            when(fenceConnection.getAutoCommit()).thenReturn(true);
            when(dataSource.getConnection()).thenReturn(outerConnection, fenceConnection);
            TransactionTemplate outer = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            IllegalStateException fenceFailure = new IllegalStateException("fence failure");

            outer.executeWithoutResult(status -> {
                assertSame(fenceFailure, assertThrows(IllegalStateException.class,
                        () -> repository.withLease(RUN, OWNER, TOKEN, () -> { throw fenceFailure; })));
                assertDoesNotThrow(() -> verify(fenceConnection).rollback());
                assertSame(outerConnection, DataSourceUtils.getConnection(dataSource));
                assertFalse(status.isRollbackOnly(), "The fence must not poison the resumed outer transaction");
                assertDoesNotThrow(() -> {
                    verify(outerConnection, never()).commit();
                    verify(outerConnection, never()).rollback();
                });
            });

            verify(outerConnection).commit();
            verify(fenceConnection, never()).commit();
            var order = inOrder(fenceConnection, outerConnection);
            order.verify(fenceConnection).rollback();
            order.verify(outerConnection).commit();
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        });
    }

    @Test
    void jdbcFenceUsesDatabaseClockAndRejectsMissingWrongAndExpiredOwnership() {
        runner().run(context -> {
            JdbcDurableRunStateRepository repository = context.getBean(JdbcDurableRunStateRepository.class);
            RecordingJdbcTemplate jdbc = context.getBean(RecordingJdbcTemplate.class);
            Connection connection = context.getBean(Connection.class);
            AtomicInteger writes = new AtomicInteger();
            // The fixture database clock deliberately differs from the JVM clock.
            assertTrue(jdbc.lease.expiresAt().isBefore(Instant.now()));
            int committed = repository.withLease(RUN, OWNER, TOKEN, writes::incrementAndGet);
            assertEquals(1, committed);
            assertSafe(assertThrows(RunLeaseLostException.class,
                    () -> repository.withLease(RUN, "other-owner", TOKEN, writes::incrementAndGet)));
            assertSafe(assertThrows(RunLeaseLostException.class,
                    () -> repository.withLease(RUN, OWNER, "other-token", writes::incrementAndGet)));
            jdbc.databaseTime = jdbc.lease.expiresAt();
            assertSafe(assertThrows(RunLeaseLostException.class,
                    () -> repository.withLease(RUN, OWNER, TOKEN, writes::incrementAndGet)));
            jdbc.lease = null;
            assertSafe(assertThrows(RunLeaseLostException.class,
                    () -> repository.withLease(RUN, OWNER, TOKEN, writes::incrementAndGet)));
            assertEquals(1, writes.get());
            verify(connection, times(4)).rollback();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"lock", "clock", "null-clock"})
    void jdbcLeaseQueryFailuresRollbackAndExposeOnlySafeDenial(String boundary) {
        runner().run(context -> {
            JdbcDurableRunStateRepository repository = context.getBean(JdbcDurableRunStateRepository.class);
            RecordingJdbcTemplate jdbc = context.getBean(RecordingJdbcTemplate.class);
            Connection connection = context.getBean(Connection.class);
            jdbc.failureBoundary = boundary;
            AtomicInteger writes = new AtomicInteger();

            assertSafe(assertThrows(RunLeaseLostException.class,
                    () -> repository.withLease(RUN, OWNER, TOKEN, writes::incrementAndGet)));

            assertEquals(0, writes.get());
            verify(connection).rollback();
        });
    }

    @Test
    void jdbcAcquireUsesDatabaseClockAndRequiresSameTokenForActiveOwner() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcDurableRunStateRepository repository = new JdbcDurableRunStateRepository(jdbc, new ObjectMapper());
        repository.acquireLease(RUN, OWNER, TOKEN, Duration.ofSeconds(30));

        assertEquals("SELECT clock_timestamp()", jdbc.statements.get(0));
        String sql = jdbc.statements.get(1).replaceAll("\\s+", " ");
        assertTrue(sql.contains("lease_expires_at <= clock_timestamp()"));
        assertTrue(sql.contains("lease_owner = ? AND agent_run_lease.lease_token = ?"));
        assertArrayEquals(new Object[]{RUN, OWNER, TOKEN, Timestamp.from(DATABASE_TIME.plusSeconds(30)),
                Timestamp.from(DATABASE_TIME), Timestamp.from(DATABASE_TIME), OWNER, TOKEN}, jdbc.parameters.get(1));
    }

    @Test
    void jdbcHeartbeatUsesDatabaseClockAndStillMatchesOwnerAndToken() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcDurableRunStateRepository repository = new JdbcDurableRunStateRepository(jdbc, new ObjectMapper());
        repository.heartbeat(RUN, OWNER, TOKEN, Duration.ofSeconds(30));

        assertEquals("SELECT clock_timestamp()", jdbc.statements.get(0));
        String sql = jdbc.statements.get(1).replaceAll("\\s+", " ");
        assertTrue(sql.contains("run_id = ? AND lease_owner = ? AND lease_token = ?"));
        assertTrue(sql.contains("lease_expires_at > clock_timestamp()"));
        assertArrayEquals(new Object[]{Timestamp.from(DATABASE_TIME.plusSeconds(30)), Timestamp.from(DATABASE_TIME),
                Timestamp.from(DATABASE_TIME), RUN, OWNER, TOKEN}, jdbc.parameters.get(1));
    }

    private static void assertSafe(RunLeaseLostException denial) {
        assertEquals(new RunLeaseLostException().getMessage(), denial.getMessage());
        assertFalse(denial.getMessage().contains(TOKEN));
        assertFalse(denial.getMessage().contains(OWNER));
        assertNull(denial.getCause(), "SQL and token details must not survive as a nested cause");
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(TransactionConfiguration.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
        @Bean Connection connection() throws SQLException {
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            return connection;
        }

        @Bean DataSource dataSource(Connection connection) throws SQLException {
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);
            return dataSource;
        }

        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean RecordingJdbcTemplate jdbc(DataSource dataSource) {
            RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
            jdbc.setDataSource(dataSource);
            return jdbc;
        }

        @Bean JdbcDurableRunStateRepository repository(RecordingJdbcTemplate jdbc) {
            return new JdbcDurableRunStateRepository(jdbc, new ObjectMapper());
        }
    }

    /** Only JDBC effects are recorded; Spring itself owns the transaction and Connection lifecycle. */
    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> statements = new ArrayList<>();
        private final List<Object[]> parameters = new ArrayList<>();
        private Instant databaseTime = DATABASE_TIME;
        private RunLease lease = new RunLease(RUN, OWNER, TOKEN, DATABASE_TIME.plusSeconds(60), DATABASE_TIME, 0);
        private String failureBoundary;

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            statements.add(sql);
            parameters.add(new Object[0]);
            if ("clock".equals(failureBoundary)) throw new DataAccessResourceFailureException("SQL " + TOKEN);
            if ("null-clock".equals(failureBoundary)) return null;
            return requiredType.cast(Timestamp.from(databaseTime));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            statements.add(sql);
            parameters.add(args);
            if ("lock".equals(failureBoundary)) throw new DataAccessResourceFailureException("SQL " + TOKEN);
            if (lease == null) throw new EmptyResultDataAccessException(1);
            return (T) lease;
        }
    }
}
