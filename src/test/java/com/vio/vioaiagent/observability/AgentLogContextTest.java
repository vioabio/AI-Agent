package com.vio.vioaiagent.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("结构化日志上下文")
class AgentLogContextTest {

    @Test @DisplayName("应生成 traceId 和 spanId")
    void shouldGenerateIds() {
        var ctx = new AgentLogContext();
        assertNotNull(ctx.getTraceId());
        assertNotNull(ctx.getSpanId());
        assertEquals(8, ctx.getTraceId().length());
    }

    @Test @DisplayName("应接受外部 traceId")
    void shouldAcceptExternalTraceId() {
        var ctx = new AgentLogContext("ext-trace-001");
        assertEquals("ext-trace-001", ctx.getTraceId());
    }

    @Test @DisplayName("fluent setter 应支持链式调用")
    void shouldSupportFluentApi() {
        var ctx = new AgentLogContext()
                .sessionId("s1")
                .agentType("VioManus")
                .stepType("think")
                .stepIndex(3)
                .toolName("web_search")
                .outcome("success");
        assertEquals("s1", ctx.getSessionId());
        assertEquals("think", ctx.getStepType());
        assertEquals(3, ctx.getStepIndex());
    }

    @Test @DisplayName("toJson 应输出有效 JSON")
    void shouldOutputValidJson() {
        var ctx = new AgentLogContext().sessionId("s1").outcome("ok");
        String json = ctx.toJson();
        assertTrue(json.contains("traceId"));
        assertTrue(json.contains("ok"));
    }

    @Test @DisplayName("injectMdc 应设置 MDC")
    void shouldInjectMdc() {
        var ctx = new AgentLogContext().sessionId("s1");
        ctx.injectMdc();
        assertEquals(ctx.getTraceId(), org.slf4j.MDC.get("traceId"));
        assertEquals("s1", org.slf4j.MDC.get("sessionId"));
        AgentLogContext.clearMdc();
    }
}
