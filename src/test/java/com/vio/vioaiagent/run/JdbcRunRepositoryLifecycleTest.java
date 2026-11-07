package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 软删除会话后，后台按 id 恢复 Run 也必须受 deleted_at 门禁保护。 */
class JdbcRunRepositoryLifecycleTest {

    @Test
    void findByIdExcludesRunsOfDeletedConversations() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcRunRepository repository = new JdbcRunRepository(jdbc);

        repository.findById("run-1");

        String statement = jdbc.sql.toLowerCase(java.util.Locale.ROOT);
        assertTrue(statement.contains("join conversation"));
        assertTrue(statement.contains("c.deleted_at is null"));
        assertArrayEquals(new Object[]{"run-1"}, jdbc.args);
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args;

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.args = args;
            return null;
        }
    }
}
