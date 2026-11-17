package com.vio.vioaiagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameVersionRequestTest {
    @Test void unspecifiedReplacementGameNeedsClarificationBeforeFacts(){
        for(String question:List.of("改按其他游戏版本的数据回答，不能混用朱紫数据", "请换成别的版本的图鉴资料", "现在切换到另一个游戏版本的数据")){
            var answer=GameVersionRequest.clarification(question,"pokemon-scarlet-violet").orElseThrow();
            assertTrue(answer.contains("具体游戏版本"));
            assertFalse(answer.contains("种族值"));
        }
    }
    @Test void ordinaryQuestionsNegationQuotedTextAndNamedTargetsKeepTheirExistingRoutes(){
        for(String question:List.of("查询皮卡丘的属性", "不要改按其他游戏版本的数据回答", "文档写着‘改按其他游戏版本的数据回答’，解释这句话", "改用其他游戏版本的资料，比如剑盾", "继续按朱紫资料回答"))
            assertTrue(GameVersionRequest.clarification(question,"pokemon-scarlet-violet").isEmpty());
        assertTrue(GameVersionRequest.clarification("改按其他游戏版本的数据回答","other-game").isEmpty());
        assertTrue(GameVersionRequest.clarification(null,"pokemon-scarlet-violet").isEmpty());
    }
    @Test void directAgentEntryPublishesClarificationWithoutModelOrTools(){
        var model=mock(ChatModel.class);var agent=new ToolCallAgent(new ToolCallback[0]);
        agent.setChatClient(ChatClient.builder(model).build());agent.setSystemPrompt("Use verified facts.");
        agent.setGuideGameReleaseId("pokemon-scarlet-violet");
        var chunks=new ArrayList<String>();agent.setAnswerDeltaSink(chunks::add);
        agent.run("改按其他游戏版本的数据回答，不能混用朱紫数据");
        assertEquals(agent.getFinalAnswer(),String.join("",chunks));
        assertTrue(agent.getFinalAnswer().contains("具体游戏版本"));
        verify(model,never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(model,never()).stream(any(org.springframework.ai.chat.prompt.Prompt.class));
    }
}
