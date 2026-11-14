package com.vio.vioaiagent.pokemon.guides;

import com.vio.vioaiagent.rag.retrieval.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuideRerankPipelineTest {
    RetrievedKnowledgeChunk hit(String id) {
        var hit=mock(RetrievedKnowledgeChunk.class,RETURNS_DEEP_STUBS);
        when(hit.view().chunk().id()).thenReturn(id);
        when(hit.view().chunk().content()).thenReturn("verified "+id);
        when(hit.view().documentVersion()).thenReturn("doc-v1");
        return hit;
    }
    KnowledgeRetrievalQuery query(){return new KnowledgeRetrievalQuery("培养","zh-CN","game","single","rule",1,"data","knowledge","index");}
    @Test void expandsCandidatesPreservesScopeAndCanonicalPayload() {
        var a=hit("a");var b=hit("b");
        var pipeline=new GuideRerankPipeline((q,c)->List.of(new GuideReranker.Score(0,0),new GuideReranker.Score(1,1)),12);
        var result=pipeline.retrieve(query(),q->{assertEquals(12,q.topK());assertEquals(query().filter(),q.filter());return List.of(a,b);});
        assertEquals("RERANKED",result.state());assertSame(b,result.chunks().getFirst());
    }
    @Test void malformedScoresFallbackWithoutLeakingProviderError() {
        var a=hit("a");var b=hit("b");
        for(var scores:List.of(List.<GuideReranker.Score>of(),List.of(new GuideReranker.Score(0,Double.NaN),new GuideReranker.Score(1,1)),List.of(new GuideReranker.Score(0,1),new GuideReranker.Score(0,2)),List.of(new GuideReranker.Score(0,1),new GuideReranker.Score(9,2)))) {
            var result=new GuideRerankPipeline((q,c)->scores,12).retrieve(query(),q->List.of(a,b));
            assertEquals("RRF_FALLBACK",result.state());assertSame(a,result.chunks().getFirst());
        }
    }
    @Test void serviceFailureFallbackAndCancellationPropagates() {
        var a=hit("a");
        assertEquals("RRF_FALLBACK",new GuideRerankPipeline((q,c)->{throw new IllegalStateException("private");},12).retrieve(query(),q->List.of(a)).state());
        assertThrows(java.util.concurrent.CancellationException.class,()->new GuideRerankPipeline((q,c)->{throw new java.util.concurrent.CancellationException();},12).retrieve(query(),q->List.of(a)));
    }
    @Test void finalAnswerBudgetIsIndependentOfRecallBudget() {
        var a=hit("a");var b=hit("b");
        var q=new KnowledgeRetrievalQuery("培养","zh-CN","game","single","rule",20);
        var result=new GuideRerankPipeline((text,c)->List.of(new GuideReranker.Score(0,1),new GuideReranker.Score(1,2)),12,1).retrieve(q,expanded->{assertEquals(20,expanded.topK());return List.of(a,b);});
        assertEquals(1,result.chunks().size());assertSame(b,result.chunks().getFirst());
    }
    @Test void emptyCandidatesDoNotCallProvider() {
        assertEquals("EMPTY",new GuideRerankPipeline((q,c)->{fail("unexpected call");return null;},12).retrieve(query(),q->List.of()).state());
    }
    @Test void diagnosticsBindRecallAndRerankToCanonicalIdsWithoutCopyingText() throws Exception {
        var a=hit("a");var b=hit("b");
        var result=new GuideRerankPipeline((q,c)->List.of(new GuideReranker.Score(1,.9),new GuideReranker.Score(0,.2)),12,1).retrieve(query(),q->List.of(a,b));
        var ranks=result.diagnostics().ranks();assertEquals(2,ranks.size());
        assertEquals(2,ranks.getFirst().rerankRank());assertFalse(ranks.getFirst().selected());
        assertEquals(1,ranks.getLast().rerankRank());assertTrue(ranks.getLast().selected());assertEquals("doc-v1",ranks.getLast().documentVersion());
        assertTrue(result.diagnostics().inputTokens()>0);assertTrue(result.diagnostics().elapsedMs()>=0);
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result.diagnostics()).contains("verified a"));
    }
    @Test void aggregateInputBudgetFallsBackWithoutSendingOrTruncatingChunks() {
        var a=hit("a");when(a.view().chunk().content()).thenReturn("培养资料".repeat(3000));
        var pipeline=new GuideRerankPipeline((q,c)->{fail("Over-budget data sent to provider");return null;},12,1,256);
        var result=pipeline.retrieve(query(),q->List.of(a));assertSame(a,result.chunks().getFirst());
        assertEquals("RRF_BUDGET_FALLBACK",result.state());assertTrue(result.diagnostics().inputTokens()>256);
        assertNull(result.diagnostics().ranks().getFirst().rerankRank());
    }
    @Test void fallbacksNeverClaimRerankedPositionsOrProviderScores() {
        var result=new GuideRerankPipeline((q,c)->{throw new IllegalStateException("private provider body");},12).retrieve(query(),q->List.of(hit("a")));
        assertEquals("RRF_FALLBACK",result.diagnostics().state());assertNull(result.diagnostics().ranks().getFirst().score());
        assertNull(result.diagnostics().ranks().getFirst().rerankRank());assertTrue(result.diagnostics().ranks().getFirst().selected());
    }
}
