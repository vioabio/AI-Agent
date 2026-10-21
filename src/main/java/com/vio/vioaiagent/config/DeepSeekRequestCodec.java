package com.vio.vioaiagent.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;

import java.io.IOException;

/** Spring AI 1.0 does not round-trip DeepSeek reasoning_content in tool history.
 * Explicitly use non-thinking mode until that protocol is supported end to end.
 * This only configures the DeepSeek clients, never the application's global mapper.
 */
final class DeepSeekRequestCodec {
    private DeepSeekRequestCodec() { }

    static ObjectMapper mapper() {
        ObjectMapper original = JsonMapper.builder().findAndAddModules().build();
        SimpleModule module = new SimpleModule();
        module.addSerializer(ChatCompletionRequest.class, new StdSerializer<>(ChatCompletionRequest.class) {
            @Override
            public void serialize(ChatCompletionRequest request, JsonGenerator generator,
                                  SerializerProvider provider) throws IOException {
                ObjectNode body = original.valueToTree(request);
                body.putObject("thinking").put("type", "disabled");
                generator.writeTree(body);
            }
        });
        return original.copy().registerModule(module);
    }
}
