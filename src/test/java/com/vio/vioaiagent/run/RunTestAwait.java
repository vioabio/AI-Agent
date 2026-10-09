package com.vio.vioaiagent.run;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Run 异步测试的等待工具。
 *
 * <p>存在的理由（本轮发现的测试竞态）：服务端的写入顺序是
 * <em>先推进 Run 状态并落库，再追加终态事件</em>
 * （见 {@code PokemonAgentRunService.failIfActive}：{@code run.fail()} → {@code save()} → {@code emitLocked()}）。
 * 因此“状态已终态”并不意味着“终态事件已写入”。
 *
 * <p>若测试先轮询状态、再断言事件，就会在一个很窄的窗口内偶发失败——
 * 表现为单独运行必过、整包运行偶发（机器越忙越容易命中）。
 * 本工具改为直接轮询**测试真正关心的那个条件**，消除该窗口。
 *
 * <p>刻意不做“睡固定时间”的兜底：固定 sleep 只是把窗口缩小而不是消除，
 * 同时会让快路径变慢。这里用条件轮询 + 明确失败信息。
 */
final class RunTestAwait {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final long POLL_MILLIS = 10;

    private RunTestAwait() {
    }

    /**
     * 等待终态事件出现。
     *
     * @return 匹配到的终态事件
     */
    static AgentEvent awaitTerminalEvent(EventRepository events, String runId) {
        return awaitTerminalEvent(events, runId, DEFAULT_TIMEOUT);
    }

    static AgentEvent awaitTerminalEvent(EventRepository events, String runId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            AgentEvent terminal = events.findAfter(runId, 0).stream()
                    .filter(event -> event.type().isTerminal())
                    .findFirst()
                    .orElse(null);
            if (terminal != null) return terminal;
            sleep();
        }
        fail("等待终态事件超时(" + timeout.toMillis() + "ms)，runId=" + runId
                + "；当前事件: " + events.findAfter(runId, 0).stream()
                .map(event -> event.type().wireName()).toList());
        return null; // 不可达
    }

    /**
     * 等待指定类型的事件出现。
     */
    static AgentEvent awaitEvent(EventRepository events, String runId, AgentEventType type) {
        Instant deadline = Instant.now().plus(DEFAULT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            AgentEvent matched = events.findAfter(runId, 0).stream()
                    .filter(event -> event.type() == type)
                    .findFirst()
                    .orElse(null);
            if (matched != null) return matched;
            sleep();
        }
        fail("等待事件 " + type.wireName() + " 超时，runId=" + runId
                + "；当前事件: " + events.findAfter(runId, 0).stream()
                .map(event -> event.type().wireName()).toList());
        return null; // 不可达
    }

    /**
     * 等待 Run 进入终态（按 runId 查找）。
     *
     * <p>注意：需要同时断言终态事件时请用 {@link #awaitTerminalEvent}，
     * 否则会命中“状态已终态但事件未写入”的窗口。
     */
    static AgentRun awaitTerminalStatus(AgentRunRepository runs, String runId) {
        return awaitStatus(runId, "Run " + runId,
                key -> runs.findById(key).orElse(null), RunTestAwait::isTerminal);
    }

    /**
     * 等待按幂等键定位的 Run 进入终态。
     *
     * <p>覆盖 `createRun` 在幂等 `complete()` 抛错但仍已提交 worker 的场景：
     * 此时调用方手里没有 runId，只能按用户 + 幂等键回查。
     */
    static AgentRun awaitTerminalStatusByIdempotencyKey(AgentRunRepository runs, String userId,
                                                        String idempotencyKey) {
        return awaitStatus(idempotencyKey,
                "idempotencyKey=" + idempotencyKey + ", userId=" + userId,
                key -> runs.findByIdempotencyKey(userId, key).orElse(null),
                RunTestAwait::isTerminal);
    }

    private static boolean isTerminal(AgentRun run) {
        return run != null && run.status().isTerminal();
    }

    /**
     * 通用条件轮询。
     *
     * @param key          传给 {@code loader} 的查找键
     * @param label        失败信息中展示的目标描述
     * @param loader       每次迭代重新读取当前值（必须重新读取，不能只读一次）
     * @param isSatisfied  判定条件
     */
    private static <T> T awaitStatus(String key, String label, Function<String, T> loader,
                                     Predicate<T> isSatisfied) {
        Instant deadline = Instant.now().plus(DEFAULT_TIMEOUT);
        T last = null;
        while (Instant.now().isBefore(deadline)) {
            last = loader.apply(key);
            if (last != null && isSatisfied.test(last)) return last;
            sleep();
        }
        fail("等待 " + label + " 进入终态超时(" + DEFAULT_TIMEOUT.toMillis() + "ms)"
                + "；当前状态: " + describe(last));
        return null; // 不可达
    }

    private static String describe(Object value) {
        if (value == null) return "<不存在>";
        if (value instanceof AgentRun run) return run.status().name();
        return String.valueOf(value);
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待终态事件时被中断", ex);
        }
    }
}
