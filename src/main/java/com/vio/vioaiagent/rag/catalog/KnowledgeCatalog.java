package com.vio.vioaiagent.rag.catalog;

import com.vio.vioaiagent.rag.domain.DocumentChunk;
import com.vio.vioaiagent.rag.domain.DocumentVersion;
import com.vio.vioaiagent.rag.domain.IngestionBatch;
import com.vio.vioaiagent.rag.domain.KnowledgeDocument;
import com.vio.vioaiagent.rag.domain.KnowledgeRelease;
import com.vio.vioaiagent.rag.domain.KnowledgeSource;
import com.vio.vioaiagent.rag.domain.VectorIndexRelease;

import java.util.List;
import java.util.Optional;

/**
 * 知识目录的持久化边界。生产环境可由 JDBC/Repository 实现，测试使用内存实现。
 */
public interface KnowledgeCatalog {

    void saveSource(KnowledgeSource source);

    void saveDocument(KnowledgeDocument document);

    void saveVersion(DocumentVersion version);

    void saveChunks(List<DocumentChunk> chunks);

    void saveBatch(IngestionBatch batch);

    /** 按来源和幂等键查询历史批次；摄取重试必须复用原批次而不是产生新版本。 */
    Optional<IngestionBatch> findBatchByIdempotencyKey(String sourceId, String idempotencyKey);

    /** 查询已落库的摄取批次，供运维/API 查询状态。 */
    Optional<IngestionBatch> findBatch(String id);

    Optional<DocumentVersion> findVersionByChecksum(String checksum);

    /** 查询文档版本，供审核状态机使用。 */
    Optional<DocumentVersion> findVersion(String id);

    Optional<KnowledgeDocument> findDocument(String id);

    List<KnowledgeChunkView> searchLexical(String text, KnowledgeFilter filter, int limit);

    List<KnowledgeChunkView> searchSemantic(String text, KnowledgeFilter filter, int limit);

    Optional<KnowledgeRelease> findRelease(String id);

    /** 查询指定索引版本；发布前必须校验其状态和所属知识 release。 */
    Optional<VectorIndexRelease> findIndex(String id);

    Optional<VectorIndexRelease> activeIndex();

    /** 将已构建的 chunk 绑定到候选索引版本，供 active 指针切换时做精确过滤。 */
    void bindChunksToIndexRelease(String indexReleaseId, List<String> chunkIds);

    /** 记录知识发布版本包含的文档版本，形成可追溯的 release 血缘。 */
    void bindDocumentVersionToKnowledgeRelease(String knowledgeReleaseId, String documentVersionId);

    /** 保存候选知识发布版本。 */
    void saveRelease(KnowledgeRelease release);

    /** 保存候选向量索引版本。 */
    void saveIndex(VectorIndexRelease index);

    /** 在评测通过后原子切换 active index 指针。 */
    void activateIndex(String indexId);

    /** 检索所需的最小过滤键，避免跨版本混答。 */
    record KnowledgeFilter(String language, String gameReleaseId, String battleFormatId,
                           String rulesetVersionId, String dataReleaseId,
                           String knowledgeReleaseId, String indexReleaseId) {
        /** v1.0 兼容构造器：仅按业务上下文过滤。 */
        public KnowledgeFilter(String language, String gameReleaseId, String battleFormatId,
                               String rulesetVersionId) {
            this(language, gameReleaseId, battleFormatId, rulesetVersionId, null, null, null);
        }

        public KnowledgeFilter {
            language = normalize(language);
            gameReleaseId = normalize(gameReleaseId);
            battleFormatId = normalize(battleFormatId);
            rulesetVersionId = normalize(rulesetVersionId);
            dataReleaseId = normalize(dataReleaseId);
            knowledgeReleaseId = normalize(knowledgeReleaseId);
            indexReleaseId = normalize(indexReleaseId);
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /** 已补齐来源和版本元数据的检索结果。 */
    record KnowledgeChunkView(
            DocumentChunk chunk,
            String documentId,
            String documentVersion,
            String sourceTitle,
            String sourceUrl,
            String language,
            String gameReleaseId,
            String battleFormatId,
            String rulesetVersionId) {
    }
}
