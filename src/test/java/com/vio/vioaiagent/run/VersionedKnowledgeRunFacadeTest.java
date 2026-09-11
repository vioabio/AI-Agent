package com.vio.vioaiagent.run;

import com.vio.vioaiagent.rag.catalog.KnowledgeCatalog;
import com.vio.vioaiagent.rag.contract.AnswerContract;
import com.vio.vioaiagent.rag.contract.EvidenceAssembler;
import com.vio.vioaiagent.rag.domain.DocumentChunk;
import com.vio.vioaiagent.rag.retrieval.KnowledgeRetriever;
import com.vio.vioaiagent.rag.retrieval.RetrievedKnowledgeChunk;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionedKnowledgeRunFacadeTest {

    @Test
    void shouldClarifyBeforeRetrievalWhenGameVersionIsMissing() {
        AtomicBoolean retrievalCalled = new AtomicBoolean();
        KnowledgeRetriever retriever = query -> {
            retrievalCalled.set(true);
            return List.of();
        };
        VersionedKnowledgeRunFacade facade = new VersionedKnowledgeRunFacade(retriever, new EvidenceAssembler());
        AtomicBoolean modelCalled = new AtomicBoolean();

        AnswerContract contract = facade.answer(run(null), prompt -> {
            modelCalled.set(true);
            return "不应生成";
        });

        assertEquals(AnswerContract.AnswerStatus.CLARIFICATION_REQUIRED, contract.status());
        assertFalse(retrievalCalled.get());
        assertFalse(modelCalled.get());
    }

    @Test
    void shouldRefuseWithoutEvidenceAndGroundAnswerWithCitations() {
        VersionedKnowledgeRunFacade emptyFacade = new VersionedKnowledgeRunFacade(
                query -> List.of(), new EvidenceAssembler());
        AnswerContract refused = emptyFacade.answer(run("sv-1"), prompt -> "不应生成");
        assertEquals(AnswerContract.AnswerStatus.REFUSED, refused.status());
        assertTrue(refused.citations().isEmpty());

        RetrievedKnowledgeChunk evidence = evidence("chunk-1", "sv-1");
        VersionedKnowledgeRunFacade groundedFacade = new VersionedKnowledgeRunFacade(
                query -> List.of(evidence), new EvidenceAssembler());
        AnswerContract answered = groundedFacade.answer(run("sv-1"), prompt -> {
            assertTrue(prompt.contains("game_release_id=sv-1"));
            assertTrue(prompt.contains("太晶团体战"));
            return "依据资料，建议先确认队伍等级。";
        });

        assertEquals(AnswerContract.AnswerStatus.ANSWERED, answered.status());
        assertEquals("sv-1", answered.applicableGameVersion());
        assertEquals(1, answered.citations().size());
        assertEquals("chunk-1", answered.citations().get(0).chunkId());
    }

    @Test
    void shouldDropCrossVersionEvidenceBeforeCallingModel() {
        VersionedKnowledgeRunFacade facade = new VersionedKnowledgeRunFacade(
                query -> List.of(evidence("chunk-cross-version", "sv-2")), new EvidenceAssembler());
        AtomicBoolean modelCalled = new AtomicBoolean();

        AnswerContract contract = facade.answer(run("sv-1"), prompt -> {
            modelCalled.set(true);
            return "不应使用跨版本资料";
        });

        assertEquals(AnswerContract.AnswerStatus.REFUSED, contract.status());
        assertFalse(modelCalled.get());
    }

    @Test
    void shouldFailClosedWhenRetrieverThrows() {
        VersionedKnowledgeRunFacade facade = new VersionedKnowledgeRunFacade(
                query -> { throw new IllegalStateException("vector store unavailable"); },
                new EvidenceAssembler());

        AnswerContract contract = facade.answer(run("sv-1"), prompt -> "不应在无证据时生成");

        assertEquals(AnswerContract.AnswerStatus.REFUSED, contract.status());
        assertTrue(contract.citations().isEmpty());
    }

    @Test
    void shouldNotCallModelForInvalidEvidence() {
        VersionedKnowledgeRunFacade facade = new VersionedKnowledgeRunFacade(
                query -> List.of((RetrievedKnowledgeChunk) null),
                new EvidenceAssembler());
        AtomicBoolean modelCalled = new AtomicBoolean();

        AnswerContract contract = facade.answer(run("sv-1"), prompt -> {
            modelCalled.set(true);
            return "不应生成";
        });

        assertEquals(AnswerContract.AnswerStatus.REFUSED, contract.status());
        assertFalse(modelCalled.get());
    }

    @Test
    void replayableGeneratorUsesModelInvocationCacheButPlainGeneratorDoesNotOptIn() {
        VersionedKnowledgeRunFacade facade = new VersionedKnowledgeRunFacade(
                query -> List.of(evidence("chunk-cache", "sv-1")), new EvidenceAssembler());
        com.vio.vioaiagent.config.ModelInvocationCacheProperties properties =
                new com.vio.vioaiagent.config.ModelInvocationCacheProperties();
        properties.setEnabled(true);
        properties.setModelVersion("model-test-v1");
        properties.setPromptVersion("prompt-test-v1");
        ModelInvocationCacheService cache = new ModelInvocationCacheService(
                new InMemoryDurableRunStateRepository(), new ModelInvocationCacheCodec() {
            @Override public String encode(String response) { return "ENC:" + response; }
            @Override public String decode(String encoded) { return encoded.substring(4); }
        }, properties, java.time.Clock.systemUTC());
        facade.setModelInvocationCache(cache);
        AtomicInteger calls = new AtomicInteger();

        ReplayableAnswerGenerator replayable = prompt -> {
            calls.incrementAndGet();
            return "缓存回答";
        };
        assertEquals(AnswerContract.AnswerStatus.ANSWERED, facade.answerReplayable(run("sv-1"), replayable).status());
        assertEquals(AnswerContract.AnswerStatus.ANSWERED, facade.answerReplayable(run("sv-1"), replayable).status());
        assertEquals(1, calls.get());

        AtomicInteger plainCalls = new AtomicInteger();
        facade.answer(run("sv-1"), prompt -> {
            plainCalls.incrementAndGet();
            return "普通路径";
        });
        facade.answer(run("sv-1"), prompt -> {
            plainCalls.incrementAndGet();
            return "普通路径";
        });
        assertEquals(2, plainCalls.get(), "未显式标记的工具/Agent 生成器不得进入缓存");
    }

    @Test
    void defaultRunExecutorUsesVersionedFacadeWhenV1IsEnabled() {
        VersionedKnowledgeRunFacade facade = new VersionedKnowledgeRunFacade(
                query -> List.of(), new EvidenceAssembler());
        DefaultRunExecutor executor = new DefaultRunExecutor(null, new org.springframework.ai.tool.ToolCallback[0],
                null, null, null, facade, true);

        String answer = executor.execute(new AgentRunContext(run("sv-1")));

        assertTrue(answer.contains("没有足够依据"));
    }

    @Test
    void missingRunReleaseKeysCannotBeFilledFromActivePointer() {
        var catalog = new com.vio.vioaiagent.rag.catalog.InMemoryKnowledgeCatalog();
        catalog.saveRelease(new com.vio.vioaiagent.rag.domain.KnowledgeRelease(
                "knowledge-v1", "2026.09.03", com.vio.vioaiagent.rag.domain.KnowledgeRelease.ReleaseStatus.ACTIVE,
                "parser-v1", "embedding-v1", "data-v1", 1, 1.0, 1.0, 1.0,
                Instant.parse("2026-09-03T00:00:00Z"), Instant.parse("2026-09-03T00:00:00Z")));
        catalog.saveIndex(new com.vio.vioaiagent.rag.domain.VectorIndexRelease(
                "index-v1", "knowledge-v1", "pgvector", "embedding-v1", 1536,
                com.vio.vioaiagent.rag.domain.VectorIndexRelease.IndexStatus.ACTIVE, "sha256:index-v1",
                Instant.parse("2026-09-03T00:00:00Z"), Instant.parse("2026-09-03T00:00:00Z")));
        VersionedKnowledgeRunFacade facade = new VersionedKnowledgeRunFacade(
                query -> List.of(), new EvidenceAssembler());
        facade.setKnowledgeCatalog(catalog);

        AnswerLineage lineage = facade.answerLineage(run("sv-1"),
                AnswerContract.answered("answer", "sv-1", "casual-singles", "rules-1", List.of(), List.of(), "trace-test"));

        assertFalse(lineage.hasReleaseLineage(), "旧 Run 不能借 active 指针伪造完整血缘");
    }

    private AgentRun run(String gameReleaseId) {
        return AgentRun.create("run-test", "session-test", "conversation-test", "user-test",
                "太晶团体战怎么准备？", gameReleaseId, "casual-singles", "rules-1", "zh-CN",
                "trace-test", null, Instant.now().plusSeconds(60));
    }

    private RetrievedKnowledgeChunk evidence(String chunkId, String gameReleaseId) {
        DocumentChunk chunk = new DocumentChunk(chunkId, "version-1", 0, "太晶团体战攻略：先确认队伍等级。",
                chunkId, Map.of(), null, null, DocumentChunk.EmbeddingStatus.READY);
        KnowledgeCatalog.KnowledgeChunkView view = new KnowledgeCatalog.KnowledgeChunkView(
                chunk, "doc-1", "v1", "官方攻略", "https://example.invalid/source", "zh-CN",
                gameReleaseId, "casual-singles", "rules-1");
        return new RetrievedKnowledgeChunk(view, 1, 1, 1.0 / 61 + 1.0 / 61);
    }
}
