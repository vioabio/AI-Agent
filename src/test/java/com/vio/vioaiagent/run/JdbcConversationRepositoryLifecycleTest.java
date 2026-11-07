package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产会话删除回归：必须软删除，不能用物理 DELETE 触发 Run 下游的
 * append-only/RESTRICT 外键链。
 */
class JdbcConversationRepositoryLifecycleTest {

    @Test
    void deleteUsesSoftDeleteToPreserveLineageAndAuditRows() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcConversationRepository repository = new JdbcConversationRepository(jdbc);

        repository.deleteForUser("conversation-1", "user-1");

        String statement = jdbc.sql.toLowerCase(java.util.Locale.ROOT);
        assertTrue(statement.contains("update conversation"));
        assertTrue(statement.contains("deleted_at"));
        assertTrue(!statement.contains("delete from conversation"));
        assertArrayEquals(new Object[]{"conversation-1", "user-1"}, jdbc.args);
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args;

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return 1;
        }
    }
}
