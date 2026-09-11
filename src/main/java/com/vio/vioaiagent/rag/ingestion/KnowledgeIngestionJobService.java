package com.vio.vioaiagent.rag.ingestion;

import com.vio.vioaiagent.rag.domain.IngestionBatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * v1.1 N1 摄取编排服务。
 *
 * <p>Controller 只提交 Job，worker 通过 {@link #runNext(String)} 显式领取和
 * 执行；因此进程启动不会偷偷扫描 staging。Repository 实现负责 CAS/事务，
 * 本服务负责有限重试、错误分类和对旧版同步摄取服务的适配。</p>
 */
@Service
public class KnowledgeIngestionJobService {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeIngestionJobService.class);

    public static final String DEFAULT_CONTRACT_VERSION = "knowledge-ingestion-v1.1";
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(2);
    private static final long DEFAULT_MAX_STAGED_BYTES = 5 * 1024 * 1024;

    private final KnowledgeIngestionJobRepository repository;
    private final KnowledgeIngestionJobProcessor processor;
    private final Clock clock;
    private final Duration lease;
    /** 生产必须由配置注入；测试构造器可留空以便使用临时目录。 */
    private final Path configuredStagingRoot;

    /** Spring 生产构造器：同步解析器仅作为一次 Job attempt 的 processor。 */
    @Autowired
    public KnowledgeIngestionJobService(KnowledgeIngestionJobRepository repository,
                                        KnowledgeIngestionService ingestionService,
                                        @Value("${vio.rag.ingestion.staging-root:${user.dir}/staging}") String stagingRoot) {
        this(repository, job -> ingestionService.ingest(
                job.payload().toRequest(job.idempotencyKey())), Clock.systemUTC(), DEFAULT_LEASE,
                stagingRoot);
    }

    /** 测试/嵌入式 worker 构造器；不注册为 Spring bean。 */
    public KnowledgeIngestionJobService(KnowledgeIngestionJobRepository repository,
                                        KnowledgeIngestionJobProcessor processor) {
        this(repository, processor, Clock.systemUTC(), DEFAULT_LEASE, null);
    }

    /** 测试/嵌入式 worker 构造器；不注册为 Spring bean。 */
    public KnowledgeIngestionJobService(KnowledgeIngestionJobRepository repository,
                                        KnowledgeIngestionJobProcessor processor,
                                        Clock clock, Duration lease) {
        this(repository, processor, clock, lease, null);
    }

    /** 测试/嵌入式 worker 可显式指定 staging 根，复用与生产相同的 real-path 校验。 */
    public KnowledgeIngestionJobService(KnowledgeIngestionJobRepository repository,
                                        KnowledgeIngestionJobProcessor processor,
                                        Clock clock, Duration lease, String stagingRoot) {
        if (repository == null || processor == null || clock == null || lease == null || lease.isZero()
                || lease.isNegative()) {
            throw new IllegalArgumentException("repository、processor、clock、lease 参数不合法");
        }
        this.repository = repository;
        this.processor = processor;
        this.clock = clock;
        this.lease = lease;
        this.configuredStagingRoot = normalizeRoot(stagingRoot);
    }

    /**
     * 根据隔离文件创建幂等 Job。相同 source/checksum/目标 release/contract 的提交
     * 会返回同一个 generation=0 Job；调用方可安全重试 HTTP 请求。
     */
    public KnowledgeIngestionJobRepository.ReservationResult submit(
            KnowledgeIngestionRequest request, String targetKnowledgeReleaseId,
            String ingestionContractVersion, String idempotencyKey, int maxAttempts) {
        if (request == null) throw new IllegalArgumentException("request 不能为空");
        requireText(targetKnowledgeReleaseId, "targetKnowledgeReleaseId");
        requireText(ingestionContractVersion, "ingestionContractVersion");
        requireText(idempotencyKey, "idempotencyKey");
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("maxAttempts 必须在 1 到 20 之间");
        }
        validateBounded(request.source().id(), 64, "source.id");
        validateBounded(request.source().sourceUrl(), 4096, "source.sourceUrl");
        validateBounded(request.source().license(), 128, "source.license");
        validateBounded(request.source().trustLevel().name(), 32, "source.trustLevel");
        validateOptionalBounded(request.source().title(), 256, "source.title");
        validateOptionalBounded(request.source().ownerId(), 128, "source.ownerId");
        validateBounded(request.document().id(), 64, "document.id");
        validateBounded(request.document().canonicalKey(), 256, "document.canonicalKey");
        validateBounded(request.versionNo(), 64, "versionNo");
        validateBounded(request.parserVersion(), 64, "parserVersion");
        validateBounded(request.schemaVersion(), 64, "schemaVersion");
        validateOptionalBounded(request.contentVersion(), 128, "contentVersion");
        validateBounded(targetKnowledgeReleaseId, 64, "targetKnowledgeReleaseId");
        validateBounded(ingestionContractVersion, 64, "ingestionContractVersion");
        validateBounded(idempotencyKey, 256, "idempotencyKey");
        Path file = request.stagedFile().toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("stagedFile 必须是隔离区中的普通文件");
        }
        validateStagingPath(file.toString());
        try {
            long size = Files.size(file);
            if (size <= 0 || size > DEFAULT_MAX_STAGED_BYTES) {
                throw new IllegalArgumentException("stagedFile 大小不在允许范围内");
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法读取 staging 文件大小", ex);
        }
        String checksum = sha256(file);
        Instant now = clock.instant();
        KnowledgeIngestionJob candidate = new KnowledgeIngestionJob(
                "ingestion-job-" + UUID.randomUUID(), request.source().id(), checksum,
                targetKnowledgeReleaseId.trim(), ingestionContractVersion.trim(), idempotencyKey.trim(),
                0, null, KnowledgeIngestionJobPayload.from(request),
                KnowledgeIngestionJob.JobStatus.QUEUED, 0, maxAttempts, null, null, null, null,
                now, now, null);
        return repository.createOrGet(candidate);
    }

    public Optional<KnowledgeIngestionJob> findJob(String jobId) {
        return repository.findJob(jobId);
    }

    public List<KnowledgeIngestionAttempt> findAttempts(String jobId) {
        return repository.findAttempts(jobId);
    }

    public Optional<KnowledgeIngestionDlqEntry> findDlq(String dlqId) {
        return repository.findDlq(dlqId);
    }

    public List<KnowledgeIngestionDlqEntry> findDlqByJob(String jobId) {
        return repository.findDlqByJob(jobId);
    }

    public Optional<KnowledgeIngestionJob> renewLease(String jobId, String workerId) {
        requireText(workerId, "workerId");
        return repository.renewLease(jobId, workerId.trim(), clock.instant(), lease);
    }

    /** 领取并执行一个 Job；无可用 Job 返回 empty。 */
    public Optional<JobExecutionResult> runNext(String workerId) {
        requireText(workerId, "workerId");
        return repository.reserveNext(workerId.trim(), clock.instant(), lease)
                .map(reserved -> runReserved(reserved, workerId.trim()));
    }

    /** 仅供运维/测试对指定已领取 Job 执行；不会绕过 lease owner 校验。 */
    public JobExecutionResult runReserved(KnowledgeIngestionJob reserved, String workerId) {
        if (reserved == null) throw new IllegalArgumentException("reserved job 不能为空");
        requireText(workerId, "workerId");
        KnowledgeIngestionJob execution = repository.findJob(reserved.id()).orElseThrow(() ->
                new KnowledgeIngestionNotFoundException("找不到摄取 Job: " + reserved.id()));
        if (execution.status() != KnowledgeIngestionJob.JobStatus.RESERVED
                || execution.leaseOwner() == null
                || !workerId.trim().equals(execution.leaseOwner())) {
            throw new KnowledgeIngestionConflictException("Job 未处于当前 worker 的 reservation 状态: " + reserved.id());
        }
        KnowledgeIngestionAttempt attempt = repository.startAttempt(execution.id(), workerId.trim(), clock.instant())
                .orElseThrow(() -> new KnowledgeIngestionConflictException("Job lease/状态在启动 attempt 时发生变化: " + reserved.id()));
        // 以仓储中的 processing 快照为准，避免调用方传入的旧 payload 绕过
        // checksum、staging 根和版本血缘校验。
        execution = repository.findJob(execution.id()).orElse(execution);
        KnowledgeIngestionResult result;
        try {
            validateStagingPath(execution.payload().stagedFile());
            verifyChecksum(execution);
            result = processor.process(execution);
            if (result == null || result.batch() == null) {
                throw new IllegalStateException("摄取 processor 返回空结果");
            }
            validateResult(execution, result);
        } catch (Exception ex) {
            return recordAttemptFailure(execution, workerId, attempt, classify(ex, execution, attempt.attemptNo()), null);
        }
        IngestionBatch.IngestionStatus status = result.batch().status();
        if (status == IngestionBatch.IngestionStatus.ACCEPTED) {
            // 持久化完成失败属于基础设施/租约错误，不能重新进入下面的
            // processor failure 分支，否则可能二次写 attempt 并掩盖原始异常。
            repository.completeSuccess(execution.id(), workerId.trim(), result.batch().id(), clock.instant());
            return new JobExecutionResult(execution.id(), attempt.attemptNo(),
                    KnowledgeIngestionJob.JobStatus.SUCCEEDED, null, result);
        }
        KnowledgeIngestionJobRepository.Failure failure = failureForBatch(result.batch());
        return recordAttemptFailure(execution, workerId, attempt, failure, result);
    }

    private JobExecutionResult recordAttemptFailure(KnowledgeIngestionJob reserved, String workerId,
                                                    KnowledgeIngestionAttempt attempt,
                                                    KnowledgeIngestionJobRepository.Failure failure,
                                                    KnowledgeIngestionResult result) {
        KnowledgeIngestionJobRepository.FailureTransition transition = repository.recordFailure(
                reserved.id(), workerId.trim(), failure, clock.instant());
        LOG.warn("知识摄取 Job attempt failed jobId={}, attemptNo={}, status={}, code={}", reserved.id(),
                attempt.attemptNo(), transition.job().status(), failure.errorCode());
        return new JobExecutionResult(reserved.id(), attempt.attemptNo(), transition.job().status(),
                transition.job().status() == KnowledgeIngestionJob.JobStatus.DLQ ? failure.errorCode() : null,
                result);
    }

    /** 发布 pending outbox；sink 成功才确认，失败保留 at-least-once 重试记录。 */
    public OutboxPublishReport publishPending(OutboxSink sink, int limit) {
        if (sink == null) throw new IllegalArgumentException("sink 不能为空");
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("limit 必须在 1 到 1000 之间");
        int published = 0;
        int failed = 0;
        for (KnowledgeIngestionOutboxEvent event : repository.findPendingOutbox(clock.instant(), limit)) {
            try {
                sink.publish(event);
                repository.markOutboxPublished(event.id(), clock.instant());
                published++;
            } catch (Exception ex) {
                failed++;
                long backoff = Math.min(Duration.ofMinutes(5).toMillis(),
                        1000L * (1L << Math.min(8, event.publishAttempts())));
                repository.markOutboxFailed(event.id(), safeMessage(ex),
                        clock.instant().plusMillis(backoff), clock.instant());
            }
        }
        return new OutboxPublishReport(published, failed);
    }

    public KnowledgeIngestionJob replayDlq(String dlqId, String operatorId, String reason,
                                           String newIdempotencyKey) {
        requireText(operatorId, "operatorId");
        requireText(reason, "reason");
        requireText(newIdempotencyKey, "newIdempotencyKey");
        return repository.replayDlq(dlqId.trim(), operatorId.trim(), reason.trim(),
                newIdempotencyKey.trim(), clock.instant());
    }

    private KnowledgeIngestionJobRepository.Failure failureForBatch(IngestionBatch batch) {
        String code = batch.status() == IngestionBatch.IngestionStatus.QUARANTINED
                ? "INGESTION_QUARANTINED" : "INGESTION_FAILED";
        KnowledgeIngestionJobRepository.FailureDisposition disposition =
                batch.status() == IngestionBatch.IngestionStatus.QUARANTINED
                        ? KnowledgeIngestionJobRepository.FailureDisposition.NON_RETRYABLE
                        : KnowledgeIngestionJobRepository.FailureDisposition.SYSTEM;
        return new KnowledgeIngestionJobRepository.Failure("BATCH_" + batch.status().name(), code,
                sanitize(batch.errorMessage()), disposition, 0, 0, null);
    }

    private KnowledgeIngestionJobRepository.Failure classify(Exception ex, KnowledgeIngestionJob job,
                                                              int attemptNo) {
        KnowledgeIngestionJobRepository.FailureDisposition disposition;
        if (ex instanceof IllegalArgumentException || ex instanceof SecurityException) {
            disposition = KnowledgeIngestionJobRepository.FailureDisposition.NON_RETRYABLE;
        } else if (ex instanceof IOException || ex instanceof SQLTransientException
                || ex instanceof KnowledgeIndexingUnavailableException || ex instanceof TimeoutException) {
            disposition = KnowledgeIngestionJobRepository.FailureDisposition.TRANSIENT;
        } else {
            disposition = KnowledgeIngestionJobRepository.FailureDisposition.SYSTEM;
        }
        long backoff = 0;
        long jitter = 0;
        Instant next = null;
        if (disposition == KnowledgeIngestionJobRepository.FailureDisposition.TRANSIENT
                && attemptNo < job.maxAttempts()) {
            backoff = Math.min(Duration.ofMinutes(5).toMillis(), 1000L << Math.min(8, attemptNo - 1));
            // 稳定伪随机 jitter：同一 Job/attempt 在重放和多节点间不会漂移。
            jitter = Math.floorMod((job.id() + ":" + attemptNo).hashCode(), 251);
            next = clock.instant().plusMillis(backoff + jitter);
        }
        return new KnowledgeIngestionJobRepository.Failure(ex.getClass().getSimpleName(),
                disposition == KnowledgeIngestionJobRepository.FailureDisposition.NON_RETRYABLE
                        ? "VALIDATION_REJECTED" : disposition == KnowledgeIngestionJobRepository.FailureDisposition.TRANSIENT
                        ? "DEPENDENCY_RETRYABLE" : "SYSTEM_MANUAL_REVIEW",
                sanitize(safeMessage(ex)), disposition, backoff, jitter, next);
    }

    private String sha256(Path path) {
        try {
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) {
                throw new IOException("staging 文件不是普通文件");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            // 文件在 hash 期间被替换/追加时，不能把不一致的内容登记为可重试
            // Job；下一次显式提交应从稳定 staging 文件重新计算 checksum。
            if (!after.isRegularFile() || before.size() != after.size()
                    || (before.fileKey() != null && !before.fileKey().equals(after.fileKey()))) {
                throw new IOException("staging 文件在 checksum 计算期间发生变化");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法读取 staging 文件", ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 不支持 SHA-256", ex);
        }
    }

    private void verifyChecksum(KnowledgeIngestionJob job) {
        String actual = sha256(Path.of(job.payload().stagedFile()).toAbsolutePath().normalize());
        if (!job.contentChecksum().equalsIgnoreCase(actual)) {
            throw new IllegalArgumentException("staging 文件 checksum 在排队后发生变化");
        }
    }

    private void validateResult(KnowledgeIngestionJob job, KnowledgeIngestionResult result) {
        IngestionBatch batch = result.batch();
        if (!job.sourceId().equals(batch.sourceId())) {
            throw new IllegalStateException("processor 返回的 batch 不属于当前 source");
        }
        if (batch.idempotencyKey() != null && !batch.idempotencyKey().isBlank()
                && !job.idempotencyKey().equals(batch.idempotencyKey())) {
            throw new IllegalStateException("processor 返回的 batch 幂等键与 Job 不一致");
        }
    }

    /** 重启后的 Job 也必须重新验证隔离路径，不能信任数据库里的旧路径字符串。 */
    private void validateStagingPath(String stagedFile) {
        requireText(stagedFile, "stagedFile");
        Path file = Path.of(stagedFile).toAbsolutePath().normalize();
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IllegalArgumentException("Job staging 文件不存在或不是普通文件");
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Job staging 文件不存在或不是普通文件");
        }
        String configuredRoot = configuredStagingRoot == null ? null : configuredStagingRoot.toString();
        if (configuredRoot == null || configuredRoot.isBlank()) {
            configuredRoot = System.getProperty("vio.rag.ingestion.staging-root");
            if (configuredRoot == null || configuredRoot.isBlank()) {
                configuredRoot = System.getenv("VIO_RAG_INGESTION_STAGING_ROOT");
            }
        }
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            try {
                Path root = Path.of(configuredRoot).toAbsolutePath().normalize().toRealPath();
                Path real = file.toRealPath();
                if (!real.startsWith(root)) {
                    throw new IllegalArgumentException("Job staging 文件越过隔离根目录");
                }
            } catch (IOException ex) {
                throw new IllegalArgumentException("Job staging 隔离根目录不可读", ex);
            }
        }
    }

    private String safeMessage(Exception ex) {
        return sanitize(ex == null ? null : ex.getMessage());
    }

    private String sanitize(String value) {
        return KnowledgeIngestionErrorSanitizer.sanitize(value);
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
    }

    private void validateBounded(String value, int max, String name) {
        requireText(value, name);
        if (value.length() > max || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " 长度或字符不合法");
        }
    }

    private void validateOptionalBounded(String value, int max, String name) {
        if (value != null) validateBounded(value, max, name);
    }

    private Path normalizeRoot(String root) {
        if (root == null || root.isBlank()) return null;
        try {
            return Path.of(root).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("staging-root 配置不合法", ex);
        }
    }

    public record JobExecutionResult(String jobId, int attemptNo, KnowledgeIngestionJob.JobStatus status,
                                     String terminalErrorCode, KnowledgeIngestionResult ingestionResult) {
    }

    public record OutboxPublishReport(int published, int failed) {
    }

    @FunctionalInterface
    public interface OutboxSink {
        void publish(KnowledgeIngestionOutboxEvent event) throws Exception;
    }
}
