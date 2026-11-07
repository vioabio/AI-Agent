package com.vio.vioaiagent.run;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbox publisher：默认关闭，且没有显式 sink 时不会领取消息。
 * 启用后按 at-least-once 语义投递，消费者必须按 outboxId 去重。
 */
@Component
@ConditionalOnProperty(prefix = "vio.run", name = "event-broker-enabled", havingValue = "true")
public class RunOutboxDispatcher {
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 10;
    private final RunOutboxRepository repository;
    private final ObjectProvider<RunOutboxSink> sinkProvider;
    private final Clock clock;
    private final String owner = "outbox-" + UUID.randomUUID();

    public RunOutboxDispatcher(RunOutboxRepository repository,
                               ObjectProvider<RunOutboxSink> sinkProvider) {
        this(repository, sinkProvider, Clock.systemUTC());
    }

    RunOutboxDispatcher(RunOutboxRepository repository,
                        ObjectProvider<RunOutboxSink> sinkProvider,
                        Clock clock) {
        this.repository = repository;
        this.sinkProvider = sinkProvider;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Scheduled(fixedDelayString = "${vio.run.outbox-poll-ms:1000}")
    public void poll() {
        dispatchOnce();
    }

    /** 执行一批投递，便于集成测试/运维手工触发。 */
    public DispatchReport dispatchOnce() {
        RunOutboxSink sink = sinkProvider.getIfAvailable();
        if (sink == null) return new DispatchReport(0, 0, 0, true);
        Instant now = clock.instant();
        List<RunOutboxMessage> claimed = repository.claimDue(owner, now, BATCH_SIZE);
        int published = 0, failed = 0, deadLettered = 0;
        for (RunOutboxMessage message : claimed) {
            try {
                sink.publish(message);
                if (repository.markPublished(message.outboxId(), owner, clock.instant())) published++;
            } catch (Exception ex) {
                int attempts = message.attempts();
                boolean dlq = attempts >= MAX_ATTEMPTS;
                Instant retryAt = clock.instant().plus(backoff(attempts));
                if (repository.markFailed(message.outboxId(), owner, safeMessage(ex), retryAt, dlq)) {
                    failed++;
                    if (dlq) deadLettered++;
                }
            }
        }
        return new DispatchReport(claimed.size(), published, failed, false, deadLettered);
    }

    private static Duration backoff(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 8));
        return Duration.ofSeconds(Math.min(900, 5L << exponent));
    }

    private static String safeMessage(Exception ex) {
        String type = ex == null ? "Exception" : ex.getClass().getSimpleName();
        String text = ex == null ? "" : String.valueOf(ex.getMessage());
        if (text.length() > 256) text = text.substring(0, 256);
        text = text.replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*[^,;\\s]+", "$1=[REDACTED]");
        return type + (text.isBlank() ? "" : ": " + text);
    }

    public record DispatchReport(int claimed, int published, int failed, boolean sinkMissing,
                                 int deadLettered) {
        DispatchReport(int claimed, int published, int failed, boolean sinkMissing) {
            this(claimed, published, failed, sinkMissing, 0);
        }
    }
}
