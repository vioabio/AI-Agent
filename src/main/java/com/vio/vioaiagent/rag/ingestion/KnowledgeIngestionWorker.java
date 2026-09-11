package com.vio.vioaiagent.rag.ingestion;

import com.vio.vioaiagent.config.KnowledgeIngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small, bounded N1 worker loop. Reservation, lease checks, retry
 * classification and DLQ transitions remain in the durable JobService.
 *
 * <p>No worker is created unless {@code vio.rag.ingestion.worker-enabled=true}.
 * A missing outbox sink is a safe no-op rather than evidence that a broker is
 * configured.</p>
 */
@Component
@ConditionalOnProperty(prefix = "vio.rag.ingestion", name = "worker-enabled", havingValue = "true")
public class KnowledgeIngestionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeIngestionWorker.class);

    private final KnowledgeIngestionJobService jobs;
    private final ObjectProvider<KnowledgeIngestionJobService.OutboxSink> sinkProvider;
    private final KnowledgeIngestionProperties properties;
    private final String workerId = "ingestion-worker-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean();

    public KnowledgeIngestionWorker(KnowledgeIngestionJobService jobs,
                                    ObjectProvider<KnowledgeIngestionJobService.OutboxSink> sinkProvider,
                                    KnowledgeIngestionProperties properties) {
        if (jobs == null || sinkProvider == null || properties == null) {
            throw new IllegalArgumentException("jobs、sinkProvider、properties 不能为空");
        }
        this.jobs = jobs;
        this.sinkProvider = sinkProvider;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${vio.rag.ingestion.worker-poll-ms:1000}")
    public void poll() { pollOnce(); }

    /** Execute at most the configured batch; useful for a controlled smoke. */
    public WorkerReport pollOnce() {
        if (!running.compareAndSet(false, true)) {
            return new WorkerReport(0, 0, 0, 0, true, false);
        }
        int claimed = 0;
        int succeeded = 0;
        int failed = 0;
        int deadLettered = 0;
        try {
            for (int i = 0; i < properties.workerBatchSize(); i++) {
                Optional<KnowledgeIngestionJobService.JobExecutionResult> next;
                try {
                    next = jobs.runNext(workerId);
                } catch (RuntimeException ex) {
                    // Do not kill the scheduler or spin on a repository outage.
                    LOG.warn("知识摄取 worker 领取失败 type={}", safeType(ex));
                    failed++;
                    break;
                }
                if (next.isEmpty()) break;
                claimed++;
                KnowledgeIngestionJobService.JobExecutionResult result = next.get();
                if (result.status() == KnowledgeIngestionJob.JobStatus.SUCCEEDED) succeeded++;
                if (result.status() == KnowledgeIngestionJob.JobStatus.DLQ) {
                    failed++;
                    deadLettered++;
                } else if (result.status() != KnowledgeIngestionJob.JobStatus.SUCCEEDED) {
                    failed++;
                }
            }

            KnowledgeIngestionJobService.OutboxSink sink = sinkProvider.getIfAvailable();
            boolean sinkMissing = sink == null;
            if (!sinkMissing) {
                try {
                    KnowledgeIngestionJobService.OutboxPublishReport report =
                            jobs.publishPending(sink, properties.outboxBatchSize());
                    failed += report.failed();
                } catch (RuntimeException ex) {
                    LOG.warn("知识摄取 outbox 发布失败 type={}", safeType(ex));
                    failed++;
                }
            }
            return new WorkerReport(claimed, succeeded, failed, deadLettered, false, sinkMissing);
        } finally {
            running.set(false);
        }
    }

    private String safeType(Exception ex) {
        return ex == null ? "Exception" : ex.getClass().getSimpleName();
    }

    public record WorkerReport(int claimed, int succeeded, int failed, int deadLettered,
                               boolean overlappingPoll, boolean sinkMissing) {
        public WorkerReport {
            if (claimed < 0 || succeeded < 0 || failed < 0 || deadLettered < 0) {
                throw new IllegalArgumentException("worker 统计不能为负数");
            }
        }
    }
}
