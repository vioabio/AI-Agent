package com.vio.vioaiagent.orchestration;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/**
 * 工具重试策略 — 指数退避 + Jitter.
 *
 * <p>区分可重试错误（网络超时、连接失败）和不可重试错误（安全拦截、参数非法）.
 * 最多重试 3 次, 延迟公式: 2^attempt × 1000ms + random(0, 1000ms).
 *
 * @author vio
 */
@Slf4j
public class ToolRetryStrategy {

    public static final int MAX_RETRIES = 3;

    private static final Set<Class<? extends Throwable>> RETRYABLE = Set.of(
            TimeoutException.class,
            IOException.class,
            ConnectException.class
    );

    private static final Set<Class<? extends Throwable>> NON_RETRYABLE = Set.of(
            SecurityException.class,
            IllegalArgumentException.class
    );

    /**
     * 评估应重试还是直接失败.
     *
     * @param error        异常
     * @param attemptCount 已尝试次数（1, 2, 3...）
     */
    public RetryDecision evaluate(Throwable error, int attemptCount) {
        if (attemptCount >= MAX_RETRIES) {
            log.warn("已达最大重试次数 ({}), 不再重试", MAX_RETRIES);
            return RetryDecision.fail();
        }
        if (NON_RETRYABLE.stream().anyMatch(c -> c.isInstance(error))) {
            log.warn("不可重试的错误: {}, 直接失败", error.getClass().getSimpleName());
            return RetryDecision.fail();
        }
        if (RETRYABLE.stream().anyMatch(c -> c.isInstance(error))) {
            long delayMs = (long) Math.pow(2, attemptCount) * 1000
                    + ThreadLocalRandom.current().nextLong(0, 1000);
            log.info("可重试错误, 第 {} 次重试, 延迟 {}ms", attemptCount, delayMs);
            return RetryDecision.retry(delayMs);
        }
        // 未知异常也重试一次
        if (attemptCount < 1) {
            return RetryDecision.retry(1000);
        }
        return RetryDecision.fail();
    }
}
