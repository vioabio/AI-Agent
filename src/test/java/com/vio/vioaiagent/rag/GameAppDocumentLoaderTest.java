package com.vio.vioaiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 文档加载器测试
 */
@SpringBootTest
class GameAppDocumentLoaderTest {

    @Resource
    private GameAppDocumentLoader gameAppDocumentLoader;

    @Test
    void loadMarkdowns() {
        List<Document> documents = gameAppDocumentLoader.loadMarkdowns();
        Assertions.assertNotNull(documents);
        Assertions.assertFalse(documents.isEmpty(), "应至少加载 1 篇文档");

        // 验证元数据
        Document firstDoc = documents.get(0);
        Assertions.assertNotNull(firstDoc.getText(), "文档内容不应为空");
        Assertions.assertNotNull(firstDoc.getMetadata(), "文档元数据不应为空");
        Assertions.assertTrue(firstDoc.getMetadata().containsKey("filename"), "应包含 filename 元数据");
        Assertions.assertTrue(firstDoc.getMetadata().containsKey("status"), "应包含 status 元数据");

        System.out.printf("共加载 %d 个文档切片%n", documents.size());
        documents.forEach(doc -> {
            String filename = (String) doc.getMetadata().get("filename");
            String status = (String) doc.getMetadata().get("status");
            System.out.printf("  文档: %s | 状态: %s | 内容长度: %d%n",
                    filename, status, doc.getText().length());
        });
    }
}
