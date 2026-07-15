package com.vio.vioaiagent.mcp.bridge;

import com.vio.vioaiagent.mcp.protocol.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SpringAiMpcBridge 测试.
 *
 * @author vio
 */
@DisplayName("Spring AI MCP 桥接器")
class SpringAiMpcBridgeTest {

    private SpringAiMpcBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new SpringAiMpcBridge();
    }

    @Test
    @DisplayName("null provider 应返回空列表")
    void shouldReturnEmptyListForNullProvider() {
        List<ToolDefinition> tools = bridge.toToolDefinitions(null);
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    @DisplayName("executeViaSpringAi 异常应返回错误结果")
    void shouldCatchExecutionErrors() {
        // 使用真实回调测试错误处理 — 传入无效参数
        var result = bridge.executeViaSpringAi(null, java.util.Map.of("key", "value"));
        assertNotNull(result);
        assertTrue(result.isError());
    }
}
