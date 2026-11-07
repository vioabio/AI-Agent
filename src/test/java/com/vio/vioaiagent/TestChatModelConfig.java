package com.vio.vioaiagent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

/**
 * Spring 上下文烟测使用的无网络 ChatModel 占位 Bean。
 * 真实模型调用测试单独放在 integration 标记下，默认 CI 不读取开发机密钥。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestChatModelConfig {
    @Bean(name = "dashscopeChatModel")
    @Primary
    ChatModel testChatModel() {
        return mock(ChatModel.class);
    }
}
