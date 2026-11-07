package com.vio.vioaiagent.rag.ingestion;

import com.vio.vioaiagent.rag.domain.IngestionBatch;
import com.vio.vioaiagent.rag.domain.KnowledgeDocument;
import com.vio.vioaiagent.rag.domain.KnowledgeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** N1 核心状态机：reservation、有限重试、DLQ/replay 和 outbox。 */
class KnowledgeIngestionJobServiceTest {

    @TempDir
    Path staging;

    @Test
    void sameChecksumAndContractUsesOneReservation() throws Exception {
        Path file = Files.writeString(staging.resolve("guide.md"), "朱紫规则\n");
        InMemoryKnowledgeIngestionJobRepository repository = new InMemoryKnowledgeIngestionJobRepository();
        KnowledgeIngestionJobService service = new KnowledgeIngestionJobService(repository, job -> accepted(job),
                Clock.systemUTC(), Duration.ofMinutes(1));
        KnowledgeIngestionRequest request = request(file, "same-key");

        var left = service.submit(request, "knowledge-candidate-1", "contract-1", "same-key", 3);
        var right = service.submit(request, "knowledge-candidate-1", "contract-1", "same-key", 3);

        assertTrue(left.created());
        assertEquals(left.job().id(), right.job().id());
        assertFalse(right.created());
    }

    @Test
    void concurrentWorkersCanOnlyReserveOneJob() throws Exception {
        Path file = Files.writeString(staging.resolve("concurrent.md"), "并发 reservation\n");
        InMemoryKnowledgeIngestionJobRepository repository = new InMemoryKnowledgeIngestionJobRepository();
        KnowledgeIngestionJobService service = new KnowledgeIngestionJobService(repository, job -> accepted(job),
                Clock.systemUTC(), Duration.ofMinutes(1));
        service.submit(request(file, "key-1"), "knowledge-candidate-1", "contract-1", "key-1", 3);

        var first = CompletableFuture.supplyAsync(() -> repository.reserveNext("worker-a", Instant.now(), Duration.ofMinutes(1)));
        var second = CompletableFuture.supplyAsync(() -> repository.reserveNext("worker-b", Instant.now(), Duration.ofMinutes(1)));
        var reservations = CompletableFuture.allOf(first, second).thenApply(ignored -> List.of(first.join(), second.join())).join();

        assertEquals(1, reservations.stream().filter(java.util.Optional::isPresent).count());
    }

    @Test
    void leaseCanBeRenewedOnlyByCurrentOwner() throws Exception {
        Path file = Files.writeString(staging.resolve("lease.md"), "lease\n");
        InMemoryKnowledgeIngestionJobRepository repository = new InMemoryKnowledgeIngestionJobRepository();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T00:00:00Z"));
        KnowledgeIngestionJobService service = new KnowledgeIngestionJobService(repository, job -> accepted(job),
                clock, Duration.ofSeconds(10));
        var submitted = service.submit(request(file, "lease-key"), "knowledge-candidate-1", "contract-1", "lease-key", 3);
        var reserved = repository.reserveNext("worker-a", clock.instant(), Duration.ofSeconds(10)).orElseThrow();

        assertTrue(service.renewLease(submitted.job().id(), "worker-a").isPresent());
        assertTrue(service.renewLease(submitted.job().id(), "worker-b").isEmpty());
        assertTrue(service.runNext("worker-b").isEmpty(), "续租后的 Job 不应被其他 worker 抢占");
        assertEquals("worker-a", repository.findJob(reserved.id()).orElseThrow().leaseOwner());
    }

