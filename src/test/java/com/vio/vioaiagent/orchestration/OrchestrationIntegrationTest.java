package com.vio.vioaiagent.orchestration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("编排集成测试")
class OrchestrationIntegrationTest {

    private SessionStateMachine stateMachine;
    private AgentConcurrencyGuard concurrencyGuard;
    private ThreadPoolTaskExecutor agentExecutor;

    @BeforeEach
    void setUp() {
        stateMachine = new SessionStateMachine();
        concurrencyGuard = new AgentConcurrencyGuard(3);
        agentExecutor = new ThreadPoolTaskExecutor();
        agentExecutor.setCorePoolSize(2);
        agentExecutor.setMaxPoolSize(4);
        agentExecutor.setThreadNamePrefix("test-agent-");
        agentExecutor.initialize();
    }

    @Test @DisplayName("全链路: 状态机 + 并发控制 + 线程池")
    void shouldCompleteFullOrchestrationFlow() throws Exception {
        // 1. 创建会话
        String sessionId = "test-session-1";
        stateMachine.create(sessionId);
        assertEquals(SessionState.CREATED, stateMachine.getState(sessionId));

        // 2. 状态机正常流转
        stateMachine.transition(sessionId, SessionState.INITIALIZING);
        stateMachine.transition(sessionId, SessionState.READY);
        stateMachine.transition(sessionId, SessionState.RUNNING);

        // 3. 并发控制
        concurrencyGuard.acquire();
        assertEquals(1, concurrencyGuard.activeCount());
        concurrencyGuard.release();

        // 4. 线程池可用
        CountDownLatch latch = new CountDownLatch(1);
        agentExecutor.execute(latch::countDown);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test @DisplayName("并发限制 + 状态机联合验证")
    void shouldEnforceConcurrencyAndState() {
        String sid = "test-session-2";
        stateMachine.create(sid);
        stateMachine.transition(sid, SessionState.INITIALIZING);
        stateMachine.transition(sid, SessionState.READY);

        // 状态和并发应独立运作
        assertEquals(SessionState.READY, stateMachine.getState(sid));
        concurrencyGuard.acquire();
        concurrencyGuard.release();
        assertEquals(0, concurrencyGuard.activeCount());
    }

    @Test @DisplayName("非法状态转换 + 超限应同时被拦截")
    void shouldCatchBothIllegalTransitionAndOverLimit() {
        stateMachine.create("test-session-3");
        assertThrows(IllegalStateTransitionException.class,
                () -> stateMachine.transition("test-session-3", SessionState.RUNNING));

        AgentConcurrencyGuard tiny = new AgentConcurrencyGuard(1);
        tiny.acquire();
        assertThrows(com.vio.vioaiagent.exception.BusinessException.class,
                tiny::acquire);
        tiny.release();
    }
}
