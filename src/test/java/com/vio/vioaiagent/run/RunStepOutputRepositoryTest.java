package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunStepOutputRepositoryTest {
    private static final Instant CREATED = Instant.parse("2026-10-14T00:00:00.123456Z");

    @Test
    void memorySaveIsIdempotentButRejectsAnImmutableKeyConflict() {
        InMemoryRunStepOutputRepository repository = new InMemoryRunStepOutputRepository();
        RunStepOutput first = output("encrypted-v1", CREATED.plusSeconds(60));
        RunStepOutput replay = output("encrypted-v1", CREATED.plusSeconds(60));
        RunStepOutput conflict = output("encrypted-v2", CREATED.plusSeconds(60));

        assertSame(first, repository.save(first));
        assertSame(first, repository.save(replay));
        assertThrows(RunStepOutputConflictException.class, () -> repository.save(conflict));
        assertEquals(first, repository.find(first.runId(), first.stepNo()).orElseThrow());
    }

    @Test
    void memoryFindDoesNotHideExpiredRowsAndCleanupHonorsBoundedLimit() {
        InMemoryRunStepOutputRepository repository = new InMemoryRunStepOutputRepository();
        Instant now = CREATED.plusSeconds(100);
        RunStepOutput expiredOne = output(1, "expired-1", CREATED.plusSeconds(1));
        RunStepOutput expiredTwo = output(2, "expired-2", CREATED.plusSeconds(2));
        RunStepOutput live = output(3, "live", CREATED.plusSeconds(1000));
        repository.save(expiredOne);
        repository.save(expiredTwo);
        repository.save(live);

        assertEquals(expiredOne, repository.find(expiredOne.runId(), expiredOne.stepNo()).orElseThrow());
        assertEquals(1, repository.deleteExpired(now, 1));
        assertEquals(1, repository.find(expiredTwo.runId(), expiredTwo.stepNo()).stream().count());
        assertEquals(1, repository.deleteExpired(now, 100));
        assertTrue(repository.find(expiredOne.runId(), expiredOne.stepNo()).isEmpty());
        assertTrue(repository.find(expiredTwo.runId(), expiredTwo.stepNo()).isEmpty());
        assertTrue(repository.find(live.runId(), live.stepNo()).isPresent());
    }

    @Test
    void jdbcSaveReadsBackCanonicalRowAndRejectsImmutableConflict() {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcRunStepOutputRepository repository = new JdbcRunStepOutputRepository(jdbc);
        RunStepOutput first = output("encrypted-v1", CREATED.plusSeconds(60));

        assertEquals(first, repository.save(first));
        assertEquals(first, repository.save(first));
        assertThrows(RunStepOutputConflictException.class,
                () -> repository.save(output("encrypted-v2", CREATED.plusSeconds(60))));
        assertTrue(jdbc.insertSql.contains("ON CONFLICT (run_id, step_no) DO NOTHING"));
        assertEquals(first, repository.find(first.runId(), first.stepNo()).orElseThrow());
    }

    @Test
    void jdbcFindReturnsExpiredFactUntilExplicitCleanupAndCleanupUsesLimit() {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcRunStepOutputRepository repository = new JdbcRunStepOutputRepository(jdbc);
        RunStepOutput expired = output("expired", CREATED.plusSeconds(1));
        repository.save(expired);
        Instant now = CREATED.plusSeconds(2);

        assertTrue(repository.find(expired.runId(), expired.stepNo()).isPresent());
        assertEquals(1, repository.deleteExpired(now, 1));
        assertTrue(repository.find(expired.runId(), expired.stepNo()).isEmpty());
        assertTrue(jdbc.deleteSql.contains("expires_at <= ? LIMIT ?"));
    }

    @Test
    void invalidLookupArgumentsAreSafeAndDoNotReachJdbc() {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcRunStepOutputRepository repository = new JdbcRunStepOutputRepository(jdbc);

        assertEquals(Optional.empty(), repository.find("", 0));
        assertEquals(Optional.empty(), repository.find("run-step", -1));
        assertEquals(Optional.empty(), repository.find(null, 0));
        assertNull(jdbc.insertSql);
    }

    @Test
    void subMicrosecondTtlThatCollapsesAtDatabasePrecisionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RunStepOutput(
                "run-step-output", 9, "executor-v1", "a".repeat(64), "b".repeat(64),
                "encrypted", "kms-key-v3", CREATED.plusNanos(1), CREATED.plusNanos(999)));
    }

    @Test
    void migrationStoresOnlyOpaqueEncryptedPayloadAndBindsOutputToRun() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V27__immutable_run_step_outputs.sql")) {
            assertNotNull(stream, "R07 migration must be packaged with the application");
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertTrue(sql.contains("references agent_run(id) on delete cascade"));
            assertTrue(sql.contains("primary key (run_id, step_no)"));
            assertTrue(sql.contains("encrypted_payload text not null"));
            assertTrue(sql.contains("payload_digest"));
            assertTrue(sql.contains("key_version"));
            assertTrue(sql.contains("idx_agent_run_step_output_expiry"));
            String ddl = sql.substring(sql.indexOf("create table"), sql.indexOf(");\n\ncreate index"));
            assertFalse(ddl.contains("answer_text"));
            assertFalse(ddl.contains("prompt"));
            assertFalse(ddl.contains("policy"));
        }
    }

    private static RunStepOutput output(String encryptedPayload, Instant expiresAt) {
        return output(3, encryptedPayload, expiresAt);
    }

    private static RunStepOutput output(int stepNo, String encryptedPayload, Instant expiresAt) {
        return new RunStepOutput("run-step-output", stepNo, "executor-v1", "a".repeat(64),
                "b".repeat(64), encryptedPayload, "kms-key-v3", CREATED, expiresAt);
    }

    /** In-memory SQL boundary that preserves the production repository's mapper and conflict path. */
    private static final class RecordingJdbc extends JdbcTemplate {
        private static final String[] COLUMNS = {
                "run_id", "step_no", "executor_version", "context_hash", "payload_digest",
                "encrypted_payload", "key_version", "created_at", "expires_at"
        };
        private Map<String, Object> row;
        private String insertSql;
        private String deleteSql;

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("INSERT INTO agent_run_step_output")) {
                insertSql = sql;
                assertEquals(COLUMNS.length, args.length);
                if (row != null) return 0;
                row = new LinkedHashMap<>();
                for (int index = 0; index < COLUMNS.length; index++) row.put(COLUMNS[index], args[index]);
                return 1;
            }
            deleteSql = sql;
            assertTrue(sql.contains("DELETE FROM agent_run_step_output"));
            Instant cutoff = ((Timestamp) args[0]).toInstant();
            int limit = ((Number) args[1]).intValue();
            if (row == null || !((Timestamp) row.get("expires_at")).toInstant().isAfter(cutoff)) {
                if (row != null && limit > 0) { row = null; return 1; }
            }
            return 0;
        }

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> mapper, Object... args) {
            if (row == null || !row.get("run_id").equals(args[0])
                    || !row.get("step_no").equals(args[1])) throw new EmptyResultDataAccessException(1);
            try {
                ResultSet resultSet = mock(ResultSet.class);
                when(resultSet.getString(anyString())).thenAnswer(invocation -> {
                    Object value = row.get(invocation.getArgument(0, String.class));
                    return value == null ? null : value.toString();
                });
                when(resultSet.getInt("step_no")).thenReturn((Integer) row.get("step_no"));
                when(resultSet.getTimestamp(anyString())).thenAnswer(invocation ->
                        (Timestamp) row.get(invocation.getArgument(0, String.class)));
                return mapper.mapRow(resultSet, 0);
            } catch (SQLException ex) {
                throw new IllegalStateException("fixture ResultSet mapping failed", ex);
            }
        }
    }
}
