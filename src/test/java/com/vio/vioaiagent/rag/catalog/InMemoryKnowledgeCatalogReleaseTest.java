package com.vio.vioaiagent.rag.catalog;

import com.vio.vioaiagent.rag.domain.DocumentChunk;
import com.vio.vioaiagent.rag.domain.DocumentVersion;
import com.vio.vioaiagent.rag.domain.KnowledgeDocument;
import com.vio.vioaiagent.rag.domain.KnowledgeRelease;
import com.vio.vioaiagent.rag.domain.KnowledgeSource;
import com.vio.vioaiagent.rag.domain.VectorIndexRelease;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** M2 active_index 回归：只有绑定到活动索引的已审核版本才可检索。 */
class InMemoryKnowledgeCatalogReleaseTest {

    @Test
    void activePointerFiltersUnboundChunksAndPromotesReleaseAtomically() {
        InMemoryKnowledgeCatalog catalog = new InMemoryKnowledgeCatalog();
        catalog.saveSource(new KnowledgeSource("source-1", "https://example.invalid", "官方",
                "CC-BY-4.0", KnowledgeSource.TrustLevel.OFFICIAL, "operator", Instant.now(), Instant.now()));
        catalog.saveDocument(new KnowledgeDocument("doc-1", "source-1", "guide", "zh-CN", "sv-1",
                "singles", "rules-1", null, null, Instant.now()));
        DocumentVersion version = new DocumentVersion("version-1", "doc-1", "v1", "checksum-1",
                "parser-1", "schema-1", "content-1", DocumentVersion.ReviewStatus.APPROVED,
                Instant.now(), Instant.now(), 10, Map.of());
        catalog.saveVersion(version);
        DocumentChunk chunk = new DocumentChunk("chunk-1", version.id(), 0, "太晶团体战攻略",
                "hash-1", Map.of(), "embedding-v1", 3, DocumentChunk.EmbeddingStatus.READY);
        catalog.saveChunks(List.of(chunk));

        KnowledgeRelease release = new KnowledgeRelease("knowledge-1", "2026.09.1",
                KnowledgeRelease.ReleaseStatus.CANDIDATE, "parser-1", "embedding-v1", "data-1",
                10, 0.99, 0.99, 0.99, Instant.now(), null);
        VectorIndexRelease index = new VectorIndexRelease("index-1", release.id(), "pgvector",
                "embedding-v1", 3, VectorIndexRelease.IndexStatus.CANDIDATE, "index-hash", Instant.now(), null);
        catalog.saveRelease(release);
        catalog.saveIndex(index);
        catalog.bindDocumentVersionToKnowledgeRelease(release.id(), version.id());
        catalog.bindChunksToIndexRelease(index.id(), List.of(chunk.id()));
        catalog.activateIndex(index.id());

        assertEquals(KnowledgeRelease.ReleaseStatus.ACTIVE,
                catalog.findRelease(release.id()).orElseThrow().status());
        assertEquals(VectorIndexRelease.IndexStatus.ACTIVE,
                catalog.findIndex(index.id()).orElseThrow().status());
        List<KnowledgeCatalog.KnowledgeChunkView> result = catalog.searchLexical("太晶团体战攻略",
                new KnowledgeCatalog.KnowledgeFilter("zh-CN", "sv-1", "singles", "rules-1"), 5);
        assertEquals(1, result.size());
        assertEquals("chunk-1", result.get(0).chunk().id());

        List<KnowledgeCatalog.KnowledgeChunkView> wrongRelease = catalog.searchLexical("太晶团体战攻略",
                new KnowledgeCatalog.KnowledgeFilter("zh-CN", "sv-1", "singles", "rules-1",
                        "data-other", "knowledge-1", "index-1"), 5);
        assertEquals(0, wrongRelease.size(), "data release mismatch must fail closed");
        List<KnowledgeCatalog.KnowledgeChunkView> exactRelease = catalog.searchLexical("太晶团体战攻略",
                new KnowledgeCatalog.KnowledgeFilter("zh-CN", "sv-1", "singles", "rules-1",
                        "data-1", "knowledge-1", "index-1"), 5);
        assertEquals(1, exactRelease.size());
    }

