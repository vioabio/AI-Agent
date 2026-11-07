package com.vio.vioaiagent.tools.governance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class BoundedToolExecutorTest {
    @Test
    void springBindsDefaultsAndOverridesAndRejectsInvalidResourceCeiling() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ToolExecutionConfiguration.class);
        runner.run(context -> assertEquals(ToolExecutionProperties.defaults(),
                context.getBean(ToolExecutionProperties.class)));
        runner.withPropertyValues("vio.tools.execution.thread-count=2", "vio.tools.execution.queue-capacity=0",
                "vio.tools.execution.shutdown-grace-ms=17").run(context ->
                assertEquals(new ToolExecutionProperties(2, 0, 17), context.getBean(ToolExecutionProperties.class)));
        runner.withPropertyValues("vio.tools.execution.thread-count=0").run(context ->
                assertNotNull(context.getStartupFailure(), "Unsafe thread limits must fail actual Spring binding"));
    }

    @Test
    void saturationBoundsThreadsAndPendingWorkWithoutRunningOnCaller() throws Exception {
        BoundedToolExecutor executor = new BoundedToolExecutor(new ToolExecutionProperties(1, 1, 1000));
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> callbackThread = new AtomicReference<>();
        AtomicInteger rejectedCalls = new AtomicInteger();
        try {
            Future<String> first = callers.submit(() -> executor.call(() -> { }, () -> {
                callbackThread.set(Thread.currentThread().getName());
                started.countDown();
                release.await();
                return "first";
            }, 5000));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Future<String> second = callers.submit(() -> executor.call(() -> { }, () -> "second", 5000));
            queued(executor, 1);
            BoundedToolExecutor.Unavailable rejection = assertThrows(BoundedToolExecutor.Unavailable.class,
                    () -> executor.call(() -> { }, () -> { rejectedCalls.incrementAndGet(); return "wrong"; }, 1000));
            assertTrue(rejection.getMessage().startsWith(BoundedToolExecutor.OVERLOADED));
            assertEquals(0, rejectedCalls.get());
            assertTrue(callbackThread.get().startsWith("tool-governance-"));
            assertEquals(1, executor.snapshot().largestPoolSize());
            assertEquals(1, executor.snapshot().overloadCount());
            release.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals("second", second.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            callers.shutdownNow();
            executor.close();
        }
    }

    @Test
    void queuedTimeoutNeverStartsCallbackAndFreesCapacityForReplacement() throws Exception {
        BoundedToolExecutor executor = new BoundedToolExecutor(new ToolExecutionProperties(1, 1, 1000));
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger timedOutCalls = new AtomicInteger();
        try {
            Future<String> first = callers.submit(() -> executor.call(() -> { }, () -> {
                started.countDown(); release.await(); return "first";
            }, 5000));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            BoundedToolExecutor.Stopped timeout = assertThrows(BoundedToolExecutor.Stopped.class,
                    () -> executor.call(() -> { }, () -> { timedOutCalls.incrementAndGet(); return "wrong"; }, 30));
            assertTrue(timeout.getMessage().startsWith(BoundedToolExecutor.TIMEOUT));
            assertFalse(timeout.callbackStarted());
            assertEquals(0, executor.snapshot().queuedCount());
            Future<String> replacement = callers.submit(() -> executor.call(() -> { }, () -> "replacement", 5000));
            queued(executor, 1);
            release.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals("replacement", replacement.get(2, TimeUnit.SECONDS));
            assertEquals(0, timedOutCalls.get());
        } finally {
            release.countDown(); callers.shutdownNow(); executor.close();
        }
    }

    @Test
    void callerCancellationRemovesQueuedTaskAndPreservesInterrupt() throws Exception {
        BoundedToolExecutor executor = new BoundedToolExecutor(new ToolExecutionProperties(1, 1, 1000));
        ExecutorService callers = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger cancelledCalls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread cancelledCaller = new Thread(() -> {
            try {
                executor.call(() -> { }, () -> { cancelledCalls.incrementAndGet(); return "wrong"; }, 5000);
            } catch (Throwable ex) {
                failure.set(ex); interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        }, "cancelled-tool-caller");
        try {
            Future<String> first = callers.submit(() -> executor.call(() -> { }, () -> {
                started.countDown(); release.await(); return "first";
            }, 5000));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            cancelledCaller.start();
            queued(executor, 1);
            cancelledCaller.interrupt();
            cancelledCaller.join(2000);
            assertFalse(cancelledCaller.isAlive());
            assertInstanceOf(BoundedToolExecutor.Stopped.class, failure.get());
            assertTrue(failure.get().getMessage().startsWith(BoundedToolExecutor.CANCELLED));
            assertTrue(interruptPreserved.get());
            assertEquals(0, executor.snapshot().queuedCount());
            release.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals(0, cancelledCalls.get());
        } finally {
            cancelledCaller.interrupt(); release.countDown(); callers.shutdownNow(); executor.close();
        }
    }

    @Test
    void gracefulCloseCancelsPendingWorkAndLetsStartedCallbackFinish() throws Exception {
        BoundedToolExecutor executor = new BoundedToolExecutor(new ToolExecutionProperties(1, 1, 3000));
        ExecutorService callers = Executors.newFixedThreadPool(3);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger pendingCalls = new AtomicInteger();
        try {
            Future<String> first = callers.submit(() -> executor.call(() -> { }, () -> {
                started.countDown(); release.await(); return "first";
            }, 5000));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Future<String> second = callers.submit(() -> executor.call(() -> { }, () -> {
                pendingCalls.incrementAndGet(); return "wrong";
            }, 5000));
            queued(executor, 1);
            Future<?> closing = callers.submit(executor::close);
            java.util.concurrent.ExecutionException cancelled = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> second.get(2, TimeUnit.SECONDS));
            assertInstanceOf(BoundedToolExecutor.Unavailable.class, cancelled.getCause());
            assertTrue(cancelled.getCause().getMessage().startsWith(BoundedToolExecutor.SHUTDOWN));
            assertFalse(first.isDone());
            assertFalse(executor.snapshot().accepting());
            release.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            closing.get(2, TimeUnit.SECONDS);
            assertTrue(executor.snapshot().terminated());
            assertEquals(0, pendingCalls.get());
            assertTrue(assertThrows(BoundedToolExecutor.Unavailable.class,
                    () -> executor.call(() -> { }, () -> "wrong", 1000)).getMessage()
                    .startsWith(BoundedToolExecutor.SHUTDOWN));
            executor.close();
        } finally {
            release.countDown(); callers.shutdownNow(); executor.close();
        }
    }

    @Test
    void zeroGraceCloseInterruptsStartedWorkAndReleasesWaitingCaller() throws Exception {
        BoundedToolExecutor executor = new BoundedToolExecutor(new ToolExecutionProperties(1, 0, 0));
        ExecutorService callers = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<String> first = callers.submit(() -> executor.call(() -> { }, () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException ex) { interrupted.countDown(); throw ex; }
                return "first";
            }, 5000));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            executor.close();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            java.util.concurrent.ExecutionException cancelled = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> first.get(2, TimeUnit.SECONDS));
            assertInstanceOf(BoundedToolExecutor.Stopped.class, cancelled.getCause());
            assertTrue(((BoundedToolExecutor.Stopped) cancelled.getCause()).callbackStarted());
            assertTrue(cancelled.getCause().getMessage().startsWith(BoundedToolExecutor.SHUTDOWN));
            await().atMost(Duration.ofSeconds(2)).until(() -> executor.snapshot().terminated());
        } finally {
            release.countDown(); callers.shutdownNow(); executor.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workerEnforcesOriginalDeadlineBeforeAndAfterAuthorizationWithDelayedCaller(boolean afterAuthorization) throws Exception {
        CountDownLatch delayEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong delayedAt = new AtomicLong();
        AtomicInteger authorizationCalls = new AtomicInteger();
        AtomicInteger callbackCalls = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1)) {
            @Override public void execute(Runnable command) {
                if (!afterAuthorization) holdCaller(delayEntered, release, delayedAt);
                super.execute(command);
                // Hold the caller outside Future.get until the worker finishes. This exposes
                // deadline violations that caller-only timeout enforcement cannot prevent.
                awaitTaskCompletion(command);
            }
        };
        BoundedToolExecutor executor = new BoundedToolExecutor(new ToolExecutionProperties(1, 1, 0), pool);
        ExecutorService callers = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = callers.submit(() -> executor.call(() -> {
                authorizationCalls.incrementAndGet();
                if (afterAuthorization) holdCaller(delayEntered, release, delayedAt);
            }, () -> { callbackCalls.incrementAndGet(); return "must not execute"; }, 1000));
            assertTrue(delayEntered.await(2, TimeUnit.SECONDS));
            pastDeadline(delayedAt);
            release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> result.get(3, TimeUnit.SECONDS));
            BoundedToolExecutor.Stopped stopped = assertInstanceOf(BoundedToolExecutor.Stopped.class, failure.getCause());
            assertTrue(stopped.getMessage().startsWith(BoundedToolExecutor.TIMEOUT));
            assertFalse(stopped.callbackStarted());
            assertEquals(afterAuthorization ? 1 : 0, authorizationCalls.get());
            assertEquals(0, callbackCalls.get());
        } finally {
            release.countDown(); callers.shutdownNow(); executor.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedFutureCannotPublishCallbackOutcomeThatFinishedAfterOriginalDeadline(boolean callbackFails) throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong delayedAt = new AtomicLong();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1)) {
            @Override public void execute(Runnable command) {
                super.execute(command);
                awaitTaskCompletion(command);
            }
        };
        BoundedToolExecutor executor = new BoundedToolExecutor(new ToolExecutionProperties(1, 1, 0), pool);
        ExecutorService callers = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = callers.submit(() -> executor.call(() -> { }, () -> {
                holdCaller(callbackEntered, release, delayedAt);
                if (callbackFails) throw new IllegalStateException("late callback failure");
                return "late write result";
            }, 1000));
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
            pastDeadline(delayedAt);
            release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> result.get(3, TimeUnit.SECONDS));
            BoundedToolExecutor.Stopped stopped = assertInstanceOf(BoundedToolExecutor.Stopped.class, failure.getCause());
            assertTrue(stopped.getMessage().startsWith(BoundedToolExecutor.TIMEOUT));
            assertTrue(stopped.callbackStarted(), "a finished late write still has an uncertain effect");
        } finally {
            release.countDown(); callers.shutdownNow(); executor.close();
        }
    }

    @Test
    void delayedCallerCanStillReceiveResultThatFinishedWithinOriginalDeadline() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch releaseCaller = new CountDownLatch(1);
        AtomicLong delayedAt = new AtomicLong();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1)) {
            @Override public void execute(Runnable command) {
                super.execute(command);
                awaitTaskCompletion(command);
                holdCaller(completed, releaseCaller, delayedAt);
            }
        };
        BoundedToolExecutor executor = new BoundedToolExecutor(new ToolExecutionProperties(1, 1, 0), pool);
        ExecutorService callers = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = callers.submit(() -> executor.call(() -> { }, () -> "timely result", 1000));
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            pastDeadline(delayedAt);
            releaseCaller.countDown();
            assertEquals("timely result", result.get(3, TimeUnit.SECONDS),
                    "deadline checks must use physical completion rather than caller wake-up time");
        } finally {
            releaseCaller.countDown(); callers.shutdownNow(); executor.close();
        }
    }

    private static void holdCaller(CountDownLatch entered, CountDownLatch release, AtomicLong delayedAt) {
        delayedAt.set(System.nanoTime());
        entered.countDown();
        try { release.await(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }

    private static void awaitTaskCompletion(Runnable task) {
        try { ((Future<?>) task).get(3, TimeUnit.SECONDS); }
        catch (ExecutionException ignored) { /* the real caller must inspect the task's failure */ }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        catch (java.util.concurrent.TimeoutException ex) { throw new AssertionError("worker did not finish", ex); }
    }

    private static void pastDeadline(AtomicLong delayedAt) {
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(5))
                .until(() -> System.nanoTime() - delayedAt.get() >= TimeUnit.MILLISECONDS.toNanos(1050));
    }

    private void queued(BoundedToolExecutor executor, int count) {
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(5))
                .until(() -> executor.snapshot().queuedCount() == count);
    }
}
