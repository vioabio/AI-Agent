package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.agent.model.AgentState;
import org.junit.jupiter.api.Test;
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
    @Test
    void streamingToolRoundUsesOneGenerationPerTurnAndPreservesParentContext() {
        var original = new com.vio.vioaiagent.tools.governance.ToolExecutionContext(
                "user-a", "run-a", java.util.Set.of("trainer"), "trace-a", "key-a", event -> {});
        ChatModel model = mock(ChatModel.class);
        var call = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("tool-1", "function", "getTypeChart", "{\"attackType\":\"火\"}")));
        when(model.stream(any(Prompt.class))).thenReturn(
                reactor.core.publisher.Flux.just(new ChatResponse(List.of(new Generation(call)))),
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
            verify(model, times(2)).stream(any(Prompt.class));
            verify(model, never()).call(any(Prompt.class));
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
    @Test
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
        verify(model, never()).call(any(Prompt.class));
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
        verify(model, never()).call(any(Prompt.class));
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
        verify(model, times(1)).call(any(Prompt.class));
    }
}