    @Test
    void failedIndexCannotBecomeActive() {
        InMemoryKnowledgeCatalog catalog = new InMemoryKnowledgeCatalog();
        KnowledgeRelease release = new KnowledgeRelease("knowledge-2", "2026.09.2",
                KnowledgeRelease.ReleaseStatus.CANDIDATE, "parser-1", "embedding-v1", "data-1",
                0, null, null, null, Instant.now(), null);
        catalog.saveRelease(release);
        catalog.saveIndex(new VectorIndexRelease("index-failed", release.id(), "pgvector", "embedding-v1", 3,
                VectorIndexRelease.IndexStatus.FAILED, "hash", Instant.now(), null));
        assertThrows(IllegalStateException.class, () -> catalog.activateIndex("index-failed"));
    }

    @Test
    void refusesChunkFromAnotherKnowledgeRelease() {
        InMemoryKnowledgeCatalog catalog = new InMemoryKnowledgeCatalog();
        catalog.saveSource(new KnowledgeSource("source-lineage", "https://example.invalid", "官方",
                "CC-BY-4.0", KnowledgeSource.TrustLevel.OFFICIAL, "operator", Instant.now(), Instant.now()));
        catalog.saveDocument(new KnowledgeDocument("doc-lineage", "source-lineage", "guide", "zh-CN",
                "sv-1", "singles", "rules-1", null, null, Instant.now()));
        DocumentVersion version = new DocumentVersion("version-lineage", "doc-lineage", "v1", "checksum-lineage",
                "parser-1", "schema-1", "content-1", DocumentVersion.ReviewStatus.APPROVED,
                Instant.now(), null, 10, Map.of());
        catalog.saveVersion(version);
        catalog.saveChunks(List.of(new DocumentChunk("chunk-lineage", version.id(), 0, "攻略",
                "hash-lineage", Map.of(), "embedding-v1", 3, DocumentChunk.EmbeddingStatus.READY)));
        KnowledgeRelease release = new KnowledgeRelease("knowledge-lineage", "2026.09.lineage",
                KnowledgeRelease.ReleaseStatus.CANDIDATE, "parser-1", "embedding-v1", "data-1",
                0, null, null, null, Instant.now(), null);
        catalog.saveRelease(release);
        VectorIndexRelease index = new VectorIndexRelease("index-lineage", release.id(), "pgvector",
                "embedding-v1", 3, VectorIndexRelease.IndexStatus.CANDIDATE, null, Instant.now(), null);
        catalog.saveIndex(index);

        assertThrows(IllegalArgumentException.class,
                () -> catalog.bindChunksToIndexRelease(index.id(), List.of("chunk-lineage")));
    }

    @Test
    void excludesDocumentsOutsideTheirEffectiveWindow() {
        InMemoryKnowledgeCatalog catalog = new InMemoryKnowledgeCatalog();
        Instant now = Instant.now();
        catalog.saveSource(new KnowledgeSource("source-effective", "https://example.invalid", "官方",
                "CC-BY-4.0", KnowledgeSource.TrustLevel.OFFICIAL, "operator", now, now));

        KnowledgeDocument activeDocument = new KnowledgeDocument("doc-effective-active", "source-effective",
                "guide/active", "zh-CN", "sv-1", "singles", "rules-1",
                now.minusSeconds(60), now.plusSeconds(3600), now);
        KnowledgeDocument expiredDocument = new KnowledgeDocument("doc-effective-expired", "source-effective",
                "guide/expired", "zh-CN", "sv-1", "singles", "rules-1",
                now.minusSeconds(3600), now.minusSeconds(60), now);
        KnowledgeDocument futureDocument = new KnowledgeDocument("doc-effective-future", "source-effective",
                "guide/future", "zh-CN", "sv-1", "singles", "rules-1",
                now.plusSeconds(3600), null, now);
        catalog.saveDocument(activeDocument);
        catalog.saveDocument(expiredDocument);
        catalog.saveDocument(futureDocument);

        DocumentVersion activeVersion = approvedVersion("version-effective-active", activeDocument.id(), now);
        DocumentVersion expiredVersion = approvedVersion("version-effective-expired", expiredDocument.id(), now);
        DocumentVersion futureVersion = approvedVersion("version-effective-future", futureDocument.id(), now);
        catalog.saveVersion(activeVersion);
        catalog.saveVersion(expiredVersion);
        catalog.saveVersion(futureVersion);
        catalog.saveChunks(List.of(
                effectiveChunk("chunk-effective-active", activeVersion.id()),
                effectiveChunk("chunk-effective-expired", expiredVersion.id()),
                effectiveChunk("chunk-effective-future", futureVersion.id())));

        KnowledgeRelease release = new KnowledgeRelease("knowledge-effective", "2026.09.effective",
                KnowledgeRelease.ReleaseStatus.CANDIDATE, "parser-1", "embedding-v1", "data-1",
                0, null, null, null, now, null);
        VectorIndexRelease index = new VectorIndexRelease("index-effective", release.id(), "pgvector",
                "embedding-v1", 3, VectorIndexRelease.IndexStatus.CANDIDATE, null, now, null);
        catalog.saveRelease(release);
        catalog.saveIndex(index);
        catalog.bindDocumentVersionToKnowledgeRelease(release.id(), activeVersion.id());
        catalog.bindDocumentVersionToKnowledgeRelease(release.id(), expiredVersion.id());
        catalog.bindDocumentVersionToKnowledgeRelease(release.id(), futureVersion.id());
        catalog.bindChunksToIndexRelease(index.id(), List.of(
                "chunk-effective-active", "chunk-effective-expired", "chunk-effective-future"));
        catalog.activateIndex(index.id());

        List<KnowledgeCatalog.KnowledgeChunkView> result = catalog.searchLexical("攻略",
                new KnowledgeCatalog.KnowledgeFilter("zh-CN", "sv-1", "singles", "rules-1"), 10);
        assertEquals(List.of("chunk-effective-active"),
                result.stream().map(view -> view.chunk().id()).toList());
    }

