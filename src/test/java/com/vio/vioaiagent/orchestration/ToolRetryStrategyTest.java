package com.vio.vioaiagent.orchestration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("工具重试策略")
class ToolRetryStrategyTest {
    private ToolRetryStrategy strategy;

    @BeforeEach
    void setUp() { strategy = new ToolRetryStrategy(); }

    @Test @DisplayName("TimeoutException 应可重试")
    void shouldRetryTimeoutException() {
        RetryDecision d = strategy.evaluate(new TimeoutException("timeout"), 0);
        assertEquals(RetryDecision.Action.RETRY, d.action());
        assertTrue(d.delayMs() > 0);
    }

    @Test @DisplayName("IOException 应可重试")
    void shouldRetryIOException() {
        RetryDecision d = strategy.evaluate(new IOException("network error"), 0);
        assertEquals(RetryDecision.Action.RETRY, d.action());
    }

    @Test @DisplayName("SecurityException 不应重试")
    void shouldNotRetrySecurityException() {
        RetryDecision d = strategy.evaluate(new SecurityException("blocked"), 0);
        assertEquals(RetryDecision.Action.FAIL, d.action());
    }

    @Test @DisplayName("超过 MAX_RETRIES=3 不应再重试")
    void shouldStopAfterMaxRetries() {
        RetryDecision d = strategy.evaluate(new TimeoutException(), 3);
        assertEquals(RetryDecision.Action.FAIL, d.action());
    }

    @Test @DisplayName("退避延迟应在合理范围")
    void shouldHaveReasonableBackoff() {
        RetryDecision d1 = strategy.evaluate(new TimeoutException(), 0);
        RetryDecision d2 = strategy.evaluate(new TimeoutException(), 1);
        assertTrue(d2.delayMs() > d1.delayMs(), "第 2 次重试延迟应大于第 1 次");
        assertTrue(d1.delayMs() < 3000, "延迟应 < 3000ms");
    }
}
