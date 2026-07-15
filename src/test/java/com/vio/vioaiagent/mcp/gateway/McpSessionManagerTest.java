package com.vio.vioaiagent.mcp.gateway;

import com.vio.vioaiagent.mcp.protocol.McpProtocolHandler;
import com.vio.vioaiagent.mcp.protocol.ProtocolMapper;
import com.vio.vioaiagent.mcp.protocol.model.*;
import com.vio.vioaiagent.mcp.transport.McpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpSessionManager 测试.
 *
 * @author vio
 */
@DisplayName("MCP 会话管理器")
class McpSessionManagerTest {

    private static final String DEFAULT_PROTOCOL = "mcp-2024-11-05";

    @Test
    @DisplayName("应为新连接创建会话")
    void shouldCreateSessionForNewConnection() {
        McpSessionManager manager = createManager();
        StubTransport transport = new StubTransport();

        McpSession session = manager.getOrCreateSession("test-conn", transport);

        assertNotNull(session);
        assertTrue(session.isReady());
        assertEquals(1, manager.activeSessionCount());
    }

    @Test
    @DisplayName("应复用已有会话")
    void shouldReuseExistingSession() {
        McpSessionManager manager = createManager();
        StubTransport transport = new StubTransport();

        McpSession session1 = manager.getOrCreateSession("test-conn", transport);
        McpSession session2 = manager.getOrCreateSession("test-conn", transport);

        assertSame(session1, session2);
        assertEquals(1, manager.activeSessionCount());
    }

    @Test
    @DisplayName("closeSession 应移除并关闭会话")
    void shouldCloseAndRemoveSession() {
        McpSessionManager manager = createManager();
        StubTransport transport = new StubTransport();

        manager.getOrCreateSession("test-conn", transport);
        manager.closeSession("test-conn");

        assertEquals(0, manager.activeSessionCount());
        assertNull(manager.getSession("test-conn"));
    }

    @Test
    @DisplayName("closeAll 应关闭所有会话")
    void shouldCloseAllSessions() {
        McpSessionManager manager = createManager();
        manager.getOrCreateSession("conn-1", new StubTransport());
        manager.getOrCreateSession("conn-2", new StubTransport());

        assertEquals(2, manager.activeSessionCount());
        manager.closeAll();

        assertEquals(0, manager.activeSessionCount());
    }

    @Test
    @DisplayName("evictIdleSessions 不淘汰活跃会话")
    void shouldNotEvictActiveSessions() {
        McpSessionManager manager = createManager();
        manager.getOrCreateSession("conn-1", new StubTransport());
        assertEquals(1, manager.activeSessionCount());

        // 淘汰 10 分钟前空闲的会话 — 活跃会话不应被淘汰
        manager.evictIdleSessions(Duration.ofMinutes(10));

        assertEquals(1, manager.activeSessionCount(), "活跃会话不应被淘汰");
    }

    private McpSessionManager createManager() {
        ProtocolMapper mapper = new StubProtocolMapper();
        return new McpSessionManager(List.of(mapper), DEFAULT_PROTOCOL);
    }

    // ==================== 测试桩 ====================

    private static class StubTransport implements McpTransport {
        private boolean connected;

        @Override public void connect() { connected = true; }
        @Override public com.vio.vioaiagent.mcp.jsonrpc.JsonRpcResponse send(
                com.vio.vioaiagent.mcp.jsonrpc.JsonRpcRequest request) { return null; }
        @Override public void close() { connected = false; }
        @Override public boolean isConnected() { return connected; }
    }

    private static class StubProtocolMapper implements ProtocolMapper {
        @Override public boolean supports(String protocolId) { return true; }
        @Override
        public McpProtocolHandler createHandler(String protocolId, McpTransport transport) {
            return new StubProtocolHandler();
        }
    }

    private static class StubProtocolHandler implements McpProtocolHandler {
        private boolean initialized;
        @Override
        public InitializeResult initialize(InitializeRequest request) {
            initialized = true;
            return InitializeResult.success(ServerCapabilities.toolsOnly(), Map.of("name", "stub"));
        }
        @Override public ServerCapabilities getCapabilities() { return ServerCapabilities.toolsOnly(); }
        @Override public ToolListResult listTools(String cursor) { return ToolListResult.of(List.of()); }
        @Override public ToolCallResult callTool(String toolName, Map<String, Object> arguments) {
            return ToolCallResult.success("stub");
        }
        @Override public void shutdown() { initialized = false; }
    }
}
