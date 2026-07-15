package com.vio.vioaiagent.mcp.protocol;

import com.vio.vioaiagent.mcp.protocol.model.InitializeRequest;
import com.vio.vioaiagent.mcp.protocol.model.InitializeResult;
import com.vio.vioaiagent.mcp.protocol.model.ServerCapabilities;
import com.vio.vioaiagent.mcp.protocol.model.ToolCallResult;
import com.vio.vioaiagent.mcp.protocol.model.ToolDefinition;
import com.vio.vioaiagent.mcp.protocol.model.ToolListResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 协议处理器测试.
 *
 * @author vio
 */
@DisplayName("MCP 协议处理器")
class McpProtocolHandlerTest {

    private McpProtocolSpec spec;

    @BeforeEach
    void setUp() {
        spec = new McpProtocolSpec("test-server", "1.0.0");

        // 注册两个测试工具
        spec.registerTool(
                ToolDefinition.of("greet", "返回问候语"),
                (toolName, args) -> ToolCallResult.success("你好, " + args.getOrDefault("name", "世界") + "!")
        );
        spec.registerTool(
                ToolDefinition.of("calculate", "执行简单计算"),
                (toolName, args) -> {
                    int a = ((Number) args.get("a")).intValue();
                    int b = ((Number) args.get("b")).intValue();
                    return ToolCallResult.success(String.valueOf(a + b));
                }
        );
    }

    // ==================== 初始化 ====================

    @Test
    @DisplayName("initialize 应返回服务端能力")
    void shouldReturnCapabilitiesOnInitialize() {
        InitializeRequest request = InitializeRequest.ofLatest(
                Map.of(), Map.of("name", "test-client", "version", "1.0")
        );

        InitializeResult result = spec.initialize(request);

        assertNotNull(result);
        assertEquals("2024-11-05", result.protocolVersion());
        assertTrue(result.capabilities().toolsSupported());
        assertEquals("test-server", result.serverInfo().get("name"));
    }

    // ==================== 工具发现 ====================

    @Test
    @DisplayName("listTools 应返回已注册的工具")
    void shouldListRegisteredTools() {
        spec.initialize(InitializeRequest.ofLatest(Map.of(), Map.of()));

        ToolListResult result = spec.listTools();

        assertNotNull(result);
        assertEquals(2, result.tools().size());
        assertNull(result.nextCursor()); // 不分页

        // 验证工具定义
        assertTrue(result.tools().stream().anyMatch(t -> t.name().equals("greet")));
        assertTrue(result.tools().stream().anyMatch(t -> t.name().equals("calculate")));
    }

    @Test
    @DisplayName("未初始化时调用 listTools 应抛出异常")
    void shouldThrowWhenListingToolsBeforeInit() {
        assertThrows(IllegalStateException.class, () -> spec.listTools());
    }

    // ==================== 工具调用 ====================

    @Test
    @DisplayName("callTool 应正确分发到注册的处理器")
    void shouldDispatchCallToHandler() {
        spec.initialize(InitializeRequest.ofLatest(Map.of(), Map.of()));

        ToolCallResult result = spec.callTool("greet", Map.of("name", "训练家"));

        assertNotNull(result);
        assertFalse(result.isError());
        assertEquals("你好, 训练家!", result.content().get(0).text());
    }

    @Test
    @DisplayName("调用未知工具应返回错误")
    void shouldReturnErrorForUnknownTool() {
        spec.initialize(InitializeRequest.ofLatest(Map.of(), Map.of()));

        ToolCallResult result = spec.callTool("unknown_tool", Map.of());

        assertTrue(result.isError());
        assertTrue(result.content().get(0).text().contains("工具未找到"));
    }

    @Test
    @DisplayName("工具处理器抛异常时应返回错误而非传播")
    void shouldCatchHandlerException() {
        spec.registerTool(
                ToolDefinition.of("explosive", "会爆炸的工具"),
                (toolName, args) -> {
                    throw new RuntimeException("Boom!");
                }
        );
        spec.initialize(InitializeRequest.ofLatest(Map.of(), Map.of()));

        ToolCallResult result = spec.callTool("explosive", Map.of());

        assertTrue(result.isError());
        assertTrue(result.content().get(0).text().contains("Boom!"));
    }

    @Test
    @DisplayName("未初始化时调用 callTool 应抛出异常")
    void shouldThrowWhenCallingToolBeforeInit() {
        assertThrows(IllegalStateException.class, () -> spec.callTool("greet", Map.of()));
    }

    // ==================== 关闭 ====================

    @Test
    @DisplayName("shutdown 后调用工具应抛出异常")
    void shouldThrowAfterShutdown() {
        spec.initialize(InitializeRequest.ofLatest(Map.of(), Map.of()));
        spec.shutdown();

        assertThrows(IllegalStateException.class, () -> spec.listTools());
    }
}
