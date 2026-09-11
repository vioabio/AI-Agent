package com.vio.vioaiagent.rag.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 摄取 Job 的持久化端口。
 *
 * <p>所有会改变状态的方法都必须在实现中以单事务/CAS 方式执行。这样服务层
 * 可以在本地使用内存实现，在 prod profile 使用 PostgreSQL 实现，而不会把
 * reservation 语义散落在 Controller 或 worker 循环里。</p>
 */
public interface KnowledgeIngestionJobRepository {

    ReservationResult createOrGet(KnowledgeIngestionJob candidate);

    Optional<KnowledgeIngestionJob> findJob(String jobId);

    /** 原子领取一个到期或待处理 Job；没有可领取任务时返回 empty。 */
    Optional<KnowledgeIngestionJob> reserveNext(String workerId, Instant now, Duration lease);

    Optional<KnowledgeIngestionJob> renewLease(String jobId, String workerId, Instant now, Duration lease);

    Optional<KnowledgeIngestionAttempt> startAttempt(String jobId, String workerId, Instant now);

    void completeSuccess(String jobId, String workerId, String batchId, Instant now);

    /** 记录失败并按策略转 RETRY_WAIT 或 DLQ。 */
    FailureTransition recordFailure(String jobId, String workerId, Failure failure, Instant now);

    List<KnowledgeIngestionAttempt> findAttempts(String jobId);

    List<KnowledgeIngestionOutboxEvent> findPendingOutbox(Instant now, int limit);

    Optional<KnowledgeIngestionOutboxEvent> findOutbox(String eventId);

    void markOutboxPublished(String eventId, Instant now);

    void markOutboxFailed(String eventId, String errorMessage, Instant nextAttemptAt, Instant now);

    Optional<KnowledgeIngestionDlqEntry> findDlq(String dlqId);

    List<KnowledgeIngestionDlqEntry> findDlqByJob(String jobId);

    /** 由具备 operator 权限的调用方创建新的 replay generation。 */
    KnowledgeIngestionJob replayDlq(String dlqId, String operatorId, String reason,
                                     String newIdempotencyKey, Instant now);

    record ReservationResult(KnowledgeIngestionJob job, boolean created) {
        public ReservationResult {
            if (job == null) throw new IllegalArgumentException("job 不能为空");
        }
    }

    record Failure(String errorClass, String errorCode, String errorMessage,
                   FailureDisposition disposition, long backoffMs, long jitterMs,
                   Instant nextAttemptAt) {
        public Failure {
            errorClass = bounded(normalize(errorClass, "UNKNOWN"), 128, "errorClass");
            errorCode = bounded(normalize(errorCode, "INGESTION_FAILED"), 128, "errorCode");
            // Failure is also an in-process hand-off object.  Sanitize here as
            // well as at repository boundaries so a custom implementation cannot
            // accidentally expose a token before persistence.
            errorMessage = bounded(KnowledgeIngestionErrorSanitizer.sanitize(
                    normalize(errorMessage, "摄取失败")), 1000, "errorMessage");
            disposition = disposition == null ? FailureDisposition.SYSTEM : disposition;
            if (backoffMs < 0 || jitterMs < 0) {
                throw new IllegalArgumentException("backoff/jitter 不能为负数");
            }
        }

        private static String normalize(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static String bounded(String value, int max, String name) {
            if (value == null || value.isBlank() || value.length() > max || value.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException(name + " 长度或字符不合法");
            }
            return value;
        }
    }

    enum FailureDisposition { NON_RETRYABLE, TRANSIENT, SYSTEM }

    record FailureTransition(KnowledgeIngestionJob job, KnowledgeIngestionAttempt attempt,
                             Optional<KnowledgeIngestionDlqEntry> dlqEntry) {
        public FailureTransition {
            if (job == null || attempt == null || dlqEntry == null) {
                throw new IllegalArgumentException("failure transition 结果不能为空");
            }
        }

        public boolean movedToDlq() {
            return dlqEntry.isPresent();
        }
    }
}
