package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.agent.model.AgentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ToolCallAgentAnswerTest {
    @Test void gymInformationQueriesPublishDifferentSourcedAnswersWithoutAskingForPlayerStats() {
        ChatModel model=mock(ChatModel.class);
        var overview=new ToolCallAgent(new ToolCallback[0]);overview.setSystemPrompt("Only verified facts.");overview.setChatClient(ChatClient.builder(model).build());var chunks=new java.util.ArrayList<String>();overview.setAnswerDeltaSink(chunks::add);
        overview.run("目标馆主有哪些啊");assertNotNull(overview.getFinalAnswer());assertTrue(overview.getFinalAnswer().contains("阿枫"));assertEquals(String.join("",chunks),overview.getFinalAnswer());
        var detail=new ToolCallAgent(new ToolCallback[0]);detail.setSystemPrompt("Only verified facts.");detail.setChatClient(ChatClient.builder(model).build());detail.run("查询奇树初战队伍与招式");assertTrue(detail.getFinalAnswer().contains("充电光束"));assertNotEquals(overview.getFinalAnswer(),detail.getFinalAnswer());verify(model,never()).call(any(Prompt.class));verify(model,never()).stream(any(Prompt.class));
    }
    @Test void gymToolCompletionPublishesFinalAnswerBeforeEndingTheRun() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var snapshot=mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/pokemon-gym/sv-initial-v2.json")));
        var evidence=mapper.createObjectNode().put("sourceVersion","sv-initial-v2");evidence.set("gym",snapshot.path("gyms").get(2));evidence.putArray("plan").add("梦妖魔电太晶后仍有飘浮，地面无效");evidence.putArray("missing").add("NPC隐藏数值未知");evidence.putArray("resourceChecks");
        ChatModel model=mock(ChatModel.class);var call=new AssistantMessage("私有规划",Map.of(),List.of(new AssistantMessage.ToolCall("gym-1","function","planGymChallenge","{}")));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(call))));
        var manager=mock(ToolCallingManager.class);when(manager.executeToolCalls(any(),any())).thenReturn(new DefaultToolExecutionResult(List.of(call,new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("gym-1","planGymChallenge",evidence.toString())))),false));
        var agent=new ToolCallAgent(new ToolCallback[0]);agent.setSystemPrompt("Only verified facts.");agent.setChatClient(ChatClient.builder(model).build());agent.setToolCallingManager(manager);
        var chunks=new java.util.ArrayList<String>();agent.setAnswerDeltaSink(chunks::add);agent.run("紫，奇树初战，烈咬陆鲨Lv24地震");
        assertNotNull(agent.getFinalAnswer());assertEquals(String.join("",chunks),agent.getFinalAnswer());assertTrue(agent.getFinalAnswer().contains("梦妖魔"));assertFalse(agent.getFinalAnswer().contains("私有规划"));verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void upstreamFailurePublishesARecoverableMessageInsteadOfModelInventedDamage() {
        ChatModel model=mock(ChatModel.class);var call=new AssistantMessage("内部计划",Map.of(),List.of(new AssistantMessage.ToolCall("damage","function","calculateBattleDamage","{}")));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(call))),new ChatResponse(List.of(new Generation(new AssistantMessage("猜测造成999伤害")))));
        var manager=mock(ToolCallingManager.class);when(manager.executeToolCalls(any(),any())).thenReturn(new DefaultToolExecutionResult(List.of(call,new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("damage","calculateBattleDamage","[tool.error] PokéAPI 正文读取超时 private-secret")))),false));
        var agent=new ToolCallAgent(new ToolCallback[0]);agent.setSystemPrompt("Only verified facts.");agent.setChatClient(ChatClient.builder(model).build());agent.setToolCallingManager(manager);agent.setDamageAnswerEvidence(new DamageAnswerEvidence());
        var deltas=new java.util.ArrayList<String>();agent.setAnswerDeltaSink(deltas::add);agent.run("请调用calculateBattleDamage计算伤害");
        assertEquals(String.join("",deltas),agent.getFinalAnswer());assertTrue(agent.getFinalAnswer().contains("参考资料暂时无法读取"));assertFalse(agent.getFinalAnswer().contains("999"));assertFalse(agent.getFinalAnswer().contains("private-secret"));verify(model,never()).stream(any(Prompt.class));
    }
    @Test
    void guardedCalculationCannotBeRewrittenByModelBeforePublication() throws Exception {
        ChatModel model = mock(ChatModel.class);
        var call = new AssistantMessage("计算规划", Map.of(), List.of(
                new AssistantMessage.ToolCall("damage-1", "function", "calculateBattleDamage", "{}")));
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(call))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("准备解释计算")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("必定一击必杀，伤害999点。")))));
        var manager = mock(ToolCallingManager.class);
        String evidence = DamageAnswerEvidenceTest.result().toString();
        when(manager.executeToolCalls(any(),any())).thenReturn(new DefaultToolExecutionResult(List.of(call,
                new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("damage-1","calculateBattleDamage",evidence)))),false));
        var agent = new ToolCallAgent(new ToolCallback[0]);
        agent.setSystemPrompt("Be accurate."); agent.setChatClient(ChatClient.builder(model).build());
        agent.setToolCallingManager(manager); agent.setDamageAnswerEvidence(new DamageAnswerEvidence());
        List<String> chunks = new java.util.ArrayList<>(); agent.setAnswerDeltaSink(chunks::add);
        agent.run("请调用calculateBattleDamage计算伤害");
        assertTrue(agent.getFinalAnswer().contains("164～194 HP"));
        assertFalse(agent.getFinalAnswer().contains("999"));
        assertFalse(agent.getFinalAnswer().contains("必定一击必杀"));
        assertEquals(String.join("",chunks),agent.getFinalAnswer());
    }

    @Test
    void guardedTeamPublishesVerifiedFactsButNeverLeaksRejectedAdvice() throws Exception {
        ChatModel model = mock(ChatModel.class);
        var call = new AssistantMessage("内部规划", Map.of(), List.of(
                new AssistantMessage.ToolCall("team-1", "function", "lookupPokemonTeamReference", "{}")));
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(call))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("资料已齐，准备回答")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("雨天强化冰光束，火系威力下降25%。")))));
        var manager = mock(ToolCallingManager.class);
        String evidenceJson = TeamAnswerEvidenceTest.MAPPER.writeValueAsString(TeamAnswerEvidenceTest.evidence());
        when(manager.executeToolCalls(any(),any())).thenReturn(new DefaultToolExecutionResult(List.of(call,
                new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("team-1","lookupPokemonTeamReference",
                        evidenceJson)))), false));
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0]);
        agent.setSystemPrompt("Be accurate.");
        agent.setChatClient(ChatClient.builder(model).build());
        agent.setToolCallingManager(manager);
        agent.setTeamAnswerEvidence(new TeamAnswerEvidence());
        List<String> chunks = new java.util.ArrayList<>();
        agent.setAnswerDeltaSink(chunks::add);
        agent.run("请分析队伍。\n参考成员：squirtle、charmander");
        assertEquals(2, chunks.size());
        assertTrue(chunks.getFirst().contains("杰尼龟 | 水 | 43"));
        assertFalse(String.join("",chunks).contains("25%"));
        assertFalse(String.join("",chunks).contains("内部规划"));
        assertEquals(String.join("",chunks),agent.getFinalAnswer());
        verify(model,never()).stream(any(Prompt.class));
    }

    @Test
    void missingWholeTeamEvidenceReturnsClarificationWithoutAnotherGeneration() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("我会凭记忆分析队伍")))));
        var agent = new ToolCallAgent(new ToolCallback[0]);
        agent.setSystemPrompt("Be accurate.");
        agent.setChatClient(ChatClient.builder(model).build());
        agent.setTeamAnswerEvidence(new TeamAnswerEvidence());
        List<String> chunks = new java.util.ArrayList<>(); agent.setAnswerDeltaSink(chunks::add);
        agent.run("请分析队伍。\n参考成员：squirtle、charmander");
        assertTrue(agent.getFinalAnswer().contains("没有读齐"));
        assertFalse(agent.getFinalAnswer().contains("凭记忆"));
        verify(model,times(1)).call(any(Prompt.class));
    }

    @Test
    void terminationPlanningIsPrivateAndFinalAnswerIsGeneratedSeparately() {
        for (boolean streaming : List.of(false, true)) {
            ChatModel model = mock(ChatModel.class);
            var output = new AssistantMessage("Let me proceed with the final answer.", Map.of(), List.of(
                    new AssistantMessage.ToolCall("term-1", "function", "doTerminate", "{}")));
            var response = new ChatResponse(List.of(new Generation(output)));
            var finalResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("水系克制火系。"))));
            when(model.call(any(Prompt.class))).thenReturn(response, finalResponse);
            when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(finalResponse));
            ToolCallingManager manager = mock(ToolCallingManager.class);
            when(manager.executeToolCalls(any(), any())).thenReturn(new DefaultToolExecutionResult(
                    List.of(output, new ToolResponseMessage(List.of(
                            new ToolResponseMessage.ToolResponse("term-1", "doTerminate", "任务结束")))), false));
            ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0]);
            agent.setChatClient(ChatClient.builder(model).build());
            agent.setSystemPrompt("Be accurate.");
            agent.setToolCallingManager(manager);
            List<String> chunks = new java.util.ArrayList<>();
            if (streaming) agent.setAnswerDeltaSink(chunks::add);

            agent.run("水打火？");

            assertEquals("水系克制火系。", agent.getFinalAnswer());
            assertEquals(AgentState.FINISHED, agent.getState());
            if (streaming) {
                assertEquals(List.of("水系克制火系。"), chunks);
                verify(model, times(1)).stream(any(Prompt.class));
                verify(model, times(1)).call(any(Prompt.class));
            } else {
                verify(model, times(2)).call(any(Prompt.class));
            }
        }
    }

    @Test
    void mixedTerminationAndFactLookupStillSynthesizesAfterToolResult() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("根据工具结果整理的回答")))));
        var output = new AssistantMessage("先查询属性。", Map.of(), List.of(
                new AssistantMessage.ToolCall("fact-1", "function", "getTypeChart", "{}"),
                new AssistantMessage.ToolCall("term-1", "function", "doTerminate", "{}")));
        ToolCallingManager manager = mock(ToolCallingManager.class);
        when(manager.executeToolCalls(any(), any())).thenReturn(new DefaultToolExecutionResult(
                List.of(output, new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("fact-1", "getTypeChart", "水克制火"),
                        new ToolResponseMessage.ToolResponse("term-1", "doTerminate", "任务结束")))), false));
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0]);
        agent.setChatClient(ChatClient.builder(model).build());
        agent.setSystemPrompt("Be accurate.");
        agent.setToolCallingManager(manager);
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(output))));

        agent.act();

        assertEquals("根据工具结果整理的回答", agent.getFinalAnswer());
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void toolPlanningNeverEntersPublicAnswerAndPreservesParentContext() {
        var original = new com.vio.vioaiagent.tools.governance.ToolExecutionContext(
                "user-a", "run-a", java.util.Set.of("trainer"), "trace-a", "key-a", event -> {});
        ChatModel model = mock(ChatModel.class);
        var call = new AssistantMessage("I see the issue; let me check a tool.", Map.of(), List.of(
                new AssistantMessage.ToolCall("tool-1", "function", "getTypeChart", "{\"attackType\":\"火\"}")));
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(call))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("Let me proceed with the final answer.")))));
        when(model.stream(any(Prompt.class))).thenReturn(
                reactor.core.publisher.Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("火克制草。"))))));
        ToolCallingManager manager = mock(ToolCallingManager.class);
        when(manager.executeToolCalls(any(), any())).thenAnswer(invocation -> {
            var current = com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder.current();
            assertEquals(original.runId(), current.runId());
            assertEquals(original.userId(), current.userId());
            assertEquals(original.traceId(), current.traceId());
            return new DefaultToolExecutionResult(List.of(call, new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse("tool-1", "getTypeChart", "火克制草")))), false);
        });
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0]);
        agent.setSystemPrompt("Be helpful.");
        agent.setChatClient(ChatClient.builder(model).build());
        agent.setToolCallingManager(manager);
        java.util.List<String> chunks = new java.util.ArrayList<>();
        agent.setAnswerDeltaSink(chunks::add);
        com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder.set(original);
        try {
            agent.run("火打草？");
            assertSame(original, com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder.peek());
            assertEquals("火克制草。", agent.getFinalAnswer());
            assertEquals(List.of("火克制草。"), chunks);
            verify(model, times(1)).stream(any(Prompt.class));
            verify(model, times(2)).call(any(Prompt.class));
        } finally { com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder.clear(); }
    }

    @Test
    void failingToolManagerRestoresParentContext() {
        var original = new com.vio.vioaiagent.tools.governance.ToolExecutionContext(
                "user-a", "run-a", java.util.Set.of("trainer"), "trace-a", null, null);
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0]);
        var manager = mock(ToolCallingManager.class);
        when(manager.executeToolCalls(any(), any())).thenThrow(new IllegalStateException("tool failed"));
        agent.setToolCallingManager(manager);
        var output = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("tool-1", "function", "getTypeChart", "{}")));
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(output))));
        com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder.set(original);
        try {
            assertThrows(IllegalStateException.class, agent::act);
            assertSame(original, com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder.peek());
        } finally { com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder.clear(); }
    }
    @RepeatedTest(20)
    void brokenStreamDoesNotRestartGenerationOrPretendPartialAnswerIsComplete() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("规划完成")))));
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.concat(
                reactor.core.publisher.Flux.just(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("部分回答"))))),
                reactor.core.publisher.Flux.error(new IllegalStateException("connection lost"))));
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0]);
        agent.setChatClient(ChatClient.builder(model).build());
        agent.setSystemPrompt("Be helpful.");
        java.util.List<String> chunks = new java.util.ArrayList<>();
        agent.setAnswerDeltaSink(chunks::add);
        agent.run("你好");
        assertEquals(List.of("部分回答"), chunks);
        assertNull(agent.getFinalAnswer());
        assertEquals(AgentState.FINISHED, agent.getState());
        verify(model, times(1)).stream(any(Prompt.class));
        verify(model, times(1)).call(any(Prompt.class));
    }
    @Test
    void streamFailureBeforeAnyDeltaDoesNotInventAnAnswerOrRetry() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("规划完成")))));
        when(model.stream(any(Prompt.class))).thenReturn(
                reactor.core.publisher.Flux.error(new IllegalStateException("connection lost")));
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0]);
        agent.setChatClient(ChatClient.builder(model).build());
        agent.setSystemPrompt("Be helpful.");
        java.util.List<String> chunks = new java.util.ArrayList<>();
        agent.setAnswerDeltaSink(chunks::add);

        agent.run("你好");

        assertTrue(chunks.isEmpty());
        assertNull(agent.getFinalAnswer());
        assertEquals(AgentState.FINISHED, agent.getState());
        verify(model, times(1)).stream(any(Prompt.class));
        verify(model, times(1)).call(any(Prompt.class));
    }
    @Test
    void finalAnswerUsesModelStreamAndDeliversChunksOnWorkerThread() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("规划完成")))));
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("你好，")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("杰尼龟！"))))));
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0]);
        agent.setChatClient(ChatClient.builder(model).build());
        agent.setSystemPrompt("Be helpful.");
        java.util.List<String> chunks = new java.util.ArrayList<>();
        Thread worker = Thread.currentThread();
        agent.setAnswerDeltaSink(delta -> {
            assertSame(worker, Thread.currentThread(), "must retain worker lease context");
            chunks.add(delta);
        });

        agent.run("你好");

        assertEquals(List.of("你好，", "杰尼龟！"), chunks);
        assertEquals("你好，杰尼龟！", agent.getFinalAnswer());
        verify(model, times(1)).stream(any(Prompt.class));
        verify(model, times(1)).call(any(Prompt.class));
    }
    @Test
    void terminationSynthesizesUserAnswerSeparatelyFromToolTranscript() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("搜索超时，无法核实当前赛季阵容；水系克制火系。")))));
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0]);
        agent.setChatClient(ChatClient.builder(model).build());
        agent.setSystemPrompt("Be accurate.");
        ToolCallingManager manager = mock(ToolCallingManager.class);
        agent.setToolCallingManager(manager);
        var assistant = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("term-1", "function", "doTerminate", "{}")));
        var response = new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("term-1", "doTerminate", "任务结束")));
        when(manager.executeToolCalls(any(), any())).thenReturn(
                new DefaultToolExecutionResult(List.of(assistant, response), false));
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(assistant))));

        String transcript = agent.act();

        assertTrue(transcript.contains("doTerminate"));
        assertTrue(agent.getFinalAnswer().contains("搜索超时"));
        assertFalse(agent.getFinalAnswer().contains("任务结束"));
        assertEquals(AgentState.FINISHED, agent.getState());
        verify(model, times(1)).call(any(Prompt.class));
    }
    @Test
    void directModelAnswerIsReturnedAndEndsLoopWithoutTerminationTool() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("你好！想了解哪只宝可梦？")))));
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0]);
        agent.setChatClient(ChatClient.builder(model).build());
        agent.setSystemPrompt("Answer directly when no tools are necessary.");
        agent.setMaxSteps(5);

        String result = agent.run("你好");

        assertTrue(result.contains("你好！想了解哪只宝可梦？"));
        assertFalse(result.contains("无需行动"));
        assertFalse(result.contains("doTerminate"));
        assertEquals(AgentState.FINISHED, agent.getState());
        verify(model, times(2)).call(any(Prompt.class));
    }
}
