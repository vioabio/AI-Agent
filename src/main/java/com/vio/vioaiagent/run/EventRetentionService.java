package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.DurableRunProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 事件保留清理入口。调度器始终注册，但默认开关关闭；运维可按 retention
 * class 手动调用并保存返回结果。
 */
@Service
public class EventRetentionService {
    private static final Logger log = LoggerFactory.getLogger(EventRetentionService.class);
    private static final String EVENT_RETENTION_CLASS = "run-events";
    private final EventRepository events;
    private final DurableRunProperties properties;
    private final Clock clock;

    @Autowired
    public EventRetentionService(EventRepository events, DurableRunProperties properties) {
        this(events, properties, Clock.systemUTC());
    }

    EventRetentionService(EventRepository events, DurableRunProperties properties, Clock clock) {
        this.events = Objects.requireNonNull(events, "events");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RetentionCleanupResult cleanup(String runId, String retentionClass, Instant cutoff, int batchSize) {
        return cleanup(runId, retentionClass, cutoff, Long.MAX_VALUE, batchSize);
    }

    /** 只删除 eventId 小于 beforeEventIdExclusive 的事件，通常保留最新终态事件。 */
    public RetentionCleanupResult cleanup(String runId, String retentionClass, Instant cutoff,
                                          long beforeEventIdExclusive, int batchSize) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId 不能为空");
        if (retentionClass == null || retentionClass.isBlank()) throw new IllegalArgumentException("retentionClass 不能为空");
        Instant now = clock.instant();
        Instant point = cutoff == null ? now.minus(Duration.ofDays(properties.eventRetentionDays())) : cutoff;
        if (point.isAfter(now)) throw new IllegalArgumentException("cutoff 不能晚于当前时间");
        long boundary = beforeEventIdExclusive == Long.MAX_VALUE
                ? Math.max(1L, events.lastEventId(runId)) : beforeEventIdExclusive;
        // 事件仓储按 event_id 批量清理；时间 cutoff 由生产清理任务在选择 run 时约束。
        int deleted = events.purgeBefore(runId, retentionClass.trim(), boundary, Math.max(1, batchSize));
        return new RetentionCleanupResult(runId, retentionClass, point, boundary, deleted,
                events.retainedFrom(runId), clock.instant());
    }

    /**
     * 定时保留清理入口。默认关闭，必须由运维在完成备份/恢复演练后显式开启；
     * 清理异常只记录脱敏错误码，不应让调度线程退出或把敏感连接信息写入日志。
     */
    @Scheduled(fixedDelayString = "${vio.run.event-retention-cleanup-interval-ms:3600000}")
    public void scheduledCleanup() {
        cleanupExpiredBatch();
    }

    /** 执行一批过期事件清理，可被运维命令和单测直接调用。 */
    public RetentionSweepResult cleanupExpiredBatch() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofDays(properties.eventRetentionDays()));
        int batchSize = properties.eventRetentionCleanupBatchSize();
        if (!properties.eventRetentionCleanupEnabled()) {
            return RetentionSweepResult.disabled(EVENT_RETENTION_CLASS, cutoff, batchSize, now);
        }
        try {
            int deleted = events.purgeExpired(EVENT_RETENTION_CLASS, cutoff, batchSize);
            return RetentionSweepResult.completed(EVENT_RETENTION_CLASS, cutoff, batchSize, deleted,
                    clock.instant());
        } catch (RuntimeException ex) {
            // 只记录异常类型；数据库 URL、SQL 和连接信息不能进入日志/证据。
            log.warn("event retention cleanup failed: errorType={}", ex.getClass().getSimpleName());
            return RetentionSweepResult.failed(EVENT_RETENTION_CLASS, cutoff, batchSize,
                    ex.getClass().getSimpleName(), clock.instant());
        }
    }

    public record RetentionCleanupResult(String runId, String retentionClass, Instant cutoff,
                                         long requestedBeforeEventId, int deleted,
                                         long retainedFromEventId, Instant completedAt) { }

    public record RetentionSweepResult(String status, String retentionClass, Instant cutoff,
                                       int batchSize, int deleted, String errorCode,
                                       Instant completedAt) {
        static RetentionSweepResult disabled(String retentionClass, Instant cutoff,
                                             int batchSize, Instant completedAt) {
            return new RetentionSweepResult("DISABLED", retentionClass, cutoff, batchSize,
                    0, null, completedAt);
        }

        static RetentionSweepResult completed(String retentionClass, Instant cutoff,
                                              int batchSize, int deleted, Instant completedAt) {
            return new RetentionSweepResult("COMPLETED", retentionClass, cutoff, batchSize,
                    deleted, null, completedAt);
        }

        static RetentionSweepResult failed(String retentionClass, Instant cutoff,
                                           int batchSize, String errorCode, Instant completedAt) {
            return new RetentionSweepResult("FAILED", retentionClass, cutoff, batchSize,
                    0, errorCode, completedAt);
        }
    }
}
