package com.vio.vioaiagent.observability;

import com.vio.vioaiagent.tools.governance.*;
import io.micrometer.observation.*;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AgentToolDispatchObservationTest {
    @Test void exportsRunDispatchCallbackModelTreeAndOnlyMeasuredTiming() throws Exception {
        var capture=new AgentTracingConfigurationTest.Capture();
        try(var runtime=AgentTracingConfiguration.runtime(capture,1);var worker=new BoundedToolExecutor(new ToolExecutionProperties(1,1,500))){
            var registry=ObservationRegistry.create();registry.observationConfig().observationHandler(runtime.handler());
            var observation=new AgentToolObservation(registry);var timing=new ToolAttemptTiming();
            assertEquals("ok",new AgentRunObservation(registry).execute("run-dispatch",()->observation.dispatch("lookupPokemonReference",timing,
                ()->worker.call(()->{},observation.capture("lookupPokemonReference",()->new AgentModelObservation(registry).call(()->"ok")),2000,timing))));
            runtime.provider().forceFlush().join(2,TimeUnit.SECONDS);assertEquals(4,capture.spans.size());
            var names=List.of("agent.run.execute","agent.tool.dispatch","agent.tool.callback","agent.model.call");
            for(int i=1;i<names.size();i++){
                String parentName=names.get(i-1);var parent=capture.spans.stream().filter(s->s.getName().equals(parentName)).findFirst().orElseThrow();
                String childName=names.get(i);var child=capture.spans.stream().filter(s->s.getName().equals(childName)).findFirst().orElseThrow();
                assertEquals(parent.getSpanId(),child.getParentSpanId());assertEquals(parent.getTraceId(),child.getTraceId());
            }
            var span=capture.spans.stream().filter(s->s.getName().equals("agent.tool.dispatch")).findFirst().orElseThrow();
            assertEquals("SUCCESS",span.getAttributes().get(AttributeKey.stringKey("outcome")));
            assertEquals("true",span.getAttributes().get(AttributeKey.stringKey("agent.tool.callbackCompleted")));
            for(String field:List.of("queueWaitMs","authorizationMs","executionMs","observedMs"))assertEquals(timing.freeze().get(field).toString(),span.getAttributes().get(AttributeKey.stringKey("agent.tool."+field)));
            worker.call(()->{},()->{assertNull(registry.getCurrentObservation());return "clean";},2000);
            assertNull(registry.getCurrentObservation());
        }
    }
    @Test void workerAuthorizationFailureHasNoCallbackSpanOrPrivateErrorPayload(){
        var capture=new AgentTracingConfigurationTest.Capture();
        try(var runtime=AgentTracingConfiguration.runtime(capture,1);var worker=new BoundedToolExecutor(new ToolExecutionProperties(1,1,500))){
            var registry=ObservationRegistry.create();registry.observationConfig().observationHandler(runtime.handler());
            var observation=new AgentToolObservation(registry);var timing=new ToolAttemptTiming();var calls=new AtomicInteger();
            assertThrows(RuntimeException.class,()->observation.dispatch("lookupPokemonReference",timing,()->worker.call(()->{throw new SecurityException("private-dispatch-input");},observation.capture("lookupPokemonReference",()->{calls.incrementAndGet();return "bad";}),2000,timing)));
            runtime.provider().forceFlush().join(2,TimeUnit.SECONDS);assertEquals(1,capture.spans.size());assertEquals(0,calls.get());
            var span=capture.spans.getFirst();assertEquals("FAILED",span.getAttributes().get(AttributeKey.stringKey("outcome")));
            assertEquals("false",span.getAttributes().get(AttributeKey.stringKey("agent.tool.callbackStarted")));
            assertNull(span.getAttributes().get(AttributeKey.stringKey("agent.tool.executionMs")));
            assertFalse(capture.spans.toString().contains("private-dispatch-input"));assertNull(registry.getCurrentObservation());
        }
    }
    @Test void rejectedSchedulingDoesNotInventQueueOrExecutionMeasurements(){
        var registry=ObservationRegistry.create();var stopped=new ArrayList<Observation.Context>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>(){
            public boolean supportsContext(Observation.Context c){return true;}
            public void onStop(Observation.Context c){stopped.add(c);}
        });
        var worker=new BoundedToolExecutor(new ToolExecutionProperties(1,0,500));worker.close();
        var timing=new ToolAttemptTiming();var calls=new AtomicInteger();
        assertThrows(RuntimeException.class,()->new AgentToolObservation(registry).dispatch("lookupPokemonReference",timing,()->worker.call(()->{},()->{calls.incrementAndGet();return "bad";},2000,timing)));
        assertEquals(0,calls.get());assertEquals(1,stopped.size());
        assertEquals("false",stopped.getFirst().getLowCardinalityKeyValue("agent.tool.accepted").getValue());
        assertNull(stopped.getFirst().getHighCardinalityKeyValue("agent.tool.queueWaitMs"));assertNull(registry.getCurrentObservation());
    }
    @Test void telemetryFailuresNeverRetryOrChangeBusinessReturnOrOriginalError(){
        for(boolean failStart:List.of(true,false)){
            var registry=ObservationRegistry.create();var calls=new AtomicInteger();
            registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>(){
                public boolean supportsContext(Observation.Context c){return true;}
                public void onStart(Observation.Context c){if(failStart)throw new IllegalStateException("broken-start");}
                public void onStop(Observation.Context c){throw new IllegalStateException("broken-stop");}
            });
            var observed=new AgentToolObservation(registry);
            assertEquals(1,observed.dispatch("read",new ToolAttemptTiming(),calls::incrementAndGet));assertEquals(1,calls.get());
            var original=new IllegalArgumentException("original-private");
            assertSame(original,assertThrows(IllegalArgumentException.class,()->observed.dispatch("read",new ToolAttemptTiming(),()->{throw original;})));
            assertNull(registry.getCurrentObservation());
        }
    }
}
