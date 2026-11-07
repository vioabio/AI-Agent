package com.vio.vioaiagent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 结构化日志上下文 — Agent 执行链路的核心载体。
 *
 * <p>每次 Agent 执行创建一个 AgentLogContext，通过 MDC 注入到所有日志中。
 * traceId 贯穿请求入口 → Agent 推理 → 工具调用全链路。
 *
 * @author vio
 */
public class AgentLogContext {

    private final String traceId;
    private final String spanId;
    private String sessionId;
    private String agentType = "VioManus";
    private String stepType;
    private int stepIndex;
    private String toolName;
    private long durationMs;
    private String outcome = "running";
    private final Instant startTime;
    private static final ObjectMapper mapper = new ObjectMapper();

    public AgentLogContext() {
        this.traceId = UUID.randomUUID().toString().substring(0, 8);
        this.spanId = UUID.randomUUID().toString().substring(0, 8);
        this.startTime = Instant.now();
    }

    public AgentLogContext(String traceId) {
        this.traceId = traceId;
        this.spanId = UUID.randomUUID().toString().substring(0, 8);
        this.startTime = Instant.now();
    }

    // --- fluent setters ---
    public AgentLogContext sessionId(String v) { this.sessionId = v; return this; }
    public AgentLogContext agentType(String v) { this.agentType = v; return this; }
    public AgentLogContext stepType(String v) { this.stepType = v; return this; }
    public AgentLogContext stepIndex(int v) { this.stepIndex = v; return this; }
    public AgentLogContext toolName(String v) { this.toolName = v; return this; }
    public AgentLogContext durationMs(long v) { this.durationMs = v; return this; }
    public AgentLogContext outcome(String v) { this.outcome = v; return this; }

    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getSessionId() { return sessionId; }
    public String getStepType() { return stepType; }
    public int getStepIndex() { return stepIndex; }
    public String getToolName() { return toolName; }

    /** 输出为 JSON 格式的结构化日志行 */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", traceId);
        m.put("spanId", spanId);
        if (sessionId != null) m.put("sessionId", sessionId);
        m.put("agentType", agentType);
        if (stepType != null) m.put("stepType", stepType);
        if (stepIndex > 0) m.put("stepIndex", stepIndex);
        if (toolName != null) m.put("toolName", toolName);
        m.put("durationMs", durationMs);
        m.put("outcome", outcome);
        m.put("timestamp", startTime.toString());
        try { return mapper.writeValueAsString(m); } catch (Exception e) { return m.toString(); }
    }

    /** 注入 SLF4J MDC */
    public void injectMdc() {
        org.slf4j.MDC.put("traceId", traceId);
        org.slf4j.MDC.put("spanId", spanId);
        if (sessionId != null) org.slf4j.MDC.put("sessionId", sessionId);
        if (agentType != null) org.slf4j.MDC.put("agentType", agentType);
    }

    /** 清除 MDC */
    public static void clearMdc() {
        org.slf4j.MDC.clear();
    }
}
