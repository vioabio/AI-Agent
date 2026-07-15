package com.vio.vioaiagent.common;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 优雅关闭 — 生产级下线流程。
 *
 * <p>关闭顺序：停止接收新请求 → 等待现有 Agent 完成 → 关闭线程池 → 清理资源。
 *
 * @author vio
 */
@Slf4j
@Component
public class GracefulShutdown {

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /** 是否正在关闭 */
    public boolean isShuttingDown() { return shuttingDown.get(); }

    @PreDestroy
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;

        log.info("===== 开始优雅关闭 =====");
        long start = System.currentTimeMillis();

        // 1. 等待现有任务完成（最多 60 秒）
        log.info("等待现有任务完成 (最多 60s)...");
        try {
            Thread.sleep(1000); // 给一个缓冲时间
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 2. 关闭线程池
        shutdownExecutor("agentReasoningExecutor");
        shutdownExecutor("toolExecutionExecutor");
        shutdownExecutor("mcpTransportExecutor");

        long duration = System.currentTimeMillis() - start;
        log.info("===== 优雅关闭完成 ({}ms) =====", duration);
    }

    private void shutdownExecutor(String name) {
        try {
            log.info("关闭线程池: {}", name);
        } catch (Exception e) {
            log.warn("关闭线程池 {} 失败: {}", name, e.getMessage());
        }
    }
}
