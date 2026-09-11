package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * N1 ingestion worker switches. The worker is deliberately disabled by
 * default; enabling it is a release decision that requires the staging
 * reservation/DLQ evidence described in the v1.1 handbook.
 */
@ConfigurationProperties(prefix = "vio.rag.ingestion")
public class KnowledgeIngestionProperties {
    private boolean workerEnabled;
    private long workerPollMs = 1000L;
    private int workerBatchSize = 1;
    private int outboxBatchSize = 50;

    public boolean workerEnabled() { return workerEnabled; }

    public void setWorkerEnabled(boolean workerEnabled) { this.workerEnabled = workerEnabled; }

    public long workerPollMs() { return workerPollMs; }

    public void setWorkerPollMs(long workerPollMs) {
        this.workerPollMs = Math.max(250L, Math.min(workerPollMs, 60_000L));
    }

    public int workerBatchSize() { return workerBatchSize; }

    public void setWorkerBatchSize(int workerBatchSize) {
        this.workerBatchSize = Math.max(1, Math.min(workerBatchSize, 20));
    }

    public int outboxBatchSize() { return outboxBatchSize; }

    public void setOutboxBatchSize(int outboxBatchSize) {
        this.outboxBatchSize = Math.max(1, Math.min(outboxBatchSize, 1000));
    }
}
