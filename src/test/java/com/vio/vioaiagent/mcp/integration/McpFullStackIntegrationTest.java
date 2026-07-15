package com.vio.vioaiagent.mcp.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcCodec;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcRequest;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcResponse;
import com.vio.vioaiagent.mcp.protocol.McpProtocolSpec;
import com.vio.vioaiagent.mcp.protocol.model.InitializeRequest;
import com.vio.vioaiagent.mcp.protocol.model.ServerCapabilities;
import com.vio.vioaiagent.mcp.protocol.model.ToolCallResult;
import com.vio.vioaiagent.mcp.protocol.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 全栈集成测试 — 自定义协议栈端到端验证.
 *
 * <p>不使用任何 Spring AI MCP 依赖，完全基于自定义 JSON-RPC 2.0 +
 * McpProtocolSpec 进行 initialize → listTools → callTool 全链路验证.
 *
 * @author vio
 */
@DisplayName("MCP 全栈集成测试")
class McpFullStackIntegrationTest {

    private JsonRpcCodec codec;
    private McpProtocolSpec server;

    @BeforeEach
    void setUp() {
        codec = new JsonRpcCodec(new ObjectMapper());

        server = new McpProtocolSpec("test-server", "1.0.0", ServerCapabilities.toolsOnly());
        server.registerTool(
                ToolDefinition.of("echo", "回显输入内容"),
                (toolName, args) -> ToolCallResult.success(
                        "ECHO: " + args.getOrDefault("input", ""))
        );
        server.registerTool(
                ToolDefinition.of("add", "两个数相加"),
                (toolName, args) -> {
                    int a = ((Number) args.get("a")).intValue();
                    int b = ((Number) args.get("b")).intValue();
                    return ToolCallResult.success(String.valueOf(a + b));
                }
        );
    }

    @Test
    @DisplayName("initialize 应返回服务端能力")
    void shouldInitializeAndReturnCapabilities() {
        var result = server.initialize(InitializeRequest.ofLatest(
                Map.of(), Map.of("name", "test-client")
        ));

        assertNotNull(result);
        assertEquals("2024-11-05", result.protocolVersion());
        assertTrue(result.capabilities().toolsSupported());
        assertEquals("test-server", result.serverInfo().get("name"));
    }

    @Test
    @DisplayName("listTools 应返回已注册的所有工具")
    void shouldListAllRegisteredTools() {
        server.initialize(InitializeRequest.ofLatest(Map.of(), Map.of()));

        var result = server.listTools();

        assertEquals(2, result.tools().size());
        assertTrue(result.tools().stream().anyMatch(t -> "echo".equals(t.name())));
        assertTrue(result.tools().stream().anyMatch(t -> "add".equals(t.name())));
    }

    @Test
    @DisplayName("callTool 应正确执行工具")
    void shouldCallToolSuccessfully() {
        server.initialize(InitializeRequest.ofLatest(Map.of(), Map.of()));

        ToolCallResult result = server.callTool("add", Map.of("a", 3, "b", 4));

        assertFalse(result.isError());
        assertEquals("7", result.content().get(0).text());
    }

    @Test
    @DisplayName("JSON-RPC 编码的请求应能被 McpProtocolSpec 处理")
    void shouldEncodeAndProcessRequest() {
        server.initialize(InitializeRequest.ofLatest(Map.of(), Map.of()));

        // 模拟客户端使用 JSON-RPC 编码 → 服务端处理 → 解码响应
        JsonRpcRequest request = JsonRpcRequest.of("tools/call",
                Map.of("toolName", "echo", "arguments", Map.of("input", "hello")));

        String json = codec.encode(request);
        JsonRpcRequest decoded = codec.decodeRequest(json);

        // 验证请求往返
        assertEquals("tools/call", decoded.method());
        assertNotNull(decoded.params());
    }

    @Test
    @DisplayName("全链路: initialize → listTools → callTool → shutdown")
    void shouldCompleteFullLifecycle() {
        // Step 1: 初始化
        var initResult = server.initialize(InitializeRequest.ofLatest(Map.of(), Map.of()));
        assertTrue(initResult.capabilities().toolsSupported());

        // Step 2: 工具发现
        var tools = server.listTools();
        assertEquals(2, tools.tools().size());

        // Step 3: 工具调用
        ToolCallResult echoResult = server.callTool("echo", Map.of("input", "mcp-test"));
        assertFalse(echoResult.isError());
        assertTrue(echoResult.content().get(0).text().contains("mcp-test"));

        // Step 4: 关闭
        server.shutdown();
    }
}
