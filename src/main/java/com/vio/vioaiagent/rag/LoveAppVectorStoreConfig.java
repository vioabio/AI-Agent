package com.vio.vioaiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 恋爱大师向量数据库配置（初始化基于内存的向量数据库 Bean）
 */
@Configuration
public class LoveAppVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    @Resource
    private MyTokenTextSplitter myTokenTextSplitter;

    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    @Bean
    VectorStore loveAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel).build();
        // 1. ETL — 加载文档
        List<Document> documentList = loveAppDocumentLoader.loadMarkdowns();
        // 2. ETL — 文档切割（基于 Token 切分，chunkSize=200, overlap=100）
        List<Document> splitDocuments = myTokenTextSplitter.splitCustomized(documentList);
        // 3. ETL — 元信息增强（AI 自动提取关键词）
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(splitDocuments);
        // 4. ETL — 写入向量数据库
        simpleVectorStore.add(enrichedDocuments);
        return simpleVectorStore;
    }
}
