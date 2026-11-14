package com.vio.vioaiagent.observability;

import io.micrometer.observation.*;
import java.util.concurrent.Callable;

/** Capture only the observation parent; authentication remains owned by the gateway. */
public final class AgentToolObservation {
    private final ObservationRegistry registry;
    public AgentToolObservation(ObservationRegistry registry) { this.registry = registry == null ? ObservationRegistry.NOOP : registry; }
    public <T> Callable<T> capture(String toolName, Callable<T> callback) {
        final Observation parent = registry.getCurrentObservation();
        return () -> {
            Observation observation = null; Observation.Scope scope = null;
            try {
                observation = Observation.createNotStarted("agent.tool.callback", registry);
                if (parent != null) observation.parentObservation(parent);
                if (toolName != null && toolName.matches("[A-Za-z0-9_]{1,80}")) observation.highCardinalityKeyValue("agent.tool.name", toolName);
                observation.start(); scope = observation.openScope();
            } catch (RuntimeException telemetryFailure) {
                if (observation != null) try { observation.stop(); } catch (RuntimeException ignored) { }
                observation = null;
            }
            try { return callback.call(); }
            catch (Exception | Error failure) {
                if (observation != null) try { observation.error(new IllegalStateException("TOOL_CALLBACK_FAILED")); } catch (RuntimeException ignored) { }
                throw failure;
            } finally {
                if (scope != null) try { scope.close(); } catch (RuntimeException ignored) { }
                if (observation != null) try { observation.stop(); } catch (RuntimeException ignored) { }
            }
        };
    }
}
