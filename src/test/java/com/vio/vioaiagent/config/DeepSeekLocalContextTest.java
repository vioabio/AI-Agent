package com.vio.vioaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;

import static org.junit.jupiter.api.Assertions.*;

/** Real application wiring, no provider calls or real credentials. */
@SpringBootTest(properties = {
        "spring.profiles.active=test,deepseek",
        "vio.model.deepseek.api-key=offline-context-only",
        "vio.local-history.enabled=false"
})
class DeepSeekLocalContextTest {
    @Autowired ApplicationContext context;

    @Test
    void deepSeekIsTheOnlyChatModelAndDoesNotRequireDashScopeKey() {
        assertEquals(1, context.getBeansOfType(ChatModel.class).size());
        assertInstanceOf(OpenAiChatModel.class, context.getBean(ChatModel.class));
        assertFalse(context.containsBean("dashscopeChatModel"));
        assertFalse(context.containsBean("dashscopeEmbeddingModel"));
    }
}
