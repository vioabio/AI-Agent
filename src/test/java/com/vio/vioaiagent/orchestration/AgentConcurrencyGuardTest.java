package com.vio.vioaiagent.orchestration;

import com.vio.vioaiagent.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Agent 并发守卫")
class AgentConcurrencyGuardTest {

    @Test @DisplayName("acquire 在限制内应成功")
    void shouldAcquireWithinLimit() {
        AgentConcurrencyGuard guard = new AgentConcurrencyGuard(3);
        guard.acquire();
        assertEquals(1, guard.activeCount());
        guard.release();
        assertEquals(0, guard.activeCount());
    }

    @Test @DisplayName("超过限制应抛出异常")
    void shouldThrowWhenExceeded() {
        AgentConcurrencyGuard guard = new AgentConcurrencyGuard(1);
        guard.acquire();
        assertThrows(BusinessException.class, () -> guard.acquire());
        guard.release();
    }

    @Test @DisplayName("release 后应可重新 acquire")
    void shouldReacquireAfterRelease() {
        AgentConcurrencyGuard guard = new AgentConcurrencyGuard(1);
        guard.acquire();
        guard.release();
        assertDoesNotThrow(() -> guard.acquire());
        guard.release();
    }
}
