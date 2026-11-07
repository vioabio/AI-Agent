package com.vio.vioaiagent.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("统一错误码")
class AgentErrorCodeTest {

    @Test @DisplayName("所有错误码应可查找")
    void shouldFindAllCodes() {
        for (AgentErrorCode ec : AgentErrorCode.values()) {
            assertNotNull(AgentErrorCode.of(ec.getCode()));
            assertEquals(ec, AgentErrorCode.of(ec.getCode()));
        }
    }

    @Test @DisplayName("错误码总数应为 24 个 (5 域)")
    void shouldHaveCorrectCount() {
        assertEquals(24, AgentErrorCode.values().length,
                "AG:5 + TL:5 + MCP:5 + SEC:6 + SES:3 = 24");
    }

    @Test @DisplayName("5 个域各有独立段")
    void shouldHaveFiveDomains() {
        assertNotNull(AgentErrorCode.AGENT_NOT_IDLE.domain());
        assertEquals("AG", AgentErrorCode.AGENT_NOT_IDLE.domain());
        assertEquals("TL", AgentErrorCode.TOOL_NOT_FOUND.domain());
        assertEquals("MCP", AgentErrorCode.MCP_INITIALIZE_FAILED.domain());
        assertEquals("SEC", AgentErrorCode.SEC_AUTH_FAILED.domain());
        assertEquals("SES", AgentErrorCode.SES_NOT_FOUND.domain());
    }

    @Test @DisplayName("未知 code 应返回 null")
    void shouldReturnNullForUnknown() {
        assertNull(AgentErrorCode.of("XX-999"));
    }
}
