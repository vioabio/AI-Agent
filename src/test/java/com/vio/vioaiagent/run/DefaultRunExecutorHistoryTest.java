package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultRunExecutorHistoryTest {
    public static class ApprovalFixture {
        @org.springframework.ai.tool.annotation.Tool(description="Controlled approval pause fixture")
        public String previewTeamChanges() {
            var context=com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder.current();
            context.eventSink().accept(new com.vio.vioaiagent.tools.governance.ToolGovernanceEvent("approval.required",context.runId(),"previewTeamChanges","test-event",Instant.now(),java.util.Map.of("approvalId","test-approval")));
            return "等待确认";
        }
    }
    @Test void streamingExecutorTreatsApprovalPauseAsPendingRatherThanAnEmptyAnswerFailure() {
        ChatModel model=mock(ChatModel.class);
        var call=new AssistantMessage("",java.util.Map.of(),List.of(new AssistantMessage.ToolCall("preview","function","previewTeamChanges","{}")));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(call))));
        var callbacks=org.springframework.ai.support.ToolCallbacks.from(new ApprovalFixture());
        var executor=new DefaultRunExecutor(model,callbacks,null,null,null,null,false);
        var run=AgentRun.create("pending-r","pending-s","pending-c","pending-u","只生成预览等我确认","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(30));
        run.transitionTo(AgentRunStatus.RUNNING);
        var context=new com.vio.vioaiagent.tools.governance.ToolExecutionContext(run.userId(),run.id(),java.util.Set.of("trainer"),run.traceId(),null,event->{
            if(event.eventType().equals("approval.required"))run.transitionTo(AgentRunStatus.WAITING_APPROVAL);
        });
        com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder.set(context);
        try {
            var chunks=new java.util.ArrayList<String>();
            assertDoesNotThrow(()->executor.execute(new AgentRunContext(run,chunks::add)));
            assertEquals(AgentRunStatus.WAITING_APPROVAL,run.status()); assertTrue(chunks.isEmpty());
            verify(model,times(1)).call(any(Prompt.class)); verify(model,never()).stream(any(Prompt.class));
        } finally {com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder.clear();}
    }
    @Test
    void executorIncludesHistoryEvenWhenNoToolsAreConfigured() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("杰尼龟是水属性。")))));
        var executor = new DefaultRunExecutor(model, new ToolCallback[0], null, null, null, null, false);
        var run = AgentRun.create("r", "s", "c", "u", "它是什么属性？", "sv-1", "singles", "rules",
                "zh-CN", "t", null, Instant.now().plusSeconds(30));
        String result = executor.execute(new AgentRunContext(run, null,
                List.of(new UserMessage("我的主力是杰尼龟"), new AssistantMessage("记住了"))));
        assertEquals("杰尼龟是水属性。", result);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompt.capture());
        for (Prompt request : prompt.getAllValues()) {
        assertTrue(request.getInstructions().stream()
                .anyMatch(m -> m instanceof UserMessage && m.getText().equals("我的主力是杰尼龟")));
        assertTrue(request.getInstructions().stream()
                .anyMatch(m -> m instanceof AssistantMessage && m.getText().equals("记住了")));
        }
        assertTrue(prompt.getValue().getInstructions().stream()
                .anyMatch(m -> m instanceof UserMessage && m.getText().contains("现在只输出给用户阅读的最终回答")));
    }
}
