package com.vio.vioaiagent.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;

/** Worker-thread execution scope only; does not claim to cover queueing, persistence or detached tool threads. */
public final class AgentRunObservation {
    private final ObservationRegistry registry;
    public AgentRunObservation(ObservationRegistry registry) { this.registry = registry == null ? ObservationRegistry.NOOP : registry; }
    public <T> T execute(String runId, Supplier<T> action) {
        Observation observation = null;
        Observation.Scope scope = null;
        try {
            observation = Observation.createNotStarted("agent.run.execute", registry);
            // A correlation attribute, never a metric dimension or a user identity.
            if (runId != null && runId.matches("[A-Za-z0-9-]{1,80}")) observation.highCardinalityKeyValue("agent.run.id", runId);
            observation.start();
            scope = observation.openScope();
        } catch (RuntimeException telemetryFailure) {
            if (observation != null) try { observation.stop(); } catch (RuntimeException ignored) { }
            observation = null;
        }
        try { return action.get(); }
        catch (RuntimeException | Error failure) {
            if (observation != null) try { observation.error(new IllegalStateException("RUN_EXECUTION_FAILED")); } catch (RuntimeException ignored) { }
            throw failure;
        } finally {
            if (scope != null) try { scope.close(); } catch (RuntimeException ignored) { }
            if (observation != null) try { observation.stop(); } catch (RuntimeException ignored) { }
        }
    }
}
