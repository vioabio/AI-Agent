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
    @Test void tracingDiagnosticsPublishOnlyConfiguredSamplingFields() throws Exception {
        var factory = new DefaultListableBeanFactory();
        var environment = new MockEnvironment().withProperty("vio.observability.tracing.sample-rate","0.25")
                .withProperty("management.tracing.enabled","false")
                .withProperty("vio.observability.tracing.endpoint","http://private-user:secret@localhost:4318/v1/traces");
        var metadata = new LocalRuntimeController(environment,factory.getBeanProvider(ChatModel.class),new ToolCallback[0]).runtime();
        assertEquals(java.util.Map.of("sampleRate",0.25,"automaticTracing",false,"scope","CONFIGURED_DOMAIN_ROOT_SAMPLING"),metadata.get("domainTracingPolicy"));
        assertFalse(new ObjectMapper().writeValueAsString(metadata).contains("private-user"));
        var defaults = new LocalRuntimeController(new MockEnvironment(),factory.getBeanProvider(ChatModel.class),new ToolCallback[0]).runtime();
        assertEquals(0.1,((java.util.Map<?,?>)defaults.get("domainTracingPolicy")).get("sampleRate"));
    }
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
        assertTrue(((java.util.Map<?,?>)metadata.get("implementationSha256")).containsKey("DamageAdvicePolicy"));
        assertTrue(((java.util.Map<?,?>)metadata.get("implementationSha256")).containsKey("ModelFailureDiagnostics"));
        for(Class<?> component:java.util.List.of(com.vio.vioaiagent.agent.TaskSkillRegistry.class,com.vio.vioaiagent.agent.TaskContextAssembler.class,com.vio.vioaiagent.agent.TypeMatchupReply.class)){
            try(var bytes=component.getResourceAsStream("/"+component.getName().replace('.','/')+".class")){
                assertNotNull(bytes);
                String expected=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes()));
                assertEquals(expected,((java.util.Map<?,?>)metadata.get("implementationSha256")).get(component.getSimpleName()));
            }
        }
        assertEquals("not-attested-by-this-endpoint",metadata.get("providerReturnedModelId"));
        try(var bytes=com.vio.vioaiagent.agent.EvidenceAnswerBlock.Source.class.getResourceAsStream("EvidenceAnswerBlock$Source.class")) {
            assertNotNull(bytes);
            String expected=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes()));
            assertEquals(expected,((java.util.Map<?,?>)metadata.get("implementationSha256")).get("EvidenceAnswerBlock$Source"));
        }
        var input=(java.util.Map<?,?>)metadata.get("toolInputEvidence");
        var preview=(java.util.Map<?,?>)input.get("previewPolicy");
        assertEquals("bounded-team-preview-input-v1",preview.get("projectionVersion"));
        assertEquals(144,preview.get("fieldLimit"));assertEquals(true,preview.get("requiresConfirmation"));
    }
}
