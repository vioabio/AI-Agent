package com.vio.vioaiagent.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "vio.model.provider", havingValue = "deepseek")
public class DeepSeekModelConfiguration {
    @Bean
    @Primary
    public ChatModel deepSeekChatModel(
            @Value("${vio.model.deepseek.api-key:}") String apiKey,
            @Value("${vio.model.deepseek.model:deepseek-flash}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DeepSeek API Key 未配置，请在环境变量中设置 DEEPSEEK_API_KEY");
        }
        if (model == null || model.isBlank()) throw new IllegalArgumentException("DeepSeek model 不能为空");
        return build(apiKey, model, "https://api.deepseek.com");
    }

    /** Package boundary also permits deterministic HTTP protocol tests with a local stub. */
    static ChatModel build(String apiKey, String model, String baseUrl) {
        var mapper = DeepSeekRequestCodec.mapper();
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        var rest = RestClient.builder().requestFactory(requestFactory).messageConverters(converters -> {
            converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
            converters.add(new MappingJackson2HttpMessageConverter(mapper));
        });
        var web = WebClient.builder().codecs(codecs ->
                codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(mapper)));
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().baseUrl(baseUrl)
                        .restClientBuilder(rest).webClientBuilder(web)
                        .completionsPath("/chat/completions").apiKey(apiKey.trim()).build())
                .defaultOptions(OpenAiChatOptions.builder().model(model.trim())
                        .internalToolExecutionEnabled(false).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
    }
}
