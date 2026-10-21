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
        verify(model).call(prompt.capture());
        assertTrue(prompt.getValue().getInstructions().stream()
                .anyMatch(m -> m instanceof UserMessage && m.getText().equals("我的主力是杰尼龟")));
        assertTrue(prompt.getValue().getInstructions().stream()
                .anyMatch(m -> m instanceof AssistantMessage && m.getText().equals("记住了")));
    }
}
