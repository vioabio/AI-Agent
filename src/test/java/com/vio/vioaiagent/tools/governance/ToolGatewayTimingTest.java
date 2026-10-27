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
        } finally {gateway.shutdown();hitl.shutdown();}
    }
}
