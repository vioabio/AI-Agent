package com.vio.vioaiagent.rag.ingestion;

import com.vio.vioaiagent.rag.catalog.KnowledgeCatalog;
import com.vio.vioaiagent.rag.domain.DocumentChunk;
import com.vio.vioaiagent.rag.domain.DocumentVersion;
import com.vio.vioaiagent.rag.domain.IngestionBatch;
import com.vio.vioaiagent.rag.domain.KnowledgeDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 显式、可重试的 Embedding/向量索引任务。
 *
 * <p>该服务刻意不在应用启动阶段运行，也不把演示用的 {@code SimpleVectorStore}
 * 当作生产索引。生产 profile 通过 {@code versionedPgVectorStore} 注入；没有该
 * bean 时调用会明确失败，避免把“只完成了切块”误报为“索引已完成”。</p>
 *
 * <p>每个候选索引使用带 release 后缀的向量文档 ID，并在 metadata 中保留原始
 * chunkId 和完整版本键。这样重新构建候选索引不会覆盖旧索引，检索侧仍可通过
 * chunkId 回到关系目录生成可核查引用。</p>
 */
@Service
public class KnowledgeIndexingService {

    private static final String DEFAULT_EMBEDDING_MODEL = "dashscope-text-embedding-v3";
    private static final int DEFAULT_EMBEDDING_DIMENSION = 1536;

    private final KnowledgeCatalog catalog;
    private final String configuredEmbeddingModel;
    private final int configuredEmbeddingDimension;
    private volatile VectorStore productionVectorStore;

    /** Spring 构造器；向量存储用可选 setter 注入，以便本地 profile 无数据库也能启动。 */
    @Autowired
    public KnowledgeIndexingService(
            KnowledgeCatalog catalog,
            @Value("${vio.rag.embedding-model:" + DEFAULT_EMBEDDING_MODEL + "}") String embeddingModel,
            @Value("${vio.rag.embedding-dimensions:" + DEFAULT_EMBEDDING_DIMENSION + "}") int embeddingDimension) {
        this(catalog, null, embeddingModel, embeddingDimension);
    }

    /** 离线测试/任务编排构造器，可显式传入 VectorStore。 */
    public KnowledgeIndexingService(KnowledgeCatalog catalog, VectorStore vectorStore,
                                    String embeddingModel, int embeddingDimension) {
        this.catalog = Objects.requireNonNull(catalog, "catalog 不能为空");
        this.productionVectorStore = vectorStore;
        this.configuredEmbeddingModel = embeddingModel == null || embeddingModel.isBlank()
                ? DEFAULT_EMBEDDING_MODEL : embeddingModel.trim();
        if (embeddingDimension <= 0) {
            throw new IllegalArgumentException("embeddingDimension 必须大于 0");
        }
        this.configuredEmbeddingDimension = embeddingDimension;
    }

    /** 只允许生产 PGVector 适配器注入；不会误选本地演示 VectorStore。 */
    @Autowired(required = false)
    void setProductionVectorStore(@Qualifier("versionedPgVectorStore") VectorStore vectorStore) {
        this.productionVectorStore = vectorStore;
    }

    /**
     * 为摄取结果构建默认候选索引。调用方若要发布版本，应使用带 release ID 的重载。
     */
    public KnowledgeIndexingResult index(KnowledgeIngestionResult ingestion) {
        return index(ingestion, null, null);
    }

