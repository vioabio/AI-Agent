package com.vio.vioaiagent.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 软删除会话后的事件追加必须 fail-closed，并与删除事务保持行锁顺序。 */
class JdbcEventRepositoryLifecycleTest {

    @Test
    void appendNextLocksVisibleConversationBeforeAllocatingEventId() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.classResult = 1L;
        JdbcEventRepository repository = new JdbcEventRepository(jdbc, new ObjectMapper());

        repository.appendNext(event(1, AgentEventType.RUN_CREATED));

        String statement = jdbc.sqls.get(0).toLowerCase(java.util.Locale.ROOT);
        assertTrue(statement.contains("join conversation"));
        assertTrue(statement.contains("c.deleted_at is null"));
        assertTrue(statement.contains("for update"));
    }

    @Test
    void appendRejectsRunWhoseConversationWasSoftDeleted() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.throwOnVisibleLock = true;
        JdbcEventRepository repository = new JdbcEventRepository(jdbc, new ObjectMapper());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.append(event(1, AgentEventType.RUN_CREATED)));

        assertTrue(error.getMessage().contains("会话已删除"));
        assertTrue(jdbc.sqls.stream().anyMatch(sql -> sql.toLowerCase(java.util.Locale.ROOT)
                .contains("c.deleted_at is null")));
    }

    private AgentEvent event(long id, AgentEventType type) {
        return AgentEvent.of(id, type, "session-jdbc", "run-jdbc", "trace-jdbc", Map.of());
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> sqls = new ArrayList<>();
        private Object classResult;
        private boolean throwOnVisibleLock;

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            sqls.add(sql);
            return null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            sqls.add(sql);
            if (throwOnVisibleLock && sql.toLowerCase(java.util.Locale.ROOT).contains("join conversation")) {
                throw new EmptyResultDataAccessException(1);
            }
            return (T) classResult;
        }
    }
}
