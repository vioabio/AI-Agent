package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.memory.model.MemoryEntry;
import com.vio.vioaiagent.memory.model.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("短期记忆")
class ShortTermMemoryTest {
    private ShortTermMemory stm;

    @BeforeEach void setUp() { stm = new ShortTermMemory(100); }

    @Test @DisplayName("添加条目不应超过 Token 阈值")
    void shouldEvictWhenOverTokenLimit() {
        String longText = "A".repeat(600); // ≈150 tokens
        stm.add(MemoryEntry.of(MemoryType.CONVERSATION, longText, "s1"));
        assertTrue(stm.currentTokens() <= 100 || stm.getAll().size() <= 2,
                "Token 超限应触发淘汰");
    }

    @Test @DisplayName("应生成淘汰摘要")
    void shouldGenerateSummaryOnEviction() {
        String longText = "A".repeat(600);
        stm.add(MemoryEntry.of(MemoryType.CONVERSATION, longText, "s1"));
        assertFalse(stm.getLastSummary().isEmpty());
    }

    @Test @DisplayName("应按类型过滤")
    void shouldFilterByType() {
        stm.add(MemoryEntry.of(MemoryType.FACT, "fact-1", "s1"));
        stm.add(MemoryEntry.of(MemoryType.CONVERSATION, "conv-1", "s1"));
        assertEquals(1, stm.getByType(MemoryType.FACT).size());
        assertEquals(1, stm.getByType(MemoryType.CONVERSATION).size());
    }
}
