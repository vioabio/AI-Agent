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
    void windowKeepsWholeTurnsAndEnforcesTokenTurnAndCharacterLimits() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            messages.add(message(String.format("%02d-u", i), "prior-"+i, "owner", "user", "本轮请求"+i));
            messages.add(message(String.format("%02d-z", i), "prior-"+i, "owner", "assistant", "答复"+i));
        }
        var selected=ConversationHistory.select(run,messages,new com.vio.vioaiagent.config.ContextBudgetProperties(2048,128,256,3,125));
        assertEquals(6,selected.messages().size());assertEquals(9,selected.summary().get("omittedTurns"));
        assertTrue(((Number)selected.summary().get("estimatedTokens")).intValue()<=256);
        assertTrue(selected.messages().getFirst().getText().endsWith("9"));
        for(int i=0;i<selected.messages().size();i++)assertEquals(i%2==0?org.springframework.ai.chat.messages.MessageType.USER:org.springframework.ai.chat.messages.MessageType.ASSISTANT,selected.messages().get(i).getMessageType());
    }
    @Test void orphanDuplicateForeignConversationAndNewestOversizedTurnsAreNeverPartiallyRecalled() {
        var other=new ChatMessage("foreign","another-conversation","old","owner","assistant","别的会话",run.createdAt().minusSeconds(1),Map.of());
        var rows=new ArrayList<>(List.of(message("1","old","owner","user","规则：不使用传说宝可梦"),message("2","old","owner","assistant","好的"),other,
                message("3","broken","owner","assistant","没有问题的孤立回答"),
                message("4","duplicate","owner","user","重复"),message("5","duplicate","owner","user","重复"),message("6","duplicate","owner","assistant","答复")));
        assertEquals(2,ConversationHistory.window(run,rows).size());
        rows.add(message("7","huge","owner","user","大".repeat(13000)));rows.add(message("8","huge","owner","assistant","答复"));
        var selected=ConversationHistory.select(run,rows,com.vio.vioaiagent.config.ContextBudgetProperties.defaults());
        assertTrue(selected.messages().isEmpty());assertEquals(2,selected.summary().get("omittedTurns"));
    }
    @Test void zeroHistoryBudgetOmitsBothMessagesWithoutTruncatingSurrogates() {
        var selected=ConversationHistory.select(run,List.of(message("1","old","owner","user","规则🦊保持原文"),message("2","old","owner","assistant","好的")),
                new com.vio.vioaiagent.config.ContextBudgetProperties(1024,128,0,1,125));
        assertTrue(selected.messages().isEmpty());assertEquals(1,selected.summary().get("omittedTurns"));
    }
}
