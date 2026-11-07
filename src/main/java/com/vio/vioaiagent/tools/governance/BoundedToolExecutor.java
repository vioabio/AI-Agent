package com.vio.vioaiagent.tools.governance;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Fixed workers, bounded pending callbacks, and cancellation that releases queue capacity. */
public final class BoundedToolExecutor implements AutoCloseable {
    public static final String OVERLOADED = "TOOL_EXECUTION_OVERLOADED";
    public static final String SHUTDOWN = "TOOL_EXECUTION_SHUTDOWN";
    public static final String TIMEOUT = "TOOL_EXECUTION_TIMEOUT";
    public static final String CANCELLED = "TOOL_EXECUTION_CANCELLED";
    private final ToolExecutionProperties properties;
    private final ThreadPoolExecutor executor;
    private final Set<ToolTask> tasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong overloads = new AtomicLong();

    public BoundedToolExecutor(ToolExecutionProperties properties) {
        this(properties, newExecutor(properties));
    }

    /** Package-level injection allows deterministic scheduling tests with actual worker threads. */
    BoundedToolExecutor(ToolExecutionProperties properties, ThreadPoolExecutor executor) {
        this.properties = properties;
        this.executor = executor;
    }

    private static ThreadPoolExecutor newExecutor(ToolExecutionProperties properties) {
        BlockingQueue<Runnable> queue = properties.queueCapacity() == 0
                ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(properties.queueCapacity());
        AtomicInteger names = new AtomicInteger();
        return new ThreadPoolExecutor(properties.threadCount(), properties.threadCount(),
                0, TimeUnit.MILLISECONDS, queue, runnable -> {
                    Thread thread = new Thread(runnable, "tool-governance-" + names.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** The timeout covers pending time and execution; beforeStart runs in the worker. */
    public String call(Runnable beforeStart, Callable<String> callback, long timeoutMs) {
        return call(beforeStart,callback,timeoutMs,new ToolAttemptTiming());
    }
    public String call(Runnable beforeStart,Callable<String> callback,long timeoutMs,ToolAttemptTiming timing) {
        try { return measuredCall(beforeStart,callback,timeoutMs,timing); }
        finally { timing.freeze(); }
    }
    private String measuredCall(Runnable beforeStart, Callable<String> callback, long timeoutMs,ToolAttemptTiming timing) {
        long admittedAt = System.nanoTime();
        if (timeoutMs < 1) throw new IllegalArgumentException("Tool timeout must be positive");
        if (Thread.currentThread().isInterrupted()) {
            throw new Stopped(CANCELLED, "工具执行被中断", false, null);
        }
        if (closing.get()) throw new Unavailable(SHUTDOWN, "工具执行器已关闭");
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        AtomicInteger state = new AtomicInteger(); // 0=pending, 1=callback started, 2=cancelled before start
        AtomicLong callbackFinishedAt = new AtomicLong();
        ToolTask task = new ToolTask(() -> {
            timing.worker();
            verifyDeadline(admittedAt, budgetNanos, timeoutMs, false);
            beforeStart.run();
            // Authorization and caller scheduling can consume the remaining budget.
            // A delayed caller must not be the only thread enforcing this deadline.
            verifyDeadline(admittedAt, budgetNanos, timeoutMs, false);
            if (closing.get() || !state.compareAndSet(0, 1)) {
                throw new CancellationException("Cancelled before callback");
            }
            timing.callback();
            try {
                return callback.call();
            } finally {
                callbackFinishedAt.set(System.nanoTime());
                timing.finished(callbackFinishedAt.get());
            }
        }, state);
        tasks.add(task);
        try {
            timing.submitted();
            executor.execute(task);
            timing.accepted();
        } catch (RejectedExecutionException ex) {
            task.cancel(false);
            if (closing.get() || executor.isShutdown()) throw new Unavailable(SHUTDOWN, "工具执行器已关闭");
            overloads.incrementAndGet();
            throw new Unavailable(OVERLOADED, "工具执行资源已满，请稍后重试");
        }
        try {
            long remaining = Math.max(0, budgetNanos - (System.nanoTime() - admittedAt));
            String result = task.get(remaining, TimeUnit.NANOSECONDS);
            // Future.get(0) can return an already completed result even after its deadline.
            // Judge the callback's actual completion, so a late caller still accepts timely work.
            if (callbackFinishedAt.get() - admittedAt >= budgetNanos) {
                throw timeout(timeoutMs, true, null);
            }
            return result;
        } catch (TimeoutException ex) {
            task.cancel(true);
            throw timeout(timeoutMs, task.callbackStarted(), ex);
        } catch (InterruptedException ex) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new Stopped(CANCELLED, "工具执行被中断", task.callbackStarted(), ex);
        } catch (CancellationException ex) {
            if (!task.callbackStarted() && closing.get()) throw new Unavailable(SHUTDOWN, "工具执行器已关闭");
            throw new Stopped(closing.get() ? SHUTDOWN : CANCELLED, "工具执行已取消", task.callbackStarted(), ex);
        } catch (ExecutionException ex) {
            if (task.callbackStarted() && callbackFinishedAt.get() - admittedAt >= budgetNanos) {
                throw timeout(timeoutMs, true, ex.getCause());
            }
            Throwable cause = ex.getCause();
            if (cause instanceof CancellationException) {
                if (!task.callbackStarted() && closing.get()) throw new Unavailable(SHUTDOWN, "工具执行器已关闭");
                throw new Stopped(closing.get() ? SHUTDOWN : CANCELLED, "工具执行已取消", task.callbackStarted(), cause);
            }
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new RuntimeException(cause == null ? "工具执行失败" : cause.getMessage(), cause);
        }
    }

    private static void verifyDeadline(long admittedAt, long budgetNanos, long timeoutMs, boolean started) {
        if (System.nanoTime() - admittedAt >= budgetNanos) throw timeout(timeoutMs, started, null);
    }

    private static Stopped timeout(long timeoutMs, boolean callbackStarted, Throwable cause) {
        return new Stopped(TIMEOUT, "工具执行超时（" + timeoutMs + "ms，含排队等待）", callbackStarted, cause);
    }

    public Snapshot snapshot() {
        return new Snapshot(executor.getActiveCount(), executor.getQueue().size(), executor.getPoolSize(),
                executor.getLargestPoolSize(), overloads.get(), !closing.get(), executor.isTerminated());
    }

    /** Stop admission and discard pending callbacks; allow running work a bounded grace period. */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        executor.shutdown();
        for (ToolTask task : tasks) if (!task.callbackStarted()) task.cancel(false);
        boolean terminated = false;
        try {
            terminated = executor.awaitTermination(properties.shutdownGraceMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            if (!terminated) {
                for (ToolTask task : tasks) task.cancel(true);
                for (Runnable pending : executor.shutdownNow()) {
                    if (pending instanceof ToolTask task) task.cancel(false);
                }
            }
        }
    }

    private final class ToolTask extends FutureTask<String> {
        private final AtomicInteger state;

        private ToolTask(Callable<String> callback, AtomicInteger state) {
            super(callback);
            this.state = state;
        }

        boolean callbackStarted() { return state.get() == 1; }

        @Override
        public boolean cancel(boolean interrupt) {
            state.compareAndSet(0, 2);
            return super.cancel(interrupt);
        }

        @Override
        protected void done() {
            tasks.remove(this);
            executor.remove(this);
        }
    }

    public record Snapshot(int activeCount, int queuedCount, int poolSize, int largestPoolSize,
                           long overloadCount, boolean accepting, boolean terminated) { }

    static final class Unavailable extends RuntimeException {
        private Unavailable(String code, String message) { super(code + ": " + message); }
    }

    static final class Stopped extends RuntimeException {
        private final boolean callbackStarted;

        private Stopped(String code, String message, boolean callbackStarted, Throwable cause) {
            super(code + ": " + message, cause);
            this.callbackStarted = callbackStarted;
        }

        boolean callbackStarted() { return callbackStarted; }
    }
}
