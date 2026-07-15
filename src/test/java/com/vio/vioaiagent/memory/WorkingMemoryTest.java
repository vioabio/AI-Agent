package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.memory.model.MemoryEntry;
import com.vio.vioaiagent.memory.model.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("工作记忆")
class WorkingMemoryTest {
    private WorkingMemory wm;

    @BeforeEach void setUp() { wm = new WorkingMemory(); }

    @Test @DisplayName("应保留最近 3 轮对话 (≤6 条)")
    void shouldKeepRecentRounds() {
        for (int i = 0; i < 8; i++) {
            wm.addConversation(MemoryEntry.of(MemoryType.CONVERSATION, "msg" + i, "s1"));
        }
        assertEquals(6, wm.recentConversations().size(), "应保留最近 6 条 (3 轮)");
    }

    @Test @DisplayName("未超出限制时 drainOverflow 应返回空")
    void shouldReturnEmptyWhenWithinLimit() {
        for (int i = 0; i < 3; i++) {
            wm.addConversation(MemoryEntry.of(MemoryType.CONVERSATION, "msg" + i, "s1"));
        }
        var overflow = wm.drainOverflow();
        assertTrue(overflow.isEmpty(), "未超限时应无溢出");
    }

    @Test @DisplayName("应跟踪当前任务")
    void shouldTrackCurrentTask() {
        wm.setCurrentTask("测试任务");
        assertEquals("测试任务", wm.getCurrentTask());
    }
}
