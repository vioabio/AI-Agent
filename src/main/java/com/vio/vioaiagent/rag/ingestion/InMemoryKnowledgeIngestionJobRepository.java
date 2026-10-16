package com.vio.vioaiagent.rag.ingestion;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 开发/单测用的并发安全仓储。
 *
 * <p>方法与 PostgreSQL 实现保持同一 CAS 语义，便于在没有外部依赖时验证
 * Job 状态机、重复 reservation、重试和 DLQ。它不是生产持久化替代品。</p>
 */
@Repository
@Primary
@Profile("!prod & !demo")
public class InMemoryKnowledgeIngestionJobRepository implements KnowledgeIngestionJobRepository {

    private final Map<String, KnowledgeIngestionJob> jobs = new LinkedHashMap<>();
    private final Map<String, List<KnowledgeIngestionAttempt>> attempts = new LinkedHashMap<>();
    private final Map<String, KnowledgeIngestionOutboxEvent> outbox = new LinkedHashMap<>();
    private final Map<String, KnowledgeIngestionDlqEntry> dlq = new LinkedHashMap<>();

    @Override
    public synchronized ReservationResult createOrGet(KnowledgeIngestionJob candidate) {
        if (candidate == null) throw new IllegalArgumentException("candidate 不能为空");
        validateCandidate(candidate);
        Optional<KnowledgeIngestionJob> idem = jobs.values().stream()
                .filter(job -> job.sourceId().equals(candidate.sourceId()))
                .filter(job -> job.idempotencyKey().equals(candidate.idempotencyKey()))
                .findFirst();
        if (idem.isPresent() && !sameReservation(idem.get(), candidate)) {
            throw new KnowledgeIngestionConflictException("idempotencyKey 已用于不同的摄取 reservation: "
                    + candidate.idempotencyKey());
        }
        Optional<KnowledgeIngestionJob> natural = jobs.values().stream()
                .filter(job -> job.sourceId().equals(candidate.sourceId()))
                .filter(job -> job.contentChecksum().equalsIgnoreCase(candidate.contentChecksum()))
                .filter(job -> job.targetKnowledgeReleaseId().equals(candidate.targetKnowledgeReleaseId()))
                .filter(job -> job.ingestionContractVersion().equals(candidate.ingestionContractVersion()))
                .filter(job -> job.replayGeneration() == candidate.replayGeneration())
                .findFirst();
        if (natural.isPresent()) return new ReservationResult(natural.get(), false);
        jobs.put(candidate.id(), candidate);
        return new ReservationResult(candidate, true);
    }

    @Override
    public synchronized Optional<KnowledgeIngestionJob> findJob(String jobId) {
        return jobId == null || jobId.isBlank() ? Optional.empty() : Optional.ofNullable(jobs.get(jobId.trim()));
    }

    @Override
    public synchronized Optional<KnowledgeIngestionJob> reserveNext(String workerId, Instant now, Duration lease) {
        requireText(workerId, "workerId");
        validateWorkerId(workerId);
        if (now == null || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("now/lease 参数不合法");
        }
        moveExhaustedExpiredToDlq(now);
        KnowledgeIngestionJob candidate = jobs.values().stream()
                .filter(job -> eligible(job, now))
                .sorted(Comparator.comparing(KnowledgeIngestionJob::createdAt).thenComparing(KnowledgeIngestionJob::id))
                .findFirst().orElse(null);
        if (candidate == null) return Optional.empty();
        if ((candidate.status() == KnowledgeIngestionJob.JobStatus.RESERVED
                || candidate.status() == KnowledgeIngestionJob.JobStatus.PROCESSING)
                && candidate.leaseExpiresAt() != null && !candidate.leaseExpiresAt().isAfter(now)) {
            markOpenAttemptsAbandoned(candidate.id(), now);
        }
        KnowledgeIngestionJob reserved = copy(candidate, KnowledgeIngestionJob.JobStatus.RESERVED,
                candidate.attemptCount(), null, workerId.trim(), now.plus(lease), null, now);
        jobs.put(reserved.id(), reserved);
        return Optional.of(reserved);
    }

