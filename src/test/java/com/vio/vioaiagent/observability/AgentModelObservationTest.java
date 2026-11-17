package com.vio.vioaiagent.observability;
import io.micrometer.observation.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AgentModelObservationTest {
    @Test void streamKeepsCapturedParentWhenSubscriptionMovesThreads() throws Exception {
        var registry=ObservationRegistry.create();var completed=new java.util.concurrent.CountDownLatch(1);
        var parentRef=new java.util.concurrent.atomic.AtomicReference<Observation>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>(){
            public boolean supportsContext(Observation.Context c){return true;}
            public void onStop(Observation.Context c){if(c.getName().equals("agent.model.stream")){assertSame(parentRef.get(),c.getParentObservation());completed.countDown();}}
        });
        var parent=Observation.start("agent.run.fixture",registry);parentRef.set(parent);
        reactor.core.publisher.Flux<String> stream;
        try(var scope=parent.openScope()){stream=new AgentModelObservation(registry).stream(()->reactor.core.publisher.Flux.just("ok"));}
        assertNull(registry.getCurrentObservation());
        assertEquals("ok",stream.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).blockLast());
        assertTrue(completed.await(5,java.util.concurrent.TimeUnit.SECONDS));parent.stop();assertNull(registry.getCurrentObservation());
    }

    @Test void streamLifecycleIsPerSubscriptionAndNeverLeaksScope(){
        var registry=ObservationRegistry.create();var stops=new AtomicInteger();var sources=new AtomicInteger();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context c){return true;}
            public void onStop(Observation.Context c){stops.incrementAndGet();}
        });
        var observed=new AgentModelObservation(registry);
        var flux=observed.stream(()->{sources.incrementAndGet();return reactor.core.publisher.Flux.just("x");});
        assertEquals(0,sources.get());assertEquals("x",flux.blockLast());assertEquals("x",flux.blockLast());
        observed.stream(()->reactor.core.publisher.Flux.just("partial").concatWith(reactor.core.publisher.Flux.never())).take(1).blockLast();
        assertEquals(3,stops.get());assertEquals(2,sources.get());assertNull(registry.getCurrentObservation());
    }
    @Test void streamOriginalErrorPropagatesWhileObservationIsSanitized(){
        var registry=ObservationRegistry.create();var stops=new AtomicInteger();var original=new IllegalStateException("private provider body");
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context c){return true;}
            public void onStop(Observation.Context c){assertEquals("MODEL_STREAM_FAILED",c.getError().getMessage());stops.incrementAndGet();}
        });
        var actual=assertThrows(IllegalStateException.class,()->new AgentModelObservation(registry).stream(()->reactor.core.publisher.Flux.error(original)).blockLast());
        assertSame(original,actual);assertEquals(1,stops.get());
    }

    @Test void recordsCompletionWithoutPromptOrUserAttributes(){
        var registry=ObservationRegistry.create();var stops=new AtomicInteger();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>(){
            public boolean supportsContext(Observation.Context c){return true;}
            public void onStop(Observation.Context c){assertEquals("agent.model.call",c.getName());assertFalse(c.getHighCardinalityKeyValues().iterator().hasNext());stops.incrementAndGet();}
        });
        assertEquals("result",new AgentModelObservation(registry).call(()->"result"));assertEquals(1,stops.get());assertNull(registry.getCurrentObservation());
    }
    @Test void brokenExporterDoesNotReplayApplicationCall(){
        var registry=ObservationRegistry.create();var calls=new AtomicInteger();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>(){
            public boolean supportsContext(Observation.Context c){return true;}
            public void onStart(Observation.Context c){throw new IllegalStateException("unavailable");}
        });
        assertEquals(1,new AgentModelObservation(registry).call(calls::incrementAndGet));assertEquals(1,calls.get());
    }
    @Test void originalFailureIsPreservedAndNeverRetried(){
        var expected=new IllegalArgumentException("original");var calls=new AtomicInteger();
        var actual=assertThrows(IllegalArgumentException.class,()->new AgentModelObservation(null).call(()->{calls.incrementAndGet();throw expected;}));
        assertSame(expected,actual);assertEquals(1,calls.get());
    }
}
