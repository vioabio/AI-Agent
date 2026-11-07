package com.vio.vioaiagent.rag.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.rag.domain.DocumentChunk;
import com.vio.vioaiagent.rag.domain.DocumentVersion;
import com.vio.vioaiagent.rag.domain.IngestionBatch;
import com.vio.vioaiagent.rag.domain.KnowledgeDocument;
import com.vio.vioaiagent.rag.domain.KnowledgeRelease;
import com.vio.vioaiagent.rag.domain.KnowledgeSource;
import com.vio.vioaiagent.rag.domain.VectorIndexRelease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 生产知识目录的 JDBC 实现。
 *
 * <p>该类只负责 V2 生命周期表的持久化和确定性的词法检索，向量检索仍由
 * {@code VersionedPgVectorStoreConfig} 提供。由于 V2 的目录表和 PGVector 的
 * 向量表分离，{@link #searchSemantic(String, KnowledgeCatalog.KnowledgeFilter, int)}
 * 在向量索引尚未接通时明确降级为词法检索，不会伪造语义分数。</p>
 *
 * <p>该实现仅在 {@code prod} profile 注册；开发/测试 profile 继续使用
 * {@link InMemoryKnowledgeCatalog}，避免本地启动时隐式依赖 PostgreSQL。</p>
 */
@Repository
@Primary
@Profile({"prod", "demo"})
public class JdbcKnowledgeCatalog implements KnowledgeCatalog {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };
    private static final Logger LOG = LoggerFactory.getLogger(JdbcKnowledgeCatalog.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean semanticFallbackLogged = new AtomicBoolean();

    /** 生产 profile 下由 VersionedPgVectorStoreConfig 提供；关闭向量开关时允许为空并降级。 */
    @Autowired(required = false)
    @Qualifier("versionedPgVectorStore")
    private VectorStore vectorStore;

    public JdbcKnowledgeCatalog(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ObjectMapper());
    }

    @Autowired
    public JdbcKnowledgeCatalog(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate 不能为空");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper 不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveSource(KnowledgeSource source) {
        requireNonNull(source, "source");
        jdbcTemplate.update("""
                        INSERT INTO knowledge_source
                            (id, source_url, title, license, trust_level, owner_id, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP), COALESCE(?, CURRENT_TIMESTAMP))
                        ON CONFLICT (id) DO UPDATE SET
                            source_url = EXCLUDED.source_url,
                            title = EXCLUDED.title,
                            license = EXCLUDED.license,
                            trust_level = EXCLUDED.trust_level,
                            owner_id = EXCLUDED.owner_id,
                            updated_at = EXCLUDED.updated_at
                        """,
                source.id(), source.sourceUrl(), source.title(), source.license(), source.trustLevel().name(),
                source.ownerId(), timestamp(source.createdAt()), timestamp(source.updatedAt()));
    }

    @Override
    public void saveDocument(KnowledgeDocument document) {
        requireNonNull(document, "document");
        jdbcTemplate.update("""
                        INSERT INTO knowledge_document
                            (id, source_id, canonical_key, language, game_release_id, battle_format_id,
                             ruleset_version_id, effective_from, effective_to, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP))
                        ON CONFLICT (id) DO UPDATE SET
                            source_id = EXCLUDED.source_id,
                            canonical_key = EXCLUDED.canonical_key,
                            language = EXCLUDED.language,
                            game_release_id = EXCLUDED.game_release_id,
                            battle_format_id = EXCLUDED.battle_format_id,
                            ruleset_version_id = EXCLUDED.ruleset_version_id,
                            effective_from = EXCLUDED.effective_from,
                            effective_to = EXCLUDED.effective_to
                        """,
                document.id(), document.sourceId(), document.canonicalKey(), document.language(),
                document.gameReleaseId(), document.battleFormatId(), document.rulesetVersionId(),
                timestamp(document.effectiveFrom()), timestamp(document.effectiveTo()), timestamp(document.createdAt()));
    }

    @Override
    public void saveVersion(DocumentVersion version) {
        requireNonNull(version, "version");
        jdbcTemplate.update("""
                        INSERT INTO document_version
                            (id, document_id, version_no, checksum, parser_version, schema_version,
                             content_version, review_status, retrieved_at, published_at, size_bytes, metadata)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP), ?, ?, ?::jsonb)
                        ON CONFLICT (id) DO UPDATE SET
                            document_id = EXCLUDED.document_id,
                            version_no = EXCLUDED.version_no,
                            checksum = EXCLUDED.checksum,
                            parser_version = EXCLUDED.parser_version,
                            schema_version = EXCLUDED.schema_version,
                            content_version = EXCLUDED.content_version,
                            review_status = EXCLUDED.review_status,
                            retrieved_at = EXCLUDED.retrieved_at,
                            published_at = EXCLUDED.published_at,
                            size_bytes = EXCLUDED.size_bytes,
                            metadata = EXCLUDED.metadata
                        """,
                version.id(), version.documentId(), version.versionNo(), version.checksum(), version.parserVersion(),
                version.schemaVersion(), version.contentVersion(), version.reviewStatus().name(),
                timestamp(version.retrievedAt()), timestamp(version.publishedAt()), version.sizeBytes(),
                json(version.metadata()));
    }

    @Override
    public void saveChunks(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                        INSERT INTO document_chunk
                            (id, document_version_id, chunk_no, content, content_hash, metadata,
                             embedding_model, embedding_dimension, embedding_status)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET
                            document_version_id = EXCLUDED.document_version_id,
                            chunk_no = EXCLUDED.chunk_no,
                            content = EXCLUDED.content,
                            content_hash = EXCLUDED.content_hash,
                            metadata = EXCLUDED.metadata,
                            embedding_model = EXCLUDED.embedding_model,
                            embedding_dimension = EXCLUDED.embedding_dimension,
                            embedding_status = EXCLUDED.embedding_status
                        """,
                chunks,
                chunks.size(),
                (statement, chunk) -> {
                    statement.setString(1, chunk.id());
                    statement.setString(2, chunk.documentVersionId());
                    statement.setInt(3, chunk.chunkNo());
                    statement.setString(4, chunk.content());
                    statement.setString(5, chunk.contentHash());
                    statement.setString(6, json(chunk.metadata()));
                    statement.setString(7, chunk.embeddingModel());
                    if (chunk.embeddingDimension() == null) {
                        statement.setNull(8, Types.INTEGER);
                    } else {
                        statement.setInt(8, chunk.embeddingDimension());
                    }
                    statement.setString(9, chunk.embeddingStatus().name());
                });
    }

    @Override
    public void saveBatch(IngestionBatch batch) {
        requireNonNull(batch, "batch");
        try {
            jdbcTemplate.update("""
                        INSERT INTO ingestion_batch
                            (id, source_id, idempotency_key, status, parser_version, chunk_config,
                             embedding_model, accepted_count, rejected_count, error_message,
                             started_at, completed_at)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP), ?)
                        ON CONFLICT (id) DO UPDATE SET
                            source_id = EXCLUDED.source_id,
                            idempotency_key = EXCLUDED.idempotency_key,
                            status = EXCLUDED.status,
                            parser_version = EXCLUDED.parser_version,
                            chunk_config = EXCLUDED.chunk_config,
                            embedding_model = EXCLUDED.embedding_model,
                            accepted_count = EXCLUDED.accepted_count,
                            rejected_count = EXCLUDED.rejected_count,
                            error_message = EXCLUDED.error_message,
                            started_at = EXCLUDED.started_at,
                            completed_at = EXCLUDED.completed_at
                        """,
                batch.id(), batch.sourceId(), batch.idempotencyKey(), batch.status().name(), batch.parserVersion(),
                jsonObject(batch.chunkConfig()), batch.embeddingModel(), batch.acceptedCount(), batch.rejectedCount(),
                    batch.errorMessage(), timestamp(batch.startedAt()), timestamp(batch.completedAt()));
        } catch (DataIntegrityViolationException conflict) {
            // 并发摄取可能先占用 (source_id,idempotency_key) 唯一键；胜者批次
            // 是唯一事实，避免把该冲突误报成新的 quarantine 失败。
            if (batch.idempotencyKey() != null && !batch.idempotencyKey().isBlank()
                    && findBatchByIdempotencyKey(batch.sourceId(), batch.idempotencyKey()).isPresent()) {
                return;
            }
            throw conflict;
        }
    }

    @Override
    public Optional<IngestionBatch> findBatchByIdempotencyKey(String sourceId, String idempotencyKey) {
        if (sourceId == null || sourceId.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT id, source_id, idempotency_key, status, parser_version, chunk_config,
                               embedding_model, accepted_count, rejected_count, error_message,
                               started_at, completed_at
                        FROM ingestion_batch
                        WHERE source_id = ? AND idempotency_key = ?
                        ORDER BY started_at DESC, id
                        LIMIT 1
                        """, this::mapBatch, sourceId.trim(), idempotencyKey.trim()).stream().findFirst();
    }

    @Override
    public Optional<IngestionBatch> findBatch(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT id, source_id, idempotency_key, status, parser_version, chunk_config,
                               embedding_model, accepted_count, rejected_count, error_message,
                               started_at, completed_at
                        FROM ingestion_batch WHERE id = ?
                        """, this::mapBatch, id.trim()).stream().findFirst();
    }

    @Override
    public Optional<DocumentVersion> findVersionByChecksum(String checksum) {
        if (checksum == null || checksum.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT id, document_id, version_no, checksum, parser_version, schema_version,
                               content_version, review_status, retrieved_at, published_at, size_bytes, metadata
                        FROM document_version
                        WHERE lower(checksum) = lower(?)
                        ORDER BY retrieved_at DESC, id
                        LIMIT 1
                        """,
                this::mapVersion, checksum.trim()).stream().findFirst();
    }

    @Override
    public Optional<DocumentVersion> findVersion(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT id, document_id, version_no, checksum, parser_version, schema_version,
                               content_version, review_status, retrieved_at, published_at, size_bytes, metadata
                        FROM document_version WHERE id = ?
                        """, this::mapVersion, id.trim()).stream().findFirst();
    }

    @Override
    public Optional<KnowledgeDocument> findDocument(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT id, source_id, canonical_key, language, game_release_id, battle_format_id,
                               ruleset_version_id, effective_from, effective_to, created_at
                        FROM knowledge_document
                        WHERE id = ?
                        """,
                this::mapDocument, id.trim()).stream().findFirst();
    }

    @Override
    public List<KnowledgeChunkView> searchLexical(String text, KnowledgeFilter filter, int limit) {
        if (text == null || text.isBlank() || limit <= 0) {
            return List.of();
        }
        KnowledgeFilter safeFilter = filter == null ? new KnowledgeFilter(null, null, null, null) : filter;
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.document_version_id, c.chunk_no, c.content, c.content_hash, c.metadata,
                       c.embedding_model, c.embedding_dimension, c.embedding_status,
                       d.id AS document_id, v.version_no, s.title AS source_title, s.source_url,
                       d.language, d.game_release_id, d.battle_format_id, d.ruleset_version_id,
                       ts_rank_cd(to_tsvector('simple', c.content), plainto_tsquery('simple', ?)) AS rank_score
                FROM document_chunk c
                JOIN document_version v ON v.id = c.document_version_id
                JOIN knowledge_document d ON d.id = v.document_id
                JOIN knowledge_source s ON s.id = d.source_id
                JOIN knowledge_release_document_version rv ON rv.document_version_id = v.id
                JOIN knowledge_release kr ON kr.id = rv.knowledge_release_id AND kr.status = 'ACTIVE'
                JOIN active_index_release air ON air.vector_index_release_id IS NOT NULL
                JOIN vector_index_release vir ON vir.id = air.vector_index_release_id
                    AND vir.knowledge_release_id = kr.id AND vir.status = 'ACTIVE'
                 JOIN vector_index_release_chunk vic ON vic.vector_index_release_id = vir.id
                     AND vic.document_chunk_id = c.id
                 WHERE v.review_status = 'APPROVED'
                   AND c.embedding_status = 'READY'
                   AND (d.effective_from IS NULL OR d.effective_from <= CURRENT_TIMESTAMP)
                   AND (d.effective_to IS NULL OR d.effective_to > CURRENT_TIMESTAMP)
                   AND (to_tsvector('simple', c.content) @@ plainto_tsquery('simple', ?)
                        OR lower(c.content) LIKE lower(?))
                """);
        List<Object> args = new ArrayList<>();
        args.add(text.trim());
        args.add(text.trim());
        args.add("%" + text.trim() + "%");
        appendFilter(sql, args, "d.language", safeFilter.language());
        appendFilter(sql, args, "d.game_release_id", safeFilter.gameReleaseId());
        appendFilter(sql, args, "d.battle_format_id", safeFilter.battleFormatId());
        appendFilter(sql, args, "d.ruleset_version_id", safeFilter.rulesetVersionId());
        appendFilter(sql, args, "kr.data_release_id", safeFilter.dataReleaseId());
        appendFilter(sql, args, "kr.id", safeFilter.knowledgeReleaseId());
        appendFilter(sql, args, "vir.id", safeFilter.indexReleaseId());
        sql.append(" ORDER BY rank_score DESC, v.published_at DESC NULLS LAST, c.id LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapChunkView, args.toArray());
    }

    @Override
    public List<KnowledgeChunkView> searchSemantic(String text, KnowledgeFilter filter, int limit) {
        if (vectorStore == null || text == null || text.isBlank() || limit <= 0) {
            logSemanticFallback();
            return searchLexical(text, filter, limit);
        }
        try {
            // 先读取活动指针并把 release 过滤下推到 PGVector，避免旧索引占满 topK
            // 导致候选索引片段永远无法进入目录侧过滤。
            Optional<VectorIndexRelease> active = activeIndex();
            if (active.isEmpty()) {
                return List.of();
            }
            KnowledgeFilter safeFilter = filter == null
                    ? new KnowledgeFilter(null, null, null, null) : filter;
            if (!matchesRequestedRelease(safeFilter, active.get())) return List.of();
            String activeIndexId = active.get().id().replace("'", "''");
            // 通过 metadata.chunkId 回到 V2 目录，保证引用包含 source/document/version，
            // 并在目录侧再次执行版本过滤；不能直接把向量表文本当成最终证据。
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(text.trim())
                    .topK(Math.max(limit * 2, limit))
                    .similarityThresholdAll()
                    .filterExpression("vectorIndexReleaseId == '" + activeIndexId + "'")
                    .build());
            List<KnowledgeChunkView> result = new ArrayList<>();
            for (Document document : documents) {
                String chunkId = metadataText(document, "chunkId");
                if (chunkId == null || chunkId.isBlank()) chunkId = document.getId();
                KnowledgeChunkView view = findChunkView(chunkId);
                if (view != null && matches(view, safeFilter)) {
                    result.add(view);
                }
                if (result.size() >= limit) break;
            }
            return result;
        } catch (RuntimeException ex) {
            logSemanticFallback();
            LOG.warn("PGVector 语义检索失败，降级为词法检索: {}", ex.getMessage());
            return searchLexical(text, filter, limit);
        }
    }

    private void logSemanticFallback() {
        if (semanticFallbackLogged.compareAndSet(false, true)) {
            LOG.warn("知识语义检索未配置可用 PGVector，当前降级为词法检索");
        }
    }

    private String metadataText(Document document, String key) {
        if (document == null || document.getMetadata() == null) return null;
        Object value = document.getMetadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private boolean matches(KnowledgeChunkView view, KnowledgeFilter filter) {
        return equalsOrUnbounded(filter.language(), view.language())
                && equalsOrUnbounded(filter.gameReleaseId(), view.gameReleaseId())
                && equalsOrUnbounded(filter.battleFormatId(), view.battleFormatId())
                && equalsOrUnbounded(filter.rulesetVersionId(), view.rulesetVersionId());
    }

    private boolean matchesRequestedRelease(KnowledgeFilter filter, VectorIndexRelease active) {
        if (filter == null || active == null) return false;
        if (!equalsOrUnbounded(filter.indexReleaseId(), active.id())
                || !equalsOrUnbounded(filter.knowledgeReleaseId(), active.knowledgeReleaseId())) {
            return false;
        }
        if (filter.dataReleaseId() == null) return true;
        Optional<KnowledgeRelease> release = findRelease(active.knowledgeReleaseId());
        return release.isPresent() && equalsOrUnbounded(filter.dataReleaseId(), release.get().dataReleaseId());
    }

    private boolean equalsOrUnbounded(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }

    private KnowledgeChunkView findChunkView(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) return null;
        return jdbcTemplate.query("""
                        SELECT c.id, c.document_version_id, c.chunk_no, c.content, c.content_hash, c.metadata,
                               c.embedding_model, c.embedding_dimension, c.embedding_status,
                               d.id AS document_id, v.version_no, s.title AS source_title, s.source_url,
                               d.language, d.game_release_id, d.battle_format_id, d.ruleset_version_id
                        FROM document_chunk c
                        JOIN document_version v ON v.id = c.document_version_id
                        JOIN knowledge_document d ON d.id = v.document_id
                        JOIN knowledge_source s ON s.id = d.source_id
                        JOIN knowledge_release_document_version rv ON rv.document_version_id = v.id
                        JOIN knowledge_release kr ON kr.id = rv.knowledge_release_id AND kr.status = 'ACTIVE'
                        JOIN active_index_release air ON air.vector_index_release_id IS NOT NULL
                        JOIN vector_index_release vir ON vir.id = air.vector_index_release_id
                            AND vir.knowledge_release_id = kr.id AND vir.status = 'ACTIVE'
                         JOIN vector_index_release_chunk vic ON vic.vector_index_release_id = vir.id
                             AND vic.document_chunk_id = c.id
                        WHERE c.id = ? AND v.review_status = 'APPROVED'
                          AND c.embedding_status = 'READY'
                          AND (d.effective_from IS NULL OR d.effective_from <= CURRENT_TIMESTAMP)
                          AND (d.effective_to IS NULL OR d.effective_to > CURRENT_TIMESTAMP)
                        LIMIT 1
                        """, this::mapChunkView, chunkId).stream().findFirst().orElse(null);
    }

    @Override
    public Optional<KnowledgeRelease> findRelease(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT id, release_no, status, parser_version, embedding_model, data_release_id,
                               sample_size, recall_at_5, citation_accuracy, groundedness, created_at, published_at
                        FROM knowledge_release
                        WHERE id = ?
                        """,
                this::mapRelease, id.trim()).stream().findFirst();
    }

    @Override
    public Optional<VectorIndexRelease> findIndex(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT id, knowledge_release_id, vector_store, embedding_model, dimension,
                               status, index_checksum, created_at, published_at
                        FROM vector_index_release
                        WHERE id = ?
                        """, this::mapIndex, id.trim()).stream().findFirst();
    }

    @Override
    public Optional<VectorIndexRelease> activeIndex() {
        return jdbcTemplate.query("""
                        SELECT v.id, v.knowledge_release_id, v.vector_store, v.embedding_model, v.dimension,
                               v.status, v.index_checksum, v.created_at, v.published_at
                        FROM active_index_release a
                        JOIN vector_index_release v ON v.id = a.vector_index_release_id
                        JOIN knowledge_release k ON k.id = v.knowledge_release_id
                        WHERE v.status = 'ACTIVE' AND k.status = 'ACTIVE'
                        LIMIT 1
                        """,
                this::mapIndex).stream().findFirst();
    }

    /** 保存候选知识发布版本，供 M2 评测/发布流水线使用。 */
    @Override
    public void saveRelease(KnowledgeRelease release) {
        requireNonNull(release, "release");
        jdbcTemplate.update("""
                        INSERT INTO knowledge_release
                            (id, release_no, status, parser_version, embedding_model, data_release_id,
                             sample_size, recall_at_5, citation_accuracy, groundedness, created_at, published_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP), ?)
                        ON CONFLICT (id) DO UPDATE SET
                            release_no = EXCLUDED.release_no,
                            status = EXCLUDED.status,
                            parser_version = EXCLUDED.parser_version,
                            embedding_model = EXCLUDED.embedding_model,
                            data_release_id = EXCLUDED.data_release_id,
                            sample_size = EXCLUDED.sample_size,
                            recall_at_5 = EXCLUDED.recall_at_5,
                            citation_accuracy = EXCLUDED.citation_accuracy,
                            groundedness = EXCLUDED.groundedness,
                            created_at = EXCLUDED.created_at,
                            published_at = EXCLUDED.published_at
                        """,
                release.id(), release.releaseNo(), release.status().name(), release.parserVersion(),
                release.embeddingModel(), release.dataReleaseId(), release.sampleSize(), release.recallAt5(),
                release.citationAccuracy(), release.groundedness(), timestamp(release.createdAt()),
                timestamp(release.publishedAt()));
    }

    /** 保存候选向量索引，并可在发布事务中更新 active 指针。 */
    @Override
    public void saveIndex(VectorIndexRelease index) {
        requireNonNull(index, "index");
        jdbcTemplate.update("""
                        INSERT INTO vector_index_release
                            (id, knowledge_release_id, vector_store, embedding_model, dimension, status,
                             index_checksum, created_at, published_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP), ?)
                        ON CONFLICT (id) DO UPDATE SET
                            knowledge_release_id = EXCLUDED.knowledge_release_id,
                            vector_store = EXCLUDED.vector_store,
                            embedding_model = EXCLUDED.embedding_model,
                            dimension = EXCLUDED.dimension,
                            status = EXCLUDED.status,
                            index_checksum = EXCLUDED.index_checksum,
                            created_at = EXCLUDED.created_at,
                            published_at = EXCLUDED.published_at
                        """,
                index.id(), index.knowledgeReleaseId(), index.vectorStore(), index.embeddingModel(), index.dimension(),
                index.status().name(), index.indexChecksum(), timestamp(index.createdAt()), timestamp(index.publishedAt()));
    }

    @Override
    public void bindDocumentVersionToKnowledgeRelease(String knowledgeReleaseId, String documentVersionId) {
        if (knowledgeReleaseId == null || knowledgeReleaseId.isBlank()) {
            throw new IllegalArgumentException("knowledgeReleaseId 不能为空");
        }
        if (documentVersionId == null || documentVersionId.isBlank()) {
            throw new IllegalArgumentException("documentVersionId 不能为空");
        }
        jdbcTemplate.update("""
                INSERT INTO knowledge_release_document_version (knowledge_release_id, document_version_id)
                VALUES (?, ?)
                ON CONFLICT (knowledge_release_id, document_version_id) DO NOTHING
                """, knowledgeReleaseId.trim(), documentVersionId.trim());
    }

    @Override
    @Transactional
    public void bindChunksToIndexRelease(String indexReleaseId, List<String> chunkIds) {
        if (indexReleaseId == null || indexReleaseId.isBlank()) {
            throw new IllegalArgumentException("indexReleaseId 不能为空");
        }
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        List<String> safeChunkIds = chunkIds.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        if (safeChunkIds.isEmpty()) {
            return;
        }
        String safeIndexId = indexReleaseId.trim();
        String knowledgeReleaseId = jdbcTemplate.query("""
                        SELECT knowledge_release_id FROM vector_index_release WHERE id = ?
                        """, rs -> rs.next() ? rs.getString(1) : null, safeIndexId);
        if (knowledgeReleaseId == null || knowledgeReleaseId.isBlank()) {
            throw new IllegalArgumentException("找不到索引版本: " + safeIndexId);
        }
        // 在批量 INSERT 前一次性校验完整血缘，避免只验证“chunk 存在”而把
        // 其他 knowledge release 的内容绑定进候选索引。动态部分只有占位符，
        // 实际 ID 仍通过 JDBC 参数绑定，不能注入 SQL。
        String placeholders = String.join(",", safeChunkIds.stream().map(ignored -> "?").toList());
        List<Object> lineageArgs = new ArrayList<>(safeChunkIds.size() + 1);
        lineageArgs.add(knowledgeReleaseId);
        lineageArgs.addAll(safeChunkIds);
        Integer validCount = jdbcTemplate.queryForObject("""
                        SELECT COUNT(DISTINCT c.id)
                        FROM document_chunk c
                        JOIN knowledge_release_document_version rv
                          ON rv.document_version_id = c.document_version_id
                         AND rv.knowledge_release_id = ?
                        WHERE c.id IN (""" + placeholders + ")", Integer.class, lineageArgs.toArray());
        if (validCount == null || validCount != safeChunkIds.size()) {
            throw new IllegalArgumentException("存在不属于索引所属 knowledge release 的 chunk");
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO vector_index_release_chunk (vector_index_release_id, document_chunk_id)
                VALUES (?, ?)
                ON CONFLICT (vector_index_release_id, document_chunk_id) DO NOTHING
                """, safeChunkIds, safeChunkIds.size(), (statement, chunkId) -> {
                    statement.setString(1, safeIndexId);
                    statement.setString(2, chunkId.trim());
                });
    }

    /**
     * 原子切换活动索引指针。调用方应先完成离线评测，再调用此方法。
     * 目标索引必须已有 chunk 绑定，且所属知识 release 处于 CANDIDATE/ACTIVE。
     */
    @Override
    @Transactional
    public void activateIndex(String indexId) {
        if (indexId == null || indexId.isBlank()) {
            throw new IllegalArgumentException("indexId 不能为空");
        }
        String safeId = indexId.trim();
        VectorIndexRelease target = findIndex(safeId)
                .orElseThrow(() -> new IllegalArgumentException("找不到索引版本: " + safeId));
        if (target.status() != VectorIndexRelease.IndexStatus.CANDIDATE
                && target.status() != VectorIndexRelease.IndexStatus.BUILDING
                && target.status() != VectorIndexRelease.IndexStatus.ACTIVE) {
            throw new IllegalStateException("索引版本不是可发布状态: " + target.status());
        }
        KnowledgeRelease release = findRelease(target.knowledgeReleaseId())
                .orElseThrow(() -> new IllegalStateException("找不到索引所属知识发布版本: "
                        + target.knowledgeReleaseId()));
        if (release.status() != KnowledgeRelease.ReleaseStatus.CANDIDATE
                && release.status() != KnowledgeRelease.ReleaseStatus.ACTIVE) {
            throw new IllegalStateException("知识发布版本不是可发布状态: " + release.status());
        }
        Integer boundChunks = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM vector_index_release_chunk WHERE vector_index_release_id = ?
                """, Integer.class, safeId);
        if (boundChunks == null || boundChunks <= 0) {
            throw new IllegalStateException("索引版本尚未绑定任何 chunk: " + safeId);
        }
        // 先保留旧记录（ROLLED_BACK），再将目标设为 ACTIVE；整个方法在事务内执行，
        // active_index_release 指针不会短暂指向未完成的候选索引。
        jdbcTemplate.update("""
                UPDATE vector_index_release SET status = 'ROLLED_BACK'
                WHERE status = 'ACTIVE' AND id <> ?
                """, safeId);
        jdbcTemplate.update("""
                UPDATE knowledge_release SET status = 'ARCHIVED'
                WHERE status = 'ACTIVE' AND id <> ?
                """, release.id());
        jdbcTemplate.update("""
                UPDATE vector_index_release
                SET status = 'ACTIVE', published_at = COALESCE(published_at, CURRENT_TIMESTAMP)
                WHERE id = ?
                """, safeId);
        jdbcTemplate.update("""
                UPDATE knowledge_release
                SET status = 'ACTIVE', published_at = COALESCE(published_at, CURRENT_TIMESTAMP)
                WHERE id = ?
                """, release.id());
        jdbcTemplate.update("""
                INSERT INTO active_index_release (singleton_id, vector_index_release_id, updated_at)
                VALUES (TRUE, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (singleton_id) DO UPDATE SET
                    vector_index_release_id = EXCLUDED.vector_index_release_id,
                    updated_at = EXCLUDED.updated_at
                """, safeId);
    }

    private void appendFilter(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private KnowledgeChunkView mapChunkView(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        DocumentChunk chunk = new DocumentChunk(rs.getString("id"), rs.getString("document_version_id"),
                rs.getInt("chunk_no"), rs.getString("content"), rs.getString("content_hash"),
                readJson(rs.getString("metadata")), rs.getString("embedding_model"),
                integer(rs.getObject("embedding_dimension")),
                enumValue(DocumentChunk.EmbeddingStatus.class, rs.getString("embedding_status"),
                        DocumentChunk.EmbeddingStatus.PENDING));
        return new KnowledgeChunkView(chunk, rs.getString("document_id"), rs.getString("version_no"),
                rs.getString("source_title"), rs.getString("source_url"), rs.getString("language"),
                rs.getString("game_release_id"), rs.getString("battle_format_id"),
                rs.getString("ruleset_version_id"));
    }

    private DocumentVersion mapVersion(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DocumentVersion(rs.getString("id"), rs.getString("document_id"), rs.getString("version_no"),
                rs.getString("checksum"), rs.getString("parser_version"), rs.getString("schema_version"),
                rs.getString("content_version"), enumValue(DocumentVersion.ReviewStatus.class,
                rs.getString("review_status"), DocumentVersion.ReviewStatus.PENDING),
                instant(rs.getObject("retrieved_at")), instant(rs.getObject("published_at")),
                rs.getLong("size_bytes"), readJson(rs.getString("metadata")));
    }

    private IngestionBatch mapBatch(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new IngestionBatch(rs.getString("id"), rs.getString("source_id"),
                rs.getString("idempotency_key"), enumValue(IngestionBatch.IngestionStatus.class,
                rs.getString("status"), IngestionBatch.IngestionStatus.FAILED),
                rs.getString("parser_version"), rs.getString("chunk_config"),
                rs.getString("embedding_model"), rs.getInt("accepted_count"),
                rs.getInt("rejected_count"), rs.getString("error_message"),
                instant(rs.getObject("started_at")), instant(rs.getObject("completed_at")));
    }

    private KnowledgeDocument mapDocument(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new KnowledgeDocument(rs.getString("id"), rs.getString("source_id"), rs.getString("canonical_key"),
                rs.getString("language"), rs.getString("game_release_id"), rs.getString("battle_format_id"),
                rs.getString("ruleset_version_id"), instant(rs.getObject("effective_from")),
                instant(rs.getObject("effective_to")), instant(rs.getObject("created_at")));
    }

    private KnowledgeRelease mapRelease(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new KnowledgeRelease(rs.getString("id"), rs.getString("release_no"),
                enumValue(KnowledgeRelease.ReleaseStatus.class, rs.getString("status"),
                        KnowledgeRelease.ReleaseStatus.CANDIDATE), rs.getString("parser_version"),
                rs.getString("embedding_model"), rs.getString("data_release_id"), rs.getInt("sample_size"),
                number(rs.getObject("recall_at_5")), number(rs.getObject("citation_accuracy")),
                number(rs.getObject("groundedness")), instant(rs.getObject("created_at")),
                instant(rs.getObject("published_at")));
    }

    private VectorIndexRelease mapIndex(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new VectorIndexRelease(rs.getString("id"), rs.getString("knowledge_release_id"),
                rs.getString("vector_store"), rs.getString("embedding_model"), rs.getInt("dimension"),
                enumValue(VectorIndexRelease.IndexStatus.class, rs.getString("status"),
                        VectorIndexRelease.IndexStatus.CANDIDATE), rs.getString("index_checksum"),
                instant(rs.getObject("created_at")), instant(rs.getObject("published_at")));
    }

    private Map<String, Object> readJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, JSON_MAP);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("知识目录 JSON 元数据损坏", ex);
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("无法序列化知识元数据", ex);
        }
    }

    private String jsonObject(String value) {
        if (value == null || value.isBlank()) {
            return "{}";
        }
        try {
            objectMapper.readTree(value);
            return value;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("chunkConfig 必须是合法 JSON", ex);
        }
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.toInstant(java.time.ZoneOffset.UTC);
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return Instant.parse(value.toString());
    }

    private Double number(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private Integer integer(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("知识目录状态值非法: " + value, ex);
        }
    }

    private void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
