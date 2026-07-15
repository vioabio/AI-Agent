package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.memory.model.MemoryEntry;
import com.vio.vioaiagent.memory.model.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("长期记忆")
class LongTermMemoryTest {
    private LongTermMemory ltm;
    private File testFile;

    @BeforeEach void setUp() {
        testFile = new File("target/test-long-term-memory.json");
        testFile.delete();
        ltm = new LongTermMemory(testFile);
    }

    @Test @DisplayName("应能添加和检索记忆")
    void shouldAddAndSearch() {
        MemoryEntry e = MemoryEntry.of(MemoryType.FACT, "用户训练烈空坐", "s1", 8);
        assertTrue(ltm.add(e));
        var results = ltm.search("烈空坐");
        assertFalse(results.isEmpty());
    }

    @Test @DisplayName("重复内容应被去重")
    void shouldDedupDuplicate() {
        MemoryEntry e1 = MemoryEntry.of(MemoryType.FACT, "unique content", "s1", 5);
        MemoryEntry e2 = MemoryEntry.of(MemoryType.FACT, "unique content", "s2", 5);
        assertTrue(ltm.add(e1));
        assertFalse(ltm.add(e2));
        assertEquals(1, ltm.size());
    }

    @Test @DisplayName("应持久化并重新加载")
    void shouldPersistAndReload() {
        ltm.add(MemoryEntry.of(MemoryType.FACT, "持久化测试", "s1", 9));
        // 重新加载
        LongTermMemory reloaded = new LongTermMemory(testFile);
        assertTrue(reloaded.size() > 0);
    }

    @Test @DisplayName("extractKeyFacts 应返回高重要性事实")
    void shouldExtractKeyFacts() {
        ltm.add(MemoryEntry.of(MemoryType.FACT, "关键事实A", "s1", 9));
        ltm.add(MemoryEntry.of(MemoryType.FACT, "普通事实B", "s2", 3));
        var facts = ltm.extractKeyFacts();
        assertFalse(facts.isEmpty());
        assertTrue(facts.stream().allMatch(f -> f.importance() >= 7));
    }
}
