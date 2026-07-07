package com.vio.vioaiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 AI 的文档元信息增强器（为文档补充元信息）
 */
@Component
public class MyKeywordEnricher {

    @Resource
    private ChatModel dashscopeChatModel;

    public List<Document> enrichDocuments(List<Document> documents) {
        try {
            KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(dashscopeChatModel, 5);
            return keywordMetadataEnricher.apply(documents);
        } catch (Exception e) {
            // API 欠费或其他错误时跳过元信息增强，返回原始文档
            org.slf4j.LoggerFactory.getLogger(MyKeywordEnricher.class)
                    .warn("文档元信息增强失败（可能 API 欠费）: {}，跳过此步骤", e.getMessage());
            return documents;
        }
    }
}
