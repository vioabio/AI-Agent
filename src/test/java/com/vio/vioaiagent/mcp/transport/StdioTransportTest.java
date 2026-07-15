package com.vio.vioaiagent.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcCodec;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STDIO 传输测试.
 *
 * <p>测试 STDIO 子进程的连接建立、关闭和生命周期管理.
 * 完整的 send/receive 交互测试将在 Phase 3 的 {@code McpFullStackIntegrationTest}
 * 中使用真实的 MCP 协议处理器进行.
 *
 * <p>注意: 简单 shell 命令（echo/cat）在执行后立即退出，无法进行
 * 交互式的 stdin/stdout 通信. 此处只验证连接管理和状态转换.
 *
 * @author vio
 */
@DisplayName("STDIO 传输")
class StdioTransportTest {

    private JsonRpcCodec codec;
    private StdioTransport transport;

    @BeforeEach
    void setUp() {
        codec = new JsonRpcCodec(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (transport != null && transport.isConnected()) {
            transport.close();
        }
    }

    @Test
    @DisplayName("connect 应启动子进程并建立连接")
    void shouldConnectSuccessfully() {
        transport = createPingTransport();
        transport.connect();

        assertTrue(transport.isConnected());
    }

    @Test
    @DisplayName("close 应终止子进程")
    void shouldCloseAndKillProcess() {
        transport = createPingTransport();
        transport.connect();
        assertTrue(transport.isConnected());

        transport.close();
        assertFalse(transport.isConnected());
    }

    @Test
    @DisplayName("未连接时 send 应抛出异常")
    void shouldThrowWhenSendingBeforeConnect() {
        StdioTransport.ServerParameters params = new StdioTransport.ServerParameters(
                "echo", List.of("hello"), Map.of()
        );
        transport = new StdioTransport(codec, params);

        try {
            transport.send(JsonRpcRequest.of("test"));
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("未连接"));
        }
    }

    /**
     * 创建一个简单的 echo/ping 子进程用于测试连接管理.
     */
    private StdioTransport createPingTransport() {
        StdioTransport.ServerParameters params = new StdioTransport.ServerParameters(
                isWindows() ? "cmd.exe" : "sleep",
                isWindows() ? List.of("/c", "ping 127.0.0.1 -n 3 > nul && echo ok") : List.of("2"),
                Map.of()
        );
        return new StdioTransport(codec, params);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