    @Test
    void transientFailureBacksOffThenSucceedsWithoutDuplicateAttemptNumber() throws Exception {
        Path file = Files.writeString(staging.resolve("retry.md"), "依赖暂时失败\n");
        InMemoryKnowledgeIngestionJobRepository repository = new InMemoryKnowledgeIngestionJobRepository();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T00:00:00Z"));
        AtomicInteger calls = new AtomicInteger();
        KnowledgeIngestionJobService service = new KnowledgeIngestionJobService(repository, job -> {
            if (calls.getAndIncrement() == 0) throw new java.io.IOException("embedding unavailable");
            return accepted(job);
        }, clock, Duration.ofMinutes(1));
        var submitted = service.submit(request(file, "retry-key"), "knowledge-candidate-1", "contract-1", "retry-key", 3);

        var first = service.runNext("worker-a").orElseThrow();
        assertEquals(KnowledgeIngestionJob.JobStatus.RETRY_WAIT, first.status());
        assertEquals(1, service.findAttempts(submitted.job().id()).size());
        assertEquals(KnowledgeIngestionAttempt.AttemptStatus.FAILED,
                service.findAttempts(submitted.job().id()).getFirst().status());

        clock.advance(Duration.ofSeconds(3));
        var second = service.runNext("worker-b").orElseThrow();
        assertEquals(KnowledgeIngestionJob.JobStatus.SUCCEEDED, second.status());
        assertEquals(2, service.findAttempts(submitted.job().id()).size());
        assertEquals(KnowledgeIngestionAttempt.AttemptStatus.SUCCEEDED,
                service.findAttempts(submitted.job().id()).getLast().status());
        assertEquals(2, repository.findPendingOutbox(clock.instant(), 10).size());
    }

    @Test
    void nonRetryableFailureEntersDlqAndReplayRequiresNewKey() throws Exception {
        Path file = Files.writeString(staging.resolve("bad.md"), "毒数据\n");
        InMemoryKnowledgeIngestionJobRepository repository = new InMemoryKnowledgeIngestionJobRepository();
        KnowledgeIngestionJobService service = new KnowledgeIngestionJobService(repository,
                job -> { throw new IllegalArgumentException("schema rejected"); }, Clock.systemUTC(), Duration.ofMinutes(1));
        var submitted = service.submit(request(file, "poison-key"), "knowledge-candidate-1", "contract-1", "poison-key", 3);

        var result = service.runNext("worker-a").orElseThrow();
        assertEquals(KnowledgeIngestionJob.JobStatus.DLQ, result.status());
        var dlq = repository.findDlqByJob(submitted.job().id()).getFirst();
        assertEquals("VALIDATION_REJECTED", dlq.errorCode());
        assertTrue(dlq.errorMessage().contains("schema"));

        var replay = service.replayDlq(dlq.id(), "operator-1", "修复 schema 后重放", "poison-replay-key");
        assertEquals(KnowledgeIngestionJob.JobStatus.QUEUED, replay.status());
        assertEquals(replay.id(), repository.findDlq(dlq.id()).orElseThrow().replayJobId());
        assertThrows(IllegalStateException.class,
                () -> service.replayDlq(dlq.id(), "operator-1", "重复重放", "another-key"));
    }

    @Test
    void outboxFailureIsRetriedAndEventuallyMarkedFailed() throws Exception {
        Path file = Files.writeString(staging.resolve("outbox.md"), "outbox\n");
        InMemoryKnowledgeIngestionJobRepository repository = new InMemoryKnowledgeIngestionJobRepository();
        KnowledgeIngestionJobService service = new KnowledgeIngestionJobService(repository, job -> accepted(job),
                Clock.systemUTC(), Duration.ofMinutes(1));
        service.submit(request(file, "outbox-key"), "knowledge-candidate-1", "contract-1", "outbox-key", 3);
        service.runNext("worker-a");

        var first = repository.findPendingOutbox(Instant.now(), 10).getFirst();
        for (int i = 0; i < 5; i++) {
            repository.markOutboxFailed(first.id(), "broker unavailable", Instant.now().minusSeconds(1), Instant.now());
        }
        assertEquals(KnowledgeIngestionOutboxEvent.OutboxStatus.FAILED,
                repository.findOutbox(first.id()).orElseThrow().status());
    }

