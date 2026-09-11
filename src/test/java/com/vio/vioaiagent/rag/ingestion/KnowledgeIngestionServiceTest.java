package com.vio.vioaiagent.rag.ingestion;

import com.vio.vioaiagent.rag.catalog.InMemoryKnowledgeCatalog;
import com.vio.vioaiagent.rag.domain.KnowledgeDocument;
import com.vio.vioaiagent.rag.domain.KnowledgeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2 摄取回归：显式任务、checksum 去重和 quarantine 都可复查。 */
class KnowledgeIngestionServiceTest {

    @TempDir
    Path staging;

    @Test
    void ingestionIsExplicitAndDuplicateChecksumDoesNotCreateAnotherVersion() throws Exception {
        Path file = Files.writeString(staging.resolve("guide.md"), "朱紫太晶团体战攻略\n", StandardCharsets.UTF_8);
        InMemoryKnowledgeCatalog catalog = new InMemoryKnowledgeCatalog();
        KnowledgeIngestionService service = new KnowledgeIngestionService(catalog);
        KnowledgeSource source = source();
        KnowledgeDocument document = document(source.id());

        KnowledgeIngestionResult first = service.ingest(
                KnowledgeIngestionRequest.defaults(file, source, document, "v1"));
        KnowledgeIngestionResult duplicate = service.ingest(
                KnowledgeIngestionRequest.defaults(file, source, document, "v2"));

        assertEquals(1, first.chunks().size());
        assertEquals(0, duplicate.chunks().size());
        assertTrue(duplicate.warnings().stream().anyMatch(value -> value.contains("checksum")));
    }

    @Test
    void invalidExtensionIsQuarantinedWithoutLosingSourceAudit() throws Exception {
        Path file = Files.writeString(staging.resolve("guide.exe"), "not allowed", StandardCharsets.UTF_8);
        InMemoryKnowledgeCatalog catalog = new InMemoryKnowledgeCatalog();
        KnowledgeSource source = source();
        KnowledgeIngestionResult result = new KnowledgeIngestionService(catalog)
                .ingest(KnowledgeIngestionRequest.defaults(file, source, document(source.id()), "v1"));

        assertEquals(com.vio.vioaiagent.rag.domain.IngestionBatch.IngestionStatus.QUARANTINED,
                result.batch().status());
        assertTrue(catalog.findDocument("doc-1").isEmpty());
    }

    @Test
    void concurrentSameIdempotencyKeyReusesSingleBatch() throws Exception {
        Path file = Files.writeString(staging.resolve("concurrent.md"), "并发摄取幂等测试\n", StandardCharsets.UTF_8);
        InMemoryKnowledgeCatalog catalog = new InMemoryKnowledgeCatalog();
        KnowledgeIngestionService service = new KnowledgeIngestionService(catalog);
        KnowledgeSource source = source();
        KnowledgeDocument document = document(source.id());
        KnowledgeIngestionRequest request = new KnowledgeIngestionRequest(file, source, document,
                "v1", "parser-1", "schema-1", "content-1", "same-key", 1200, 120);

        CompletableFuture<KnowledgeIngestionResult> left = CompletableFuture.supplyAsync(() -> service.ingest(request));
        CompletableFuture<KnowledgeIngestionResult> right = CompletableFuture.supplyAsync(() -> service.ingest(request));
        List<KnowledgeIngestionResult> results = CompletableFuture.allOf(left, right)
                .thenApply(ignored -> List.of(left.join(), right.join())).join();

        assertEquals(1, results.stream().filter(value -> value.chunks().size() == 1).count());
        assertEquals(1, results.stream().filter(value -> value.chunks().isEmpty()
                && value.warnings().stream().anyMatch(warning -> warning.contains("幂等"))).count());
        assertEquals(1, catalog.findBatchByIdempotencyKey(source.id(), "same-key").stream().count());
    }

    private KnowledgeSource source() {
        return new KnowledgeSource("source-1", "https://example.invalid/guide", "官方攻略",
                "CC-BY-4.0", KnowledgeSource.TrustLevel.OFFICIAL, "operator-1",
                Instant.now(), Instant.now());
    }

    private KnowledgeDocument document(String sourceId) {
        return new KnowledgeDocument("doc-1", sourceId, "guide/sv", "zh-CN", "sv-1",
                "singles", "rules-1", null, null, Instant.now());
    }
}
