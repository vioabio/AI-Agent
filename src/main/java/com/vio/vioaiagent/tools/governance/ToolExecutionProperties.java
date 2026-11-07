package com.vio.vioaiagent.tools.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Explicit resource ceilings; invalid values fail startup instead of being clamped. */
@ConfigurationProperties(prefix = "vio.tools.execution")
public record ToolExecutionProperties(
        @DefaultValue("8") int threadCount,
        @DefaultValue("32") int queueCapacity,
        @DefaultValue("5000") long shutdownGraceMs) {
    public ToolExecutionProperties {
        if (threadCount < 1 || threadCount > 256) {
            throw new IllegalArgumentException("vio.tools.execution.thread-count must be in 1..256");
        }
        if (queueCapacity < 0 || queueCapacity > 10_000) {
            throw new IllegalArgumentException("vio.tools.execution.queue-capacity must be in 0..10000");
        }
        if (shutdownGraceMs < 0 || shutdownGraceMs > 60_000) {
            throw new IllegalArgumentException("vio.tools.execution.shutdown-grace-ms must be in 0..60000");
        }
    }

    public static ToolExecutionProperties defaults() {
        return new ToolExecutionProperties(8, 32, 5000);
    }
}
