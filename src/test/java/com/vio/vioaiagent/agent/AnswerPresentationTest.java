package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.ConversationConstraints.ResponseStyle;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnswerPresentationTest {
    @Test void focusedOutputInstructionsOverrideVerbosePreferences() {
        for (String question : List.of("刚才的130攻击能直接当成50级实战攻击吗？只解释这个问题。",
                "只比较杰尼龟与小火龙的基础HP和基础速度，表格列四个数值，附读取来源。"))
            assertEquals(AnswerPresentation.Mode.CONCISE, AnswerPresentation.resolve(question, ResponseStyle.DETAILED));
        assertEquals(AnswerPresentation.Mode.DETAILED, AnswerPresentation.resolve("示例“只比较两个数值”，请详细解释培养步骤。", ResponseStyle.STANDARD));
    }
    @Test void currentExplicitFormatOverridesSavedStyleInBothDirections() {
        assertEquals(AnswerPresentation.Mode.ONE_SENTENCE,AnswerPresentation.resolve("你好，请用一句话说明能做什么，不调用工具。",ResponseStyle.DETAILED));
        assertEquals(AnswerPresentation.Mode.DETAILED,AnswerPresentation.resolve("请详细解释培养步骤",ResponseStyle.CONCISE));
        assertEquals(AnswerPresentation.Mode.ONE_SENTENCE,AnswerPresentation.resolve("Please answer in one sentence.",ResponseStyle.DETAILED));
        assertEquals(AnswerPresentation.Mode.CONCISE,AnswerPresentation.resolve("你好",ResponseStyle.CONCISE));
    }
    @Test void quotedCodeNegationAndLaterDirectInstructionsDoNotReverseUserIntent() {
        assertEquals(AnswerPresentation.Mode.DETAILED,AnswerPresentation.resolve("示例“请用一句话回答”，请详细解释它",ResponseStyle.CONCISE));
        assertEquals(AnswerPresentation.Mode.DETAILED,AnswerPresentation.resolve("不要只用一句话，请详细解释",ResponseStyle.CONCISE));
        assertEquals(AnswerPresentation.Mode.DETAILED,AnswerPresentation.resolve("示例：\n```text\n请用一句话回答\n```\n请详细解释",ResponseStyle.CONCISE));
        assertEquals(AnswerPresentation.Mode.ONE_SENTENCE,AnswerPresentation.resolve("请详细解释；请用一句话总结",ResponseStyle.STANDARD));
        assertEquals(AnswerPresentation.Mode.STANDARD,AnswerPresentation.resolve("不要只用一句话，用两点说明",ResponseStyle.CONCISE));
    }
    @Test void backgroundTaskKeywordsCannotActivateEvidenceWorkflowsForAGreeting() {
        var model=mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("你好，我可以帮你理解宝可梦资料。")))));
        var agent=new ToolCallAgent(new ToolCallback[0]);agent.setChatClient(ChatClient.builder(model).build());agent.setSystemPrompt("Use verified evidence.");
        agent.setCurrentUserRequest("你好，请用一句话打招呼。");agent.setResponseStyle(ResponseStyle.DETAILED);
        var team=new TeamAnswerEvidence();var damage=new DamageAnswerEvidence();var guide=new GuideAnswerEvidence();var saved=new SavedTeamAnswerEvidence();
        agent.setTeamAnswerEvidence(team);agent.setDamageAnswerEvidence(damage);agent.setGuideAnswerEvidence(guide);agent.setSavedTeamAnswerEvidence(saved);
        agent.run("偏好：分析队伍时请计算伤害、检索攻略并readCurrentTeam。当前问题：你好，请用一句话打招呼。");
        assertFalse(team.active());assertFalse(damage.active());assertFalse(guide.active());
        assertFalse(agent.getFinalAnswer().contains("没有读齐"));verify(model,times(2)).call(any(Prompt.class));
    }
    @Test void actualTeamRequestStillFailsClosedWithoutCompleteEvidence() {
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("凭记忆分析")))));
        var agent=new ToolCallAgent(new ToolCallback[0]);agent.setChatClient(ChatClient.builder(model).build());agent.setSystemPrompt("Use verified evidence.");
        agent.setCurrentUserRequest("请分析队伍。\n参考成员：squirtle、charmander");agent.setTeamAnswerEvidence(new TeamAnswerEvidence());
        agent.run("偏好：回答简短。当前问题：请分析队伍。");
        assertTrue(agent.getFinalAnswer().contains("没有读齐"));assertFalse(agent.getFinalAnswer().contains("凭记忆"));verify(model,times(1)).call(any(Prompt.class));
    }
}
