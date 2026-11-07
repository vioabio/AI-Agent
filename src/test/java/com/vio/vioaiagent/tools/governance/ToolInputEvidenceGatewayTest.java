package com.vio.vioaiagent.tools.governance;

import com.vio.vioaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ToolInputEvidenceGatewayTest {
    @Test void actualArgumentsRetriesCacheAndFailuresKeepTheSameBoundedProof() {
        var hitl=new HitlManager();var masker=new ParameterMasker();var calls=new AtomicInteger();
        var gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(System.getProperty("user.dir")+"/tmp")),new CommandGuard(List.of()),masker,new AuditLogger(masker));
        try {
            ToolCallback callback=new ToolCallback(){
                public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("checkTypeEffectiveness").description("fixture").inputSchema("{}").build();}
                public String call(String input){assertTrue(input.contains("火"));if(calls.incrementAndGet()==1)throw new IllegalStateException("retry");return "2x";}
            };
            gateway.wrapMcp(callback,new ToolCapabilityCard("checkTypeEffectiveness","1",ToolCapabilityCard.RiskLevel.LOW,ToolCapabilityCard.SideEffect.NONE,true,2000,List.of("trainer"),"{}","{}"));
            var events=new ArrayList<ToolGovernanceEvent>();var context=new ToolExecutionContext("owner","run",Set.of("trainer"),"trace","key",events::add);
            assertEquals(ToolGovernanceResult.Status.EXECUTED,gateway.execute("checkTypeEffectiveness","{\"attackType\":\"火\",\"defendType\":\"草\"}",context).status());
            var started=events.stream().filter(e->e.eventType().equals("tool.started")).toList();assertEquals(2,started.size());
            var proof=(Map<?,?>)started.getFirst().attributes().get("inputEvidence");assertEquals(proof,started.getLast().attributes().get("inputEvidence"));
            var finished=events.stream().filter(e->e.eventType().equals("tool.finished")).findFirst().orElseThrow();assertEquals(finished.attributes().get("parameterDigest"),proof.get("parameterDigest"));
            assertEquals(ToolGovernanceResult.Status.DEDUPLICATED,gateway.execute("checkTypeEffectiveness","{\"defendType\":\"草\",\"attackType\":\"火\"}",context).status());
            assertEquals(proof,events.getLast().attributes().get("inputEvidence"));assertEquals(2,calls.get());
            int before=events.size();assertEquals(ToolGovernanceResult.Status.REJECTED,gateway.execute("checkTypeEffectiveness","not-json",context).status());
            assertFalse(events.subList(before,events.size()).stream().anyMatch(e->e.attributes().containsKey("inputEvidence")));
        } finally {gateway.shutdown();hitl.shutdown();}
    }
}
