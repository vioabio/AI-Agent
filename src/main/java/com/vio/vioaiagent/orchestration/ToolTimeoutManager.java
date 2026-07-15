package com.vio.vioaiagent.orchestration;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 工具分级超时管理器.
 *
 * <p>不同类别的工具有不同的超时限制, 避免单个慢工具阻塞整个 Agent Loop.
 *
 * <pre>{@code
 * ToolTimeoutManager mgr = new ToolTimeoutManager(executor);
 * String result = mgr.executeWithTimeout(
 *     () -> tool.call("hello"), ToolCategory.WEB_SEARCH
 * );
 * }</pre>
 *
 * @author vio
 */
@Slf4j
public class ToolTimeoutManager {

    private static final Map<ToolCategory, Duration> TIMEOUTS = Map.ofEntries(
            Map.entry(ToolCategory.WEB_SEARCH, Duration.ofSeconds(15)),
            Map.entry(ToolCategory.WEB_SCRAPING, Duration.ofSeconds(20)),
            Map.entry(ToolCategory.FILE_OPERATION, Duration.ofSeconds(10)),
            Map.entry(ToolCategory.PDF_GENERATION, Duration.ofSeconds(30)),
            Map.entry(ToolCategory.TERMINAL_COMMAND, Duration.ofSeconds(60)),
            Map.entry(ToolCategory.RESOURCE_DOWNLOAD, Duration.ofSeconds(30)),
            Map.entry(ToolCategory.MCP_REMOTE, Duration.ofSeconds(30)),
            Map.entry(ToolCategory.OTHER, Duration.ofSeconds(30))
    );

    /** Agent 全局超时 */
    private static final Duration GLOBAL_AGENT_TIMEOUT = Duration.ofMinutes(10);

    private final ExecutorService executor;

    public ToolTimeoutManager(ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * 在超时限制内执行任务.
     *
     * @param task     可调用的任务
     * @param category 工具类别（决定超时长度）
     * @return 任务执行结果
     * @throws ToolTimeoutException 超时时抛出
     */
    public <T> T executeWithTimeout(Callable<T> task, ToolCategory category) throws Exception {
        Duration timeout = TIMEOUTS.getOrDefault(category, Duration.ofSeconds(30));
        Future<T> future = executor.submit(task);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("工具执行超时: {} ({}s)", category, timeout.toSeconds());
            throw new ToolTimeoutException("工具 " + category + " 执行超时 (" + timeout.toSeconds() + "s)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
    }

    /** 获取指定类别的超时值 */
    public static Duration getTimeout(ToolCategory category) {
        return TIMEOUTS.getOrDefault(category, Duration.ofSeconds(30));
    }

    /** 获取 Agent 全局超时 */
    public static Duration getGlobalAgentTimeout() {
        return GLOBAL_AGENT_TIMEOUT;
    }
}
