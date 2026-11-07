package com.vio.vioaiagent.rag.ingestion;

import com.vio.vioaiagent.config.KnowledgeIngestionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Worker 只负责 bounded 调度；状态机仍由 JobService/Repository 负责。 */
class KnowledgeIngestionWorkerTest {

    @Test
    void pollIsBoundedAndMissingSinkIsReported() {
        AtomicInteger calls = new AtomicInteger();
        KnowledgeIngestionJobService service = fakeService(() -> {
            if (calls.getAndIncrement() == 0) {
                return Optional.of(new KnowledgeIngestionJobService.JobExecutionResult(
                        "job-1", 1, KnowledgeIngestionJob.JobStatus.SUCCEEDED, null, null));
            }
            return Optional.empty();
        });
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.setWorkerBatchSize(2);
        KnowledgeIngestionWorker worker = new KnowledgeIngestionWorker(service, emptyProvider(), properties);

        KnowledgeIngestionWorker.WorkerReport report = worker.pollOnce();

        assertEquals(1, report.claimed());
        assertEquals(1, report.succeeded());
        assertEquals(0, report.failed());
        assertTrue(report.sinkMissing());
        assertEquals(2, calls.get());
    }

    @Test
    void propertiesClampUnsafeValues() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.setWorkerPollMs(1);
        properties.setWorkerBatchSize(1000);
        properties.setOutboxBatchSize(10000);

        assertEquals(250, properties.workerPollMs());
        assertEquals(20, properties.workerBatchSize());
        assertEquals(1000, properties.outboxBatchSize());
    }

    private KnowledgeIngestionJobService fakeService(java.util.function.Supplier<Optional<KnowledgeIngestionJobService.JobExecutionResult>> next) {
        return new KnowledgeIngestionJobService(new InMemoryKnowledgeIngestionJobRepository(), job -> null) {
            @Override
            public Optional<JobExecutionResult> runNext(String workerId) { return next.get(); }
        };
    }

    private ObjectProvider<KnowledgeIngestionJobService.OutboxSink> emptyProvider() {
        return new ObjectProvider<>() {
            @Override public KnowledgeIngestionJobService.OutboxSink getObject(Object... args) { return null; }
            @Override public KnowledgeIngestionJobService.OutboxSink getObject() { return null; }
            @Override public KnowledgeIngestionJobService.OutboxSink getIfAvailable() { return null; }
            @Override public KnowledgeIngestionJobService.OutboxSink getIfUnique() { return null; }
            @Override public Iterator<KnowledgeIngestionJobService.OutboxSink> iterator() { return List.<KnowledgeIngestionJobService.OutboxSink>of().iterator(); }
        };
    }
}
