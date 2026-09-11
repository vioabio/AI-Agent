package com.vio.vioaiagent.observability;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 指标收集器 — 基于 Atomic 计数器的轻量指标。
 *
 * <p>保留轻量快照接口供诊断端点使用，同时把同一组事件绑定到
 * Micrometer。Prometheus 采集必须使用 Actuator 的
 * {@code /actuator/prometheus}，{@code /metrics} 仅用于人工诊断。
 *
 * @author vio
 */
@Slf4j
@Component
public class AgentMetrics {

    /** Agent 执行总次数 */
    private final AtomicLong agentExecutionCount = new AtomicLong(0);
    /** 工具调用总次数 */
    private final AtomicLong toolCallCount = new AtomicLong(0);
    /** 工具调用成功次数 */
    private final AtomicLong toolCallSuccess = new AtomicLong(0);
    /** 工具调用失败次数 */
    private final AtomicLong toolCallFailure = new AtomicLong(0);
    /** 错误总次数 */
    private final AtomicLong errorCount = new AtomicLong(0);
    /** 当前活跃 Agent 数 */
    @Getter private final AtomicInteger activeAgentCount = new AtomicInteger(0);
    /** 估算总 Token 消耗 */
    private final AtomicLong totalTokensConsumed = new AtomicLong(0);
    /** SSE 连接总数 */
    private final AtomicLong sseConnectionCount = new AtomicLong(0);

    /** 可选的标准 Micrometer 注册表（单元测试可不提供）。 */
    private final MeterRegistry meterRegistry;
    private Counter agentExecutionMeter;
    private Counter toolCallMeter;
    private Counter toolCallSuccessMeter;
    private Counter toolCallFailureMeter;
    private Counter errorMeter;
    private Counter tokenMeter;
    private Counter sseConnectionMeter;
    private Counter runMeter;
    private Counter retrievalMeter;
    private Counter feedbackMeter;
    private Counter approvalMeter;
    private Timer runDurationMeter;
    private Timer ttftMeter;

    /** 保持旧测试和轻量调用方的无参构造契约。 */
    public AgentMetrics() {
        this(null);
    }

