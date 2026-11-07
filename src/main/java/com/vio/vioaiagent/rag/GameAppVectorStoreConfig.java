package com.vio.vioaiagent.rag;

import com.vio.vioaiagent.config.LegacyMemoryRagProperties;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * 宝可梦智能体向量数据库配置（初始化基于内存的向量数据库 Bean）
 */
@Configuration
@Profile("!prod & !demo")
@EnableConfigurationProperties(LegacyMemoryRagProperties.class)
public class GameAppVectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(GameAppVectorStoreConfig.class);

    @Resource
    private GameAppDocumentLoader gameAppDocumentLoader;

    @Resource
    private MyTokenTextSplitter myTokenTextSplitter;

    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    @Resource
    private LegacyMemoryRagProperties legacyMemoryProperties;

    /** Keep the historical no-arg constructor for Spring/compatibility callers. */
    public GameAppVectorStoreConfig() {
    }

    /** Package-private constructor used by deterministic unit tests. */
    GameAppVectorStoreConfig(GameAppDocumentLoader gameAppDocumentLoader,
                             MyTokenTextSplitter myTokenTextSplitter,
                             MyKeywordEnricher myKeywordEnricher,
                             LegacyMemoryRagProperties legacyMemoryProperties) {
        this.gameAppDocumentLoader = gameAppDocumentLoader;
        this.myTokenTextSplitter = myTokenTextSplitter;
        this.myKeywordEnricher = myKeywordEnricher;
        this.legacyMemoryProperties = legacyMemoryProperties;
    }

    @Bean
    @Lazy
    VectorStore gameAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
        String phase = "vector-store-build";
        try {
            SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel).build();
            // 1. ETL — 加载文档
            phase = "document-load";
            List<Document> documentList = gameAppDocumentLoader.loadMarkdowns();
            if (documentList == null || documentList.isEmpty()) {
                throw new IllegalStateException("document loader returned no documents");
            }
            // 2. ETL — 文档切割（基于 Token 切分，chunkSize=200, overlap=100）
            phase = "document-split";
            List<Document> splitDocuments = myTokenTextSplitter.splitCustomized(documentList);
            if (splitDocuments == null || splitDocuments.isEmpty()) {
                throw new IllegalStateException("document splitter returned no chunks");
            }
            // 3. ETL — 元信息增强（AI 自动提取关键词）
            phase = "metadata-enrichment";
            List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(splitDocuments);
            if (enrichedDocuments == null || enrichedDocuments.isEmpty()) {
                throw new IllegalStateException("metadata enricher returned no documents");
            }
            // 4. ETL — 写入向量数据库
            phase = "vector-store-write";
            simpleVectorStore.add(enrichedDocuments);
            return simpleVectorStore;
        } catch (Exception e) {
            if (legacyMemoryProperties == null || !legacyMemoryProperties.enabled()) {
                // 默认 fail-closed：空库会让 RAG 看起来成功，却返回无依据答案。
                throw new LegacyMemoryVectorStoreInitializationException(phase, e);
            }
            // 仅显式 opt-in 的 local/test 兼容运行允许降级；日志固定带上
            // degraded=true，便于 readiness/审计采集器识别，而不泄露 provider 错误文本。
            log.warn("event=rag_legacy_memory_degraded degraded=true phase={} failureType={} "
                            + "fallback=empty-vector-store",
                    phase, e.getClass().getSimpleName());
            return SimpleVectorStore.builder(dashscopeEmbeddingModel).build();
        }
    }
}
