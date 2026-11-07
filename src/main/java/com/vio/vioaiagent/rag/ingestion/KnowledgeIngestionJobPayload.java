package com.vio.vioaiagent.rag.ingestion;

import com.vio.vioaiagent.rag.domain.KnowledgeDocument;
import com.vio.vioaiagent.rag.domain.KnowledgeSource;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 可在 worker 重启后重建原始摄取请求的脱敏快照。
 *
 * <p>只保存元数据和 staging 文件路径，不保存文件正文。staging 目录必须由
 * 配置的隔离卷提供；Job API 不会把该路径放入对外 DTO。</p>
 */
public record KnowledgeIngestionJobPayload(
        String stagedFile,
        String sourceId,
        String sourceUrl,
        String sourceTitle,
        String license,
        String trustLevel,
        String ownerId,
        String sourceCreatedAt,
        String sourceUpdatedAt,
        String documentId,
        String canonicalKey,
        String language,
        String gameReleaseId,
        String battleFormatId,
        String rulesetVersionId,
        String effectiveFrom,
        String effectiveTo,
        String documentCreatedAt,
        String versionNo,
        String parserVersion,
        String schemaVersion,
        String contentVersion,
        int chunkSize,
        int overlap) {

    public KnowledgeIngestionJobPayload {
        requireText(stagedFile, "stagedFile");
        requireText(sourceId, "sourceId");
        requireText(sourceUrl, "sourceUrl");
        requireText(license, "license");
        requireText(documentId, "documentId");
        requireText(canonicalKey, "canonicalKey");
        requireText(language, "language");
        requireText(versionNo, "versionNo");
        parserVersion = parserVersion == null || parserVersion.isBlank() ? "markdown-parser-1" : parserVersion;
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "1" : schemaVersion;
        chunkSize = chunkSize <= 0 ? 1200 : chunkSize;
        overlap = overlap < 0 ? 0 : Math.min(overlap, Math.max(0, chunkSize - 1));
        trustLevel = trustLevel == null || trustLevel.isBlank() ? "UNVERIFIED" : trustLevel;
    }

    public static KnowledgeIngestionJobPayload from(KnowledgeIngestionRequest request) {
        KnowledgeSource source = request.source();
        KnowledgeDocument document = request.document();
        return new KnowledgeIngestionJobPayload(
                request.stagedFile().toAbsolutePath().normalize().toString(),
                source.id(), source.sourceUrl(), source.title(), source.license(), source.trustLevel().name(),
                source.ownerId(), instant(source.createdAt()), instant(source.updatedAt()),
                document.id(), document.canonicalKey(), document.language(), document.gameReleaseId(),
                document.battleFormatId(), document.rulesetVersionId(), instant(document.effectiveFrom()),
                instant(document.effectiveTo()), instant(document.createdAt()), request.versionNo(),
                request.parserVersion(), request.schemaVersion(), request.contentVersion(), request.chunkSize(),
                request.overlap());
    }

    public KnowledgeIngestionRequest toRequest(String idempotencyKey) {
        KnowledgeSource source = new KnowledgeSource(sourceId, sourceUrl, sourceTitle, license,
                KnowledgeSource.TrustLevel.valueOf(trustLevel.toUpperCase(java.util.Locale.ROOT)), ownerId,
                parseInstant(sourceCreatedAt), parseInstant(sourceUpdatedAt));
        KnowledgeDocument document = new KnowledgeDocument(documentId, sourceId, canonicalKey, language,
                gameReleaseId, battleFormatId, rulesetVersionId, parseInstant(effectiveFrom),
                parseInstant(effectiveTo), parseInstant(documentCreatedAt));
        return new KnowledgeIngestionRequest(Path.of(stagedFile), source, document, versionNo, parserVersion,
                schemaVersion, contentVersion, idempotencyKey, chunkSize, overlap);
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
