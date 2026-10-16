package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** N2 Run 耐久化开关与租约参数。默认关闭恢复/跨实例 broker，避免未经验收误接管生产流量。 */
@ConfigurationProperties(prefix = "vio.run")
public class DurableRunProperties {
    private boolean durableResume;
    private boolean eventBrokerEnabled;
    private long leaseTtlSeconds = 30;
    private long heartbeatSeconds = 10;
    private int eventRetentionDays = 30;
    private long outboxPollMs = 1000;
    /** 清理任务默认关闭，只有完成保留/备份演练后才允许显式开启。 */
    private boolean eventRetentionCleanupEnabled;
    private long eventRetentionCleanupIntervalMs = 3_600_000L;
    private int eventRetentionCleanupBatchSize = 1000;
    /** Step artifacts are opaque encrypted facts; cleanup remains opt-in until backup evidence exists. */
    private boolean stepOutputRetentionCleanupEnabled;
    private long stepOutputRetentionCleanupIntervalMs = 3_600_000L;
    private int stepOutputRetentionCleanupBatchSize = 1000;

    public boolean durableResume() { return durableResume; }
    public void setDurableResume(boolean durableResume) { this.durableResume = durableResume; }
    public boolean eventBrokerEnabled() { return eventBrokerEnabled; }
    public void setEventBrokerEnabled(boolean eventBrokerEnabled) { this.eventBrokerEnabled = eventBrokerEnabled; }
    public long leaseTtlSeconds() { return leaseTtlSeconds; }
    public void setLeaseTtlSeconds(long value) { this.leaseTtlSeconds = Math.max(5, Math.min(value, 3600)); }
    public long heartbeatSeconds() { return heartbeatSeconds; }
    public void setHeartbeatSeconds(long value) { this.heartbeatSeconds = Math.max(1, Math.min(value, 600)); }
    public int eventRetentionDays() { return eventRetentionDays; }
    public void setEventRetentionDays(int value) { this.eventRetentionDays = Math.max(1, Math.min(value, 3650)); }
    public long outboxPollMs() { return outboxPollMs; }
    public void setOutboxPollMs(long value) { this.outboxPollMs = Math.max(100, Math.min(value, 60000)); }
    public boolean eventRetentionCleanupEnabled() { return eventRetentionCleanupEnabled; }
    public void setEventRetentionCleanupEnabled(boolean value) { this.eventRetentionCleanupEnabled = value; }
    public long eventRetentionCleanupIntervalMs() { return eventRetentionCleanupIntervalMs; }
    public void setEventRetentionCleanupIntervalMs(long value) {
        this.eventRetentionCleanupIntervalMs = Math.max(60_000L, Math.min(value, 86_400_000L));
    }
    public int eventRetentionCleanupBatchSize() { return eventRetentionCleanupBatchSize; }
    public void setEventRetentionCleanupBatchSize(int value) {
        this.eventRetentionCleanupBatchSize = Math.max(1, Math.min(value, 10_000));
    }
    public boolean stepOutputRetentionCleanupEnabled() { return stepOutputRetentionCleanupEnabled; }
    public void setStepOutputRetentionCleanupEnabled(boolean value) { this.stepOutputRetentionCleanupEnabled = value; }
    public long stepOutputRetentionCleanupIntervalMs() { return stepOutputRetentionCleanupIntervalMs; }
    public void setStepOutputRetentionCleanupIntervalMs(long value) {
        this.stepOutputRetentionCleanupIntervalMs = Math.max(60_000L, Math.min(value, 86_400_000L));
    }
    public int stepOutputRetentionCleanupBatchSize() { return stepOutputRetentionCleanupBatchSize; }
    public void setStepOutputRetentionCleanupBatchSize(int value) {
        this.stepOutputRetentionCleanupBatchSize = Math.max(1, Math.min(value, 10_000));
    }
}
