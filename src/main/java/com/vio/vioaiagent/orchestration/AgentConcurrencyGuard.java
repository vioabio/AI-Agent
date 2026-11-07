package com.vio.vioaiagent.orchestration;

import com.vio.vioaiagent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Agent 并发守卫 — 基于 Semaphore 限制同时运行的 Agent 数量.
 *
 * <p>防止无限创建 Agent 实例导致 OOM. 超限请求被快速拒绝,
 * 返回明确的并发限制提示而非无响应.
 *
 * <pre>{@code
 * AgentConcurrencyGuard guard = new AgentConcurrencyGuard(10);
 * guard.acquire();  // 获取许可, 超限抛异常
 * try {
 *     agent.run(message);
 * } finally {
 *     guard.release();  // 释放许可
 * }
 * }</pre>
 *
 * @author vio
 */
@Slf4j
public class AgentConcurrencyGuard {

    private final Semaphore semaphore;
    private final int maxConcurrency;

    /**
     * @param maxConcurrency 最大并发 Agent 数
     */
    public AgentConcurrencyGuard(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
        this.semaphore = new Semaphore(maxConcurrency);
        log.info("Agent 并发守卫已初始化: maxConcurrency={}", maxConcurrency);
    }

    /**
     * 尝试获取执行许可（阻塞最多 1 秒）.
     *
     * @throws BusinessException 超限时抛出
     */
    public void acquire() {
        try {
            if (!semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
                throw new BusinessException(
                        "当前并发 Agent 数量已达上限 (" + maxConcurrency + "), 请稍后重试");
            }
            log.debug("获得 Agent 执行许可 (可用: {})", semaphore.availablePermits());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("获取 Agent 执行许可被中断", e);
        }
    }

    /**
     * 释放执行许可.
     */
    public void release() {
        semaphore.release();
        log.debug("释放 Agent 执行许可 (可用: {})", semaphore.availablePermits());
    }

    /** 当前可用许可数 */
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    /** 当前活跃 Agent 数 */
    public int activeCount() {
        return maxConcurrency - semaphore.availablePermits();
    }
}
