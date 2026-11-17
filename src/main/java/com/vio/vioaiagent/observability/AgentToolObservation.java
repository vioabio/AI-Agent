package com.vio.vioaiagent.observability;

import io.micrometer.observation.*;
import java.util.concurrent.Callable;

/** Capture only the observation parent; authentication remains owned by the gateway. */
public final class AgentToolObservation {
    private final ObservationRegistry registry;
    public AgentToolObservation(ObservationRegistry registry) { this.registry = registry == null ? ObservationRegistry.NOOP : registry; }
    /** Caller-side scheduling boundary; includes executor wait, not earlier gateway/HITL decisions. */
    public <T> T dispatch(String toolName,com.vio.vioaiagent.tools.governance.ToolAttemptTiming timing,java.util.function.Supplier<T> action){
        Observation observation=null;Observation.Scope scope=null;String outcome="SUCCESS";
        try{
            observation=Observation.createNotStarted("agent.tool.dispatch",registry);
            if(toolName!=null&&toolName.matches("[A-Za-z0-9_]{1,80}"))observation.highCardinalityKeyValue("agent.tool.name",toolName);
            observation.start();scope=observation.openScope();
        }catch(RuntimeException telemetryFailure){if(observation!=null)try{observation.stop();}catch(RuntimeException ignored){}observation=null;}
        try{return action.get();}
        catch(RuntimeException|Error failure){
            outcome="FAILED";
            if(observation!=null)try{observation.error(new IllegalStateException("TOOL_DISPATCH_FAILED"));}catch(RuntimeException ignored){}
            throw failure;
        }finally{
            if(observation!=null){
                try{
                    observation.lowCardinalityKeyValue("outcome",outcome);
                    var values=timing.freeze();
                    for(String flag:java.util.List.of("accepted","callbackStarted","callbackCompleted"))
                        if(values.get(flag) instanceof Boolean value)observation.lowCardinalityKeyValue("agent.tool."+flag,value.toString());
                    for(String field:java.util.List.of("queueWaitMs","authorizationMs","executionMs","observedMs"))
                        if(values.get(field) instanceof Long value&&value>=0&&value<=604800000)observation.highCardinalityKeyValue("agent.tool."+field,value.toString());
                }catch(RuntimeException ignored){}
            }
            if(scope!=null)try{scope.close();}catch(RuntimeException ignored){}
            if(observation!=null)try{observation.stop();}catch(RuntimeException ignored){}
        }
    }
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
