package com.vio.vioaiagent.orchestration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("工具超时管理器")
class ToolTimeoutManagerTest {
    private ToolTimeoutManager manager;

    @BeforeEach
    void setUp() { manager = new ToolTimeoutManager(Executors.newSingleThreadExecutor()); }

    @Test @DisplayName("各类别超时值应正确")
    void shouldHaveCorrectTimeouts() {
        assertEquals(15, ToolTimeoutManager.getTimeout(ToolCategory.WEB_SEARCH).toSeconds());
        assertEquals(60, ToolTimeoutManager.getTimeout(ToolCategory.TERMINAL_COMMAND).toSeconds());
        assertEquals(10, ToolTimeoutManager.getTimeout(ToolCategory.FILE_OPERATION).toSeconds());
        assertEquals(30, ToolTimeoutManager.getTimeout(ToolCategory.PDF_GENERATION).toSeconds());
    }

    @Test @DisplayName("正常执行应在超时内返回")
    void shouldReturnWithinTimeout() throws Exception {
        String result = manager.executeWithTimeout(
                () -> "ok", ToolCategory.FILE_OPERATION);
        assertEquals("ok", result);
    }

    @Test @DisplayName("超时值配置验证：FILE_OPERATION=10s, TERMINAL=60s")
    void shouldHaveCorrectTimeoutValues() {
        assertEquals(10, ToolTimeoutManager.getTimeout(ToolCategory.FILE_OPERATION).toSeconds());
        assertEquals(60, ToolTimeoutManager.getTimeout(ToolCategory.TERMINAL_COMMAND).toSeconds());
        assertEquals(15, ToolTimeoutManager.getTimeout(ToolCategory.WEB_SEARCH).toSeconds());
        assertEquals(20, ToolTimeoutManager.getTimeout(ToolCategory.WEB_SCRAPING).toSeconds());
        assertEquals(30, ToolTimeoutManager.getTimeout(ToolCategory.MCP_REMOTE).toSeconds());
    }

    @Test @DisplayName("全局 Agent 超时应为 10 分钟")
    void shouldHaveGlobalAgentTimeout() {
        assertEquals(10, ToolTimeoutManager.getGlobalAgentTimeout().toMinutes());
    }
}
