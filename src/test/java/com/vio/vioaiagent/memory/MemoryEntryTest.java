package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.memory.model.MemoryEntry;
import com.vio.vioaiagent.memory.model.MemoryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("记忆条目")
class MemoryEntryTest {

    @Test @DisplayName("of 工厂方法应创建有效条目")
    void shouldCreateEntry() {
        MemoryEntry e = MemoryEntry.of(MemoryType.FACT, "用户喜欢宝可梦", "session-1", 8);
        assertNotNull(e.id());
        assertEquals(MemoryType.FACT, e.type());
        assertEquals("用户喜欢宝可梦", e.content());
        assertEquals(8, e.importance());
        assertTrue(e.tokens() > 0);
    }

    @Test @DisplayName("FACT 来源权重应为 1.0")
    void shouldHaveCorrectSourceWeights() {
        assertEquals(1.0, MemoryType.FACT.getSourceWeight());
        assertEquals(0.7, MemoryType.CONVERSATION.getSourceWeight());
        assertEquals(0.5, MemoryType.TOOL_RESULT.getSourceWeight());
    }

    @Test @DisplayName("ageInHours 应 ≥ 0")
    void shouldHaveValidAge() {
        MemoryEntry e = MemoryEntry.of(MemoryType.FACT, "test", "s1");
        assertTrue(e.ageInHours() >= 0);
    }

    @Test @DisplayName("中文 Token 估算")
    void shouldEstimateChineseTokens() {
        MemoryEntry e = MemoryEntry.of(MemoryType.FACT, "你好世界你好世界你好世界你好世界", "s1");
        assertTrue(e.tokens() > 0);
        assertTrue(e.tokens() < 50);
    }
}
