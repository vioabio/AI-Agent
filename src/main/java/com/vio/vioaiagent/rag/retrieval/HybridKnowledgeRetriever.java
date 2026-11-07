package com.vio.vioaiagent.rag.retrieval;

import com.vio.vioaiagent.rag.catalog.KnowledgeCatalog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL FTS + PGVector 的统一检索编排。两路排名使用 RRF 融合，避免直接比较不同量纲的分数。
 */
@Service
public class HybridKnowledgeRetriever implements KnowledgeRetriever {

    private static final int RRF_K = 60;
    private final KnowledgeCatalog catalog;

    public HybridKnowledgeRetriever(KnowledgeCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<RetrievedKnowledgeChunk> retrieve(KnowledgeRetrievalQuery query) {
        int candidateLimit = Math.max(query.topK() * 2, 10);
        List<KnowledgeCatalog.KnowledgeChunkView> lexical =
                catalog.searchLexical(query.text(), query.filter(), candidateLimit);
        List<KnowledgeCatalog.KnowledgeChunkView> semantic =
                catalog.searchSemantic(query.text(), query.filter(), candidateLimit);
        return fuse(lexical, semantic, query.topK());
    }

    static List<RetrievedKnowledgeChunk> fuse(List<KnowledgeCatalog.KnowledgeChunkView> lexical,
                                              List<KnowledgeCatalog.KnowledgeChunkView> semantic,
                                              int topK) {
        Map<String, Ranked> byChunk = new LinkedHashMap<>();
        index(byChunk, lexical, true);
        index(byChunk, semantic, false);
        List<RetrievedKnowledgeChunk> result = new ArrayList<>();
        for (Ranked ranked : byChunk.values()) {
            result.add(new RetrievedKnowledgeChunk(ranked.view(), ranked.lexicalRank(), ranked.semanticRank(),
                    ranked.score()));
        }
        result.sort(Comparator.comparingDouble(RetrievedKnowledgeChunk::retrievalScore).reversed()
                .thenComparing(chunk -> chunk.view().chunk().id()));
        return result.stream().limit(Math.max(0, topK)).toList();
    }

    private static void index(Map<String, Ranked> target,
                              List<KnowledgeCatalog.KnowledgeChunkView> values, boolean lexical) {
        if (values == null) {
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            KnowledgeCatalog.KnowledgeChunkView view = values.get(i);
            if (view == null || view.chunk() == null) {
                continue;
            }
            int rank = i + 1;
            Ranked ranked = target.computeIfAbsent(view.chunk().id(), ignored -> new Ranked(view));
            if (lexical && ranked.lexicalRank() == 0) {
                ranked.setLexicalRank(rank);
            }
            if (!lexical && ranked.semanticRank() == 0) {
                ranked.setSemanticRank(rank);
            }
        }
    }

    private static final class Ranked {
        private final KnowledgeCatalog.KnowledgeChunkView view;
        private int lexicalRank;
        private int semanticRank;

        private Ranked(KnowledgeCatalog.KnowledgeChunkView view) {
            this.view = view;
        }

        KnowledgeCatalog.KnowledgeChunkView view() { return view; }
        int lexicalRank() { return lexicalRank; }
        int semanticRank() { return semanticRank; }
        void setLexicalRank(int rank) { lexicalRank = rank; }
        void setSemanticRank(int rank) { semanticRank = rank; }
        double score() {
            double value = 0;
            if (lexicalRank > 0) value += 1.0 / (RRF_K + lexicalRank);
            if (semanticRank > 0) value += 1.0 / (RRF_K + semanticRank);
            return value;
        }
    }
}

