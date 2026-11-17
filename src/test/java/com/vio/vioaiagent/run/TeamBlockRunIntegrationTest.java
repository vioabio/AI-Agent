package com.vio.vioaiagent.run;

import com.vio.vioaiagent.agent.TeamSuggestionAnswerBlockTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class TeamBlockRunIntegrationTest {
    @Test void referenceCardUsesRunFenceFeatureFlagAndFrozenVersion(){
        var block=TeamSuggestionAnswerBlockTest.block();
        for(String mode:new String[]{"enabled","disabled","wrong-version"}){
            var events=new InMemoryEventRepository();RunExecutor executor=context->{context.mutations().publishTeam(block);return "正文保留";};
            var service=new PokemonAgentRunService(new InMemoryRunRepository(),events,new InMemoryConversationRepository(),new InMemoryChatMessageRepository(),executor,Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());
            ReflectionTestUtils.setField(service,"answerBlocksEnabled",!mode.equals("disabled"));
            try{
                var run=service.createRun(new CreateRunCommand("u","s",null,"分析队伍","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN",null,mode.equals("wrong-version")?"wrong":"pokeapi-reference-v1",null,null,"v1.1","trace",null,Duration.ofSeconds(10)));
                assertEquals(AgentEventType.RUN_COMPLETED,RunTestAwait.awaitTerminalEvent(events,run.runId()).type());
                var cards=events.findAfter(run.runId(),0).stream().filter(e->e.type()==AgentEventType.TEAM_MATRIX).toList();assertEquals(mode.equals("enabled")?1:0,cards.size());
                if(!cards.isEmpty()){assertFalse(cards.getFirst().payload().containsKey("knowledgeReleaseId"));assertEquals(block,cards.getFirst().payload().get("answerBlock"));}
                assertTrue(events.findAfter(run.runId(),0).stream().noneMatch(e->e.type()==AgentEventType.APPROVAL_REQUIRED));
            }finally{service.shutdown();}
        }
    }
}
