package com.vio.vioaiagent.rag.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL 实现。reservation、attempt 记录、Job 终态和 outbox/DLQ 写入均在
 * 单事务中完成；生产实例通过 {@code FOR UPDATE SKIP LOCKED} 竞争同一队列。
 */
@Repository
@Primary
@Profile({"prod", "demo"})
public class JdbcKnowledgeIngestionJobRepository implements KnowledgeIngestionJobRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcKnowledgeIngestionJobRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        if (jdbcTemplate == null || objectMapper == null) {
            throw new IllegalArgumentException("jdbcTemplate/objectMapper 不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ReservationResult createOrGet(KnowledgeIngestionJob candidate) {
        if (candidate == null) throw new IllegalArgumentException("candidate 不能为空");
        validateCandidate(candidate);
        // Job 表对 source_id 有外键。异步 worker 尚未开始解析时，来源必须先在
        // 同一事务中登记；否则一个全新的 source 会在提交阶段因 FK 失败，直到
        // worker 才有机会执行 KnowledgeIngestionService.saveSource。仅 INSERT
        // 缺失来源，绝不用 Job 请求悄悄覆盖已有来源的核心治理字段。
        ensureSource(candidate.payload());
        ensureCandidateRelease(candidate.targetKnowledgeReleaseId());
        String payload = writePayload(candidate.payload());
        int inserted = jdbcTemplate.update("""
                INSERT INTO knowledge_ingestion_job
                    (id, source_id, content_checksum, target_knowledge_release_id,
                     ingestion_contract_version, idempotency_key, replay_generation, replay_of_job_id,
                     request_payload, status, attempt_count, max_attempts, next_attempt_at,
                     lease_owner, lease_expires_at, result_batch_id, created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, candidate.id(), candidate.sourceId(), normalizeChecksum(candidate.contentChecksum()),
                candidate.targetKnowledgeReleaseId(), candidate.ingestionContractVersion(), candidate.idempotencyKey(),
                candidate.replayGeneration(), candidate.replayOfJobId(), payload, candidate.status().name(),
                candidate.attemptCount(), candidate.maxAttempts(), timestamp(candidate.nextAttemptAt()),
                candidate.leaseOwner(), timestamp(candidate.leaseExpiresAt()), candidate.resultBatchId(),
                timestamp(candidate.createdAt()), timestamp(candidate.updatedAt()), timestamp(candidate.completedAt()));
        Optional<KnowledgeIngestionJob> existingByIdempotency =
                findByIdempotency(candidate.sourceId(), candidate.idempotencyKey());
        if (existingByIdempotency.isPresent() && !sameReservation(existingByIdempotency.get(), candidate)) {
            throw new KnowledgeIngestionConflictException("idempotencyKey 已用于不同的摄取 reservation: "
                    + candidate.idempotencyKey());
        }
        Optional<KnowledgeIngestionJob> existing = findByReservation(candidate);
        if (existing.isEmpty()) existing = existingByIdempotency;
        if (existing.isEmpty()) {
            throw new IllegalStateException("摄取 Job reservation 写入后无法回读: " + candidate.id());
        }
        return new ReservationResult(existing.get(), inserted == 1);
    }

    @Override
    public Optional<KnowledgeIngestionJob> findJob(String jobId) {
        if (jobId == null || jobId.isBlank()) return Optional.empty();
        return jdbcTemplate.query("SELECT " + JOB_COLUMNS + " FROM knowledge_ingestion_job WHERE id = ?",
                this::mapJob, jobId.trim()).stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<KnowledgeIngestionJob> reserveNext(String workerId, Instant now, Duration lease) {
        requireText(workerId, "workerId");
        validateWorkerId(workerId);
        if (now == null || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("now/lease 参数不合法");
        }
        // 先关闭过期 attempt，保证重启/抢占在审计上可见。
        jdbcTemplate.update("""
                UPDATE knowledge_ingestion_attempt a
                   SET status = 'ABANDONED', error_class = 'LEASE_EXPIRED',
                       error_code = 'LEASE_EXPIRED', error_message = 'worker lease 已过期',
                       completed_at = ?
                 WHERE a.status = 'RUNNING'
                   AND EXISTS (SELECT 1 FROM knowledge_ingestion_job j
                               WHERE j.id = a.job_id
                                 AND j.lease_expires_at IS NOT NULL
                                 AND j.lease_expires_at <= ?
                                 AND j.status IN ('RESERVED','PROCESSING'))
                 """, timestamp(now), timestamp(now));
        moveExhaustedExpiredToDlq(now);
        Instant expires = now.plus(lease);
        List<KnowledgeIngestionJob> rows = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT id
                      FROM knowledge_ingestion_job
                     WHERE (status = 'QUEUED' AND attempt_count < max_attempts
                            OR (status = 'RETRY_WAIT' AND attempt_count < max_attempts
                                AND next_attempt_at IS NOT NULL AND next_attempt_at <= ?)
                            OR (status IN ('RESERVED','PROCESSING')
                                AND lease_expires_at IS NOT NULL AND lease_expires_at <= ?))
                     ORDER BY created_at, id
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                )
                UPDATE knowledge_ingestion_job j
                   SET status = 'RESERVED', next_attempt_at = NULL,
                       lease_owner = ?, lease_expires_at = ?, updated_at = ?
                  FROM candidate c
                 WHERE j.id = c.id
                 RETURNING """ + JOB_COLUMNS_PREFIXED, this::mapJob,
                timestamp(now), timestamp(now), workerId.trim(), timestamp(expires), timestamp(now));
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<KnowledgeIngestionAttempt> startAttempt(String jobId, String workerId, Instant now) {
        requireText(jobId, "jobId");
        requireText(workerId, "workerId");
        validateWorkerId(workerId);
        if (now == null) throw new IllegalArgumentException("now 不能为空");
        List<KnowledgeIngestionJob> rows = jdbcTemplate.query("""
                UPDATE knowledge_ingestion_job
                   SET status = 'PROCESSING', attempt_count = attempt_count + 1, updated_at = ?
                 WHERE id = ? AND status = 'RESERVED' AND lease_owner = ?
                   AND attempt_count < max_attempts
                   AND lease_expires_at IS NOT NULL AND lease_expires_at > ?
                RETURNING """ + JOB_COLUMNS, this::mapJob,
                timestamp(now), jobId.trim(), workerId.trim(), timestamp(now));
        KnowledgeIngestionJob job = rows.stream().findFirst().orElse(null);
        if (job == null) return Optional.empty();
        KnowledgeIngestionAttempt attempt = new KnowledgeIngestionAttempt(
                "ingestion-attempt-" + UUID.randomUUID(), job.id(), job.attemptCount(),
                KnowledgeIngestionAttempt.AttemptStatus.RUNNING, null, null, null, 0, 0,
                workerId.trim(), now, null);
        jdbcTemplate.update("""
                INSERT INTO knowledge_ingestion_attempt
                    (id, job_id, attempt_no, status, error_class, error_code, error_message,
                     backoff_ms, jitter_ms, lease_owner, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, attempt.id(), attempt.jobId(), attempt.attemptNo(), attempt.status().name(),
                null, null, null, 0, 0, attempt.leaseOwner(), timestamp(now), null);
        return Optional.of(attempt);
    }

    @Override
    @Transactional
    public Optional<KnowledgeIngestionJob> renewLease(String jobId, String workerId, Instant now, Duration lease) {
        requireText(jobId, "jobId");
        requireText(workerId, "workerId");
        validateWorkerId(workerId);
        if (now == null || lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("now/lease 参数不合法");
        }
        Instant expires = now.plus(lease);
        return jdbcTemplate.query("""
                UPDATE knowledge_ingestion_job
                   SET lease_expires_at = ?, updated_at = ?
                 WHERE id = ? AND status IN ('RESERVED','PROCESSING') AND lease_owner = ?
                   AND lease_expires_at IS NOT NULL AND lease_expires_at > ?
                RETURNING """ + JOB_COLUMNS, this::mapJob, timestamp(expires), timestamp(now), jobId.trim(),
                workerId.trim(), timestamp(now)).stream().findFirst();
    }

    @Override
    @Transactional
    public void completeSuccess(String jobId, String workerId, String batchId, Instant now) {
        requireText(jobId, "jobId");
        requireText(workerId, "workerId");
        requireText(batchId, "batchId");
        if (now == null) throw new IllegalArgumentException("now 不能为空");
        KnowledgeIngestionJob job = ownedJob(jobId, workerId, now);
        int attemptUpdated = jdbcTemplate.update("""
                UPDATE knowledge_ingestion_attempt
                   SET status = 'SUCCEEDED', completed_at = ?
                 WHERE job_id = ? AND attempt_no = ? AND status = 'RUNNING'
                """, timestamp(now), job.id(), job.attemptCount());
        if (attemptUpdated != 1) throw new KnowledgeIngestionConflictException("找不到运行中的 attempt: " + job.id());
        int updated = jdbcTemplate.update("""
                UPDATE knowledge_ingestion_job
                   SET status = 'SUCCEEDED', result_batch_id = ?, lease_owner = NULL,
                       lease_expires_at = NULL, completed_at = ?, updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING' AND lease_owner = ?
                """, batchId.trim(), timestamp(now), timestamp(now), job.id(), workerId.trim());
        if (updated != 1) throw new KnowledgeIngestionConflictException("Job lease/状态在完成时发生变化: " + job.id());
        insertOutbox(job.id(), "knowledge.ingestion.succeeded", payload(job.id(), "SUCCEEDED", job.contentChecksum()), now);
    }

    @Override
    @Transactional
    public FailureTransition recordFailure(String jobId, String workerId, Failure failure, Instant now) {
        if (failure == null) throw new IllegalArgumentException("failure 不能为空");
        if (now == null) throw new IllegalArgumentException("now 不能为空");
        KnowledgeIngestionJob job = ownedJob(jobId, workerId, now);
        KnowledgeIngestionAttempt attempt = jdbcTemplate.query("""
                SELECT id, job_id, attempt_no, status, error_class, error_code, error_message,
                       backoff_ms, jitter_ms, lease_owner, started_at, completed_at
                  FROM knowledge_ingestion_attempt
                 WHERE job_id = ? AND attempt_no = ? AND status = 'RUNNING'
                """, this::mapAttempt, job.id(), job.attemptCount()).stream().findFirst()
                .orElseThrow(() -> new KnowledgeIngestionConflictException("找不到运行中的 attempt: " + job.id()));
        jdbcTemplate.update("""
                UPDATE knowledge_ingestion_attempt
                   SET status = 'FAILED', error_class = ?, error_code = ?, error_message = ?,
                       backoff_ms = ?, jitter_ms = ?, completed_at = ?
                 WHERE id = ? AND status = 'RUNNING'
                """, failure.errorClass(), failure.errorCode(), safeMessage(failure.errorMessage()),
                failure.backoffMs(), failure.jitterMs(), timestamp(now), attempt.id());
        boolean retry = failure.disposition() == FailureDisposition.TRANSIENT
                && job.attemptCount() < job.maxAttempts() && failure.nextAttemptAt() != null;
        KnowledgeIngestionJob.JobStatus nextStatus = retry
                ? KnowledgeIngestionJob.JobStatus.RETRY_WAIT : KnowledgeIngestionJob.JobStatus.DLQ;
        int updated = jdbcTemplate.update("""
                UPDATE knowledge_ingestion_job
                   SET status = ?, next_attempt_at = ?, lease_owner = NULL, lease_expires_at = NULL,
                       updated_at = ?, completed_at = CASE WHEN ? = 'DLQ' THEN ? ELSE completed_at END
                 WHERE id = ? AND status = 'PROCESSING' AND lease_owner = ?
                """, nextStatus.name(), timestamp(retry ? failure.nextAttemptAt() : null), timestamp(now),
                nextStatus.name(), timestamp(nextStatus == KnowledgeIngestionJob.JobStatus.DLQ ? now : null),
                job.id(), workerId.trim());
        if (updated != 1) throw new KnowledgeIngestionConflictException("Job lease/状态在失败记录时发生变化: " + job.id());
        KnowledgeIngestionJob resulting = findJob(job.id()).orElseThrow();
        Optional<KnowledgeIngestionDlqEntry> dlqEntry = Optional.empty();
        if (retry) {
            insertOutbox(job.id(), "knowledge.ingestion.retry_scheduled",
                    payload(job.id(), nextStatus.name(), job.contentChecksum()), now);
        } else {
            KnowledgeIngestionDlqEntry entry = new KnowledgeIngestionDlqEntry(
                    "ingestion-dlq-" + UUID.randomUUID(), job.id(), job.contentChecksum(), attempt.attemptNo(),
                    failure.errorClass(), failure.errorCode(), safeMessage(failure.errorMessage()),
                     now.plus(Duration.ofDays(90)), null, null, null, now, null);
            jdbcTemplate.update("""
                    INSERT INTO knowledge_ingestion_dlq
                        (id, job_id, original_checksum, last_attempt_no, error_class, error_code,
                         error_message, retention_until, replayed_by, replay_reason, replay_job_id,
                         created_at, replayed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (job_id) DO UPDATE SET
                        original_checksum = EXCLUDED.original_checksum,
                        last_attempt_no = EXCLUDED.last_attempt_no,
                        error_class = EXCLUDED.error_class,
                        error_code = EXCLUDED.error_code,
                        error_message = EXCLUDED.error_message,
                        retention_until = EXCLUDED.retention_until
                    """, entry.id(), entry.jobId(), entry.originalChecksum(), entry.lastAttemptNo(),
                    entry.errorClass(), entry.errorCode(), entry.errorMessage(), timestamp(entry.retentionUntil()),
                    null, null, null, timestamp(entry.createdAt()), null);
            KnowledgeIngestionDlqEntry persisted = jdbcTemplate.query("""
                    SELECT id, job_id, original_checksum, last_attempt_no, error_class, error_code,
                           error_message, retention_until, replayed_by, replay_reason, replay_job_id,
                           created_at, replayed_at
                      FROM knowledge_ingestion_dlq WHERE job_id = ?
                    """, this::mapDlq, job.id()).stream().findFirst().orElse(entry);
            dlqEntry = Optional.of(persisted);
            insertOutbox(job.id(), "knowledge.ingestion.dlq",
                    payload(job.id(), nextStatus.name(), job.contentChecksum()), now);
        }
        KnowledgeIngestionAttempt persistedAttempt = jdbcTemplate.query("""
                SELECT id, job_id, attempt_no, status, error_class, error_code, error_message,
                       backoff_ms, jitter_ms, lease_owner, started_at, completed_at
                  FROM knowledge_ingestion_attempt WHERE id = ?
                """, this::mapAttempt, attempt.id()).stream().findFirst().orElseThrow();
        return new FailureTransition(resulting, persistedAttempt, dlqEntry);
    }

    @Override
    public List<KnowledgeIngestionAttempt> findAttempts(String jobId) {
        if (jobId == null || jobId.isBlank()) return List.of();
        return jdbcTemplate.query("""
                SELECT id, job_id, attempt_no, status, error_class, error_code, error_message,
                       backoff_ms, jitter_ms, lease_owner, started_at, completed_at
                  FROM knowledge_ingestion_attempt WHERE job_id = ? ORDER BY attempt_no
                """, this::mapAttempt, jobId.trim());
    }

    @Override
    public List<KnowledgeIngestionOutboxEvent> findPendingOutbox(Instant now, int limit) {
        if (now == null || limit <= 0) return List.of();
        return jdbcTemplate.query("""
                SELECT id, job_id, event_type, payload, status, publish_attempts, next_attempt_at,
                       last_error, created_at, published_at
                  FROM knowledge_ingestion_outbox
                 WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                 ORDER BY created_at, id LIMIT ?
                """, this::mapOutbox, timestamp(now), limit);
    }

    @Override
    public Optional<KnowledgeIngestionOutboxEvent> findOutbox(String eventId) {
        if (eventId == null || eventId.isBlank()) return Optional.empty();
        return jdbcTemplate.query("""
                SELECT id, job_id, event_type, payload, status, publish_attempts, next_attempt_at,
                       last_error, created_at, published_at
                  FROM knowledge_ingestion_outbox WHERE id = ?
                """, this::mapOutbox, eventId.trim()).stream().findFirst();
    }

    @Override
    @Transactional
    public void markOutboxPublished(String eventId, Instant now) {
        if (now == null) throw new IllegalArgumentException("now 不能为空");
        requireText(eventId, "eventId");
        int updated = jdbcTemplate.update("""
                UPDATE knowledge_ingestion_outbox
                   SET status = 'PUBLISHED', published_at = ?, next_attempt_at = NULL
                 WHERE id = ? AND status = 'PENDING'
                """, timestamp(now), eventId.trim());
        if (updated != 1) throw new IllegalStateException("outbox 事件不存在或已确认: " + eventId);
    }

    @Override
    @Transactional
    public void markOutboxFailed(String eventId, String errorMessage, Instant nextAttemptAt, Instant now) {
        requireText(eventId, "eventId");
        if (now == null) throw new IllegalArgumentException("now 不能为空");
        Instant scheduledAt = nextAttemptAt == null || !nextAttemptAt.isAfter(now)
                ? now.plusMillis(1) : nextAttemptAt;
        int updated = jdbcTemplate.update("""
                UPDATE knowledge_ingestion_outbox
                   SET publish_attempts = publish_attempts + 1,
                       status = CASE WHEN publish_attempts + 1 >= 5 THEN 'FAILED' ELSE 'PENDING' END,
                       next_attempt_at = CASE WHEN publish_attempts + 1 >= 5 THEN NULL ELSE ? END,
                       last_error = ?
                 WHERE id = ? AND status = 'PENDING'
                """, timestamp(scheduledAt), safeMessage(errorMessage), eventId.trim());
        if (updated != 1) throw new IllegalStateException("outbox 事件不存在或已结束: " + eventId);
    }

    @Override
    public Optional<KnowledgeIngestionDlqEntry> findDlq(String dlqId) {
        if (dlqId == null || dlqId.isBlank()) return Optional.empty();
        return jdbcTemplate.query("""
                SELECT id, job_id, original_checksum, last_attempt_no, error_class, error_code,
                       error_message, retention_until, replayed_by, replay_reason, replay_job_id,
                       created_at, replayed_at
                  FROM knowledge_ingestion_dlq WHERE id = ?
                """, this::mapDlq, dlqId.trim()).stream().findFirst();
    }

    @Override
    public List<KnowledgeIngestionDlqEntry> findDlqByJob(String jobId) {
        if (jobId == null || jobId.isBlank()) return List.of();
        return jdbcTemplate.query("""
                SELECT id, job_id, original_checksum, last_attempt_no, error_class, error_code,
                       error_message, retention_until, replayed_by, replay_reason, replay_job_id,
                       created_at, replayed_at
                  FROM knowledge_ingestion_dlq WHERE job_id = ? ORDER BY created_at
                """, this::mapDlq, jobId.trim());
    }

    @Override
    @Transactional
    public KnowledgeIngestionJob replayDlq(String dlqId, String operatorId, String reason,
                                           String newIdempotencyKey, Instant now) {
        requireText(dlqId, "dlqId");
        requireText(operatorId, "operatorId");
        requireText(reason, "reason");
        requireText(newIdempotencyKey, "newIdempotencyKey");
        validateLength(dlqId.trim(), 96, "dlqId");
        validateLength(operatorId.trim(), 128, "operatorId");
        validateLength(reason.trim(), 2000, "reason");
        validateLength(newIdempotencyKey.trim(), 256, "newIdempotencyKey");
        KnowledgeIngestionDlqEntry entry = jdbcTemplate.query("""
                SELECT id, job_id, original_checksum, last_attempt_no, error_class, error_code,
                       error_message, retention_until, replayed_by, replay_reason, replay_job_id,
                       created_at, replayed_at
                  FROM knowledge_ingestion_dlq WHERE id = ? FOR UPDATE
                """, this::mapDlq, dlqId.trim()).stream().findFirst().orElseThrow(() ->
                new KnowledgeIngestionNotFoundException("找不到 DLQ: " + dlqId));
        if (entry.replayJobId() != null) {
            throw new KnowledgeIngestionConflictException("DLQ 已 replay: " + entry.id());
        }
        Instant replayNow = now == null ? Instant.now() : now;
        if (entry.retentionUntil() != null && !entry.retentionUntil().isAfter(replayNow)) {
            throw new KnowledgeIngestionConflictException("DLQ 已超过保留期限: " + entry.id());
        }
        KnowledgeIngestionJob original = findJob(entry.jobId()).orElseThrow(() ->
                new KnowledgeIngestionNotFoundException("DLQ 原 Job 不存在: " + entry.jobId()));
        if (original.status() != KnowledgeIngestionJob.JobStatus.DLQ) {
            throw new KnowledgeIngestionConflictException("DLQ 原 Job 当前不可 replay: " + original.id());
        }
        if (original.idempotencyKey().equals(newIdempotencyKey.trim())) {
            throw new KnowledgeIngestionConflictException("replay 必须使用新的 idempotencyKey");
        }
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(replay_generation), ?) FROM knowledge_ingestion_job
                 WHERE replay_of_job_id = ?
                """, Integer.class, original.replayGeneration(), original.id());
        int generation = (max == null ? original.replayGeneration() : max) + 1;
        Instant created = replayNow;
        KnowledgeIngestionJob replay = new KnowledgeIngestionJob(
                "ingestion-job-" + UUID.randomUUID(), original.sourceId(), original.contentChecksum(),
                original.targetKnowledgeReleaseId(), original.ingestionContractVersion(), newIdempotencyKey.trim(),
                generation, original.id(), original.payload(), KnowledgeIngestionJob.JobStatus.QUEUED, 0,
                original.maxAttempts(), null, null, null, null, created, created, null);
        int inserted = jdbcTemplate.update("""
                INSERT INTO knowledge_ingestion_job
                    (id, source_id, content_checksum, target_knowledge_release_id,
                     ingestion_contract_version, idempotency_key, replay_generation, replay_of_job_id,
                     request_payload, status, attempt_count, max_attempts, next_attempt_at,
                     lease_owner, lease_expires_at, result_batch_id, created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'QUEUED', 0, ?, ?, NULL, NULL, NULL, ?, ?, NULL)
                ON CONFLICT DO NOTHING
                """, replay.id(), replay.sourceId(), replay.contentChecksum(), replay.targetKnowledgeReleaseId(),
                replay.ingestionContractVersion(), replay.idempotencyKey(), replay.replayGeneration(), replay.replayOfJobId(),
                writePayload(replay.payload()), replay.maxAttempts(), null, timestamp(created), timestamp(created));
        if (inserted != 1) throw new KnowledgeIngestionConflictException("replay reservation 冲突");
        int marked = jdbcTemplate.update("""
                UPDATE knowledge_ingestion_dlq
                   SET replayed_by = ?, replay_reason = ?, replay_job_id = ?, replayed_at = ?
                 WHERE id = ? AND replay_job_id IS NULL
                """, operatorId.trim(), reason.trim(), replay.id(), timestamp(created), entry.id());
        if (marked != 1) throw new KnowledgeIngestionConflictException("DLQ 已被其他操作 replay: " + entry.id());
        insertOutbox(replay.id(), "knowledge.ingestion.replayed",
                payload(replay.id(), replay.status().name(), replay.contentChecksum()), created);
        return replay;
    }

    private Optional<KnowledgeIngestionJob> findByReservation(KnowledgeIngestionJob value) {
        return jdbcTemplate.query("SELECT " + JOB_COLUMNS + " FROM knowledge_ingestion_job "
                        + "WHERE source_id = ? AND lower(content_checksum) = lower(?) "
                        + "AND target_knowledge_release_id = ? AND ingestion_contract_version = ? "
                        + "AND replay_generation = ? ORDER BY created_at LIMIT 1",
                this::mapJob, value.sourceId(), normalizeChecksum(value.contentChecksum()), value.targetKnowledgeReleaseId(),
                value.ingestionContractVersion(), value.replayGeneration()).stream().findFirst();
    }

    /**
     * 将已耗尽尝试次数且租约过期的执行态任务收敛到 DLQ，避免它们被反复
     * reservation 或永久卡在 PROCESSING。调用方已处于事务中，行锁保证跨实例只处理一次。
     */
    private void moveExhaustedExpiredToDlq(Instant now) {
        List<KnowledgeIngestionJob> exhausted = jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM knowledge_ingestion_job "
                        + "WHERE status IN ('RESERVED','PROCESSING') "
                        + "AND lease_expires_at IS NOT NULL AND lease_expires_at <= ? "
                        + "AND attempt_count >= max_attempts ORDER BY created_at, id "
                        + "LIMIT 100 FOR UPDATE SKIP LOCKED",
                this::mapJob, timestamp(now));
        for (KnowledgeIngestionJob job : exhausted) {
            jdbcTemplate.update("""
                    UPDATE knowledge_ingestion_attempt
                       SET status = 'ABANDONED', error_class = 'LEASE_EXPIRED',
                           error_code = 'MAX_ATTEMPTS_EXCEEDED',
                           error_message = '租约过期且已达到最大尝试次数', completed_at = ?
                     WHERE job_id = ? AND status = 'RUNNING'
                    """, timestamp(now), job.id());
            jdbcTemplate.update("""
                    UPDATE knowledge_ingestion_job
                       SET status = 'DLQ', next_attempt_at = NULL, lease_owner = NULL,
                           lease_expires_at = NULL, completed_at = ?, updated_at = ?
                     WHERE id = ? AND status IN ('RESERVED','PROCESSING')
                    """, timestamp(now), timestamp(now), job.id());
            String dlqId = "ingestion-dlq-" + UUID.randomUUID();
            int dlqInserted = jdbcTemplate.update("""
                    INSERT INTO knowledge_ingestion_dlq
                        (id, job_id, original_checksum, last_attempt_no, error_class, error_code,
                         error_message, retention_until, replayed_by, replay_reason, replay_job_id,
                         created_at, replayed_at)
                    VALUES (?, ?, ?, ?, 'LEASE_EXPIRED', 'MAX_ATTEMPTS_EXCEEDED', ?, ?, NULL, NULL, NULL, ?, NULL)
                    ON CONFLICT (job_id) DO NOTHING
                    """, dlqId, job.id(), job.contentChecksum(), Math.max(1, job.attemptCount()),
                    "租约过期且已达到最大尝试次数", timestamp(now.plus(Duration.ofDays(90))), timestamp(now));
            if (dlqInserted == 1) {
                insertOutbox(job.id(), "knowledge.ingestion.dlq",
                        payload(job.id(), KnowledgeIngestionJob.JobStatus.DLQ.name(), job.contentChecksum()), now);
            }
        }
    }

    private Optional<KnowledgeIngestionJob> findByIdempotency(String sourceId, String key) {
        return jdbcTemplate.query("SELECT " + JOB_COLUMNS + " FROM knowledge_ingestion_job "
                        + "WHERE source_id = ? AND idempotency_key = ? ORDER BY created_at LIMIT 1",
                this::mapJob, sourceId, key).stream().findFirst();
    }

    private boolean sameReservation(KnowledgeIngestionJob left, KnowledgeIngestionJob right) {
        return left.sourceId().equals(right.sourceId())
                && left.contentChecksum().equalsIgnoreCase(right.contentChecksum())
                && left.targetKnowledgeReleaseId().equals(right.targetKnowledgeReleaseId())
                && left.ingestionContractVersion().equals(right.ingestionContractVersion())
                && left.replayGeneration() == right.replayGeneration();
    }

    private KnowledgeIngestionJob ownedJob(String jobId, String workerId, Instant now) {
        requireText(jobId, "jobId");
        requireText(workerId, "workerId");
        validateWorkerId(workerId);
        if (now == null) throw new IllegalArgumentException("now 不能为空");
        KnowledgeIngestionJob job = jdbcTemplate.query("SELECT " + JOB_COLUMNS + " FROM knowledge_ingestion_job "
                        + "WHERE id = ? FOR UPDATE", this::mapJob, jobId.trim()).stream().findFirst()
                .orElseThrow(() -> new KnowledgeIngestionNotFoundException("找不到摄取 Job: " + jobId));
        if (job.status() != KnowledgeIngestionJob.JobStatus.PROCESSING
                || !workerId.trim().equals(job.leaseOwner()) || job.leaseExpiresAt() == null
                || !job.leaseExpiresAt().isAfter(now)) {
            throw new KnowledgeIngestionConflictException("Job lease/状态不匹配: " + jobId);
        }
        return job;
    }

    private void insertOutbox(String jobId, String type, String payload, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_ingestion_outbox
                    (id, job_id, event_type, payload, status, publish_attempts,
                     next_attempt_at, last_error, created_at, published_at)
                VALUES (?, ?, ?, ?::jsonb, 'PENDING', 0, ?, NULL, ?, NULL)
                """, "ingestion-outbox-" + UUID.randomUUID(), jobId, type, payload,
                timestamp(now), timestamp(now));
    }

    /**
     * Ensure the source root exists before inserting a Job.  Source URL、许可、
     * trust level 和 owner 是治理身份的一部分；相同 id 但不同核心字段时
     * 直接返回冲突，避免重试请求借 Job API 覆盖已审核来源。
     */
    private void ensureSource(KnowledgeIngestionJobPayload payload) {
        if (payload == null) throw new IllegalArgumentException("request payload 不能为空");
        validateSourcePayload(payload);
        List<SourceSnapshot> rows = jdbcTemplate.query("""
                SELECT id, source_url, license, trust_level, owner_id
                  FROM knowledge_source WHERE id = ? FOR UPDATE
                """, (rs, rowNum) -> new SourceSnapshot(rs.getString("id"), rs.getString("source_url"),
                rs.getString("license"), rs.getString("trust_level"), rs.getString("owner_id")),
                payload.sourceId());
        if (rows.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO knowledge_source
                        (id, source_url, title, license, trust_level, owner_id, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP), COALESCE(?, CURRENT_TIMESTAMP))
                    ON CONFLICT (id) DO NOTHING
                    """, payload.sourceId(), payload.sourceUrl(), payload.sourceTitle(), payload.license(),
                    payload.trustLevel(), payload.ownerId(), instant(payload.sourceCreatedAt()),
                    instant(payload.sourceUpdatedAt()));
            // A concurrent transaction may have won the insert. Re-read under the
            // current transaction before accepting the Job FK and metadata.
            rows = jdbcTemplate.query("""
                    SELECT id, source_url, license, trust_level, owner_id
                      FROM knowledge_source WHERE id = ? FOR UPDATE
                    """, (rs, rowNum) -> new SourceSnapshot(rs.getString("id"), rs.getString("source_url"),
                    rs.getString("license"), rs.getString("trust_level"), rs.getString("owner_id")),
                    payload.sourceId());
        }
        SourceSnapshot existing = rows.stream().findFirst().orElseThrow(() ->
                new IllegalStateException("source 登记后无法回读: " + payload.sourceId()));
        if (!existing.sameGovernanceIdentity(payload)) {
            throw new KnowledgeIngestionConflictException("source 身份治理字段与已登记来源不一致: "
                    + payload.sourceId());
        }
    }

    /** A Job must point at an existing candidate release; FK errors become a
     * stable 404/409 contract instead of an opaque database failure. */
    private void ensureCandidateRelease(String releaseId) {
        requireText(releaseId, "targetKnowledgeReleaseId");
        validateLength(releaseId.trim(), 64, "targetKnowledgeReleaseId");
        String status = jdbcTemplate.query("""
                SELECT status FROM knowledge_release WHERE id = ?
                """, rs -> rs.next() ? rs.getString("status") : null, releaseId.trim());
        if (status == null || status.isBlank()) {
            throw new KnowledgeIngestionNotFoundException("找不到目标知识发布版本: " + releaseId);
        }
        if (!"CANDIDATE".equalsIgnoreCase(status)) {
            throw new KnowledgeIngestionConflictException("目标知识发布版本不是 CANDIDATE: " + releaseId);
        }
    }

    private void validateSourcePayload(KnowledgeIngestionJobPayload payload) {
        validateLength(payload.sourceId(), 64, "sourceId");
        validateLength(payload.sourceUrl(), 4096, "sourceUrl");
        validateLength(payload.license(), 128, "license");
        validateLength(payload.trustLevel(), 32, "trustLevel");
        validateOptionalLength(payload.sourceTitle(), 256, "sourceTitle");
        validateOptionalLength(payload.ownerId(), 128, "ownerId");
    }

    private Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("source 时间字段不合法", ex);
        }
    }

    private String payload(String jobId, String status, String checksum) {
        try {
            return objectMapper.writeValueAsString(Map.of("schemaVersion", "v1.1", "jobId", jobId,
                    "status", status, "checksum", checksum));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("outbox payload 序列化失败", ex);
        }
    }

    private String writePayload(KnowledgeIngestionJobPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("摄取请求快照序列化失败", ex);
        }
    }

    private KnowledgeIngestionJob mapJob(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeIngestionJob(rs.getString("id"), rs.getString("source_id"),
                rs.getString("content_checksum"), rs.getString("target_knowledge_release_id"),
                rs.getString("ingestion_contract_version"), rs.getString("idempotency_key"),
                rs.getInt("replay_generation"), rs.getString("replay_of_job_id"),
                readPayload(rs.getString("request_payload")),
                enumValue(KnowledgeIngestionJob.JobStatus.class, rs.getString("status"), KnowledgeIngestionJob.JobStatus.QUEUED),
                rs.getInt("attempt_count"), rs.getInt("max_attempts"), instant(rs.getObject("next_attempt_at")),
                rs.getString("lease_owner"), instant(rs.getObject("lease_expires_at")), rs.getString("result_batch_id"),
                instant(rs.getObject("created_at")), instant(rs.getObject("updated_at")), instant(rs.getObject("completed_at")));
    }

    private KnowledgeIngestionJobPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, KnowledgeIngestionJobPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("摄取请求快照 JSON 非法", ex);
        }
    }

    private KnowledgeIngestionAttempt mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeIngestionAttempt(rs.getString("id"), rs.getString("job_id"), rs.getInt("attempt_no"),
                enumValue(KnowledgeIngestionAttempt.AttemptStatus.class, rs.getString("status"), KnowledgeIngestionAttempt.AttemptStatus.RUNNING),
                rs.getString("error_class"), rs.getString("error_code"), rs.getString("error_message"),
                rs.getLong("backoff_ms"), rs.getLong("jitter_ms"), rs.getString("lease_owner"),
                instant(rs.getObject("started_at")), instant(rs.getObject("completed_at")));
    }

    private KnowledgeIngestionOutboxEvent mapOutbox(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeIngestionOutboxEvent(rs.getString("id"), rs.getString("job_id"),
                rs.getString("event_type"), rs.getString("payload"),
                enumValue(KnowledgeIngestionOutboxEvent.OutboxStatus.class, rs.getString("status"), KnowledgeIngestionOutboxEvent.OutboxStatus.PENDING),
                rs.getInt("publish_attempts"), instant(rs.getObject("next_attempt_at")), rs.getString("last_error"),
                instant(rs.getObject("created_at")), instant(rs.getObject("published_at")));
    }

    private KnowledgeIngestionDlqEntry mapDlq(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeIngestionDlqEntry(rs.getString("id"), rs.getString("job_id"), rs.getString("original_checksum"),
                rs.getInt("last_attempt_no"), rs.getString("error_class"), rs.getString("error_code"),
                rs.getString("error_message"), instant(rs.getObject("retention_until")), rs.getString("replayed_by"),
                rs.getString("replay_reason"), rs.getString("replay_job_id"), instant(rs.getObject("created_at")),
                instant(rs.getObject("replayed_at")));
    }

    private Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.time.LocalDateTime local) return local.toInstant(java.time.ZoneOffset.UTC);
        if (value instanceof Instant instant) return instant;
        return Instant.parse(value.toString());
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
    }

    private String safeMessage(String value) {
        return KnowledgeIngestionErrorSanitizer.sanitize(value);
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
    }

    private void validateCandidate(KnowledgeIngestionJob candidate) {
        validateLength(candidate.id(), 96, "id");
        validateLength(candidate.sourceId(), 64, "sourceId");
        validateLength(candidate.contentChecksum(), 128, "contentChecksum");
        validateLength(candidate.targetKnowledgeReleaseId(), 64, "targetKnowledgeReleaseId");
        validateLength(candidate.ingestionContractVersion(), 64, "ingestionContractVersion");
        validateLength(candidate.idempotencyKey(), 256, "idempotencyKey");
        if (candidate.status() != KnowledgeIngestionJob.JobStatus.QUEUED
                || candidate.attemptCount() != 0 || candidate.leaseOwner() != null
                || candidate.leaseExpiresAt() != null || candidate.resultBatchId() != null) {
            throw new IllegalArgumentException("新建摄取 reservation 必须处于 QUEUED 初始状态");
        }
    }

    private void validateWorkerId(String workerId) {
        validateLength(workerId.trim(), 128, "workerId");
    }

    private void validateLength(String value, int max, String name) {
        if (value == null || value.length() > max || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " 长度或字符不合法");
        }
    }

    private void validateOptionalLength(String value, int max, String name) {
        if (value != null) validateLength(value, max, name);
    }

    private String normalizeChecksum(String checksum) {
        return checksum == null ? null : checksum.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static final String JOB_COLUMNS = "id, source_id, content_checksum, target_knowledge_release_id, "
            + "ingestion_contract_version, idempotency_key, replay_generation, replay_of_job_id, request_payload, "
            + "status, attempt_count, max_attempts, next_attempt_at, lease_owner, lease_expires_at, result_batch_id, "
            + "created_at, updated_at, completed_at";

    private static final String JOB_COLUMNS_PREFIXED = "j.id, j.source_id, j.content_checksum, "
            + "j.target_knowledge_release_id, j.ingestion_contract_version, j.idempotency_key, "
            + "j.replay_generation, j.replay_of_job_id, j.request_payload, j.status, j.attempt_count, "
            + "j.max_attempts, j.next_attempt_at, j.lease_owner, j.lease_expires_at, j.result_batch_id, "
            + "j.created_at, j.updated_at, j.completed_at";

    private record SourceSnapshot(String id, String sourceUrl, String license,
                                  String trustLevel, String ownerId) {
        private boolean sameGovernanceIdentity(KnowledgeIngestionJobPayload payload) {
            return java.util.Objects.equals(sourceUrl, payload.sourceUrl())
                    && java.util.Objects.equals(license, payload.license())
                    && trustLevel != null && payload.trustLevel() != null
                    && trustLevel.equalsIgnoreCase(payload.trustLevel())
                    && java.util.Objects.equals(ownerId, payload.ownerId());
        }
    }
}
