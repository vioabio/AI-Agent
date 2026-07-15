package com.vio.vioaiagent.observability;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 指标收集器 — 基于 Atomic 计数器的轻量指标。
 *
 * <p>零外部依赖，纯 JDK Atomic + Spring Bean。
 * 后续可零修改迁移到 Micrometer/Prometheus。
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

    // --- 递增方法 ---
    public void recordAgentExecution() { agentExecutionCount.incrementAndGet(); }
    public void recordToolCall(boolean success) {
        toolCallCount.incrementAndGet();
        if (success) toolCallSuccess.incrementAndGet();
        else toolCallFailure.incrementAndGet();
    }
    public void recordError() { errorCount.incrementAndGet(); }
    public void recordTokens(long tokens) { totalTokensConsumed.addAndGet(tokens); }
    public void incrementActiveAgents() { activeAgentCount.incrementAndGet(); }
    public void decrementActiveAgents() { activeAgentCount.decrementAndGet(); }
    public void recordSseConnection() { sseConnectionCount.incrementAndGet(); }

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
