package com.vio.vioaiagent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 配置
 *
 * <p>解决全局异常处理器（GlobalExceptionHandler）返回的 ResultVoid
 * 被自动添加到所有端点 500 响应中的问题。</p>
 *
 * <p>SSE 端点类型泄露问题通过 AiController 上的 {@code @Hidden} 注解解决。</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("VIO AI Agent API")
                        .version("1.0.0")
                        .description("REST 端点使用 axios 调用；SSE 端点使用 EventSource（见 src/api/index.js）")
                        .contact(new Contact()
                                .name("vio")
                                .email("vio@example.com")));
    }

    /**
     * 移除每个端点自动注入的 ResultVoid 500 错误响应。
     * 全局异常处理器的返回类型不应污染具体端点的响应定义。
     */
    @Bean
    public OpenApiCustomizer removeAutoErrorResponses() {
        return openApi -> openApi.getPaths().forEach((path, pathItem) ->
                pathItem.readOperations().forEach(OpenApiConfig::strip500));
    }

    private static void strip500(Operation op) {
        if (op.getResponses() != null) {
            op.getResponses().remove("500");
        }
    }
}
