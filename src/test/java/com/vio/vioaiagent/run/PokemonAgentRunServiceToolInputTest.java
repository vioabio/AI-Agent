package com.vio.vioaiagent.run;

import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.PokeTypeTool;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class PokemonAgentRunServiceToolInputTest {
    @Test void actualGatewayProofSurvivesRunEventsAndRemainsOwnerScoped() {
        var hitl=new HitlManager();var masker=new ParameterMasker();
        var gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(System.getProperty("user.dir")+"/tmp")),new CommandGuard(List.of()),masker,new AuditLogger(masker));
        var callback=Arrays.stream(org.springframework.ai.support.ToolCallbacks.from(new PokeTypeTool())).filter(t->t.getToolDefinition().name().equals("checkTypeEffectiveness")).findFirst().orElseThrow();
        gateway.wrapMcp(callback,new ToolCapabilityCard("checkTypeEffectiveness","1",ToolCapabilityCard.RiskLevel.LOW,ToolCapabilityCard.SideEffect.NONE,true,2000,List.of("trainer"),"{}","{}"));
        var events=new InMemoryEventRepository();
        RunExecutor executor=context->{var result=gateway.execute("checkTypeEffectiveness","{\"attackType\":\"火\",\"defendType\":\"草\"}",ToolExecutionContextHolder.current());assertEquals(ToolGovernanceResult.Status.EXECUTED,result.status());return result.output();};
        var service=new PokemonAgentRunService(new InMemoryRunRepository(),events,new InMemoryConversationRepository(),new InMemoryChatMessageRepository(),executor,Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());
        try {
            var run=service.createRun(new CreateRunCommand("owner",null,null,"核对火打草","sv-1","singles","rules-1","zh-CN",null,null,Duration.ofSeconds(15)));
            assertEquals(AgentEventType.RUN_COMPLETED,RunTestAwait.awaitTerminalEvent(events,run.runId()).type());
            var page=service.events(run.runId(),"owner",0);
            var start=page.events().stream().filter(e->e.type()==AgentEventType.TOOL_STARTED).findFirst().orElseThrow();
            var proof=(Map<?,?>)start.payload().get("inputEvidence");assertEquals("COMPLETE",proof.get("coverage"));
            assertEquals("火",((Map<?,?>)((Map<?,?>)proof.get("fields")).get("/attackType")).get("value"));
            var finish=page.events().stream().filter(e->e.type()==AgentEventType.TOOL_FINISHED).findFirst().orElseThrow();
            assertEquals(start.payload().get("governanceEventId"),finish.payload().get("governanceEventId"));assertEquals(proof.get("parameterDigest"),finish.payload().get("parameterDigest"));
            assertThrows(RunNotFoundException.class,()->service.events(run.runId(),"other-owner",0));
        } finally {service.shutdown();gateway.shutdown();hitl.shutdown();}
    }
}
