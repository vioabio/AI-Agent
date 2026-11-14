package com.vio.vioaiagent.pokemon.guides;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class GuideRerankObservationTest {
    @TempDir Path directory;
    @Test void concurrentRequestsObserveTheirOwnRankingAndDoNotRecordRawQueries() throws Exception {
        var search=new LocalGuideSearch(new ObjectMapper(),new DefaultResourceLoader(),"classpath:pokemon-strategy/overview-v1.json",directory.resolve("guide.sqlite").toString());
        search.setReranking(new GuideRerankPipeline((q,c)->{
            if(q.contains("FAIL_PRIVATE"))throw new IllegalStateException("private response");
            var scores=new ArrayList<GuideReranker.Score>();for(int i=0;i<c.size();i++)scores.add(new GuideReranker.Score(i,i));return scores;
        },12,4));
        var events=new CopyOnWriteArrayList<ToolGovernanceEvent>();var workers=Executors.newFixedThreadPool(2);
        try {
            var a=workers.submit(()->run(search,"a","培养 努力值 性格",events));
            var b=workers.submit(()->run(search,"b","FAIL_PRIVATE 培养 努力值 性格",events));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertEquals(4,events.size());
            var rankings=events.stream().filter(e->e.attributes().containsKey("rerankState")).toList();assertEquals(2,rankings.size());
            for(var event:rankings){assertEquals("retrieval.finished",event.eventType());assertEquals(event.runId().equals("a")?"RERANKED":"RRF_FALLBACK",event.attributes().get("rerankState"));}
            assertFalse(new ObjectMapper().writeValueAsString(events.stream().map(ToolGovernanceEvent::attributes).toList()).contains("FAIL_PRIVATE"));
            assertNull(ToolExecutionContextHolder.peek());
        }finally{workers.shutdownNow();}
    }
    void run(LocalGuideSearch search,String run,String query,List<ToolGovernanceEvent> events){
        ToolExecutionContextHolder.set(new ToolExecutionContext("owner",run,Set.of("trainer"),"trace",null,events::add));
        try{
            var hits=search.search(query,"pokemon-scarlet-violet");assertFalse(hits.isEmpty());
            var selection=events.stream().filter(e->e.runId().equals(run)&&"FINAL_GUIDE_SELECTION".equals(e.attributes().get("retrievalStage"))).findFirst().orElseThrow();
            assertEquals(hits.stream().map(h->new LocalGuideSearch.FinalSource(h.document().id(),h.citation().chunkId(),h.citation().documentVersion())).toList(),selection.attributes().get("selectedSources"));
        }finally{ToolExecutionContextHolder.clear();}
    }
    @Test void requiredTopicReservationIsRecordedAsFinalSelectionNotProviderRanking(){
        var search=new LocalGuideSearch(new ObjectMapper(),new DefaultResourceLoader(),"classpath:pokemon-strategy/overview-v1.json",directory.resolve("topic.sqlite").toString());
        search.setReranking(new GuideRerankPipeline((q,c)->{throw new IllegalStateException("offline");},12,1));
        var events=new ArrayList<ToolGovernanceEvent>();run(search,"topic","组队 角色 属性覆盖 轮转",events);
        assertEquals(2,events.size());assertEquals("RRF_FALLBACK",events.getFirst().attributes().get("rerankState"));
        assertFalse(events.getLast().attributes().containsKey("rerankState"));
    }
}
