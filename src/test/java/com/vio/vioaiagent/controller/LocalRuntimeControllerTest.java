package com.vio.vioaiagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalRuntimeControllerTest {
    @Test void identifiesConfiguredModelAndLoadedCodeWithoutPublishingSecretProperties() throws Exception {
        var factory = new DefaultListableBeanFactory();
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(ChatOptions.builder().model("test-model-id").build());
        factory.registerSingleton("chatModel",model);
        var environment = new MockEnvironment().withProperty("vio.model.provider","deepseek")
                .withProperty("vio.model.deepseek.api-key","DO-NOT-PUBLISH-THIS");
        environment.setActiveProfiles("local","deepseek");
        var metadata = new LocalRuntimeController(environment,factory.getBeanProvider(ChatModel.class),new ToolCallback[0]).runtime();
        assertEquals("test-model-id",metadata.get("configuredModelId"));
        assertFalse(new ObjectMapper().writeValueAsString(metadata).contains("DO-NOT-PUBLISH"));
        assertTrue(((java.util.Map<?,?>)metadata.get("implementationSha256")).values().stream()
                .allMatch(value -> value.toString().matches("[a-f0-9]{64}")));
        assertTrue(((java.util.Map<?,?>)metadata.get("implementationSha256")).containsKey("AccountScopeReply"));
        assertEquals("not-attested-by-this-endpoint",metadata.get("providerReturnedModelId"));
        var input=(java.util.Map<?,?>)metadata.get("toolInputEvidence");
        var preview=(java.util.Map<?,?>)input.get("previewPolicy");
        assertEquals("bounded-team-preview-input-v1",preview.get("projectionVersion"));
        assertEquals(144,preview.get("fieldLimit"));assertEquals(true,preview.get("requiresConfirmation"));
    }
}