    /**
     * 生产环境由 Spring 注入注册表；没有注册表时仍可使用诊断快照。
     */
    @Autowired
    public AgentMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider == null
                ? null : meterRegistryProvider.getIfAvailable();
        registerMeters();
    }

    private void registerMeters() {
        if (meterRegistry == null) {
            return;
        }
        agentExecutionMeter = Counter.builder("vio_agent_execution")
                .description("Agent execution count").register(meterRegistry);
        toolCallMeter = Counter.builder("vio_tool_call")
                .description("Tool call count").register(meterRegistry);
        toolCallSuccessMeter = Counter.builder("vio_tool_call_success")
                .description("Successful tool calls").register(meterRegistry);
        toolCallFailureMeter = Counter.builder("vio_tool_call_failure")
                .description("Failed tool calls").register(meterRegistry);
        errorMeter = Counter.builder("vio_error")
                .description("Agent error count").register(meterRegistry);
        tokenMeter = Counter.builder("vio_tokens_consumed")
                .description("Estimated consumed tokens").register(meterRegistry);
        sseConnectionMeter = Counter.builder("vio_sse_connection")
                .description("SSE connection count").register(meterRegistry);
        runMeter = Counter.builder("vio_agent_run")
                .description("Agent run terminal events")
                .tag("status", "unknown").register(meterRegistry);
        retrievalMeter = Counter.builder("vio_retrieval")
                .description("Retrieval requests")
                .tag("outcome", "unknown").register(meterRegistry);
        feedbackMeter = Counter.builder("vio_feedback")
                .description("Feedback submissions")
                .tag("type", "unknown").register(meterRegistry);
        approvalMeter = Counter.builder("vio_approval")
                .description("HITL approval decisions")
                .tag("outcome", "unknown").register(meterRegistry);
        runDurationMeter = Timer.builder("vio_agent_run_duration")
                .description("Agent run duration").register(meterRegistry);
        ttftMeter = Timer.builder("vio_agent_ttft")
                .description("Time to first token").register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("vio_agent_active", activeAgentCount,
                        AtomicInteger::get)
                .description("Active Agent count").register(meterRegistry);
    }

    // --- 递增方法 ---
    public void recordAgentExecution() {
        agentExecutionCount.incrementAndGet();
        if (agentExecutionMeter != null) agentExecutionMeter.increment();
    }
    public void recordToolCall(boolean success) {
        toolCallCount.incrementAndGet();
        if (toolCallMeter != null) toolCallMeter.increment();
        if (success) {
            toolCallSuccess.incrementAndGet();
            if (toolCallSuccessMeter != null) toolCallSuccessMeter.increment();
        } else {
            toolCallFailure.incrementAndGet();
            if (toolCallFailureMeter != null) toolCallFailureMeter.increment();
        }
    }
    public void recordError() {
        errorCount.incrementAndGet();
        if (errorMeter != null) errorMeter.increment();
    }
    public void recordTokens(long tokens) {
        if (tokens <= 0) return;
        totalTokensConsumed.addAndGet(tokens);
        if (tokenMeter != null) tokenMeter.increment(tokens);
    }
    public void incrementActiveAgents() { activeAgentCount.incrementAndGet(); }
    public void decrementActiveAgents() {
        activeAgentCount.updateAndGet(value -> Math.max(0, value - 1));
    }
    public void recordSseConnection() {
        sseConnectionCount.incrementAndGet();
        if (sseConnectionMeter != null) sseConnectionMeter.increment();
    }

    /** 记录 Run 终态及完整耗时，status 使用固定低基数枚举。 */
    public void recordRun(String status, long durationMs) {
        if (runMeter != null) {
            incrementTaggedCounter("vio_agent_run", "status", normalize(status));
        }
        if (runDurationMeter != null && durationMs >= 0) {
            runDurationMeter.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** 记录首 Token 延迟。 */
    public void recordTimeToFirstToken(long durationMs) {
        if (ttftMeter != null && durationMs >= 0) {
            ttftMeter.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** 记录检索是否返回证据。 */
    public void recordRetrieval(boolean evidenceFound) {
        if (retrievalMeter != null) {
            incrementTaggedCounter("vio_retrieval", "outcome",
                    evidenceFound ? "evidence" : "empty");
        }
    }

    /** 记录用户反馈类型（只允许有限枚举，避免高基数标签）。 */
    public void recordFeedback(String type) {
        if (feedbackMeter != null) {
            incrementTaggedCounter("vio_feedback", "type", normalize(type));
        }
    }

    /** 记录 HITL 决策。 */
    public void recordApproval(String outcome) {
        if (approvalMeter != null) {
            incrementTaggedCounter("vio_approval", "outcome", normalize(outcome));
        }
    }

    private void incrementTaggedCounter(String name, String tag, String value) {
        if (meterRegistry == null) return;
        Counter.builder(name).tag(tag, value).register(meterRegistry).increment();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_");
        return normalized.length() > 32 ? normalized.substring(0, 32) : normalized;
    }

    /** 生成指标快照 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent.execution.count", agentExecutionCount.get());
        m.put("tool.call.count", toolCallCount.get());
        m.put("tool.call.success", toolCallSuccess.get());
        m.put("tool.call.failure", toolCallFailure.get());
        m.put("tool.call.successRate", toolCallCount.get() > 0
                ? String.format("%.1f%%", 100.0 * toolCallSuccess.get() / toolCallCount.get()) : "N/A");
        m.put("error.count", errorCount.get());
        m.put("agent.active.count", activeAgentCount.get());
        m.put("tokens.consumed", totalTokensConsumed.get());
        m.put("sse.connections", sseConnectionCount.get());
        return m;
    }

    /** 重置所有计数器 */
    public void reset() {
        agentExecutionCount.set(0);
        toolCallCount.set(0);
        toolCallSuccess.set(0);
        toolCallFailure.set(0);
        errorCount.set(0);
        totalTokensConsumed.set(0);
        sseConnectionCount.set(0);
    }
}
