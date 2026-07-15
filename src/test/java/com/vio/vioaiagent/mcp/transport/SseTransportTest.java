package com.vio.vioaiagent.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 传输测试.
 *
 * <p>注：完整的 SSE 集成测试需要嵌入式 MCP SSE Server,
 * 将在 Phase 3 的 McpProtocolCompatibilityTest 中实现.
 * 当前测试验证基本构造和配置.
 *
 * @author vio
 */
@DisplayName("SSE 传输")
class SseTransportTest {

    private final JsonRpcCodec codec = new JsonRpcCodec(new ObjectMapper());

    @Test
    @DisplayName("应能构造 SseTransport 实例")
    void shouldConstructSseTransport() {
        SseTransport transport = new SseTransport(codec, "http://localhost:8127");
        assertNotNull(transport);
        assertTrue(transport instanceof McpTransport);
    }

    @Test
    @DisplayName("默认端点应为 /mcp/sse")
    void shouldUseDefaultSseEndpoint() {
        SseTransport transport = new SseTransport(codec, "http://localhost:8127");
        assertNotNull(transport);
    }

    @Test
    @DisplayName("TransportFactory 应可创建 SSE 传输")
    void shouldCreateSseViaFactory() {
        McpTransport transport = TransportFactory.createSse(codec, "http://localhost:8127");
        assertNotNull(transport);
    }
}
