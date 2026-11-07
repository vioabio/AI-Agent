package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.memory.model.MemoryEntry;
import com.vio.vioaiagent.memory.model.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("上下文压缩器")
class ContextCompressorTest {
    private ContextCompressor compressor;

    @BeforeEach void setUp() { compressor = new ContextCompressor(); }

    @Test @DisplayName("少于保留量的消息不应被压缩")
    void shouldNotCompressShortList() {
        List<MemoryEntry> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            msgs.add(MemoryEntry.of(MemoryType.CONVERSATION, "msg" + i, "s1"));

        var result = compressor.compress(msgs);
        assertTrue(result.summary().isEmpty());
        assertEquals(3, result.recent().size());
    }

    @Test @DisplayName("长消息列表应产生压缩摘要")
    void shouldCompressLongList() {
        List<MemoryEntry> msgs = new ArrayList<>();
        for (int i = 0; i < 12; i++)
            msgs.add(MemoryEntry.of(MemoryType.CONVERSATION, "message number " + i, "s1"));

        var result = compressor.compress(msgs);
        assertFalse(result.summary().isEmpty());
        assertEquals(6, result.recent().size()); // 保留最近 6 条
    }

    @Test @DisplayName("应提取高重要性关键事实")
    void shouldExtractKeyFacts() {
        List<MemoryEntry> msgs = new ArrayList<>();
        msgs.add(MemoryEntry.of(MemoryType.FACT, "关键事实", "s1", 9));
        msgs.add(MemoryEntry.of(MemoryType.FACT, "普通事实", "s1", 3));
        for (int i = 0; i < 8; i++)
            msgs.add(MemoryEntry.of(MemoryType.CONVERSATION, "msg" + i, "s1"));

        var result = compressor.compress(msgs);
        assertFalse(result.keyFacts().isEmpty());
        assertTrue(result.keyFacts().stream().allMatch(f -> f.importance() >= 7));
    }
}
