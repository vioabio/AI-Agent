package com.vio.vioaiagent.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;

import java.io.IOException;

/** Spring AI 1.0 does not round-trip DeepSeek reasoning_content in tool history.
 * Explicitly use non-thinking mode until that protocol is supported end to end.
 * This only configures the DeepSeek clients, never the application's global mapper.
 */
public final class DeepSeekRequestCodec {
    private DeepSeekRequestCodec() { }

    static ObjectMapper mapper() {
        ObjectMapper original = JsonMapper.builder().findAndAddModules().build();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(OpenAiApi.Usage.class,new StdDeserializer<>(OpenAiApi.Usage.class) {
            @Override public OpenAiApi.Usage deserialize(JsonParser parser,DeserializationContext context) throws IOException {
                JsonNode node=parser.getCodec().readTree(parser);
                Integer input=count(node,"prompt_tokens"),output=count(node,"completion_tokens"),total=count(node,"total_tokens");
                var prompt=node.path("prompt_tokens_details");var completion=node.path("completion_tokens_details");
                Integer cached=count(prompt,"cached_tokens"),hit=count(node,"prompt_cache_hit_tokens"),miss=count(node,"prompt_cache_miss_tokens");
                if(hit!=null) {
                    if(cached!=null && !cached.equals(hit) || hit<0 || miss!=null
                            && (miss<0 || input==null || (long)hit+miss!=input.longValue()))cached=-1;
                    else cached=hit;
                } else if(miss!=null && (miss<0 || input==null || miss>input))cached=-1;
                if(miss!=null && cached!=null && (input==null || cached<0 || miss<0 || (long)cached+miss!=input.longValue()))cached=-1;
                var promptDetails=cached!=null || prompt.isObject()?new OpenAiApi.Usage.PromptTokensDetails(count(prompt,"audio_tokens"),cached):null;
                var completionDetails=completion.isObject()?new OpenAiApi.Usage.CompletionTokenDetails(count(completion,"reasoning_tokens"),
                        count(completion,"accepted_prediction_tokens"),count(completion,"audio_tokens"),count(completion,"rejected_prediction_tokens")):null;
                return new OpenAiApi.Usage(output,input,total,promptDetails,completionDetails);
            }
        });
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
    /** Invalid optional counters use a numeric sentinel, preserving valid total usage without retaining raw JSON. */
    private static Integer count(JsonNode node,String name) {
        var value=node.get(name);
        return value==null || value.isNull()?null:value.isIntegralNumber() && value.canConvertToInt()?value.intValue():-1;
    }
}
