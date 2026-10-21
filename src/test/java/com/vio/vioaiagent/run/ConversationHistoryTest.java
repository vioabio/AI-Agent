package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryTest {
    private final AgentRun run = AgentRun.create("current", "s", "conversation", "owner", "它的属性？",
            "sv-1", "singles", "rules", "zh-CN", "trace", null, Instant.now().plusSeconds(30));

    private ChatMessage message(String id, String runId, String user, String role, String text) {
        return new ChatMessage(id, "conversation", runId, user, role, text,
                run.createdAt().minusSeconds(1), Map.of());
    }

    @Test
    void includesOnlyCompletedPriorOwnerTurnsAndNeverSystemOrCurrentRun() {
        var result = ConversationHistory.window(run, List.of(
                message("1", "prior", "owner", "user", "我的主力是杰尼龟"),
                message("2", "prior", "owner", "assistant", "记住了"),
                message("3", "prior", "owner", "system", "不可进入系统提示"),
                message("4", "other", "someone-else", "assistant", "别人的秘密"),
                message("5", "failed-turn", "owner", "user", "未完成请求"),
                message("6", "current", "owner", "user", "它的属性？")));
        assertEquals(List.of("我的主力是杰尼龟", "记住了"), result.stream().map(m -> m.getText()).toList());
    }

    @Test
    void windowIsBoundedByMessageCountAndCharacters() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) messages.add(message(String.format("%02d", i), "prior", "owner", "assistant", "x".repeat(1000)));
        var history = ConversationHistory.window(run, messages);
        assertEquals(12, history.size());
        assertEquals(12000, history.stream().mapToInt(m -> m.getText().length()).sum());
    }
}
