package com.vio.vioaiagent.rag.ingestion;

import com.vio.vioaiagent.rag.catalog.KnowledgeCatalog;
import com.vio.vioaiagent.rag.domain.DocumentChunk;
import com.vio.vioaiagent.rag.domain.DocumentVersion;
import com.vio.vioaiagent.rag.domain.IngestionBatch;
import com.vio.vioaiagent.rag.domain.KnowledgeDocument;
import com.vio.vioaiagent.rag.domain.KnowledgeSource;
import com.vio.vioaiagent.rag.domain.VectorIndexRelease;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M2 Embedding/索引回归：显式任务、release 隔离 ID、状态回写和失败可重试。 */
class KnowledgeIndexingServiceTest {

    @Test
    void writesReleaseScopedDocumentsAndMarksChunksReady() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        VectorStore vectorStore = mock(VectorStore.class);
        KnowledgeIngestionResult ingestion = acceptedResult();
        when(catalog.findDocument("doc-1")).thenReturn(Optional.of(document()));
        when(catalog.findIndex("index-7")).thenReturn(Optional.of(index("index-7", "knowledge-7")));

        KnowledgeIndexingResult result = new KnowledgeIndexingService(catalog, vectorStore,
                "embedding-test-v1", 3).index(ingestion, "knowledge-7", "index-7");

        assertEquals(KnowledgeIndexingResult.Status.INDEXED, result.status());
        assertEquals(1, result.indexedCount());
        assertEquals("index-7", result.vectorIndexReleaseId());
        assertEquals(64, result.indexChecksum().length());

        @SuppressWarnings("unchecked")
        var documents = org.mockito.ArgumentCaptor.forClass((Class<List<Document>>) (Class<?>) List.class);
        verify(vectorStore).add(documents.capture());
        Document vectorDocument = documents.getValue().get(0);
        assertEquals("chunk-1::index::index-7", vectorDocument.getId());
        assertEquals("chunk-1", vectorDocument.getMetadata().get("chunkId"));
        assertEquals("knowledge-7", vectorDocument.getMetadata().get("knowledgeReleaseId"));
        assertEquals("sv-1", vectorDocument.getMetadata().get("gameReleaseId"));

        @SuppressWarnings("unchecked")
        var chunks = org.mockito.ArgumentCaptor.forClass((Class<List<DocumentChunk>>) (Class<?>) List.class);
        verify(catalog).saveChunks(chunks.capture());
        assertEquals(DocumentChunk.EmbeddingStatus.READY,
                chunks.getValue().get(0).embeddingStatus());
        assertEquals("embedding-test-v1", chunks.getValue().get(0).embeddingModel());
        assertEquals(3, chunks.getValue().get(0).embeddingDimension());
    }

    @Test
    void marksAllChunksFailedWhenVectorStoreWriteFails() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(catalog.findDocument("doc-1")).thenReturn(Optional.of(document()));
        when(catalog.findIndex("index-7")).thenReturn(Optional.of(index("index-7", "knowledge-7")));
        doThrow(new IllegalStateException("embedding unavailable")).when(vectorStore).add(any());

        KnowledgeIndexingResult result = new KnowledgeIndexingService(catalog, vectorStore,
                "embedding-test-v1", 3).index(acceptedResult(), "knowledge-7", "index-7");

        assertEquals(KnowledgeIndexingResult.Status.FAILED, result.status());
        assertEquals(1, result.failedCount());
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("embedding unavailable")));
        @SuppressWarnings("unchecked")
        var chunks = org.mockito.ArgumentCaptor.forClass((Class<List<DocumentChunk>>) (Class<?>) List.class);
        verify(catalog).saveChunks(chunks.capture());
        assertEquals(DocumentChunk.EmbeddingStatus.FAILED,
                chunks.getValue().get(0).embeddingStatus());
    }

    @Test
    void failsClosedWhenProductionVectorStoreIsNotConfigured() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        KnowledgeIndexingService service = new KnowledgeIndexingService(catalog, null,
                "embedding-test-v1", 3);

        assertThrows(KnowledgeIndexingUnavailableException.class,
                () -> service.index(acceptedResult(), "knowledge-7", "index-7"));
    }

    @Test
    void rejectsUnknownIndexReleaseBeforeWritingVectors() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(catalog.findDocument("doc-1")).thenReturn(Optional.of(document()));
        when(catalog.findIndex("missing-index")).thenReturn(Optional.empty());

        KnowledgeIndexingService service = new KnowledgeIndexingService(catalog, vectorStore,
                "embedding-test-v1", 3);

        assertThrows(IllegalArgumentException.class,
                () -> service.index(acceptedResult(), "knowledge-7", "missing-index"));
        org.mockito.Mockito.verifyNoInteractions(vectorStore);
    }

    @Test
    void duplicateIngestionCanBeSafelySkippedWithoutEmbedding() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        IngestionBatch batch = new IngestionBatch("batch-duplicate", "source-1", "key",
                IngestionBatch.IngestionStatus.ACCEPTED, "parser-1", "{}", null,
                0, 0, "checksum 已存在，跳过重复版本", Instant.now(), Instant.now());
        KnowledgeIngestionResult duplicate = new KnowledgeIngestionResult(batch, null, List.of(),
                List.of("checksum 已存在"));

        KnowledgeIndexingResult result = new KnowledgeIndexingService(catalog, null,
                "embedding-test-v1", 3).index(duplicate);

        assertEquals(KnowledgeIndexingResult.Status.SKIPPED, result.status());
        assertEquals(0, result.indexedCount());
        assertFalse(result.warnings().isEmpty());
    }

    private KnowledgeIngestionResult acceptedResult() {
        DocumentVersion version = new DocumentVersion("version-1", "doc-1", "v1", "checksum-1",
                "parser-1", "schema-1", "content-1", DocumentVersion.ReviewStatus.PENDING,
                Instant.now(), null, 10, Map.of());
        DocumentChunk chunk = new DocumentChunk("chunk-1", version.id(), 0, "太晶团体战攻略",
                "chunk-hash-1", Map.of("section", "guide"), null, null,
                DocumentChunk.EmbeddingStatus.PENDING);
        IngestionBatch batch = new IngestionBatch("batch-1", "source-1", "key-1",
                IngestionBatch.IngestionStatus.ACCEPTED, "parser-1", "{}", null,
                1, 0, null, Instant.now(), Instant.now());
        return new KnowledgeIngestionResult(batch, version, List.of(chunk), List.of());
    }

    private KnowledgeDocument document() {
        return new KnowledgeDocument("doc-1", "source-1", "guide/sv", "zh-CN", "sv-1",
                "singles", "rules-1", null, null, Instant.now());
    }

    private VectorIndexRelease index(String id, String knowledgeReleaseId) {
        return new VectorIndexRelease(id, knowledgeReleaseId, "pgvector", "embedding-test-v1", 3,
                VectorIndexRelease.IndexStatus.CANDIDATE, null, Instant.now(), null);
    }
}
