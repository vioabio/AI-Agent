package com.vio.vioaiagent.run;

import com.vio.vioaiagent.agent.PokemonAnswerBlock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PokemonBlockRunIntegrationTest {
    static <T> ObjectProvider<T> provider(Class<T> type,T value){var f=new StaticListableBeanFactory();if(value!=null)f.addBean("test",value);return f.getBeanProvider(type);}
    PokemonAnswerBlock block(String version){return new PokemonAnswerBlock("answer-block-v1","pokemon-39","pokemon",39,"胖丁",List.of("一般","妖精"),List.of(115,45,20,45,25,20),"sv-1",version,"sha256:"+"a".repeat(64));}
    void verify(boolean enabled,String version,int expected) {
        verify(enabled,version,expected,"k","i");
    }
    void verify(boolean enabled,String version,int expected,String knowledge,String index) {
        var events=new InMemoryEventRepository();
        RunExecutor executor=c->{c.mutations().publishFact(block(version));return "正文保留";};
        var service=new PokemonAgentRunService(new InMemoryRunRepository(),events,new InMemoryConversationRepository(),new InMemoryChatMessageRepository(),
            provider(RunExecutor.class,executor),provider(com.vio.vioaiagent.observability.AgentMetrics.class,null),provider(com.vio.vioaiagent.security.HitlManager.class,null),
            provider(RunOutboxRepository.class,null),provider(CheckpointRepository.class,null),provider(com.vio.vioaiagent.config.DurableRunProperties.class,null),provider(com.vio.vioaiagent.config.RunAdmissionProperties.class,null));
        ReflectionTestUtils.setField(service,"answerBlocksEnabled",enabled);
        try{
            var run=service.createRun(new CreateRunCommand("u","s",null,"资料","sv-1","singles","rules-1","zh-CN","dlc","data-1",knowledge,index,"v1.1","trace",null,Duration.ofSeconds(10)));
            assertEquals(AgentEventType.RUN_COMPLETED,RunTestAwait.awaitTerminalEvent(events,run.runId()).type());
            var cards=events.findAfter(run.runId(),0).stream().filter(e->e.type()==AgentEventType.FACT_CARD).toList();
            assertEquals(expected,cards.size());
            if(expected>0)assertEquals(List.of("一般","妖精"),cards.getFirst().payload().get("types"));
            if(expected>0&&knowledge==null)assertFalse(cards.getFirst().payload().containsKey("knowledgeReleaseId"));
            if(expected>0&&index==null)assertFalse(cards.getFirst().payload().containsKey("indexReleaseId"));
        }finally{service.shutdown();}
    }
    @Test void enabledBlockTravelsThroughRunEventBoundary(){verify(true,"data-1",1);}
    @Test void disabledFlagPreservesLegacyAnswer(){verify(false,"data-1",0);}
    @Test void versionMismatchCannotPublishFact(){verify(true,"other",0);}
    @Test void publicFactsDoNotInventKnowledgeOrIndexLineage(){verify(true,"data-1",1,null,null);}
}
