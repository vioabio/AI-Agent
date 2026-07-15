package com.vio.vioaiagent.orchestration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("幂等管理器")
class IdempotencyManagerTest {
    private IdempotencyManager manager;

    @BeforeEach
    void setUp() { manager = new IdempotencyManager(); }

    @Test @DisplayName("相同参数应生成相同 Key")
    void shouldGenerateSameKeyForSameParams() {
        String k1 = manager.generateKey("s1", 0, "search", "{\"q\":\"test\"}");
        String k2 = manager.generateKey("s1", 0, "search", "{\"q\":\"test\"}");
        assertEquals(k1, k2);
    }

    @Test @DisplayName("不同参数应生成不同 Key")
    void shouldGenerateDifferentKeys() {
        String k1 = manager.generateKey("s1", 0, "search", "a");
        String k2 = manager.generateKey("s1", 1, "search", "a");
        assertNotEquals(k1, k2);
    }

    @Test @DisplayName("record 后可 isDuplicate")
    void shouldDetectDuplicateAfterRecord() {
        String key = manager.generateKey("s1", 0, "t", "{}");
        assertFalse(manager.isDuplicate(key));
        manager.record(key, "result");
        assertTrue(manager.isDuplicate(key));
        assertEquals("result", manager.getCachedResult(key));
    }
}
