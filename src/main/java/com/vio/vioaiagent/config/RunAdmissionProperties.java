package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-instance capacity. Cluster-wide admission requires a shared coordinator. */
@ConfigurationProperties(prefix = "vio.run.admission")
public class RunAdmissionProperties {
    private int threadCount = 8;
    private int queueCapacity = 16;
    private int maxPerPrincipal = 4;
    private int maxPerTenant = 16;
    private long queueWaitMs = 30_000;
    private int retryAfterSeconds = 1;

    public int threadCount() { return threadCount; }
    public void setThreadCount(int value) { threadCount = range(value, 1, 256, "thread-count"); }
    public int queueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = range(value, 0, 10_000, "queue-capacity"); }
    public int maxPerPrincipal() { return maxPerPrincipal; }
    public void setMaxPerPrincipal(int value) { maxPerPrincipal = range(value, 1, 10_256, "max-per-principal"); }
    public int maxPerTenant() { return maxPerTenant; }
    public void setMaxPerTenant(int value) { maxPerTenant = range(value, 1, 10_256, "max-per-tenant"); }
    public long queueWaitMs() { return queueWaitMs; }
    public void setQueueWaitMs(long value) {
        if (value < 1 || value > 3_600_000) throw new IllegalArgumentException("queue-wait-ms must be 1..3600000");
        queueWaitMs = value;
    }
    public int retryAfterSeconds() { return retryAfterSeconds; }
    public void setRetryAfterSeconds(int value) { retryAfterSeconds = range(value, 1, 3600, "retry-after-seconds"); }
    public int maxInFlight() { return threadCount + queueCapacity; }
    private static int range(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " is outside its supported range");
        return value;
    }
}
