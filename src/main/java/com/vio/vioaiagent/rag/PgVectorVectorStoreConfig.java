package com.vio.vioaiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/**
 * PGVector 向量存储配置（生产环境使用）
 * 为方便本地开发调试，默认注释 @Configuration，需要时取消注释即可
 * 使用前需先启动 PostgreSQL 数据库并配置 datasource
 */
//@Configuration
public class PgVectorVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    @Resource
    private MyTokenTextSplitter myTokenTextSplitter;

    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    @Bean
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashscopeEmbeddingModel) {
        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1536)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("vector_store")
                .maxDocumentBatchSize(10000)
                .build();
        // ETL 完整流程：加载 -> 切割 -> 增强 -> 写入 PGVector
        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
        List<Document> splitDocuments = myTokenTextSplitter.splitCustomized(documents);
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(splitDocuments);
        vectorStore.add(enrichedDocuments);
        return vectorStore;
    }
}
