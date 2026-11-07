package com.vio.vioaiagent.mcp.gateway;

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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpGateway 测试 — 验证完整的工具发现和调用流程.
 *
 * @author vio
 */
@DisplayName("MCP 网关")
class McpGatewayTest {

    private McpGateway gateway;
    private McpSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        ProtocolMapper mapper = new InMemoryProtocolMapper();
        sessionManager = new McpSessionManager(List.of(mapper), "mcp-2024-11-05");
        gateway = new McpGateway(sessionManager);

        // 预注册一个连接
        gateway.connect("memory", new StubTransport());
    }

    @AfterEach
    void tearDown() {
        gateway.shutdown();
    }

    @Test
    @DisplayName("discoverTools 应返回已注册的工具")
    void shouldDiscoverRegisteredTools() {
        List<ToolDefinition> tools = gateway.discoverTools("memory");

        assertNotNull(tools);
        assertEquals(1, tools.size());
        assertEquals("greet", tools.get(0).name());
    }

    @Test
    @DisplayName("invokeTool 应正确调用工具并返回结果")
    void shouldInvokeToolSuccessfully() {
        ToolCallResult result = gateway.invokeTool("memory", "greet",
                Map.of("name", "训练家"));

        assertNotNull(result);
        assertFalse(result.isError());
        assertTrue(result.content().get(0).text().contains("你好"));
    }

    @Test
    @DisplayName("调用未知工具应返回错误")
    void shouldReturnErrorForUnknownTool() {
        ToolCallResult result = gateway.invokeTool("memory", "unknown", Map.of());

        assertTrue(result.isError());
    }

    @Test
    @DisplayName("disconnect 后会话应不可用")
    void shouldNotFindToolsAfterDisconnect() {
        gateway.disconnect("memory");

        List<ToolDefinition> tools = gateway.discoverTools("memory");
        assertTrue(tools.isEmpty());
    }

    @Test
    @DisplayName("getActiveConnectionCount 应正确计数")
    void shouldCountActiveConnections() {
        assertEquals(1, gateway.getActiveConnectionCount());

        gateway.connect("second", new StubTransport());
        assertEquals(2, gateway.getActiveConnectionCount());

        gateway.disconnect("second");
        assertEquals(1, gateway.getActiveConnectionCount());
    }

    // ==================== 测试桩 ====================

    private static class StubTransport implements McpTransport {
        @Override public void connect() {}
        @Override public com.vio.vioaiagent.mcp.jsonrpc.JsonRpcResponse send(
                com.vio.vioaiagent.mcp.jsonrpc.JsonRpcRequest request) { return null; }
        @Override public void close() {}
        @Override public boolean isConnected() { return true; }
    }

    /**
     * 基于内存的协议映射器 — 使用 McpProtocolSpec 注册测试工具.
     */
    private static class InMemoryProtocolMapper implements ProtocolMapper {
        @Override
        public boolean supports(String protocolId) {
            return "mcp-2024-11-05".equals(protocolId);
        }

        @Override
        public McpProtocolHandler createHandler(String protocolId, McpTransport transport) {
            McpProtocolSpec spec = new McpProtocolSpec("test-gateway", "1.0.0", ServerCapabilities.toolsOnly());
            spec.registerTool(
                    ToolDefinition.of("greet", "返回问候语"),
                    (toolName, args) -> ToolCallResult.success(
                            "你好, " + args.getOrDefault("name", "世界") + "!")
            );
            return spec;
        }
    }
}
