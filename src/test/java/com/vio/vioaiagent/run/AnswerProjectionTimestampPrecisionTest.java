package com.vio.vioaiagent.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercise the real projection JDBC mapper against PostgreSQL's microsecond timestamp boundary. */
class AnswerProjectionTimestampPrecisionTest {
    private static final Instant BASE = Instant.parse("2026-10-12T08:00:00Z");

    @ParameterizedTest
    @ValueSource(ints = {123456001, 123456499, 123456500, 999999999})
    void firstJdbcSaveAndReadbackRemainEqualWithNanosecondInputs(int nanos) {
        MicrosecondJdbc jdbc = new MicrosecondJdbc();
        JdbcAnswerProjectionRepository repository = repository(jdbc);
        Instant created = BASE.plusNanos(nanos);
        Instant completed = created.plusSeconds(2).plusNanos(137);
        AnswerProjection value = projection("precision answer", created, completed);

        assertEquals(value, repository.save(value), "first INSERT must not be mistaken for an immutable conflict");
        assertEquals(value, repository.findById(value.answerId()).orElseThrow());
        assertEquals(value, repository.findByRunId(value.runId()).orElseThrow());
        assertEquals(0, ((Timestamp) jdbc.lastInsert[20]).getNanos() % 1000);
        assertEquals(0, ((Timestamp) jdbc.lastInsert[21]).getNanos() % 1000);
        assertEquals(created.minusNanos(created.getNano() % 1000), value.createdAt());
        assertEquals(completed.minusNanos(completed.getNano() % 1000), value.completedAt());
    }

    @Test
    void replayWithOnlySubMicrosecondDifferencesReturnsSameImmutableProjection() {
        MicrosecondJdbc jdbc = new MicrosecondJdbc();
        JdbcAnswerProjectionRepository repository = repository(jdbc);
        AnswerProjection first = projection("same answer", BASE.plusNanos(123456001),
                BASE.plusSeconds(2).plusNanos(987654999));
        AnswerProjection replay = projection("same answer", BASE.plusNanos(123456999),
                BASE.plusSeconds(2).plusNanos(987654001));

        assertEquals(first, repository.save(first));
        assertEquals(first, repository.save(replay));
        assertEquals(first, repository.findById(first.answerId()).orElseThrow());
    }

    @Test
    void answerOrMicrosecondTimestampChangesStillRejectImmutableReplay() {
        MicrosecondJdbc jdbc = new MicrosecondJdbc();
        JdbcAnswerProjectionRepository repository = repository(jdbc);
        Instant created = BASE.plusNanos(123456789);
        Instant completed = BASE.plusSeconds(2).plusNanos(987654321);
        AnswerProjection first = projection("original answer", created, completed);
        repository.save(first);

        assertThrows(IllegalStateException.class,
                () -> repository.save(projection("changed answer", created, completed)));
        assertThrows(IllegalStateException.class,
                () -> repository.save(projection("original answer", created, completed.plusNanos(1000))));
        assertEquals(first, repository.findById(first.answerId()).orElseThrow(), "rejected replays must not alter the row");
    }

    @Test
    void earlierCompletionWithinSameMicrosecondStillFailsValidation() {
        assertThrows(IllegalArgumentException.class, () -> projection("invalid chronology",
                BASE.plusNanos(123456999), BASE.plusNanos(123456001)));
    }

    @Test
    void runFactoryCanonicalizesProjectionWithoutChangingOriginalRunTimestamp() {
        Instant created = BASE.plusNanos(999999999);
        Instant completed = BASE.plusSeconds(2).plusNanos(123456789);
        AgentRun run = AgentRun.restore("run-from", "session-from", "conversation-from", "user-from",
                "precision prompt", "sv-1", "singles", "rules-1", "zh-CN", "trace-from", null,
                created, BASE.plusSeconds(120), AgentRunStatus.RUNNING, created, null);
        AnswerProjection value = AnswerProjection.from(run, null, "answer-from", completed);

        assertEquals(created, run.createdAt());
        assertEquals(created.minusNanos(999), value.createdAt());
        assertEquals(completed.minusNanos(789), value.completedAt());
        assertEquals(value, repository(new MicrosecondJdbc()).save(value));
    }

    @Test
    void defaultTimestampsAlsoUseDatabasePrecision() {
        AnswerProjection value = projection("default timestamps", null, null);

        assertEquals(value.createdAt(), value.completedAt());
        assertEquals(0, value.createdAt().getNano() % 1000);
        assertEquals(value, repository(new MicrosecondJdbc()).save(value));
    }

    private static JdbcAnswerProjectionRepository repository(MicrosecondJdbc jdbc) {
        return new JdbcAnswerProjectionRepository(jdbc, new ObjectMapper());
    }

    private static AnswerProjection projection(String answer, Instant created, Instant completed) {
        return new AnswerProjection("answer-precision", "run-precision", "session-precision",
                "conversation-precision", "user-precision", "trace-precision", "ANSWERED", answer,
                "sv-1", "dlc-1", "singles", "rules-1", "data-1", "knowledge-1", "index-1", "v1",
                null, null, List.of(Map.of("documentId", "doc-precision", "retrievalScore", 0.875)),
                List.of("fixture limitation"), created, completed);
    }

    /** SQL effects alone are substituted; save(), JSON handling and ResultSet mapping remain real. */
    private static final class MicrosecondJdbc extends JdbcTemplate {
        private static final List<String> COLUMNS = List.of("answer_id", "run_id", "session_id",
                "conversation_id", "user_id", "trace_id", "status", "answer_text", "game_release_id",
                "dlc_id", "battle_format_id", "ruleset_version_id", "data_release_id", "knowledge_release_id",
                "index_release_id", "contract_version", "lineage_status", "answer_digest", "citations",
                "limitations", "created_at", "completed_at");
        private Map<String, Object> row;
        private Object[] lastInsert;

        @Override
        public int update(String sql, Object... args) {
            assertTrue(sql.contains("INSERT INTO answer_projection"));
            assertTrue(sql.contains("ON CONFLICT (answer_id) DO NOTHING"));
            assertEquals(COLUMNS.size(), args.length);
            lastInsert = args.clone();
            if (row != null) return 0;
            row = new LinkedHashMap<>();
            for (int index = 0; index < COLUMNS.size(); index++) {
                Object value = args[index];
                row.put(COLUMNS.get(index), value instanceof Timestamp timestamp
                        ? postgresTimestamp(timestamp) : value);
            }
            return 1;
        }

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> mapper, Object... args) {
            String key = sql.contains("WHERE answer_id") ? "answer_id" : "run_id";
            if (row == null || !row.get(key).equals(args[0])) throw new EmptyResultDataAccessException(1);
            try {
                ResultSet resultSet = mock(ResultSet.class);
                when(resultSet.getString(anyString())).thenAnswer(invocation -> {
                    Object value = row.get(invocation.getArgument(0, String.class));
                    return value == null ? null : value.toString();
                });
                when(resultSet.getTimestamp(anyString())).thenAnswer(invocation ->
                        (Timestamp) row.get(invocation.getArgument(0, String.class)));
                return mapper.mapRow(resultSet, 0);
            } catch (SQLException ex) {
                throw new IllegalStateException("fixture ResultSet mapping failed", ex);
            }
        }

        private static Timestamp postgresTimestamp(Timestamp timestamp) {
            Instant value = timestamp.toInstant();
            int subMicrosecond = value.getNano() % 1000;
            Instant rounded = value.minusNanos(subMicrosecond);
            if (subMicrosecond >= 500) rounded = rounded.plusNanos(1000);
            return Timestamp.from(rounded);
        }
    }
}
