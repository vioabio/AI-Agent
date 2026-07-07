package com.vio.vioaiagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;

import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端 SSE（Server-Sent Events）传输模式单元测试
 * <p>
 * 使用 Spring Boot 内嵌 Tomcat + HttpServletSseServerTransportProvider 搭建
 * 嵌入式 MCP 服务端，验证客户端通过 SSE 协议连接、发现工具和调用工具。
 * <p>
 * 仅加载 Web 服务器和 MCP 所需的最小 Bean 集，不扫描业务代码以避免
 * spring-ai-alibaba 依赖版本冲突。
 * <p>
 * 测试工具：
 * <ul>
 *   <li>hello — 返回问候语，验证字符串参数传递</li>
 *   <li>add  — 两数相加，验证数字参数传递和计算</li>
 * </ul>
 *
 * @author vio
 */
@SpringBootTest(classes = McpClientSseTest.MinimalTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "server.servlet.context-path=/")
class McpClientSseTest {

    @LocalServerPort
    private int port;

    private McpSyncClient client;

    /**
     * 最小化测试应用：仅启用自动配置和 MCP SSE 测试配置
     */
    @EnableAutoConfiguration
    @ComponentScan(basePackageClasses = McpClientSseTest.class)
    static class MinimalTestApp {
    }

    @BeforeEach
    void setUp() throws Exception {
        // 等待嵌入式服务器完全启动
        Thread.sleep(500);

        var transport = HttpClientSseClientTransport.builder("http://localhost:" + port)
                .sseEndpoint("/mcp/sse")
                .build();
        client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    void testSseConnectionAndToolDiscovery() {
        McpSchema.ListToolsResult tools = client.listTools();
        Assertions.assertNotNull(tools, "工具列表不应为空");
        Assertions.assertNotNull(tools.tools(), "工具数组不应为空");
        Assertions.assertEquals(2, tools.tools().size(), "应包含 2 个工具");

        List<String> toolNames = tools.tools().stream()
                .map(McpSchema.Tool::name)
                .sorted()
                .toList();
        Assertions.assertEquals(List.of("add", "hello"), toolNames, "工具名称应为 hello 和 add");

        McpSchema.Tool helloTool = tools.tools().stream()
                .filter(t -> "hello".equals(t.name()))
                .findFirst()
                .orElseThrow();
        Assertions.assertEquals("Returns a greeting message", helloTool.description());
        Assertions.assertNotNull(helloTool.inputSchema(), "hello 工具应有参数 schema");
    }

    @Test
    void testSseToolInvocationHello() {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("hello", Map.of("name", "World")));
        Assertions.assertNotNull(result, "工具调用结果不应为空");
        Assertions.assertFalse(result.isError(), "工具调用不应返回错误");

        String content = extractTextContent(result);
        Assertions.assertTrue(content.contains("Hello, World!"),
                "返回内容应包含问候语，实际: " + content);
    }

    @Test
    void testSseToolInvocationAdd() {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("add", Map.of("a", 3, "b", 5)));
        Assertions.assertNotNull(result, "工具调用结果不应为空");
        Assertions.assertFalse(result.isError(), "工具调用不应返回错误");

        String content = extractTextContent(result);
        Assertions.assertTrue(content.contains("8"),
                "返回内容应包含计算结果 8，实际: " + content);
    }

    @Test
    void testSseClientGracefulShutdown() {
        boolean closed = client.closeGracefully();
        Assertions.assertTrue(closed, "客户端应能优雅关闭");
        client = null;
    }

    private String extractTextContent(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .reduce("", String::concat);
    }

    // ==================== 测试配置 ====================

    @TestConfiguration
    static class EmbeddedMcpSseServerConfig {

        @Bean
        HttpServletSseServerTransportProvider sseTransportProvider(ObjectMapper objectMapper) {
            return new HttpServletSseServerTransportProvider(objectMapper,
                    "/mcp/message", "/mcp/sse");
        }

        @Bean
        ServletRegistrationBean<HttpServletSseServerTransportProvider> sseServlet(
                HttpServletSseServerTransportProvider provider) {
            // 注册到两个路径：SSE 连接端点和消息端点
            ServletRegistrationBean<HttpServletSseServerTransportProvider> registration =
                    new ServletRegistrationBean<>(provider, "/mcp/sse", "/mcp/message");
            registration.setAsyncSupported(true);
            return registration;
        }

        @Bean
        McpSyncServer testMcpServer(HttpServletSseServerTransportProvider transportProvider) {
            var helloTool = new McpSchema.Tool("hello", "Returns a greeting message", """
                    {
                      "type": "object",
                      "properties": {
                        "name": {"type": "string", "description": "Name to greet"}
                      },
                      "required": ["name"]
                    }
                    """);
            var helloSpec = new McpServerFeatures.SyncToolSpecification(helloTool,
                    (exchange, args) -> {
                        String name = (String) args.getOrDefault("name", "World");
                        return new McpSchema.CallToolResult(
                                "Hello, " + name + "! Nice to meet you via MCP SSE.", false);
                    });

            var addTool = new McpSchema.Tool("add", "Adds two numbers together", """
                    {
                      "type": "object",
                      "properties": {
                        "a": {"type": "number", "description": "First number"},
                        "b": {"type": "number", "description": "Second number"}
                      },
                      "required": ["a", "b"]
                    }
                    """);
            var addSpec = new McpServerFeatures.SyncToolSpecification(addTool,
                    (exchange, args) -> {
                        Number a = (Number) args.get("a");
                        Number b = (Number) args.get("b");
                        int result = a.intValue() + b.intValue();
                        return new McpSchema.CallToolResult(
                                String.valueOf(result), false);
                    });

            return McpServer.sync(transportProvider)
                    .tools(helloSpec, addSpec)
                    .build();
        }
    }
}