    @Override
    public synchronized Optional<KnowledgeIngestionAttempt> startAttempt(String jobId, String workerId, Instant now) {
        KnowledgeIngestionJob job = requireOwned(jobId, workerId, KnowledgeIngestionJob.JobStatus.RESERVED, now);
        if (job.attemptCount() >= job.maxAttempts()) {
            throw new KnowledgeIngestionConflictException("摄取 Job 已达到最大尝试次数: " + job.id());
        }
        int attemptNo = job.attemptCount() + 1;
        KnowledgeIngestionJob processing = copy(job, KnowledgeIngestionJob.JobStatus.PROCESSING,
                attemptNo, job.nextAttemptAt(), job.leaseOwner(), job.leaseExpiresAt(), null, now);
        jobs.put(processing.id(), processing);
        KnowledgeIngestionAttempt attempt = new KnowledgeIngestionAttempt(
                "ingestion-attempt-" + UUID.randomUUID(), job.id(), attemptNo,
                KnowledgeIngestionAttempt.AttemptStatus.RUNNING, null, null, null, 0, 0,
                workerId.trim(), now, null);
        attempts.computeIfAbsent(job.id(), ignored -> new ArrayList<>()).add(attempt);
        return Optional.of(attempt);
    }

    @Override
    public synchronized Optional<KnowledgeIngestionJob> renewLease(String jobId, String workerId, Instant now, Duration lease) {
        if (jobId == null || jobId.isBlank() || workerId == null || workerId.isBlank()
                || now == null || lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("jobId、workerId、now、lease 参数不合法");
        }
        KnowledgeIngestionJob job = jobs.get(jobId.trim());
        if (job == null || (job.status() != KnowledgeIngestionJob.JobStatus.RESERVED
                && job.status() != KnowledgeIngestionJob.JobStatus.PROCESSING)
                || !workerId.trim().equals(job.leaseOwner()) || job.leaseExpiresAt() == null
                || !job.leaseExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        KnowledgeIngestionJob renewed = new KnowledgeIngestionJob(job.id(), job.sourceId(), job.contentChecksum(),
                job.targetKnowledgeReleaseId(), job.ingestionContractVersion(), job.idempotencyKey(),
                job.replayGeneration(), job.replayOfJobId(), job.payload(), job.status(), job.attemptCount(),
                job.maxAttempts(), job.nextAttemptAt(), job.leaseOwner(), now.plus(lease), job.resultBatchId(),
                job.createdAt(), now, job.completedAt());
        jobs.put(renewed.id(), renewed);
        return Optional.of(renewed);
    }

    @Override
    public synchronized void completeSuccess(String jobId, String workerId, String batchId, Instant now) {
        requireText(batchId, "batchId");
        KnowledgeIngestionJob job = requireOwned(jobId, workerId, KnowledgeIngestionJob.JobStatus.PROCESSING, now);
        KnowledgeIngestionAttempt current = runningAttempt(job.id(), job.attemptCount())
                .orElseThrow(() -> new IllegalStateException("找不到运行中的 attempt: " + job.id()));
        replaceAttempt(new KnowledgeIngestionAttempt(current.id(), current.jobId(), current.attemptNo(),
                KnowledgeIngestionAttempt.AttemptStatus.SUCCEEDED, null, null, null, current.backoffMs(),
                current.jitterMs(), current.leaseOwner(), current.startedAt(), now));
        KnowledgeIngestionJob succeeded = copy(job, KnowledgeIngestionJob.JobStatus.SUCCEEDED,
                job.attemptCount(), null, null, null, batchId.trim(), now);
        jobs.put(succeeded.id(), succeeded);
        addOutbox(succeeded, "knowledge.ingestion.succeeded", now);
    }

    @Override
    public synchronized FailureTransition recordFailure(String jobId, String workerId, Failure failure, Instant now) {
        if (failure == null) throw new IllegalArgumentException("failure 不能为空");
        KnowledgeIngestionJob job = requireOwned(jobId, workerId, KnowledgeIngestionJob.JobStatus.PROCESSING, now);
        KnowledgeIngestionAttempt current = runningAttempt(job.id(), job.attemptCount())
                .orElseThrow(() -> new IllegalStateException("找不到运行中的 attempt: " + job.id()));
        replaceAttempt(new KnowledgeIngestionAttempt(current.id(), current.jobId(), current.attemptNo(),
                KnowledgeIngestionAttempt.AttemptStatus.FAILED, failure.errorClass(), failure.errorCode(),
                safeMessage(failure.errorMessage()), failure.backoffMs(), failure.jitterMs(),
                current.leaseOwner(), current.startedAt(), now));
        boolean retry = failure.disposition() == FailureDisposition.TRANSIENT
                && job.attemptCount() < job.maxAttempts() && failure.nextAttemptAt() != null;
        KnowledgeIngestionJob next = copy(job,
                retry ? KnowledgeIngestionJob.JobStatus.RETRY_WAIT : KnowledgeIngestionJob.JobStatus.DLQ,
                job.attemptCount(), retry ? failure.nextAttemptAt() : null, null, null, null, now);
        jobs.put(next.id(), next);
        Optional<KnowledgeIngestionDlqEntry> entry = Optional.empty();
        if (retry) {
            addOutbox(next, "knowledge.ingestion.retry_scheduled", now);
        } else {
            KnowledgeIngestionDlqEntry created = new KnowledgeIngestionDlqEntry(
                    "ingestion-dlq-" + UUID.randomUUID(), next.id(), next.contentChecksum(), current.attemptNo(),
                    failure.errorClass(), failure.errorCode(), safeMessage(failure.errorMessage()),
                     now.plus(Duration.ofDays(90)), null, null, null, now, null);
            dlq.put(created.id(), created);
            entry = Optional.of(created);
            addOutbox(next, "knowledge.ingestion.dlq", now);
        }
        return new FailureTransition(next, attempts.get(next.id()).get(attempts.get(next.id()).size() - 1), entry);
    }

    @Override
    public synchronized List<KnowledgeIngestionAttempt> findAttempts(String jobId) {
        return jobId == null ? List.of() : List.copyOf(attempts.getOrDefault(jobId.trim(), List.of()));
    }

    @Override
    public synchronized List<KnowledgeIngestionOutboxEvent> findPendingOutbox(Instant now, int limit) {
        if (now == null || limit <= 0) return List.of();
        return outbox.values().stream()
                .filter(event -> event.status() == KnowledgeIngestionOutboxEvent.OutboxStatus.PENDING)
                .filter(event -> event.nextAttemptAt() == null || !event.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(KnowledgeIngestionOutboxEvent::createdAt).thenComparing(KnowledgeIngestionOutboxEvent::id))
                .limit(limit).toList();
    }

    @Override
    public synchronized Optional<KnowledgeIngestionOutboxEvent> findOutbox(String eventId) {
        return eventId == null || eventId.isBlank() ? Optional.empty() : Optional.ofNullable(outbox.get(eventId.trim()));
    }

    @Override
    public synchronized void markOutboxPublished(String eventId, Instant now) {
        if (now == null) throw new IllegalArgumentException("now 不能为空");
        KnowledgeIngestionOutboxEvent event = requireOutbox(eventId);
        if (event.status() != KnowledgeIngestionOutboxEvent.OutboxStatus.PENDING) {
            throw new KnowledgeIngestionConflictException("outbox 事件不存在或已确认: " + event.id());
        }
        outbox.put(event.id(), new KnowledgeIngestionOutboxEvent(event.id(), event.jobId(), event.eventType(),
                event.payload(), KnowledgeIngestionOutboxEvent.OutboxStatus.PUBLISHED, event.publishAttempts(),
                null, event.lastError(), event.createdAt(), now));
    }

    @Override
    public synchronized void markOutboxFailed(String eventId, String errorMessage, Instant nextAttemptAt, Instant now) {
        if (now == null) throw new IllegalArgumentException("now 不能为空");
        KnowledgeIngestionOutboxEvent event = requireOutbox(eventId);
        if (event.status() != KnowledgeIngestionOutboxEvent.OutboxStatus.PENDING) {
            throw new KnowledgeIngestionConflictException("outbox 事件不存在或已结束: " + event.id());
        }
        int attempts = event.publishAttempts() + 1;
        var status = attempts >= 5 ? KnowledgeIngestionOutboxEvent.OutboxStatus.FAILED
                : KnowledgeIngestionOutboxEvent.OutboxStatus.PENDING;
        Instant scheduledAt = status == KnowledgeIngestionOutboxEvent.OutboxStatus.PENDING
                ? (nextAttemptAt == null || !nextAttemptAt.isAfter(now)
                ? now.plusMillis(1) : nextAttemptAt) : null;
        outbox.put(event.id(), new KnowledgeIngestionOutboxEvent(event.id(), event.jobId(), event.eventType(),
                event.payload(), status, attempts, status == KnowledgeIngestionOutboxEvent.OutboxStatus.PENDING
                ? scheduledAt : null, safeMessage(errorMessage), event.createdAt(), null));
    }

    @Override
    public synchronized Optional<KnowledgeIngestionDlqEntry> findDlq(String dlqId) {
        return dlqId == null || dlqId.isBlank() ? Optional.empty() : Optional.ofNullable(dlq.get(dlqId.trim()));
    }

    @Override
    public synchronized List<KnowledgeIngestionDlqEntry> findDlqByJob(String jobId) {
        if (jobId == null || jobId.isBlank()) return List.of();
        return dlq.values().stream().filter(item -> jobId.trim().equals(item.jobId())).toList();
    }

    @Override
    public synchronized KnowledgeIngestionJob replayDlq(String dlqId, String operatorId, String reason,
                                                         String newIdempotencyKey, Instant now) {
        requireText(operatorId, "operatorId");
        requireText(reason, "reason");
        requireText(newIdempotencyKey, "newIdempotencyKey");
        validateLength(dlqId == null ? null : dlqId.trim(), 96, "dlqId");
        validateLength(operatorId.trim(), 128, "operatorId");
        validateLength(reason.trim(), 2000, "reason");
        validateLength(newIdempotencyKey.trim(), 256, "newIdempotencyKey");
        if (now == null) throw new IllegalArgumentException("now 不能为空");
        KnowledgeIngestionDlqEntry entry = findDlq(dlqId).orElseThrow(() ->
                new KnowledgeIngestionNotFoundException("找不到 DLQ: " + dlqId));
        if (entry.replayJobId() != null) {
            throw new KnowledgeIngestionConflictException("DLQ 已 replay: " + entry.id());
        }
        if (entry.retentionUntil() != null && !entry.retentionUntil().isAfter(now)) {
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
        int generation = jobs.values().stream().filter(job -> job.replayOfJobId() != null
                        && job.replayOfJobId().equals(original.id()))
                .mapToInt(KnowledgeIngestionJob::replayGeneration).max().orElse(original.replayGeneration()) + 1;
        KnowledgeIngestionJob replay = new KnowledgeIngestionJob(
                "ingestion-job-" + UUID.randomUUID(), original.sourceId(), original.contentChecksum(),
                original.targetKnowledgeReleaseId(), original.ingestionContractVersion(), newIdempotencyKey.trim(),
                generation, original.id(), original.payload(), KnowledgeIngestionJob.JobStatus.QUEUED, 0,
                original.maxAttempts(), null, null, null, null, now, now, null);
        if (jobs.values().stream().anyMatch(job -> job.sourceId().equals(replay.sourceId())
                 && job.idempotencyKey().equals(replay.idempotencyKey()))) {
            throw new KnowledgeIngestionConflictException("新的 idempotencyKey 已存在");
        }
        jobs.put(replay.id(), replay);
        dlq.put(entry.id(), new KnowledgeIngestionDlqEntry(entry.id(), entry.jobId(), entry.originalChecksum(),
                entry.lastAttemptNo(), entry.errorClass(), entry.errorCode(), entry.errorMessage(), entry.retentionUntil(),
                operatorId.trim(), reason.trim(), replay.id(), entry.createdAt(), now));
        addOutbox(replay, "knowledge.ingestion.replayed", now);
        return replay;
    }

    private boolean eligible(KnowledgeIngestionJob job, Instant now) {
        return switch (job.status()) {
            case QUEUED -> true;
            case RETRY_WAIT -> job.nextAttemptAt() != null && !job.nextAttemptAt().isAfter(now);
            case RESERVED, PROCESSING -> job.leaseExpiresAt() != null && !job.leaseExpiresAt().isAfter(now);
            default -> false;
        };
    }

    private boolean sameReservation(KnowledgeIngestionJob left, KnowledgeIngestionJob right) {
        return left.sourceId().equals(right.sourceId())
                && left.contentChecksum().equalsIgnoreCase(right.contentChecksum())
                && left.targetKnowledgeReleaseId().equals(right.targetKnowledgeReleaseId())
                && left.ingestionContractVersion().equals(right.ingestionContractVersion())
                && left.replayGeneration() == right.replayGeneration();
    }

    private KnowledgeIngestionJob requireOwned(String jobId, String workerId,
                                               KnowledgeIngestionJob.JobStatus status, Instant now) {
        if (jobId == null || jobId.isBlank() || workerId == null || workerId.isBlank() || now == null) {
            throw new IllegalArgumentException("jobId、workerId、now 不能为空");
        }
        validateWorkerId(workerId);
        KnowledgeIngestionJob job = jobs.get(jobId.trim());
        if (job == null) throw new KnowledgeIngestionNotFoundException("找不到摄取 Job: " + jobId);
        if (job.status() != status || !workerId.trim().equals(job.leaseOwner())
                || job.leaseExpiresAt() == null || !job.leaseExpiresAt().isAfter(now)) {
            throw new KnowledgeIngestionConflictException("Job lease/状态不匹配: " + jobId);
        }
        return job;
    }

    private KnowledgeIngestionJob copy(KnowledgeIngestionJob value, KnowledgeIngestionJob.JobStatus status,
                                       int attemptCount, Instant nextAttemptAt, String leaseOwner,
                                       Instant leaseExpiresAt, String resultBatchId, Instant now) {
        Instant completedAt = status == KnowledgeIngestionJob.JobStatus.SUCCEEDED
                || status == KnowledgeIngestionJob.JobStatus.DLQ ? now : value.completedAt();
        return new KnowledgeIngestionJob(value.id(), value.sourceId(), value.contentChecksum(),
                value.targetKnowledgeReleaseId(), value.ingestionContractVersion(), value.idempotencyKey(),
                value.replayGeneration(), value.replayOfJobId(), value.payload(), status, attemptCount,
                value.maxAttempts(), nextAttemptAt, leaseOwner, leaseExpiresAt, resultBatchId,
                value.createdAt(), now, completedAt);
    }

    private Optional<KnowledgeIngestionAttempt> runningAttempt(String jobId, int attemptNo) {
        return attempts.getOrDefault(jobId, List.of()).stream()
                .filter(item -> item.attemptNo() == attemptNo)
                .filter(item -> item.status() == KnowledgeIngestionAttempt.AttemptStatus.RUNNING)
                .findFirst();
    }

    private void replaceAttempt(KnowledgeIngestionAttempt replacement) {
        List<KnowledgeIngestionAttempt> values = attempts.get(replacement.jobId());
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).id().equals(replacement.id())) {
                values.set(i, replacement);
                return;
            }
        }
        throw new IllegalStateException("attempt 不存在: " + replacement.id());
    }

    private void markOpenAttemptsAbandoned(String jobId, Instant now) {
        List<KnowledgeIngestionAttempt> values = attempts.getOrDefault(jobId, List.of());
        for (int i = 0; i < values.size(); i++) {
            KnowledgeIngestionAttempt item = values.get(i);
            if (item.status() == KnowledgeIngestionAttempt.AttemptStatus.RUNNING) {
                values.set(i, new KnowledgeIngestionAttempt(item.id(), item.jobId(), item.attemptNo(),
                        KnowledgeIngestionAttempt.AttemptStatus.ABANDONED, "LEASE_EXPIRED", "LEASE_EXPIRED",
                        "worker lease 已过期", item.backoffMs(), item.jitterMs(), item.leaseOwner(), item.startedAt(), now));
            }
        }
    }

    /** 租约过期且已耗尽次数的任务不能再次领取，否则会越过 maxAttempts。 */
    private void moveExhaustedExpiredToDlq(Instant now) {
        List<KnowledgeIngestionJob> exhausted = jobs.values().stream()
                .filter(job -> (job.status() == KnowledgeIngestionJob.JobStatus.RESERVED
                        || job.status() == KnowledgeIngestionJob.JobStatus.PROCESSING))
                .filter(job -> job.leaseExpiresAt() != null && !job.leaseExpiresAt().isAfter(now))
                .filter(job -> job.attemptCount() >= job.maxAttempts())
                .toList();
        for (KnowledgeIngestionJob job : exhausted) {
            markOpenAttemptsAbandoned(job.id(), now);
            KnowledgeIngestionJob dlqJob = copy(job, KnowledgeIngestionJob.JobStatus.DLQ,
                    job.attemptCount(), null, null, null, null, now);
            jobs.put(dlqJob.id(), dlqJob);
            KnowledgeIngestionDlqEntry entry = new KnowledgeIngestionDlqEntry(
                    "ingestion-dlq-" + UUID.randomUUID(), dlqJob.id(), dlqJob.contentChecksum(),
                    Math.max(1, dlqJob.attemptCount()), "LEASE_EXPIRED", "MAX_ATTEMPTS_EXCEEDED",
                    "租约过期且已达到最大尝试次数", now.plus(Duration.ofDays(90)), null, null, null, now, null);
            dlq.put(entry.id(), entry);
            addOutbox(dlqJob, "knowledge.ingestion.dlq", now);
        }
    }

    private void addOutbox(KnowledgeIngestionJob job, String eventType, Instant now) {
        if (now == null) throw new IllegalArgumentException("now 不能为空");
        String eventId = "ingestion-outbox-" + UUID.randomUUID();
        String payload = "{\"schemaVersion\":\"v1.1\",\"jobId\":\"" + escape(job.id())
                + "\",\"status\":\""
                + job.status().name() + "\",\"checksum\":\"" + escape(job.contentChecksum()) + "\"}";
        KnowledgeIngestionOutboxEvent event = new KnowledgeIngestionOutboxEvent(
                eventId, job.id(), eventType, payload,
                KnowledgeIngestionOutboxEvent.OutboxStatus.PENDING, 0, now, null, now, null);
        outbox.put(event.id(), event);
    }

    private KnowledgeIngestionOutboxEvent requireOutbox(String eventId) {
        if (eventId == null || eventId.isBlank() || !outbox.containsKey(eventId.trim())) {
            throw new KnowledgeIngestionNotFoundException("找不到 outbox 事件: " + eventId);
        }
        return outbox.get(eventId.trim());
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
        if (workerId.indexOf('\u0000') >= 0) throw new IllegalArgumentException("workerId 含非法字符");
    }

    private void validateLength(String value, int max, String name) {
        if (value == null || value.length() > max || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " 长度或字符不合法");
        }
    }

    private String safeMessage(String value) {
        return KnowledgeIngestionErrorSanitizer.sanitize(value);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
    }
}
