package com.vio.vioaiagent.pokemon.guides;

import com.vio.vioaiagent.rag.retrieval.*;
import java.util.*;
import java.util.function.Function;

/** Optional post-retrieval boundary; disabled unless explicitly installed by configuration. */
public final class GuideRerankPipeline {
    private final GuideReranker provider;
    private final int candidateK;
    private final int answerTopK;
    private final int maxInputTokens;
    private final com.vio.vioaiagent.observability.ContextTokenEstimator estimator=new com.vio.vioaiagent.observability.ContextTokenEstimator(125);
    public record Rank(String chunkId,String documentVersion,int recallRank,int lexicalRank,int semanticRank,Integer rerankRank,Double score,boolean selected) { }
    public record Diagnostics(String schemaVersion,String state,String estimator,int inputTokens,int maxInputTokens,long elapsedMs,List<Rank> ranks) {
        public Diagnostics {ranks=List.copyOf(ranks);}
    }
    public record Result(List<RetrievedKnowledgeChunk> chunks, String state,Diagnostics diagnostics) {
        public Result {chunks=List.copyOf(chunks);}
    }
    public GuideRerankPipeline(GuideReranker provider, int candidateK) {
        this(provider,candidateK,50);
    }
    public GuideRerankPipeline(GuideReranker provider,int candidateK,int answerTopK) {
        this(provider,candidateK,answerTopK,12000);
    }
    public GuideRerankPipeline(GuideReranker provider,int candidateK,int answerTopK,int maxInputTokens) {
        this.provider=Objects.requireNonNull(provider);
        if(answerTopK<1||answerTopK>50)throw new IllegalArgumentException("answerTopK must be 1..50");
        this.answerTopK=answerTopK;
        if(candidateK<1||candidateK>50)throw new IllegalArgumentException("candidateK must be 1..50");
        this.candidateK=candidateK;
        if(maxInputTokens<256||maxInputTokens>32768)throw new IllegalArgumentException("maxInputTokens must be 256..32768");
        this.maxInputTokens=maxInputTokens;
    }
    public Result retrieve(KnowledgeRetrievalQuery query, Function<KnowledgeRetrievalQuery,List<RetrievedKnowledgeChunk>> retrieve) {
        long started=System.nanoTime();
        var expanded=new KnowledgeRetrievalQuery(query.text(),query.language(),query.gameReleaseId(),query.battleFormatId(),query.rulesetVersionId(),Math.max(candidateK,query.topK()),query.dataReleaseId(),query.knowledgeReleaseId(),query.indexReleaseId());
        var unique=new LinkedHashMap<String,RetrievedKnowledgeChunk>();
        for(var hit:retrieve.apply(expanded)) {
            unique.putIfAbsent(hit.view().chunk().id(),hit);
            if(unique.size()>=expanded.topK())break;
        }
        var hits=List.copyOf(unique.values());
        var fallback=hits.stream().limit(Math.min(query.topK(),answerTopK)).toList();
        if(hits.isEmpty())return result(fallback,"EMPTY",hits,List.of(),0,started);
        int tokens=32;
        try {
            tokens=Math.addExact(tokens,estimator.text(query.text()));
            for(var hit:hits)tokens=Math.addExact(tokens,Math.addExact(16,estimator.text(hit.view().chunk().content())));
        }catch(ArithmeticException|com.vio.vioaiagent.observability.ContextBudgetExceededException oversized){tokens=maxInputTokens+1;}
        if(tokens>maxInputTokens)return result(fallback,"RRF_BUDGET_FALLBACK",hits,List.of(),tokens,started);
        try {
            var candidates=hits.stream().map(h->new GuideReranker.Candidate(h.view().chunk().id(),h.view().chunk().content())).toList();
            var scores=provider.rank(query.text(),candidates);
            if(scores==null||scores.size()!=hits.size())throw new IllegalArgumentException("Incomplete scores");
            var seen=new HashSet<Integer>();
            for(var score:scores)if(score==null||score.index()<0||score.index()>=hits.size()||!Double.isFinite(score.score())||!seen.add(score.index()))throw new IllegalArgumentException("Invalid score");
            var sorted=new ArrayList<>(scores);
            sorted.sort(Comparator.comparingDouble(GuideReranker.Score::score).reversed().thenComparingInt(GuideReranker.Score::index));
            return result(sorted.stream().limit(Math.min(query.topK(),answerTopK)).map(s->hits.get(s.index())).toList(),"RERANKED",hits,sorted,tokens,started);
        }catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
        catch(RuntimeException unavailable){return result(fallback,"RRF_FALLBACK",hits,List.of(),tokens,started);}
    }
    private Result result(List<RetrievedKnowledgeChunk> selected,String state,List<RetrievedKnowledgeChunk> recalled,List<GuideReranker.Score> sorted,int tokens,long started){
        var ranks=new ArrayList<Rank>();var positions=new HashMap<Integer,Integer>();var scores=new HashMap<Integer,Double>();
        for(int i=0;i<sorted.size();i++){positions.put(sorted.get(i).index(),i+1);scores.put(sorted.get(i).index(),sorted.get(i).score());}
        var ids=selected.stream().map(hit->hit.view().chunk().id()).collect(java.util.stream.Collectors.toSet());
        for(int i=0;i<recalled.size();i++){
            var hit=recalled.get(i);ranks.add(new Rank(hit.view().chunk().id(),hit.view().documentVersion(),i+1,hit.lexicalRank(),hit.semanticRank(),positions.get(i),scores.get(i),ids.contains(hit.view().chunk().id())));
        }
        return new Result(selected,state,new Diagnostics("guide-rerank-observation-v1",state,com.vio.vioaiagent.observability.ContextTokenEstimator.ID,tokens,maxInputTokens,Math.max(0,(System.nanoTime()-started)/1_000_000),ranks));
    }
}
