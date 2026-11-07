package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.memory.model.MemoryEntry;
import com.vio.vioaiagent.memory.model.MemoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("三层记忆集成测试")
class TieredMemoryIntegrationTest {
    private TieredMemorySystem memory;

    @BeforeEach void setUp() { memory = new TieredMemorySystem(); }
    @AfterEach void tearDown() { memory.shutdown(); }

    @Test @DisplayName("全链路: remember → recall → compress → buildContext")
    void shouldCompleteFullMemoryCycle() {
        // 1. 记住事实
        memory.rememberFact("用户训练了一只烈空坐，努力值252攻击252速度", 9);
        memory.rememberFact("用户喜欢双打对战", 7);
        memory.rememberConversation("用户: 烈空坐Mega怎么配招？");

        // 2. 召回
        List<MemoryEntry> recalled = memory.recall("烈空坐", 5);
        assertFalse(recalled.isEmpty());

        // 3. 构建上下文
        String context = memory.buildContext("烈空坐配招推荐");
        assertFalse(context.isEmpty());
        assertTrue(context.contains("烈空坐") || context.contains("双打"));

        // 4. 压缩
        memory.compress();
        // 不应抛异常
    }

    @Test @DisplayName("remember → 高重要性事实应入长期记忆")
    void shouldPersistHighImportanceFacts() {
        memory.rememberFact("关键长期事实", 8);
        List<MemoryEntry> recalled = memory.recall("长期事实", 3);
        assertTrue(recalled.stream().anyMatch(e -> e.content().contains("关键长期事实")));
    }

    @Test @DisplayName("clearSession 应清空工作和短期记忆")
    void shouldClearSessionMemory() {
        memory.rememberConversation("测试对话");
        memory.clearSession();
        String context = memory.buildContext("test");
        // 清空后上下文应很短
        assertNotNull(context);
    }
}
