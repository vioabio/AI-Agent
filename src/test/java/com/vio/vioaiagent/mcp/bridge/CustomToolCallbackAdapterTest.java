package com.vio.vioaiagent.mcp.bridge;

import com.vio.vioaiagent.mcp.gateway.McpGateway;
import com.vio.vioaiagent.mcp.gateway.McpSessionManager;
import com.vio.vioaiagent.mcp.protocol.McpProtocolHandler;
import com.vio.vioaiagent.mcp.protocol.McpProtocolSpec;
import com.vio.vioaiagent.mcp.protocol.ProtocolMapper;
import com.vio.vioaiagent.mcp.protocol.model.ServerCapabilities;
import com.vio.vioaiagent.mcp.protocol.model.ToolCallResult;
import com.vio.vioaiagent.mcp.protocol.model.ToolDefinition;
import com.vio.vioaiagent.mcp.transport.McpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CustomToolCallbackAdapter 测试.
 *
 * @author vio
 */
@DisplayName("自定义 MCP 工具适配器")
class CustomToolCallbackAdapterTest {

    private McpGateway gateway;

    @BeforeEach
    void setUp() {
        // 创建内存 MCP 网关
        ProtocolMapper mapper = new StubProtocolMapper();
        McpSessionManager sessionManager = new McpSessionManager(
                List.of(mapper), "mcp-2024-11-05");
        gateway = new McpGateway(sessionManager);
        gateway.connect("test", new StubTransport());
    }

    @AfterEach
    void tearDown() {
        gateway.shutdown();
    }

    @Test
    @DisplayName("wrap 应将自定义工具包装为 Spring AI ToolCallback")
    void shouldWrapCustomToolsAsToolCallbacks() {
        ToolCallback[] callbacks = CustomToolCallbackAdapter.wrap("test", gateway);

        assertNotNull(callbacks);
        assertEquals(1, callbacks.length);
        assertEquals("greet", callbacks[0].getToolDefinition().name());
    }

    @Test
    @DisplayName("适配后的 ToolCallback 应能正确调用工具")
    void shouldInvokeToolViaAdaptedCallback() {
        ToolCallback[] callbacks = CustomToolCallbackAdapter.wrap("test", gateway);

        String result = callbacks[0].call("{\"name\":\"训练家\"}");

        assertNotNull(result);
        assertTrue(result.contains("你好"));
    }

    // ==================== 测试桩 ====================

    private static class StubTransport implements McpTransport {
        @Override public void connect() {}
        @Override public com.vio.vioaiagent.mcp.jsonrpc.JsonRpcResponse send(
                com.vio.vioaiagent.mcp.jsonrpc.JsonRpcRequest request) { return null; }
        @Override public void close() {}
        @Override public boolean isConnected() { return true; }
    }

    private static class StubProtocolMapper implements ProtocolMapper {
        @Override public boolean supports(String protocolId) { return true; }
        @Override
        public McpProtocolHandler createHandler(String protocolId, McpTransport transport) {
            McpProtocolSpec spec = new McpProtocolSpec("test-adapter", "1.0",
                    ServerCapabilities.toolsOnly());
            spec.registerTool(
                    ToolDefinition.of("greet", "返回问候"),
                    (toolName, args) -> ToolCallResult.success(
                            "你好, " + args.getOrDefault("name", "世界") + "!")
            );
            return spec;
        }
    }
}