    @Test
    void expiredLeaseAtMaxAttemptsIsMovedToDlqInsteadOfBeingReclaimed() throws Exception {
        Path file = Files.writeString(staging.resolve("exhausted.md"), "lease exhausted\n");
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T00:00:00Z"));
        InMemoryKnowledgeIngestionJobRepository repository = new InMemoryKnowledgeIngestionJobRepository();
        KnowledgeIngestionJobService service = new KnowledgeIngestionJobService(repository, job -> accepted(job),
                clock, Duration.ofSeconds(1));
        var submitted = service.submit(request(file, "exhausted-key"), "knowledge-candidate-1", "contract-1",
                "exhausted-key", 1);
        var reserved = repository.reserveNext("worker-a", clock.instant(), Duration.ofSeconds(1)).orElseThrow();
        repository.startAttempt(reserved.id(), "worker-a", clock.instant()).orElseThrow();

        clock.advance(Duration.ofSeconds(2));
        assertTrue(repository.reserveNext("worker-b", clock.instant(), Duration.ofSeconds(1)).isEmpty());
        var job = repository.findJob(submitted.job().id()).orElseThrow();
        assertEquals(KnowledgeIngestionJob.JobStatus.DLQ, job.status());
        assertEquals(KnowledgeIngestionAttempt.AttemptStatus.ABANDONED,
                repository.findAttempts(job.id()).getFirst().status());
        assertEquals(1, repository.findDlqByJob(job.id()).size());
    }

    @Test
    void queuedFileMutationIsRejectedBeforeProcessorRuns() throws Exception {
        Path file = Files.writeString(staging.resolve("mutated.md"), "before\n");
        InMemoryKnowledgeIngestionJobRepository repository = new InMemoryKnowledgeIngestionJobRepository();
        AtomicInteger processorCalls = new AtomicInteger();
        KnowledgeIngestionJobService service = new KnowledgeIngestionJobService(repository, job -> {
            processorCalls.incrementAndGet();
            return accepted(job);
        }, Clock.systemUTC(), Duration.ofMinutes(1));
        var submitted = service.submit(request(file, "mutation-key"), "knowledge-candidate-1", "contract-1",
                "mutation-key", 3);
        Files.writeString(file, "after\n");

        var result = service.runNext("worker-a").orElseThrow();
        assertEquals(KnowledgeIngestionJob.JobStatus.DLQ, result.status());
        assertEquals(0, processorCalls.get());
        assertEquals("VALIDATION_REJECTED", repository.findDlqByJob(submitted.job().id()).getFirst().errorCode());
    }

    @Test
    void errorSummaryRedactsCredentialsAndNewlines() throws Exception {
        Path file = Files.writeString(staging.resolve("secret.md"), "secret\n");
        InMemoryKnowledgeIngestionJobRepository repository = new InMemoryKnowledgeIngestionJobRepository();
        KnowledgeIngestionJobService service = new KnowledgeIngestionJobService(repository, job -> {
            throw new IllegalArgumentException("password=do-not-store\nAuthorization: Bearer do-not-store");
        }, Clock.systemUTC(), Duration.ofMinutes(1));
        var submitted = service.submit(request(file, "secret-key"), "knowledge-candidate-1", "contract-1",
                "secret-key", 3);

        service.runNext("worker-a").orElseThrow();
        String message = repository.findDlqByJob(submitted.job().id()).getFirst().errorMessage();
        assertTrue(message.contains("[REDACTED]"));
        assertFalse(message.contains("do-not-store"));
        assertFalse(message.contains("\n"));
    }

    private KnowledgeIngestionResult accepted(KnowledgeIngestionJob job) {
        IngestionBatch batch = new IngestionBatch("batch-" + job.id(), job.sourceId(), job.idempotencyKey(),
                IngestionBatch.IngestionStatus.ACCEPTED, "parser-1", "{}", null, 1, 0, null,
                Instant.now(), Instant.now());
        return new KnowledgeIngestionResult(batch, null, List.of(), List.of());
    }

    private KnowledgeIngestionRequest request(Path file, String key) {
        KnowledgeSource source = new KnowledgeSource("source-job", "https://example.invalid/guide", "攻略",
                "CC-BY-4.0", KnowledgeSource.TrustLevel.OFFICIAL, "operator-1", Instant.now(), Instant.now());
        KnowledgeDocument document = new KnowledgeDocument("doc-job", source.id(), "guide/job", "zh-CN",
                "sv-1", "singles", "rules-1", null, null, Instant.now());
        return new KnowledgeIngestionRequest(file, source, document, "v1", "parser-1", "schema-1", "content-1",
                key, 1200, 120);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
        private void advance(Duration value) { instant = instant.plus(value); }
    }
}
