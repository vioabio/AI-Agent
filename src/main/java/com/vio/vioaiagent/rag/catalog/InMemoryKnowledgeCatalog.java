package com.vio.vioaiagent.rag.catalog;

import com.vio.vioaiagent.rag.domain.DocumentChunk;
import com.vio.vioaiagent.rag.domain.DocumentVersion;
import com.vio.vioaiagent.rag.domain.IngestionBatch;
import com.vio.vioaiagent.rag.domain.KnowledgeDocument;
import com.vio.vioaiagent.rag.domain.KnowledgeRelease;
import com.vio.vioaiagent.rag.domain.KnowledgeSource;
import com.vio.vioaiagent.rag.domain.VectorIndexRelease;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * 轻量级测试/开发目录。它实现与生产目录相同的过滤和排序语义，不在启动时摄取文档。
 */
@Repository
@Primary
@Profile("!prod & !demo")
public class InMemoryKnowledgeCatalog implements KnowledgeCatalog {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final Map<String, KnowledgeSource> sources = new ConcurrentHashMap<>();
    private final Map<String, KnowledgeDocument> documents = new ConcurrentHashMap<>();
    private final Map<String, DocumentVersion> versions = new ConcurrentHashMap<>();
    private final Map<String, List<DocumentChunk>> chunksByVersion = new ConcurrentHashMap<>();
    private final Map<String, IngestionBatch> batches = new ConcurrentHashMap<>();
    private final Map<String, KnowledgeRelease> releases = new ConcurrentHashMap<>();
    private final Map<String, VectorIndexRelease> indexes = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> chunkIdsByIndex = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> versionIdsByKnowledgeRelease = new ConcurrentHashMap<>();
    private volatile String activeIndexId;

    @Override
    public void saveSource(KnowledgeSource source) {
        sources.put(source.id(), source);
    }

    @Override
    public void saveDocument(KnowledgeDocument document) {
        documents.put(document.id(), document);
    }

    @Override
    public void saveVersion(DocumentVersion version) {
        versions.put(version.id(), version);
    }

    @Override
    public void saveChunks(List<DocumentChunk> chunks) {
        for (DocumentChunk chunk : chunks) {
            chunksByVersion.computeIfAbsent(chunk.documentVersionId(), ignored -> new CopyOnWriteArrayList<>())
                    .removeIf(existing -> existing.id().equals(chunk.id()));
            chunksByVersion.computeIfAbsent(chunk.documentVersionId(), ignored -> new CopyOnWriteArrayList<>())
                    .add(chunk);
        }
    }

    @Override
    public void saveBatch(IngestionBatch batch) {
        batches.put(batch.id(), batch);
    }