    /**
     * 将摄取结果写入候选向量索引。
     *
     * @param ingestion 摄取阶段产出的版本和 chunk
     * @param knowledgeReleaseId 可选知识发布 ID，用于向量 metadata 血缘
     * @param vectorIndexReleaseId 候选索引 ID；提供后会生成 release 隔离的向量文档 ID
     */
    public KnowledgeIndexingResult index(KnowledgeIngestionResult ingestion,
                                         String knowledgeReleaseId,
                                         String vectorIndexReleaseId) {
        validateIngestion(ingestion);
        List<DocumentChunk> chunks = ingestion.chunks();
        if (ingestion.documentVersion() == null || chunks.isEmpty()) {
            return new KnowledgeIndexingResult(KnowledgeIndexingResult.Status.SKIPPED,
                    ingestion.documentVersion() == null ? null : ingestion.documentVersion().id(),
                    normalize(vectorIndexReleaseId), 0, 0, configuredEmbeddingModel,
                    configuredEmbeddingDimension, null, List.of("没有待索引 chunk（可能已因 checksum 去重）"));
        }
        if (productionVectorStore == null) {
            throw new KnowledgeIndexingUnavailableException(
                    "生产向量存储未配置；请启用 vio.rag.pgvector.enabled 并确认 PostgreSQL/Embedding 可用");
        }

        DocumentVersion version = ingestion.documentVersion();
        KnowledgeDocument document = catalog.findDocument(version.documentId())
                .orElseThrow(() -> new IllegalArgumentException("找不到文档身份: " + version.documentId()));
        validateChunks(chunks, version.id());
        String safeKnowledgeReleaseId = normalize(knowledgeReleaseId);
        String safeIndexReleaseId = normalize(vectorIndexReleaseId);
        if (safeIndexReleaseId != null) {
            // 提供 indexReleaseId 时必须能在目录中找到真实候选版本；不能因为
            // 调用方传入一个未知 ID 就把向量写入“无主”索引，绕过 release/回滚边界。
            var candidate = catalog.findIndex(safeIndexReleaseId)
                    .orElseThrow(() -> new IllegalArgumentException("找不到向量索引版本: "
                            + safeIndexReleaseId));
            if (safeKnowledgeReleaseId != null && !safeKnowledgeReleaseId.equals(candidate.knowledgeReleaseId())) {
                throw new IllegalArgumentException("索引版本与 knowledgeReleaseId 不匹配");
            }
            if (safeKnowledgeReleaseId == null) {
                safeKnowledgeReleaseId = candidate.knowledgeReleaseId();
            }
            if (candidate.status() != com.vio.vioaiagent.rag.domain.VectorIndexRelease.IndexStatus.CANDIDATE
                    && candidate.status() != com.vio.vioaiagent.rag.domain.VectorIndexRelease.IndexStatus.BUILDING) {
                throw new IllegalStateException("索引版本不是可构建状态: " + candidate.status());
            }
            if (candidate.dimension() != configuredEmbeddingDimension
                    || !configuredEmbeddingModel.equals(candidate.embeddingModel())) {
                throw new IllegalArgumentException("索引维度或 Embedding 模型与当前任务配置不一致");
            }
        }
        final String effectiveKnowledgeReleaseId = safeKnowledgeReleaseId;
        List<Document> vectorDocuments = chunks.stream()
                .sorted(Comparator.comparingInt(DocumentChunk::chunkNo).thenComparing(DocumentChunk::id))
                .map(chunk -> toVectorDocument(chunk, version, document,
                        effectiveKnowledgeReleaseId, safeIndexReleaseId))
                .toList();
        String checksum = indexChecksum(version, chunks, effectiveKnowledgeReleaseId, safeIndexReleaseId);
        try {
            // PgVectorStore.add 使用业务 ID upsert；同一候选任务重试不会重复产生向量行。
            productionVectorStore.add(vectorDocuments);
            if (effectiveKnowledgeReleaseId != null) {
                catalog.bindDocumentVersionToKnowledgeRelease(effectiveKnowledgeReleaseId, version.id());
            }
            if (safeIndexReleaseId != null) {
                catalog.bindChunksToIndexRelease(safeIndexReleaseId,
                        chunks.stream().map(DocumentChunk::id).toList());
            }
            List<DocumentChunk> ready = chunks.stream()
                    .map(chunk -> withEmbeddingState(chunk, DocumentChunk.EmbeddingStatus.READY))
                    .toList();
            catalog.saveChunks(ready);
            List<String> auditWarnings = persistBatchState(ingestion.batch(), IngestionBatch.IngestionStatus.ACCEPTED,
                    null, chunks.size(), 0);
            return new KnowledgeIndexingResult(KnowledgeIndexingResult.Status.INDEXED, version.id(),
                    safeIndexReleaseId, ready.size(), 0, configuredEmbeddingModel,
                    configuredEmbeddingDimension, checksum, auditWarnings);
        } catch (RuntimeException ex) {
            List<String> warnings = new ArrayList<>();
            warnings.add(safeMessage(ex));
            try {
                List<DocumentChunk> failed = chunks.stream()
                        .map(chunk -> withEmbeddingState(chunk, DocumentChunk.EmbeddingStatus.FAILED))
                        .toList();
                catalog.saveChunks(failed);
            } catch (RuntimeException statusFailure) {
                // 不覆盖原始 Embedding 错误；结构化日志由上层告警和重试任务消费。
                warnings.add("embedding_status 回写失败: " + safeMessage(statusFailure));
            }
            warnings.addAll(persistBatchState(ingestion.batch(), IngestionBatch.IngestionStatus.FAILED,
                    safeMessage(ex), 0, chunks.size()));
            return new KnowledgeIndexingResult(KnowledgeIndexingResult.Status.FAILED, version.id(),
                    safeIndexReleaseId, 0, chunks.size(), configuredEmbeddingModel,
                    configuredEmbeddingDimension, checksum, warnings);
        }
    }

