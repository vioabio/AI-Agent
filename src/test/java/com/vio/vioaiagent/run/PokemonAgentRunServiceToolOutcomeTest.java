package com.vio.vioaiagent.run;

import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class PokemonAgentRunServiceToolOutcomeTest {
    @Test void failedCalculationPublishesFailedTerminalWithSafeMessage() {
        var runs=new InMemoryRunRepository();var events=new InMemoryEventRepository();
        var service=new PokemonAgentRunService(runs,events,new InMemoryConversationRepository(),new InMemoryChatMessageRepository(),
                context->{throw new com.vio.vioaiagent.agent.DamageCalculationFailedException();},
                Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());
        try {
            var run=service.createRun(new CreateRunCommand("owner",null,null,"fixture","sv-1","singles","rules-1","zh-CN",null,null,Duration.ofSeconds(15)));
            var terminal=RunTestAwait.awaitTerminalEvent(events,run.runId());
            assertEquals(AgentEventType.RUN_FAILED,terminal.type());assertEquals("DAMAGE_CALCULATION_FAILED",terminal.payload().get("errorType"));
            assertTrue(terminal.payload().get("message").toString().contains("伤害计算失败"));
            assertTrue(events.findAfter(run.runId(),0).stream().noneMatch(e->e.type()==AgentEventType.RUN_COMPLETED));
        } finally {service.shutdown();}
    }
    @Test void settledCallsWithoutStartedKeepCacheRejectionAndFailedEffectStatuses() {
        var runs=new InMemoryRunRepository();var events=new InMemoryEventRepository();
        RunExecutor executor=context->{
            var sink=ToolExecutionContextHolder.current();
            sink.eventSink().accept(new ToolGovernanceEvent("tool.deduplicated",sink.runId(),"searchPokemonGuides","cached",Instant.now(),Map.of("status","SUCCEEDED","cached",true)));
            sink.eventSink().accept(new ToolGovernanceEvent("tool.deduplicated",sink.runId(),"searchPokemonGuides","legacy",Instant.now(),Map.of()));
            sink.eventSink().accept(new ToolGovernanceEvent("tool.rejected",sink.runId(),"searchPokemonGuides","denied",Instant.now(),Map.of()));
            sink.eventSink().accept(new ToolGovernanceEvent("tool.failed",sink.runId(),"searchPokemonGuides","failed",Instant.now(),Map.of()));
            sink.eventSink().accept(new ToolGovernanceEvent("tool.deduplicated",sink.runId(),"writeFile","failed-effect",Instant.now(),Map.of("effectStatus","FAILED")));
            return "fixture answer";
        };
        var service=new PokemonAgentRunService(runs,events,new InMemoryConversationRepository(),new InMemoryChatMessageRepository(),executor,
                Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());
        try {
            var run=service.createRun(new CreateRunCommand("owner",null,null,"fixture","sv-1","singles","rules-1","zh-CN",null,null,Duration.ofSeconds(15)));
            assertEquals(AgentEventType.RUN_COMPLETED,RunTestAwait.awaitTerminalEvent(events,run.runId()).type());
            var finished=events.findAfter(run.runId(),0).stream().filter(e->e.type()==AgentEventType.TOOL_FINISHED).toList();
            assertEquals(List.of("SUCCEEDED","DEDUPLICATED","REJECTED","FAILED","FAILED"),finished.stream().map(e->e.payload().get("status")).toList());
            assertEquals(true,finished.getFirst().payload().get("cached"));
            assertEquals(5,finished.stream().map(e->e.payload().get("governanceEventId")).distinct().count());
        } finally {service.shutdown();}
    }
}
