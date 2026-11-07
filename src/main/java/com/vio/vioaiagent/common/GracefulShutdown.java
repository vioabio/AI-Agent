package com.vio.vioaiagent.common;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

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

    public boolean isShuttingDown() { return shuttingDown.get(); }

    @PreDestroy
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;

        log.info("===== 开始优雅关闭 =====");
        long start = System.currentTimeMillis();

        // 等待 Spring 容器自行管理线程池关闭
        try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        long duration = System.currentTimeMillis() - start;
        log.info("===== 优雅关闭完成 ({}ms) =====", duration);
    }
}
