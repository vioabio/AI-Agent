package com.vio.vioaiagent.orchestration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent 线程池隔离配置.
 *
 * <p>为不同工作负载提供独立的线程池, 实现资源隔离：
 * <ul>
 *   <li>{@code agentReasoningExecutor} — Agent 推理（CPU 密集, 4-8 线程）</li>
 *   <li>{@code toolExecutionExecutor} — 工具调用（IO 密集, 8-16 线程）</li>
 *   <li>{@code mcpTransportExecutor} — MCP 通信（IO 密集, 4-8 线程）</li>
 * </ul>
 *
 * <p>面试话术: "我们使用线程池隔离保证高并发下不同负载之间的资源不受影响 —
 * Agent 推理满载时不影响工具调用, 工具调用高峰期不影响 MCP 通信."
 *
 * @author vio
 */
@Slf4j
@Configuration
public class AgentThreadPoolConfig {

    /**
     * Agent 推理线程池 — CPU 密集.
     * <p>核心线程 4, 最大 8, 队列 100, 拒绝策略 CallerRunsPolicy（防止任务丢失）.
     */
    @Bean
    public ThreadPoolTaskExecutor agentReasoningExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-reasoning-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("Agent 推理线程池已初始化: core=4, max=8, queue=100");
        return executor;
    }

    /**
     * 工具执行线程池 — IO 密集.
     * <p>核心线程 8, 最大 16, 队列 200, 拒绝策略 AbortPolicy（IO 任务不可延迟, 直接拒绝）.
     */
    @Bean
    public ThreadPoolTaskExecutor toolExecutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("tool-exec-");
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("工具执行线程池已初始化: core=8, max=16, queue=200");
        return executor;
    }

    /**
     * MCP 通信线程池 — IO 密集.
     * <p>核心线程 4, 最大 8, 队列 50, 拒绝策略 DiscardOldestPolicy（丢弃最旧任务）.
     */
    @Bean
    public ThreadPoolTaskExecutor mcpTransportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("mcp-transport-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("MCP 传输线程池已初始化: core=4, max=8, queue=50");
        return executor;
    }
}
