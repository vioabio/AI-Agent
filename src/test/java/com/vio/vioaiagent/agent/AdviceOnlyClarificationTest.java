package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdviceOnlyClarificationTest {
    @Test void unspecifiedFirstTurnCannotInventEmptyAccountOrCallModelsAndTools() {
        for (String prompt : List.of("给一套建议就好，不要保存，也不要生成修改预览。", "请提供一些建议即可，不保存，也不生成预览。", "给点建议就好，别保存，也别生成修改预览。")) {
            var model = mock(org.springframework.ai.chat.model.ChatModel.class);
            var tool = mock(org.springframework.ai.tool.ToolCallback.class);
            var executor = new DefaultRunExecutor(model, new org.springframework.ai.tool.ToolCallback[]{tool}, null, null, null, null, false);
            var run = AgentRun.create("run-advice-test", "s", "c", "u", prompt, "pokemon-scarlet-violet", "casual-singles", "casual-v1", "zh-CN", "trace", null, java.time.Instant.now().plusSeconds(30));
            var deltas = new ArrayList<String>();
            String answer = executor.execute(new AgentRunContext(run, deltas::add, List.of()));
            assertTrue(answer.contains("尚未明确建议对象")); assertTrue(answer.contains("本次没有读取队伍列表"));
            assertFalse(answer.contains("当前没有可读取的已保存队伍")); assertEquals(answer, String.join("", deltas));
            verifyNoInteractions(model, tool);
        }
    }
    @Test void existingTargetAndExplicitTasksAreNotReplacedByClarification() {
        assertTrue(AccountScopeReply.adviceClarification("给一套建议就好，不要保存，也不要生成修改预览。", List.of(new org.springframework.ai.chat.messages.UserMessage("看青木初战"))).isEmpty());
        assertTrue(AccountScopeReply.adviceClarification("给皮卡丘一套配招建议，不保存，也不生成预览", List.of()).isEmpty());
    }
}
