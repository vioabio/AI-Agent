package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.memory.model.MemoryEntry;
import com.vio.vioaiagent.memory.model.MemoryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("记忆集成验证")
class MemoryIntegrationTest {

    @Test @DisplayName("TokenEstimator 估算")
    void shouldEstimateTokens() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
        assertTrue(TokenEstimator.estimate("hello world") > 0);
        assertTrue(TokenEstimator.estimate("你好世界") > 0);
    }

    @Test @DisplayName("ContextCompressor.CompressResult")
    void shouldCreateCompressResult() {
        var result = new ContextCompressor.CompressResult("summary",
                List.of(MemoryEntry.of(MemoryType.CONVERSATION, "recent", "s1")),
                List.of(MemoryEntry.of(MemoryType.FACT, "fact", "s1", 8)));
        assertEquals("summary", result.summary());
        assertEquals(1, result.recent().size());
        assertEquals(1, result.keyFacts().size());
    }

    @Test @DisplayName("MemoryConfig properties 默认值")
    void shouldHaveMemoryConfigDefaults() {
        var props = new MemoryConfig.MemoryProperties();
        assertFalse(props.isEnabled());
        assertEquals(4000, props.getShortTermMaxTokens());
        assertEquals(5, props.getRecallTopK());
    }

    @Test @DisplayName("MemoryType 枚举值")
    void shouldHaveAllMemoryTypes() {
        assertEquals(4, MemoryType.values().length);
    }
}
