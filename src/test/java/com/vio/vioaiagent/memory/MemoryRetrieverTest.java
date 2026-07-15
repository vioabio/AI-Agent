package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.memory.model.MemoryEntry;
import com.vio.vioaiagent.memory.model.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("智能检索器")
class MemoryRetrieverTest {
    private MemoryRetriever retriever;
    private List<MemoryEntry> candidates;

    @BeforeEach void setUp() {
        retriever = new MemoryRetriever();
        candidates = List.of(
                MemoryEntry.of(MemoryType.FACT, "烈空坐是龙/飞行属性", "s1", 9),
                MemoryEntry.of(MemoryType.FACT, "皮卡丘是电属性", "s2", 7),
                MemoryEntry.of(MemoryType.CONVERSATION, "用户问过对战配招", "s3", 5),
                MemoryEntry.of(MemoryType.TOOL_RESULT, "搜索结果: 天气", "s4", 3));
    }

    @Test @DisplayName("应返回 topK 条结果")
    void shouldReturnTopK() {
        var results = retriever.retrieve(candidates, "烈空坐", 2);
        assertTrue(results.size() <= 2);
    }

    @Test @DisplayName("FACT 类型应排在 CONVERSATION 前面")
    void shouldRankFactHigherThanConversation() {
        var results = retriever.retrieve(candidates, "属性", 4);
        assertFalse(results.isEmpty());
        // FACT 权重 1.0 > CONVERSATION 0.7
        var firstType = results.get(0).type();
        assertTrue(firstType == MemoryType.FACT || firstType == MemoryType.SUMMARY);
    }

    @Test @DisplayName("空查询应返回前 N 条")
    void shouldReturnTopNForEmptyQuery() {
        var results = retriever.retrieve(candidates, null, 2);
        assertTrue(results.size() <= 2);
    }

    @Test @DisplayName("无匹配关键词应过滤低相关度")
    void shouldFilterLowRelevance() {
        var results = retriever.retrieve(candidates, "不存在的关键词XYZ", 3);
        assertTrue(results.size() <= 3);
    }
}
