package com.vio.vioaiagent.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/**
 * M2 生产向量存储开关。只创建存储适配器，不在 Bean 初始化期间扫描或写入全部文档。
 * 实际摄取由 KnowledgeIngestionService 显式触发。
 */
@Configuration
@ConditionalOnProperty(prefix = "vio.rag.pgvector", name = "enabled", havingValue = "true")
public class VersionedPgVectorStoreConfig {

    /** V2__knowledge_lifecycle.sql 中 document_chunk_vector.embedding 的物理维度。 */
    private static final int MIGRATION_VECTOR_DIMENSION = 1536;

    @Bean("versionedPgVectorStore")
    public VectorStore versionedPgVectorStore(JdbcTemplate jdbcTemplate,
                                               EmbeddingModel embeddingModel,
                                               @Value("${vio.rag.embedding-dimensions:1536}") int dimensions,
                                               @Value("${vio.rag.embedding-model:dashscope-text-embedding-v3}") String embeddingModelName) {
        if (dimensions != MIGRATION_VECTOR_DIMENSION) {
            throw new IllegalStateException("vio.rag.embedding-dimensions=" + dimensions
                    + " 与 V2 向量表维度 " + MIGRATION_VECTOR_DIMENSION
                    + " 不一致；请先执行数据库迁移后再切换模型维度");
        }
        if (embeddingModelName == null || embeddingModelName.isBlank()) {
            throw new IllegalStateException("vio.rag.embedding-model 不能为空");
        }
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(dimensions)
                // V2 的 document_chunk.id 是带前缀的业务 ID（不是 UUID）；
                // 使用 TEXT 才能让目录 chunk 与向量表一一对应并支持回溯引用。
                .idType(PgVectorStore.PgIdType.TEXT)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                // 表结构和索引由 V2 Flyway 迁移管理；Bean 初始化只创建适配器。
                .initializeSchema(false)
                .schemaName("public")
                .vectorTableName("document_chunk_vector")
                .maxDocumentBatchSize(1000)
                .build();
    }
}
