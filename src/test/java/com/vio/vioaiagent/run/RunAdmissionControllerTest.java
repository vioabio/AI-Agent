package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.RunAdmissionProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Admission holds every successful reservation until all competing requests finish. */
class RunAdmissionControllerTest {

    @Test
    void concurrentRequestsCannotExceedGlobalCapacity() throws Exception {
        RunAdmissionController controller = controller(5, 32, 32);
        List<Attempt> attempts = contend(controller, 24, false, false);
        try {
            assertAdmission(attempts, 5, "RUN_CAPACITY_EXCEEDED");
            assertEquals(5, controller.snapshot().reserved());
            assertEquals(5, controller.snapshot().capacity());
        } finally {
            release(attempts);
        }
        assertEmpty(controller);
    }

    @Test
    void concurrentRequestsCannotExceedPrincipalCapacity() throws Exception {
        RunAdmissionController controller = controller(32, 3, 32);
        List<Attempt> attempts = contend(controller, 24, true, true);
        try {
            assertAdmission(attempts, 3, "RUN_PRINCIPAL_LIMIT");
            assertEquals(3, controller.snapshot().reserved());
            assertEquals(1, controller.snapshot().principalScopes());
        } finally {
            release(attempts);
        }
        assertEmpty(controller);
    }

    @Test
    void concurrentDistinctPrincipalsCannotExceedTenantCapacity() throws Exception {
        RunAdmissionController controller = controller(32, 1, 4);
        List<Attempt> attempts = contend(controller, 24, true, false);
        try {
            assertAdmission(attempts, 4, "RUN_TENANT_LIMIT");
            assertEquals(4, controller.snapshot().reserved());
            assertEquals(4, controller.snapshot().principalScopes());
            assertEquals(1, controller.snapshot().tenantScopes());
        } finally {
            release(attempts);
        }
        assertEmpty(controller);
    }

    @Test
    void principalLimitsAreScopedByTenantAndTenantLimitsAreIndependent() {
        RunAdmissionController controller = controller(6, 1, 2);
        try (var first = controller.acquire("tenant-a", "same-principal");
             var otherTenant = controller.acquire("tenant-b", "same-principal");
             var otherPrincipal = controller.acquire("tenant-a", "other-principal")) {
            assertEquals("RUN_PRINCIPAL_LIMIT", assertThrows(RunAdmissionException.class,
                    () -> controller.acquire("tenant-a", "same-principal")).code());
            assertEquals("RUN_TENANT_LIMIT", assertThrows(RunAdmissionException.class,
                    () -> controller.acquire("tenant-a", "third-principal")).code());
            try (var unaffected = controller.acquire("tenant-b", "other-principal")) {
                assertEquals(4, controller.snapshot().reserved());
                assertEquals(4, controller.snapshot().principalScopes());
                assertEquals(2, controller.snapshot().tenantScopes());
            }
        }
        assertEmpty(controller);
    }

    @Test
    void concurrentDoubleCloseReleasesOnceAndRemovesOnlyUnusedKeys() throws Exception {
        RunAdmissionController controller = controller(8, 4, 8);
        var first = controller.acquire("tenant-a", "principal-a");
        var sameScope = controller.acquire("tenant-a", "principal-a");
        var otherScope = controller.acquire("tenant-a", "principal-b");
        var otherTenant = controller.acquire("tenant-b", "principal-a");
        try {
            race(24, ignored -> { first.close(); return null; });
            assertEquals(3, controller.snapshot().reserved(), "A permit must release only once");
            assertEquals(3, controller.snapshot().principalScopes(), "A live scope must remain counted");
            assertEquals(2, controller.snapshot().tenantScopes());

            sameScope.close();
            assertEquals(2, controller.snapshot().principalScopes());
            assertEquals(2, controller.snapshot().tenantScopes());
            otherScope.close();
            assertEquals(1, controller.snapshot().principalScopes());
            assertEquals(1, controller.snapshot().tenantScopes());
        } finally {
            first.close();
            sameScope.close();
            otherScope.close();
            otherTenant.close();
        }
        assertEmpty(controller);
        try (var reusable = controller.acquire("tenant-a", "principal-a")) {
            assertEquals(1, controller.snapshot().reserved());
        }
        assertEmpty(controller);
    }

    @Test
    void closedControllerRejectsNewWorkButExistingPermitCanRelease() {
        RunAdmissionProperties properties = properties(2, 2, 2);
        properties.setRetryAfterSeconds(7);
        RunAdmissionController controller = new RunAdmissionController(properties);
        var permit = controller.acquire("tenant-a", "principal-a");
        controller.close();
        controller.close();

        RunAdmissionException rejection = assertThrows(RunAdmissionException.class,
                () -> controller.acquire("tenant-b", "principal-b"));
        assertEquals("RUN_EXECUTION_UNAVAILABLE", rejection.code());
        assertEquals(7, rejection.retryAfterSeconds());
        assertTrue(rejection.unavailable());
        assertFalse(controller.snapshot().accepting());
        assertEquals(1, controller.snapshot().reserved());
        permit.close();
        assertEmpty(controller);
        assertThrows(RunAdmissionException.class,
                () -> controller.acquire("tenant-a", "principal-a"));
    }

    private static RunAdmissionController controller(int capacity, int principalLimit, int tenantLimit) {
        return new RunAdmissionController(properties(capacity, principalLimit, tenantLimit));
    }

    private static RunAdmissionProperties properties(int capacity, int principalLimit, int tenantLimit) {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        properties.setThreadCount(1);
        properties.setQueueCapacity(capacity - 1);
        properties.setMaxPerPrincipal(principalLimit);
        properties.setMaxPerTenant(tenantLimit);
        return properties;
    }

    private static List<Attempt> contend(RunAdmissionController controller, int count,
                                         boolean sameTenant, boolean samePrincipal) throws Exception {
        return race(count, index -> {
            try {
                return new Attempt(controller.acquire(sameTenant ? "tenant" : "tenant-" + index,
                        samePrincipal ? "principal" : "principal-" + index), null);
            } catch (RunAdmissionException rejection) {
                return new Attempt(null, rejection);
            }
        });
    }

    private static <T> List<T> race(int count, ConcurrentAction<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int slot = index;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start gate timed out");
                    return action.run(slot);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Every contender must reach the start gate");
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(5, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Contenders must terminate");
        }
    }

    private static void assertAdmission(List<Attempt> attempts, int accepted, String code) {
        assertEquals(accepted, attempts.stream().filter(attempt -> attempt.permit() != null).count());
        List<RunAdmissionException> rejections = attempts.stream().map(Attempt::rejection)
                .filter(value -> value != null).toList();
        assertEquals(attempts.size() - accepted, rejections.size());
        rejections.forEach(rejection -> {
            assertEquals(code, rejection.code());
            assertEquals(1, rejection.retryAfterSeconds());
            assertFalse(rejection.unavailable());
        });
    }

    private static void release(List<Attempt> attempts) {
        attempts.stream().map(Attempt::permit).filter(value -> value != null)
                .forEach(RunAdmissionController.Permit::close);
    }

    private static void assertEmpty(RunAdmissionController controller) {
        assertEquals(0, controller.snapshot().reserved());
        assertEquals(0, controller.snapshot().principalScopes());
        assertEquals(0, controller.snapshot().tenantScopes());
    }

    private record Attempt(RunAdmissionController.Permit permit, RunAdmissionException rejection) { }

    @FunctionalInterface
    private interface ConcurrentAction<T> { T run(int index) throws Exception; }
}
