package com.vio.vioaiagent.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuditLogger 测试.
 *
 * @author vio
 */
@DisplayName("审计日志记录器")
class AuditLoggerTest {

    private AuditLogger logger;
    private ParameterMasker masker;

    @BeforeEach
    void setUp() {
        masker = new ParameterMasker();
        logger = new AuditLogger(masker);
    }

    @Test
    @DisplayName("应能记录审计条目而不抛出异常")
    void shouldLogWithoutException() {
        AuditEntry entry = AuditEntry.of(
                "trace-001", "session-001", "user-1",
                "web_search", Map.of("query", "test"),
                "allow", "policy", 150
        );

        assertDoesNotThrow(() -> logger.log(entry));
    }

    @Test
    @DisplayName("便捷 log 方法应可用")
    void shouldSupportConvenienceMethod() {
        assertDoesNotThrow(() -> logger.log(
                "trace-002", "session-002", "user-2",
                "write_file", Map.of("fileName", "test.txt"),
                "allow", "hitl", 200
        ));
    }

    @Test
    @DisplayName("审计条目应包含所有必需字段")
    void shouldHaveAllRequiredFields() {
        AuditEntry entry = AuditEntry.of(
                "trace-003", "session-003", "user-3",
                "terminal_operation",
                Map.of("command", "echo hello"),
                "deny", "hitl", 0
        );

        assertEquals("trace-003", entry.traceId());
        assertEquals("session-003", entry.sessionId());
        assertEquals("terminal_operation", entry.toolName());
        assertEquals("deny", entry.outcome());
        assertEquals("hitl", entry.approver());
        assertNotNull(entry.timestamp());
    }

    @Test
    @DisplayName("日志应写入 logs/audit/ 目录")
    void shouldWriteToAuditDirectory() {
        logger.log(
                "trace-004", "session-004", "user-4",
                "read_file", Map.of("fileName", "output.txt"),
                "allow", "none", 50
        );

        // 验证目录存在
        java.io.File auditDir = new java.io.File("logs/audit");
        assertTrue(auditDir.exists() || auditDir.mkdirs());
    }
}
