package com.vio.vioaiagent.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("线程池隔离")
class ThreadPoolIsolationTest {

    @Test @DisplayName("Agent 线程池满载不影响工具线程池")
    void shouldIsolateAgentPoolFromToolPool() throws Exception {
        ThreadPoolTaskExecutor agentPool = createPool("agent", 1, 1, 1);
        ThreadPoolTaskExecutor toolPool = createPool("tool", 2, 2, 10);

        // 占满 agent 池
        CountDownLatch blocker = new CountDownLatch(1);
        agentPool.execute(() -> { try { blocker.await(); } catch (InterruptedException ignored) {} });

        // tool 池仍然可用
        CountDownLatch toolDone = new CountDownLatch(1);
        toolPool.execute(toolDone::countDown);
        assertTrue(toolDone.await(2, TimeUnit.SECONDS));

        blocker.countDown();
        shutdown(agentPool);
        shutdown(toolPool);
    }

    @Test @DisplayName("MCP 通信池独立于推理池")
    void shouldIsolateMcpPoolFromReasoningPool() throws Exception {
        ThreadPoolTaskExecutor reasoningPool = createPool("reasoning", 1, 1, 1);
        ThreadPoolTaskExecutor mcpPool = createPool("mcp", 2, 2, 10);

        CountDownLatch blocker = new CountDownLatch(1);
        reasoningPool.execute(() -> { try { blocker.await(); } catch (InterruptedException ignored) {} });

        CountDownLatch mcpDone = new CountDownLatch(1);
        mcpPool.execute(mcpDone::countDown);
        assertTrue(mcpDone.await(2, TimeUnit.SECONDS));

        blocker.countDown();
        shutdown(reasoningPool);
        shutdown(mcpPool);
    }

    private ThreadPoolTaskExecutor createPool(String name, int core, int max, int queue) {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setThreadNamePrefix(name + "-");
        pool.setCorePoolSize(core);
        pool.setMaxPoolSize(max);
        pool.setQueueCapacity(queue);
        pool.initialize();
        return pool;
    }

    private void shutdown(ThreadPoolTaskExecutor pool) {
        pool.shutdown();
    }
}
