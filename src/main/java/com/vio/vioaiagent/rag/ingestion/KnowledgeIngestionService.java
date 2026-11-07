package com.vio.vioaiagent.rag.ingestion;

import com.vio.vioaiagent.rag.catalog.KnowledgeCatalog;
import com.vio.vioaiagent.rag.domain.DocumentChunk;
import com.vio.vioaiagent.rag.domain.DocumentVersion;
import com.vio.vioaiagent.rag.domain.IngestionBatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可重试的显式知识摄取任务。该服务不会在 Spring 启动时运行，也不会覆盖已发布版本。
 */
@Service
public class KnowledgeIngestionService {

    /**
     * Keep the logger explicit so migration/smoke compilation also works when
     * Lombok annotation processing is intentionally disabled.
     */
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private static final long DEFAULT_MAX_BYTES = 5 * 1024 * 1024;
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".md", ".markdown", ".txt");

    private final KnowledgeCatalog catalog;
    private final long maxBytes;
    /** 同一 JVM 内相同 source+幂等键只允许一个摄取者进入文件/目录写入阶段。 */
    private final ConcurrentMap<String, LockRef> idempotencyLocks = new ConcurrentHashMap<>();

    @Autowired
    public KnowledgeIngestionService(KnowledgeCatalog catalog) {
        this(catalog, DEFAULT_MAX_BYTES);
    }

    public KnowledgeIngestionService(KnowledgeCatalog catalog, long maxBytes) {
        if (catalog == null) {
            throw new IllegalArgumentException("catalog 不能为空");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes 必须大于 0");
        }
        this.catalog = catalog;
        this.maxBytes = maxBytes;
    }

    public KnowledgeIngestionResult ingest(KnowledgeIngestionRequest request) {
        validateRequest(request);
        String idempotencyKey = request.idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ingestInternal(request);
        }
        String lockKey = request.source().id().trim() + "\u0000" + idempotencyKey.trim();
        LockRef lock = idempotencyLocks.compute(lockKey, (ignored, current) -> {
            LockRef ref = current == null ? new LockRef() : current;
            ref.references.incrementAndGet();
            return ref;
        });
        synchronized (lock.monitor) {
            try {
                // 重新进入时再次读取，确保等待者复用前一个调用已落库的批次。
                return ingestInternal(request);
            } finally {
                idempotencyLocks.computeIfPresent(lockKey, (ignored, current) ->
                        current == lock && current.references.decrementAndGet() == 0 ? null : current);
            }
        }
    }

    /** 带引用计数的锁槽，避免删除空闲锁时让并发等待者落入两个监视器。 */
    private static final class LockRef {
        private final Object monitor = new Object();
        private final AtomicInteger references = new AtomicInteger();
    }

    private KnowledgeIngestionResult ingestInternal(KnowledgeIngestionRequest request) {
        // 幂等键由数据库唯一约束（生产）或内存目录（本地/测试）兜底。
        // 在任何文件读取和写入前短路，避免重试产生新的 document_version。
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            var previous = catalog.findBatchByIdempotencyKey(request.source().id(), request.idempotencyKey());
            if (previous.isPresent()) {
                LOG.info("知识摄取命中幂等批次 sourceId={}, idempotencyKey={}, batchId={}, status={}",
                        request.source().id(), request.idempotencyKey(), previous.get().id(), previous.get().status());
                return new KnowledgeIngestionResult(previous.get(), null, List.of(),
                        List.of("幂等键已处理，复用既有 ingestion_batch 结果"));
            }
        }
        String batchId = "batch-" + UUID.randomUUID();
        Instant startedAt = Instant.now();
        try {
            // 先落来源根记录，再做文件校验；这样任何 quarantine 批次都不会因
            // source 外键不存在而丢失审计记录。
            catalog.saveSource(request.source());
            long size = Files.size(request.stagedFile());
            validateFile(request.stagedFile(), size, request.source().license());
            byte[] bytes = Files.readAllBytes(request.stagedFile());
            String checksum = sha256(bytes);
            var existing = catalog.findVersionByChecksum(checksum);
            if (existing.isPresent()) {
                IngestionBatch duplicate = new IngestionBatch(batchId, request.source().id(),
                        request.idempotencyKey(), IngestionBatch.IngestionStatus.ACCEPTED,
                        request.parserVersion(), chunkConfig(request), null, 0, 0,
                        "checksum 已存在，跳过重复版本", startedAt, Instant.now());
                catalog.saveBatch(duplicate);
                return new KnowledgeIngestionResult(duplicate, existing.get(), List.of(),
                        List.of("checksum 已存在，未重复写入"));
            }
            catalog.saveDocument(request.document());
            String content = new String(bytes, StandardCharsets.UTF_8);
            DocumentVersion version = new DocumentVersion("doc-version-" + UUID.randomUUID(),
                    request.document().id(), request.versionNo(), checksum, request.parserVersion(),
                    request.schemaVersion(), request.contentVersion(),
                    DocumentVersion.ReviewStatus.PENDING, startedAt, null, size,
                    Map.of("language", request.document().language()));
            List<DocumentChunk> chunks = split(version.id(), content, request);
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("文件没有可摄取的有效文本片段");
            }
            catalog.saveVersion(version);
            catalog.saveChunks(chunks);
            IngestionBatch accepted = new IngestionBatch(batchId, request.source().id(),
                    request.idempotencyKey(), IngestionBatch.IngestionStatus.ACCEPTED,
                    request.parserVersion(), chunkConfig(request), null, chunks.size(), 0,
                    null, startedAt, Instant.now());
            catalog.saveBatch(accepted);
            LOG.info("知识摄取完成 batchId={}, documentId={}, chunkCount={}", batchId,
                    request.document().id(), chunks.size());
            return new KnowledgeIngestionResult(accepted, version, chunks, List.of());
        } catch (Exception ex) {
            // 跨进程并发时，数据库唯一键可能已由另一摄取者占用；不要再写入
            // 一个带相同幂等键的 quarantine 批次（这会再次触发唯一冲突），
            // 直接回读胜者批次并将本次调用视为幂等重放。
            if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
                var winner = catalog.findBatchByIdempotencyKey(request.source().id(), request.idempotencyKey());
                if (winner.isPresent()) {
                    LOG.info("并发知识摄取复用已存在批次 sourceId={}, idempotencyKey={}, batchId={}",
                            request.source().id(), request.idempotencyKey(), winner.get().id());
                    return new KnowledgeIngestionResult(winner.get(), null, List.of(),
                            List.of("并发幂等键已由其他任务处理，复用既有批次"));
                }
            }
            IngestionBatch failed = new IngestionBatch(batchId, request.source().id(),
                    request.idempotencyKey(), IngestionBatch.IngestionStatus.QUARANTINED,
                    request.parserVersion(), chunkConfig(request), null, 0, 1,
                    safeMessage(ex), startedAt, Instant.now());
            try {
                catalog.saveBatch(failed);
            } catch (RuntimeException quarantineFailure) {
                // quarantine 写入失败不能覆盖原始失败原因；至少保留结构化日志，
                // 由运维告警触发人工补录，避免假装批次已被隔离。
                LOG.error("知识摄取失败且 quarantine 记录未落库 batchId={}, reason={}",
                        batchId, quarantineFailure.getMessage(), quarantineFailure);
            }
            LOG.warn("知识摄取进入 quarantine batchId={}, reason={}", batchId, ex.getMessage());
            return new KnowledgeIngestionResult(failed, null, List.of(), List.of(safeMessage(ex)));
        }
    }

    private void validateRequest(KnowledgeIngestionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("摄取请求不能为空");
        }
        if (request.stagedFile() == null || request.source() == null || request.document() == null) {
            throw new IllegalArgumentException("stagedFile、source、document 不能为空");
        }
        if (!Files.exists(request.stagedFile()) || !Files.isRegularFile(request.stagedFile())) {
            throw new IllegalArgumentException("摄取文件必须存在于隔离区");
        }
        if (!request.source().id().equals(request.document().sourceId())) {
            throw new IllegalArgumentException("document.sourceId 必须与 source.id 一致");
        }
    }

    private void validateFile(Path path, long size, String license) {
        String lower = path.getFileName().toString().toLowerCase();
        if (ALLOWED_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            throw new IllegalArgumentException("只允许 Markdown 或纯文本文件");
        }
        if (size <= 0 || size > maxBytes) {
            throw new IllegalArgumentException("文件大小不在允许范围内");
        }
        if (license == null || license.isBlank()) {
            throw new IllegalArgumentException("缺少许可证信息");
        }
    }

    private List<DocumentChunk> split(String versionId, String content, KnowledgeIngestionRequest request) {
        List<DocumentChunk> result = new ArrayList<>();
        int start = 0;
        int chunkNo = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + request.chunkSize());
            String chunkContent = content.substring(start, end).trim();
            if (!chunkContent.isEmpty()) {
                String hash = sha256(chunkContent.getBytes(StandardCharsets.UTF_8));
                result.add(new DocumentChunk(versionId + "-chunk-" + chunkNo, versionId, chunkNo,
                        chunkContent, hash, Map.of("chunkNo", chunkNo), null, null,
                        DocumentChunk.EmbeddingStatus.PENDING));
                chunkNo++;
            }
            if (end == content.length()) {
                break;
            }
            start = Math.max(start + 1, end - request.overlap());
        }
        return result;
    }

    private String chunkConfig(KnowledgeIngestionRequest request) {
        return "{\"chunkSize\":" + request.chunkSize() + ",\"overlap\":" + request.overlap() + "}";
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 不支持 SHA-256", ex);
        }
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