    private Document toVectorDocument(DocumentChunk chunk, DocumentVersion version,
                                      KnowledgeDocument document, String knowledgeReleaseId,
                                      String vectorIndexReleaseId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.putAll(chunk.metadata());
        metadata.put("chunkId", chunk.id());
        metadata.put("documentVersionId", version.id());
        metadata.put("documentId", document.id());
        metadata.put("documentVersion", version.versionNo());
        metadata.put("sourceId", document.sourceId());
        metadata.put("language", document.language());
        putIfPresent(metadata, "gameReleaseId", document.gameReleaseId());
        putIfPresent(metadata, "battleFormatId", document.battleFormatId());
        putIfPresent(metadata, "rulesetVersionId", document.rulesetVersionId());
        putIfPresent(metadata, "knowledgeReleaseId", knowledgeReleaseId);
        putIfPresent(metadata, "vectorIndexReleaseId", vectorIndexReleaseId);
        metadata.put("contentHash", chunk.contentHash());
        return new Document(vectorDocumentId(chunk.id(), vectorIndexReleaseId), chunk.content(), metadata);
    }

    private String vectorDocumentId(String chunkId, String vectorIndexReleaseId) {
        if (vectorIndexReleaseId == null) return chunkId;
        return chunkId + "::index::" + vectorIndexReleaseId;
    }

    private String indexChecksum(DocumentVersion version, List<DocumentChunk> chunks,
                                 String knowledgeReleaseId, String vectorIndexReleaseId) {
        StringBuilder material = new StringBuilder(version.id()).append('|')
                .append(version.checksum()).append('|').append(configuredEmbeddingModel).append('|')
                .append(configuredEmbeddingDimension).append('|').append(nullToEmpty(knowledgeReleaseId))
                .append('|').append(nullToEmpty(vectorIndexReleaseId));
        chunks.stream().sorted(Comparator.comparingInt(DocumentChunk::chunkNo).thenComparing(DocumentChunk::id))
                .forEach(chunk -> material.append('|').append(chunk.id()).append(':').append(chunk.contentHash()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 不支持 SHA-256", ex);
        }
    }

    private DocumentChunk withEmbeddingState(DocumentChunk chunk, DocumentChunk.EmbeddingStatus status) {
        return new DocumentChunk(chunk.id(), chunk.documentVersionId(), chunk.chunkNo(), chunk.content(),
                chunk.contentHash(), chunk.metadata(), configuredEmbeddingModel,
                configuredEmbeddingDimension, status);
    }

    private void validateIngestion(KnowledgeIngestionResult ingestion) {
        if (ingestion == null || ingestion.batch() == null) {
            throw new IllegalArgumentException("摄取结果不能为空");
        }
        if (ingestion.batch().status() != com.vio.vioaiagent.rag.domain.IngestionBatch.IngestionStatus.ACCEPTED) {
            throw new IllegalArgumentException("只有 ACCEPTED 摄取批次可以建立索引");
        }
    }

    private void validateChunks(List<DocumentChunk> chunks, String versionId) {
        Set<String> ids = new HashSet<>();
        Set<Integer> numbers = new HashSet<>();
        for (DocumentChunk chunk : chunks) {
            if (chunk == null || !versionId.equals(chunk.documentVersionId())) {
                throw new IllegalArgumentException("chunk 必须属于当前 documentVersion");
            }
            if (!ids.add(chunk.id()) || !numbers.add(chunk.chunkNo())) {
                throw new IllegalArgumentException("chunk id/chunkNo 不能重复");
            }
        }
    }

    private void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) metadata.put(key, value);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    /** 将 Embedding 阶段结果回写到 ingestion_batch，便于重试任务按状态扫描。 */
    private List<String> persistBatchState(IngestionBatch original, IngestionBatch.IngestionStatus status,
                                           String errorMessage, int acceptedCount, int rejectedCount) {
        try {
            IngestionBatch updated = new IngestionBatch(original.id(), original.sourceId(),
                    original.idempotencyKey(), status, original.parserVersion(), original.chunkConfig(),
                    configuredEmbeddingModel, acceptedCount, rejectedCount, errorMessage,
                    original.startedAt(), java.time.Instant.now());
            catalog.saveBatch(updated);
            return List.of();
        } catch (RuntimeException ex) {
            return List.of("ingestion_batch 状态回写失败: " + safeMessage(ex));
        }
    }
}