    @Test
    void excludesPendingAndFailedChunksFromActiveRetrieval() {
        InMemoryKnowledgeCatalog catalog = new InMemoryKnowledgeCatalog();
        Instant now = Instant.now();
        catalog.saveSource(new KnowledgeSource("source-embedding-status", "https://example.invalid", "官方",
                "CC-BY-4.0", KnowledgeSource.TrustLevel.OFFICIAL, "operator", now, now));
        KnowledgeDocument document = new KnowledgeDocument("doc-embedding-status", "source-embedding-status",
                "guide/status", "zh-CN", "sv-1", "singles", "rules-1", null, null, now);
        catalog.saveDocument(document);
        DocumentVersion version = approvedVersion("version-embedding-status", document.id(), now);
        catalog.saveVersion(version);
        catalog.saveChunks(List.of(
                new DocumentChunk("chunk-ready", version.id(), 0, "状态攻略", "hash-ready", Map.of(),
                        "embedding-v1", 3, DocumentChunk.EmbeddingStatus.READY),
                new DocumentChunk("chunk-pending", version.id(), 1, "状态攻略", "hash-pending", Map.of(),
                        "embedding-v1", 3, DocumentChunk.EmbeddingStatus.PENDING),
                new DocumentChunk("chunk-failed", version.id(), 2, "状态攻略", "hash-failed", Map.of(),
                        "embedding-v1", 3, DocumentChunk.EmbeddingStatus.FAILED)));

        KnowledgeRelease release = new KnowledgeRelease("knowledge-embedding-status", "2026.09.status",
                KnowledgeRelease.ReleaseStatus.CANDIDATE, "parser-1", "embedding-v1", "data-1",
                0, null, null, null, now, null);
        VectorIndexRelease index = new VectorIndexRelease("index-embedding-status", release.id(), "pgvector",
                "embedding-v1", 3, VectorIndexRelease.IndexStatus.CANDIDATE, null, now, null);
        catalog.saveRelease(release);
        catalog.saveIndex(index);
        catalog.bindDocumentVersionToKnowledgeRelease(release.id(), version.id());
        catalog.bindChunksToIndexRelease(index.id(), List.of("chunk-ready", "chunk-pending", "chunk-failed"));
        catalog.activateIndex(index.id());

        List<KnowledgeCatalog.KnowledgeChunkView> result = catalog.searchLexical("状态攻略",
                new KnowledgeCatalog.KnowledgeFilter("zh-CN", "sv-1", "singles", "rules-1"), 10);
        assertEquals(List.of("chunk-ready"),
                result.stream().map(view -> view.chunk().id()).toList());
    }

    private DocumentVersion approvedVersion(String id, String documentId, Instant now) {
        return new DocumentVersion(id, documentId, "v1", id + "-checksum", "parser-1", "schema-1",
                "content-1", DocumentVersion.ReviewStatus.APPROVED, now, null, 10, Map.of());
    }

    private DocumentChunk effectiveChunk(String id, String versionId) {
        return new DocumentChunk(id, versionId, 0, "攻略", id + "-hash", Map.of(),
                "embedding-v1", 3, DocumentChunk.EmbeddingStatus.READY);
    }
}
