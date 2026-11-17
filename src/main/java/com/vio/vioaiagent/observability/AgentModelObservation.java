package com.vio.vioaiagent.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;

/** U4 synchronous instrumentation. Telemetry failures must never retry a model call. */
public final class AgentModelObservation {
    private final ObservationRegistry registry;
    public AgentModelObservation(ObservationRegistry registry){this.registry=registry==null?ObservationRegistry.NOOP:registry;}
    public <T> reactor.core.publisher.Flux<T> stream(Supplier<reactor.core.publisher.Flux<T>> action) {
        final Observation parent=registry.getCurrentObservation();
        return reactor.core.publisher.Flux.defer(()->{
            Observation started=null;
            try{started=Observation.createNotStarted("agent.model.stream",registry).lowCardinalityKeyValue("mode","stream");if(parent!=null)started.parentObservation(parent);started.start();}
            catch(RuntimeException telemetryFailure){if(started!=null)try{started.stop();}catch(RuntimeException ignored){}started=null;}
            final Observation current=started;
            // Do not leave a ThreadLocal scope open while asynchronous work is pending.
            return reactor.core.publisher.Flux.defer(action)
                .doOnError(error->{if(current!=null)try{current.error(new IllegalStateException("MODEL_STREAM_FAILED"));}catch(RuntimeException ignored){}})
                .doFinally(signal->{if(current!=null)try{current.lowCardinalityKeyValue("outcome",signal.name());current.stop();}catch(RuntimeException ignored){}});
        });
    }
    public <T> T call(Supplier<T> action) {
        Observation observation=null;Observation.Scope scope=null;
        try {observation=Observation.createNotStarted("agent.model.call",registry).lowCardinalityKeyValue("mode","sync");observation.start();scope=observation.openScope();}
        catch(RuntimeException telemetryFailure){if(observation!=null)try{observation.stop();}catch(RuntimeException ignored){}observation=null;}
        try{return action.get();}
        catch(RuntimeException|Error failure){if(observation!=null)try{observation.error(new IllegalStateException("MODEL_CALL_FAILED"));}catch(RuntimeException ignored){}throw failure;}
        finally{if(scope!=null)try{scope.close();}catch(RuntimeException ignored){}if(observation!=null)try{observation.stop();}catch(RuntimeException ignored){}}
    }
}
