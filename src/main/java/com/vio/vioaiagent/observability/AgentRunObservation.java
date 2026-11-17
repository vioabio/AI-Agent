package com.vio.vioaiagent.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;

/** Worker attempt scope with bounded lifecycle phases and the recorded pre-worker wait. */
public final class AgentRunObservation {
    public enum Phase {
        AUTHORIZE("agent.run.authorize", "RUN_AUTHORIZATION_FAILED"),
        COMMIT("agent.run.commit", "RUN_COMMIT_FAILED");
        private final String name;
        private final String failure;
        Phase(String name, String failure) { this.name = name; this.failure = failure; }
    }
    private static final Set<String> STATUSES = Set.of("CREATED", "RUNNING", "WAITING_APPROVAL",
            "COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT");
    private final ObservationRegistry registry;
    public AgentRunObservation(ObservationRegistry registry) { this.registry = registry == null ? ObservationRegistry.NOOP : registry; }
    public <T> T execute(String runId, Supplier<T> action) {
        if (hasCurrentRun(runId)) return action.get();
        return observe("agent.run.execute", "RUN_EXECUTION_FAILED", runId, null, null, action, null, null);
    }
    public <T> T attempt(String runId, long dispatchWaitMs, Supplier<T> action, Supplier<String> status) {
        return attempt(runId, null, dispatchWaitMs, action, status);
    }
    public <T> T attempt(String runId, String attemptId, long dispatchWaitMs, Supplier<T> action, Supplier<String> status) {
        return observe("agent.run.execute", "RUN_EXECUTION_FAILED", runId, attemptId, dispatchWaitMs, action, status, null);
    }
    public <T> T admission(Supplier<T> action, Function<T, String> resultRunId) {
        return observe("agent.run.admit", "RUN_ADMISSION_FAILED", null, null, null, action, null, resultRunId);
    }
    public <T> T phase(Phase phase, Supplier<T> action) {
        return observe(phase.name, phase.failure, null, null, null, action, null, null);
    }
    /** No thread scope is opened while work is queued. The terminal callback closes it once. */
    public Dispatch dispatch(String runId, String attemptId) {
        Observation observation = null;
        try {
            observation = Observation.createNotStarted("agent.run.dispatch", registry);
            tagIdentity(observation, runId, attemptId);
            observation.start();
        } catch (RuntimeException unavailable) {
            if (observation != null) try { observation.stop(); } catch (RuntimeException ignored) { }
            observation = null;
        }
        return new Dispatch(observation);
    }
    public static final class Dispatch {
        private final Observation observation;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Dispatch(Observation observation) { this.observation = observation; }
        public void finish(String outcome) {
            if (!closed.compareAndSet(false, true) || observation == null) return;
            try {
                String value = Set.of("STARTED", "CANCELLED", "TIMED_OUT", "FAILED").contains(outcome) ? outcome : "ABORTED";
                observation.lowCardinalityKeyValue("agent.run.dispatch.outcome", value);
                if (Set.of("FAILED", "TIMED_OUT").contains(value)) observation.error(new IllegalStateException("RUN_DISPATCH_FAILED"));
            } catch (RuntimeException ignored) { }
            finally { try { observation.stop(); } catch (RuntimeException ignored) { } }
        }
    }
    private static void tagIdentity(Observation observation, String runId, String attemptId) {
        if (runId != null && runId.matches("[A-Za-z0-9-]{1,80}")) observation.highCardinalityKeyValue("agent.run.id", runId);
        if (attemptId != null && attemptId.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
            observation.highCardinalityKeyValue("agent.run.attempt.id", attemptId);
    }
    private boolean hasCurrentRun(String runId) {
        if (runId == null) return false;
        try {
            var current = registry.getCurrentObservation();
            if (current == null || !"agent.run.execute".equals(current.getContext().getName())) return false;
            var value = current.getContext().getHighCardinalityKeyValue("agent.run.id");
            return value != null && runId.equals(value.getValue());
        } catch (RuntimeException unavailable) { return false; }
    }
    private <T> T observe(String name, String failureCode, String runId, String attemptId, Long dispatchWaitMs,
                          Supplier<T> action, Supplier<String> status, Function<T, String> resultRunId) {
        Observation observation = null;
        Observation.Scope scope = null;
        try {
            observation = Observation.createNotStarted(name, registry);
            // A correlation attribute, never a metric dimension or a user identity.
            tagIdentity(observation, runId, attemptId);
            if (dispatchWaitMs != null && dispatchWaitMs >= 0 && dispatchWaitMs <= 604800000)
                observation.highCardinalityKeyValue("agent.run.dispatch_wait_ms", dispatchWaitMs.toString());
            observation.start();
            scope = observation.openScope();
        } catch (RuntimeException telemetryFailure) {
            if (observation != null) try { observation.stop(); } catch (RuntimeException ignored) { }
            observation = null;
        }
        try {
            T result = action.get();
            if (observation != null && resultRunId != null)
                try { tagIdentity(observation, resultRunId.apply(result), null); } catch (RuntimeException ignored) { }
            return result;
        }
        catch (RuntimeException | Error failure) {
            if (observation != null) try { observation.error(new IllegalStateException(failureCode)); } catch (RuntimeException ignored) { }
            throw failure;
        } finally {
            if (observation != null && status != null) {
                try {
                    String value = status.get();
                    if (value != null && STATUSES.contains(value)) {
                        observation.lowCardinalityKeyValue("agent.run.status", value);
                        if (Set.of("FAILED", "TIMED_OUT").contains(value))
                            observation.error(new IllegalStateException("RUN_" + value));
                    }
                } catch (RuntimeException ignored) { }
            }
            if (scope != null) try { scope.close(); } catch (RuntimeException ignored) { }
            if (observation != null) try { observation.stop(); } catch (RuntimeException ignored) { }
        }
    }
}
