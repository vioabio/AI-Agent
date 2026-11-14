package com.vio.vioaiagent.run;

import com.vio.vioaiagent.agent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class DamageBlockRunIntegrationTest {
    @Test void enabledBlockIsLeaseBoundVersionCheckedAndWorksWithoutKnowledgeLineage(){
        var request=DamageAnswerBlockTest.request("water-gun","Harsh Sunshine");var block=DamageAnswerBlock.from(request,DamageAnswerBlockTest.calculate(request));
        for(String release:new String[]{"pokeapi-reference-v1","wrong"}){
            var events=new InMemoryEventRepository();RunExecutor executor=context->{context.mutations().publishDamage(block);return "正文保留";};
            var service=new PokemonAgentRunService(new InMemoryRunRepository(),events,new InMemoryConversationRepository(),new InMemoryChatMessageRepository(),executor,Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());
            ReflectionTestUtils.setField(service,"answerBlocksEnabled",true);
            try{
                var run=service.createRun(new CreateRunCommand("u","s",null,"计算","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN",null,release,null,null,"v1.1","trace",null,Duration.ofSeconds(10)));
                assertEquals(AgentEventType.RUN_COMPLETED,RunTestAwait.awaitTerminalEvent(events,run.runId()).type());
                var cards=events.findAfter(run.runId(),0).stream().filter(e->e.type()==AgentEventType.CALCULATION_CARD).toList();assertEquals(release.equals("wrong")?0:1,cards.size());
                if(!cards.isEmpty()){assertFalse(cards.getFirst().payload().containsKey("knowledgeReleaseId"));assertEquals(block,cards.getFirst().payload().get("answerBlock"));}
            }finally{service.shutdown();}
        }
    }
}
