package com.vio.vioaiagent.rag.ingestion;

import com.vio.vioaiagent.rag.domain.KnowledgeDocument;
import com.vio.vioaiagent.rag.domain.KnowledgeSource;

import java.nio.file.Path;

/** 显式摄取任务输入；路径必须来自隔离区，不由应用启动流程隐式扫描。 */
public record KnowledgeIngestionRequest(
        Path stagedFile,
        KnowledgeSource source,
        KnowledgeDocument document,
        String versionNo,
        String parserVersion,
        String schemaVersion,
        String contentVersion,
        String idempotencyKey,
        int chunkSize,
        int overlap) {

    public KnowledgeIngestionRequest {
        if (stagedFile == null) {
            throw new IllegalArgumentException("stagedFile 不能为空");
        }
        if (source == null || document == null) {
            throw new IllegalArgumentException("source 和 document 不能为空");
        }
        if (versionNo == null || versionNo.isBlank()) {
            throw new IllegalArgumentException("versionNo 不能为空");
        }
        parserVersion = parserVersion == null || parserVersion.isBlank() ? "parser-1" : parserVersion;
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "1" : schemaVersion;
        chunkSize = chunkSize <= 0 ? 1200 : chunkSize;
        overlap = overlap < 0 ? 0 : Math.min(overlap, Math.max(0, chunkSize - 1));
    }

    public static KnowledgeIngestionRequest defaults(Path stagedFile, KnowledgeSource source,
                                                     KnowledgeDocument document, String versionNo) {
        return new KnowledgeIngestionRequest(stagedFile, source, document, versionNo,
                "markdown-parser-1", "1", null, null, 1200, 120);
    }
}

