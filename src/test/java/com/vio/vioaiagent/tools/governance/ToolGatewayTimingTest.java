package com.vio.vioaiagent.tools.governance;

import com.vio.vioaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ToolGatewayTimingTest {
    @Test void retryAttemptsAreRetainedAndCacheReuseIsExplicitlyBypassed() {
        var hitl=new HitlManager();var masker=new ParameterMasker();
        var gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(System.getProperty("user.dir")+"/tmp")),new CommandGuard(List.of()),masker,new AuditLogger(masker));
        var registry=io.micrometer.observation.ObservationRegistry.create();
        var observed=new java.util.concurrent.CopyOnWriteArrayList<io.micrometer.observation.Observation.Context>();
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>(){
            public boolean supportsContext(io.micrometer.observation.Observation.Context c){return true;}
            public void onStop(io.micrometer.observation.Observation.Context c){observed.add(c);}
        });
        org.springframework.test.util.ReflectionTestUtils.setField(gateway,"observationRegistry",registry);
        try {
            var calls=new AtomicInteger();ToolCallback callback=new ToolCallback(){
                public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("timedRead").description("fixture").inputSchema("{}").build();}
                public String call(String input){if(calls.incrementAndGet()==1)throw new IllegalStateException("retry");return "ok";}
            };
            gateway.wrapMcp(callback,new ToolCapabilityCard("timedRead","1",ToolCapabilityCard.RiskLevel.LOW,ToolCapabilityCard.SideEffect.NONE,true,2000,List.of("operator"),"{}","{}"));
            var events=new ArrayList<ToolGovernanceEvent>();var context=new ToolExecutionContext("owner","run",Set.of("operator"),"trace","key",events::add);
            assertEquals(ToolGovernanceResult.Status.EXECUTED,gateway.execute("timedRead","{}",context).status());
            var finished=events.stream().filter(e->e.eventType().equals("tool.finished")).findFirst().orElseThrow();
            var timing=ToolAttemptTiming.safeCopy(finished.attributes().get("timing"));assertNotNull(timing);assertEquals(2,((List<?>)timing.get("attempts")).size());
            assertEquals(true,timing.get("complete"));assertEquals(ToolGovernanceResult.Status.DEDUPLICATED,gateway.execute("timedRead","{}",context).status());
            var cached=events.stream().filter(e->e.eventType().equals("tool.deduplicated")).findFirst().orElseThrow();
            assertEquals("BYPASS",((Map<?,?>)cached.attributes().get("timing")).get("scope"));assertEquals(2,calls.get());
            var dispatches=observed.stream().filter(c->c.getName().equals("agent.tool.dispatch")).toList();
            var callbacks=observed.stream().filter(c->c.getName().equals("agent.tool.callback")).toList();
            assertEquals(2,dispatches.size());assertEquals(2,callbacks.size());
            assertEquals("FAILED",dispatches.getFirst().getLowCardinalityKeyValue("outcome").getValue());
            assertEquals("SUCCESS",dispatches.getLast().getLowCardinalityKeyValue("outcome").getValue());
            callbacks.forEach(c->assertTrue(dispatches.contains(c.getParentObservation().getContextView())));
            assertNull(registry.getCurrentObservation());
        } finally {gateway.shutdown();hitl.shutdown();}
    }
}
