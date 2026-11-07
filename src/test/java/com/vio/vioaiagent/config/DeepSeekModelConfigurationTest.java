package com.vio.vioaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekModelConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DeepSeekModelConfiguration.class);

    @Test
    void bothStreamingAndPlanningBodiesDisableUnsupportedReasoningHistory() throws Exception {
        var plain = new com.fasterxml.jackson.databind.ObjectMapper();
        for (boolean streaming : new boolean[]{false, true}) {
            var request = plain.readValue("{\"model\":\"deepseek-flash\",\"stream\":" + streaming
                    + ",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                    org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest.class);
            var encoded = plain.readTree(DeepSeekRequestCodec.mapper().writeValueAsBytes(request));
            assertThat(encoded.path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(encoded.path("stream").asBoolean()).isEqualTo(streaming);
            assertThat(encoded.path("messages").get(0).path("content").asText()).isEqualTo("hello");
            assertThat(plain.readTree(plain.writeValueAsBytes(request)).has("thinking")).isFalse();
        }
    }

    @Test
    void disabledUnlessExplicitlySelected() {
        context.run(c -> assertThat(c).doesNotHaveBean(ChatModel.class));
    }

    @Test
    void missingKeyFailsWithoutPublishingItsValue() {
        context.withPropertyValues("vio.model.provider=deepseek").run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasStackTraceContaining("DEEPSEEK_API_KEY");
        });
    }

    @Test
    void createsCompatibleToolCapableModelWithoutCallingExternalService() {
        context.withPropertyValues("vio.model.provider=deepseek",
                "vio.model.deepseek.api-key=test-offline-only",
                "vio.model.deepseek.model=deepseek-flash").run(c -> {
            assertThat(c).hasSingleBean(ChatModel.class);
            OpenAiChatModel model = c.getBean(OpenAiChatModel.class);
            assertThat(model.getDefaultOptions().getModel()).isEqualTo("deepseek-flash");
        });
    }
}
