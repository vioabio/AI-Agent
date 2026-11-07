package com.vio.vioaiagent.mcp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * MCP 客户端 Spring 自动配置测试
 * <p>
 * 使用 {@link ApplicationContextRunner} 轻量级测试 MCP 客户端自动配置：
 * <ul>
 *   <li>MCP 客户端公共属性（enabled, type, timeout 等）</li>
 *   <li>ToolCallbackProvider Bean 的创建和类型</li>
 *   <li>客户端启用/禁用开关</li>
 * </ul>
 * <p>
 * 不加载完整 Spring 应用上下文，避免 spring-ai-alibaba 依赖版本冲突。
 *
 * @author vio
 */
class McpClientConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    McpClientAutoConfiguration.class,
                    McpToolCallbackAutoConfiguration.class))
            // MCP 客户端启用，但不需要实际连接（initialized=false 跳过连接）
            .withPropertyValues(
                    "spring.ai.mcp.client.enabled=true",
                    "spring.ai.mcp.client.type=SYNC",
                    "spring.ai.mcp.client.initialized=false",
                    "spring.ai.mcp.client.toolcallback.enabled=true"
            );

    @Test
    void testMcpClientPropertiesLoaded() {
        contextRunner.run(ctx -> {
            McpClientCommonProperties props = ctx.getBean(McpClientCommonProperties.class);
            Assertions.assertNotNull(props, "McpClientCommonProperties Bean 应存在");
            Assertions.assertTrue(props.isEnabled(), "MCP 客户端应默认启用");
            Assertions.assertEquals("spring-ai-mcp-client", props.getName(),
                    "默认客户端名称应为 spring-ai-mcp-client");
            Assertions.assertEquals("1.0.0", props.getVersion(),
                    "默认版本号应为 1.0.0");
        });
    }

    @Test
    void testMcpClientTypeDefaultsToSync() {
        contextRunner.run(ctx -> {
            McpClientCommonProperties props = ctx.getBean(McpClientCommonProperties.class);
            Assertions.assertEquals(McpClientCommonProperties.ClientType.SYNC,
                    props.getType(), "默认客户端类型应为 SYNC");
        });
    }

    @Test
    void testRequestTimeoutIsConfigured() {
        contextRunner.run(ctx -> {
            McpClientCommonProperties props = ctx.getBean(McpClientCommonProperties.class);
            Assertions.assertNotNull(props.getRequestTimeout(), "请求超时配置不应为空");
            Assertions.assertTrue(props.getRequestTimeout().getSeconds() > 0,
                    "请求超时时间应大于 0 秒");
        });
    }

    @Test
    void testToolCallbackProviderExists() {
        // 验证：当 MCP 客户端启用时，ToolCallbackProvider Bean 存在
        contextRunner.run(ctx -> {
            ToolCallbackProvider provider = ctx.getBean(ToolCallbackProvider.class);
            Assertions.assertNotNull(provider, "ToolCallbackProvider Bean 应存在");
        });
    }

    @Test
    void testToolCallbackProviderIsSyncType() {
        contextRunner.run(ctx -> {
            ToolCallbackProvider provider = ctx.getBean(ToolCallbackProvider.class);
            Assertions.assertInstanceOf(SyncMcpToolCallbackProvider.class, provider,
                    "ToolCallbackProvider 应为 SyncMcpToolCallbackProvider 类型");
        });
    }

    @Test
    void testToolCallbackEnabledByDefault() {
        contextRunner.run(ctx -> {
            McpClientCommonProperties props = ctx.getBean(McpClientCommonProperties.class);
            Assertions.assertTrue(props.getToolcallback().isEnabled(),
                    "toolcallback 应默认启用");
        });
    }

    @Test
    void testMcpClientCanBeDisabled() {
        // 验证：当 spring.ai.mcp.client.enabled=false 时，不创建 ToolCallbackProvider
        contextRunner
                .withPropertyValues("spring.ai.mcp.client.enabled=false")
                .run(ctx -> {
                    Assertions.assertFalse(
                            ctx.getBeanFactory().containsBean("mcpToolCallbacks"),
                            "禁用 MCP 客户端时不应创建 ToolCallbackProvider");
                });
    }

    @Test
    void testToolCallbackDisabled() {
        // 验证：当 spring.ai.mcp.client.toolcallback.enabled=false 时，不创建 ToolCallbackProvider
        contextRunner
                .withPropertyValues("spring.ai.mcp.client.toolcallback.enabled=false")
                .run(ctx -> {
                    Assertions.assertFalse(
                            ctx.getBeanFactory().containsBean("mcpToolCallbacks"),
                            "禁用 toolcallback 时不应创建 ToolCallbackProvider");
                });
    }
}