    @Override
    public Optional<IngestionBatch> findBatchByIdempotencyKey(String sourceId, String idempotencyKey) {
        if (sourceId == null || sourceId.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return batches.values().stream()
                .filter(batch -> sourceId.equals(batch.sourceId()))
                .filter(batch -> idempotencyKey.equals(batch.idempotencyKey()))
                .findFirst();
    }

    @Override
    public Optional<IngestionBatch> findBatch(String id) {
        return id == null || id.isBlank() ? Optional.empty() : Optional.ofNullable(batches.get(id.trim()));
    }

    @Override
    public Optional<DocumentVersion> findVersionByChecksum(String checksum) {
        if (checksum == null || checksum.isBlank()) {
            return Optional.empty();
        }
        return versions.values().stream()
                .filter(version -> version.checksum().equalsIgnoreCase(checksum))
                .findFirst();
    }

    @Override
    public Optional<DocumentVersion> findVersion(String id) {
        return id == null || id.isBlank() ? Optional.empty() : Optional.ofNullable(versions.get(id.trim()));
    }

    @Override
    public Optional<KnowledgeDocument> findDocument(String id) {
        return Optional.ofNullable(documents.get(id));
    }

    @Override
    public List<KnowledgeChunkView> searchLexical(String text, KnowledgeFilter filter, int limit) {
        return search(text, filter, limit);
    }

    @Override
    public List<KnowledgeChunkView> searchSemantic(String text, KnowledgeFilter filter, int limit) {
        // 开发目录没有外部 Embedding 服务；使用词项相似度作为确定性替身，生产由 PGVector 适配器替换。
        return search(text, filter, limit);
    }

    private List<KnowledgeChunkView> search(String text, KnowledgeFilter filter, int limit) {
        KnowledgeFilter safeFilter = filter == null
                ? new KnowledgeFilter(null, null, null, null) : filter;
        // 一个检索请求内固定时间基准，避免跨越边界时同一批结果出现不一致。
        Instant now = Instant.now();
        List<String> queryTokens = tokens(text);
        List<RankedView> ranked = new ArrayList<>();
        for (DocumentVersion version : versions.values()) {
            if (version.reviewStatus() != DocumentVersion.ReviewStatus.APPROVED) {
                continue;
            }
            KnowledgeDocument document = documents.get(version.documentId());
            if (document == null || !isEffective(document, now) || !matches(document, safeFilter)) {
                continue;
            }
            VectorIndexRelease active = activeIndex().orElse(null);
            if (active == null && (safeFilter.dataReleaseId() != null
                    || safeFilter.knowledgeReleaseId() != null || safeFilter.indexReleaseId() != null)) {
                // 请求明确指定了发布血缘，但目录没有 active 指针；不能退回
                // 到无界的本地词法检索。
                continue;
            }
            if (active != null && (!matches(safeFilter.indexReleaseId(), active.id())
                    || !matches(safeFilter.knowledgeReleaseId(), active.knowledgeReleaseId()))) {
                continue;
            }
            if (active != null && safeFilter.dataReleaseId() != null) {
                KnowledgeRelease activeRelease = releases.get(active.knowledgeReleaseId());
                if (activeRelease == null || !matches(safeFilter.dataReleaseId(), activeRelease.dataReleaseId())) {
                    continue;
                }
            }
            Set<String> activeChunks = active == null ? null
                    : chunkIdsByIndex.getOrDefault(active.id(), Set.of());
            Set<String> activeVersions = active == null ? null
                    : versionIdsByKnowledgeRelease.getOrDefault(active.knowledgeReleaseId(), Set.of());
            KnowledgeSource source = sources.get(document.sourceId());
            for (DocumentChunk chunk : chunksByVersion.getOrDefault(version.id(), List.of())) {
                // 只有向量写入成功并完成状态回写的 chunk 才能进入 active
                // 检索；绑定关系本身不是“可召回”证明，避免 PENDING/FAILED
                // 片段在部分失败后泄漏给回答链。
                if (chunk.embeddingStatus() != DocumentChunk.EmbeddingStatus.READY) {
                    continue;
                }
                // 本地开发在没有 active 指针时保留可检索行为；一旦存在指针，
                // 只返回明确绑定到该索引版本的 chunk，模拟生产 fail-closed 语义。
                if (active != null && !activeChunks.contains(chunk.id())) {
                    continue;
                }
                if (active != null && !activeVersions.contains(version.id())) {
                    continue;
                }
                int score = overlap(queryTokens, tokens(chunk.content()));
                if (score > 0) {
                    ranked.add(new RankedView(toView(chunk, document, version, source), score));
                }
            }
        }
        ranked.sort(Comparator.comparingInt(RankedView::score).reversed()
                .thenComparing(view -> view.view().chunk().id()));
        return ranked.stream().limit(Math.max(0, limit)).map(RankedView::view).toList();
    }

    /** 文档有效区间采用 [effectiveFrom, effectiveTo) 语义；空边界表示不设限。 */
    private boolean isEffective(KnowledgeDocument document, Instant now) {
        return (document.effectiveFrom() == null || !now.isBefore(document.effectiveFrom()))
                && (document.effectiveTo() == null || now.isBefore(document.effectiveTo()));
    }

    private boolean matches(KnowledgeDocument document, KnowledgeFilter filter) {
        return matches(filter.language(), document.language())
                && matches(filter.gameReleaseId(), document.gameReleaseId())
                && matches(filter.battleFormatId(), document.battleFormatId())
                && matches(filter.rulesetVersionId(), document.rulesetVersionId());
    }

    private boolean matches(String requested, String actual) {
        return requested == null || requested.equalsIgnoreCase(actual);
    }

    private KnowledgeChunkView toView(DocumentChunk chunk, KnowledgeDocument document,
                                      DocumentVersion version, KnowledgeSource source) {
        return new KnowledgeChunkView(chunk, document.id(), version.versionNo(),
                source == null ? null : source.title(), source == null ? null : source.sourceUrl(),
                document.language(), document.gameReleaseId(), document.battleFormatId(),
                document.rulesetVersionId());
    }

    private int overlap(List<String> left, List<String> right) {
        int score = 0;
        for (String token : left) {
            if (right.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private List<String> tokens(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return TOKEN_SPLITTER.splitAsStream(value.toLowerCase(Locale.ROOT))
                .filter(token -> token.length() > 1)
                .distinct()
                .toList();
    }

    @Override
    public Optional<KnowledgeRelease> findRelease(String id) {
        return id == null || id.isBlank() ? Optional.empty() : Optional.ofNullable(releases.get(id.trim()));
    }

    @Override
    public Optional<VectorIndexRelease> findIndex(String id) {
        return id == null || id.isBlank() ? Optional.empty() : Optional.ofNullable(indexes.get(id.trim()));
    }

    @Override
    public Optional<VectorIndexRelease> activeIndex() {
        String pointer = activeIndexId;
        if (pointer != null) {
            VectorIndexRelease index = indexes.get(pointer);
            if (index != null && index.status() == VectorIndexRelease.IndexStatus.ACTIVE
                    && releaseActive(index)) {
                return Optional.of(index);
            }
        }
        return indexes.values().stream()
                .filter(index -> index.status() == VectorIndexRelease.IndexStatus.ACTIVE)
                .filter(this::releaseActive)
                .findFirst();
    }

    private boolean releaseActive(VectorIndexRelease index) {
        KnowledgeRelease release = releases.get(index.knowledgeReleaseId());
        return release != null && release.status() == KnowledgeRelease.ReleaseStatus.ACTIVE;
    }

    @Override
    public void saveRelease(KnowledgeRelease release) {
        if (release == null) throw new IllegalArgumentException("release 不能为空");
        releases.put(release.id(), release);
    }

    @Override
    public void saveIndex(VectorIndexRelease index) {
        if (index == null) throw new IllegalArgumentException("index 不能为空");
        indexes.put(index.id(), index);
        if (index.status() == VectorIndexRelease.IndexStatus.ACTIVE) {
            activeIndexId = index.id();
        }
    }

    @Override
    public void bindChunksToIndexRelease(String indexReleaseId, List<String> chunkIds) {
        if (indexReleaseId == null || indexReleaseId.isBlank()) {
            throw new IllegalArgumentException("indexReleaseId 不能为空");
        }
        String safeIndexId = indexReleaseId.trim();
        VectorIndexRelease index = indexes.get(safeIndexId);
        if (index == null) {
            throw new IllegalArgumentException("找不到索引版本: " + safeIndexId);
        }
        Set<String> requested = chunkIds == null ? Set.of() : chunkIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!requested.isEmpty()) {
            // 索引血缘不能只靠调用方自觉：每个 chunk 的 document_version
            // 必须先登记在该 index 所属 knowledge release 中，否则候选索引
            // 可能把另一 release 的内容带入 active 检索。
            Set<String> releaseVersions = versionIdsByKnowledgeRelease.getOrDefault(
                    index.knowledgeReleaseId(), Set.of());
            for (String chunkId : requested) {
                DocumentChunk chunk = findChunk(chunkId);
                if (chunk == null || !releaseVersions.contains(chunk.documentVersionId())) {
                    throw new IllegalArgumentException("chunk 不属于索引所属 knowledge release: " + chunkId);
                }
            }
        }
        Set<String> bound = chunkIdsByIndex.computeIfAbsent(safeIndexId,
                ignored -> ConcurrentHashMap.newKeySet());
        bound.addAll(requested);
    }

    private DocumentChunk findChunk(String chunkId) {
        for (List<DocumentChunk> chunks : chunksByVersion.values()) {
            for (DocumentChunk chunk : chunks) {
                if (chunkId.equals(chunk.id())) return chunk;
            }
        }
        return null;
    }

    @Override
    public void bindDocumentVersionToKnowledgeRelease(String knowledgeReleaseId, String documentVersionId) {
        if (knowledgeReleaseId == null || knowledgeReleaseId.isBlank()) {
            throw new IllegalArgumentException("knowledgeReleaseId 不能为空");
        }
        if (documentVersionId == null || documentVersionId.isBlank()) {
            throw new IllegalArgumentException("documentVersionId 不能为空");
        }
        if (!releases.containsKey(knowledgeReleaseId)) {
            throw new IllegalArgumentException("找不到知识发布版本: " + knowledgeReleaseId);
        }
        if (!versions.containsKey(documentVersionId)) {
            throw new IllegalArgumentException("找不到文档版本: " + documentVersionId);
        }
        versionIdsByKnowledgeRelease.computeIfAbsent(knowledgeReleaseId,
                ignored -> ConcurrentHashMap.newKeySet()).add(documentVersionId);
    }

    @Override
    public synchronized void activateIndex(String indexId) {
        if (indexId == null || indexId.isBlank()) {
            throw new IllegalArgumentException("indexId 不能为空");
        }
        VectorIndexRelease target = indexes.get(indexId.trim());
        if (target == null) {
            throw new IllegalArgumentException("找不到索引版本: " + indexId);
        }
        if (target.status() != VectorIndexRelease.IndexStatus.CANDIDATE
                && target.status() != VectorIndexRelease.IndexStatus.BUILDING
                && target.status() != VectorIndexRelease.IndexStatus.ACTIVE) {
            throw new IllegalStateException("索引版本不是可发布状态: " + target.status());
        }
        KnowledgeRelease release = releases.get(target.knowledgeReleaseId());
        if (release == null) {
            throw new IllegalStateException("找不到索引所属知识发布版本: " + target.knowledgeReleaseId());
        }
        if (release.status() != KnowledgeRelease.ReleaseStatus.CANDIDATE
                && release.status() != KnowledgeRelease.ReleaseStatus.ACTIVE) {
            throw new IllegalStateException("知识发布版本不是可发布状态: " + release.status());
        }
        if (chunkIdsByIndex.getOrDefault(target.id(), Set.of()).isEmpty()) {
            throw new IllegalStateException("索引版本尚未绑定任何 chunk: " + target.id());
        }
        if (versionIdsByKnowledgeRelease.getOrDefault(release.id(), Set.of()).isEmpty()) {
            throw new IllegalStateException("知识发布版本尚未绑定任何文档版本: " + release.id());
        }
        // 即使调用方此前直接保存了 ACTIVE 记录，也要先收敛为单一活动索引，
        // 与数据库 singleton 指针的约束保持一致。
        indexes.replaceAll((id, value) -> id.equals(target.id())
                ? value
                : value.status() == VectorIndexRelease.IndexStatus.ACTIVE
                ? withStatus(value, VectorIndexRelease.IndexStatus.ROLLED_BACK) : value);
        releases.replaceAll((id, value) -> id.equals(release.id())
                ? value
                : value.status() == KnowledgeRelease.ReleaseStatus.ACTIVE
                ? withStatus(value, KnowledgeRelease.ReleaseStatus.ARCHIVED) : value);
        indexes.put(target.id(), withStatus(target, VectorIndexRelease.IndexStatus.ACTIVE));
        releases.put(release.id(), withStatus(release, KnowledgeRelease.ReleaseStatus.ACTIVE));
        activeIndexId = target.id();
    }

    private VectorIndexRelease withStatus(VectorIndexRelease value, VectorIndexRelease.IndexStatus status) {
        return new VectorIndexRelease(value.id(), value.knowledgeReleaseId(), value.vectorStore(),
                value.embeddingModel(), value.dimension(), status, value.indexChecksum(), value.createdAt(),
                status == VectorIndexRelease.IndexStatus.ACTIVE ? java.time.Instant.now() : value.publishedAt());
    }

    private KnowledgeRelease withStatus(KnowledgeRelease value, KnowledgeRelease.ReleaseStatus status) {
        return new KnowledgeRelease(value.id(), value.releaseNo(), status, value.parserVersion(),
                value.embeddingModel(), value.dataReleaseId(), value.sampleSize(), value.recallAt5(),
                value.citationAccuracy(), value.groundedness(), value.createdAt(),
                status == KnowledgeRelease.ReleaseStatus.ACTIVE ? java.time.Instant.now() : value.publishedAt());
    }

    private record RankedView(KnowledgeChunkView view, int score) {
    }
}
