package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.DurableRunProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Bounded cleanup for expired opaque step artifacts. It is deliberately
 * disabled by default and treats a missing repository as a disabled result,
 * so demo/local profiles do not need a durable artifact bean.
 */
@Service
public class RunStepOutputRetentionService {
    private static final Logger log = LoggerFactory.getLogger(RunStepOutputRetentionService.class);
    private final ObjectProvider<RunStepOutputRepository> repository;
    private final DurableRunProperties properties;
    private final Clock clock;

    @Autowired
    public RunStepOutputRetentionService(ObjectProvider<RunStepOutputRepository> repository,
                                         DurableRunProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    RunStepOutputRetentionService(ObjectProvider<RunStepOutputRepository> repository,
                                  DurableRunProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${vio.run.step-output-retention-cleanup-interval-ms:3600000}")
    public void scheduledCleanup() {
        cleanupExpiredBatch();
    }

    public CleanupResult cleanupExpiredBatch() {
        Instant now = clock.instant();
        int batchSize = properties.stepOutputRetentionCleanupBatchSize();
        if (!properties.stepOutputRetentionCleanupEnabled()) {
            return new CleanupResult("DISABLED", 0, batchSize, null, now);
        }
        RunStepOutputRepository store = repository.getIfAvailable();
        if (store == null) return new CleanupResult("NO_REPOSITORY", 0, batchSize, null, now);
        try {
            int deleted = store.deleteExpired(now, batchSize);
            return new CleanupResult("COMPLETED", deleted, batchSize, null, clock.instant());
        } catch (RuntimeException ex) {
            log.warn("run step output cleanup failed: errorType={}", ex.getClass().getSimpleName());
            return new CleanupResult("FAILED", 0, batchSize, ex.getClass().getSimpleName(), clock.instant());
        }
    }

    public record CleanupResult(String status, int deleted, int batchSize,
                                String errorCode, Instant completedAt) { }
}
