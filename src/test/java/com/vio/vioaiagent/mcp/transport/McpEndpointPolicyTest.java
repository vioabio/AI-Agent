package com.vio.vioaiagent.mcp.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("自研 MCP endpoint 安全策略")
class McpEndpointPolicyTest {
    @Test
    void allowsLoopbackHttpForLab() {
        assertEquals("localhost", McpEndpointPolicy.validateBase("http://localhost:8127").getHost());
    }

    @Test
    void rejectsPlainHttpOutsideLoopback() {
        assertThrows(IllegalArgumentException.class,
                () -> McpEndpointPolicy.validateBase("http://mcp.example.test"));
    }

    @Test
    void rejectsCrossOriginSseEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> McpEndpointPolicy.validateSessionEndpoint(
                        "https://mcp.example.test", "https://evil.example.test/message"));
    }

    @Test
    void resolvesSameOriginRelativeEndpoint() {
        URI endpoint = McpEndpointPolicy.validateSessionEndpoint(
                "https://mcp.example.test", "/mcp/message?session=abc");
        assertEquals("https://mcp.example.test/mcp/message?session=abc", endpoint.toString());
    }
}
