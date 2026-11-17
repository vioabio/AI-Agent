package com.vio.vioaiagent.observability;

import io.micrometer.observation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRunObservationTest {
    @Test void parallelRunsHaveDistinctParentsAndWorkersAreCleaned() throws Exception {
        var registry = ObservationRegistry.create();
        var contexts = new ConcurrentLinkedQueue<Observation.Context>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context c) { return true; }
            public void onStop(Observation.Context c) { contexts.add(c); }
        });
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<?>>();
            for (String id : List.of("run-a", "run-b")) futures.add(workers.submit(() -> {
                new AgentRunObservation(registry).execute(id, () -> {
                    try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception failure) { throw new IllegalStateException(failure); }
                    var parent = registry.getCurrentObservation(); assertNotNull(parent);
                    return new AgentModelObservation(registry).call(() -> { assertNotSame(parent, registry.getCurrentObservation()); return "answer"; });
                });
                assertNull(registry.getCurrentObservation());
            }));
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        var runs = contexts.stream().filter(c -> c.getName().equals("agent.run.execute")).toList();
        var models = contexts.stream().filter(c -> c.getName().equals("agent.model.call")).toList();
        assertEquals(2, runs.size()); assertEquals(2, models.size());
        assertEquals(Set.of("run-a", "run-b"), runs.stream().map(c -> c.getHighCardinalityKeyValue("agent.run.id").getValue()).collect(java.util.stream.Collectors.toSet()));
        assertNotSame(models.get(0).getParentObservation(), models.get(1).getParentObservation());
        runs.forEach(c -> assertFalse(c.getLowCardinalityKeyValues().iterator().hasNext()));
        assertNull(registry.getCurrentObservation());
    }
    @Test void errorsAreSanitizedAndExporterFailuresNeverReplayTheAction() {
        var registry = ObservationRegistry.create();
        var stops = new java.util.concurrent.atomic.AtomicInteger();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context c) { return true; }
            public void onStop(Observation.Context c) { assertEquals("RUN_EXECUTION_FAILED", c.getError().getMessage()); stops.incrementAndGet(); }
        });
        var original = new IllegalArgumentException("private input");
        assertSame(original, assertThrows(IllegalArgumentException.class, () -> new AgentRunObservation(registry).execute("run-a", () -> { throw original; })));
        assertEquals(1, stops.get()); assertNull(registry.getCurrentObservation());
        var broken = ObservationRegistry.create();
        broken.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context c) { return true; }
            public void onStart(Observation.Context c) { throw new IllegalStateException("unavailable"); }
        });
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(1, new AgentRunObservation(broken).execute("run-b", calls::incrementAndGet)); assertEquals(1, calls.get());
        assertNull(broken.getCurrentObservation());
    }
}
