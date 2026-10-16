package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 本地/测试 outbox；不对外宣称跨进程耐久性。 */
@Repository
@Profile("!prod & !demo")
@Primary
public class InMemoryRunOutboxRepository implements RunOutboxRepository {
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    private final ConcurrentMap<String, RunOutboxMessage> entries = new ConcurrentHashMap<>();

    @Override
    public RunOutboxMessage enqueue(RunOutboxMessage message) {
        if (message == null) throw new IllegalArgumentException("outbox 消息不能为空");
        return entries.putIfAbsent(message.outboxId(), message) == null
                ? message : entries.get(message.outboxId());
    }

    @Override
    public synchronized List<RunOutboxMessage> claimDue(String owner, Instant now, int limit) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner 不能为空");
        Instant point = now == null ? Instant.now() : now;
        int max = Math.max(1, Math.min(limit, 1000));
        List<RunOutboxMessage> result = new ArrayList<>();
        entries.values().stream()
                // PENDING 表示事务已提交、可立即投递；nextAttemptAt 只约束失败重试。
                // 这样即使调用方用历史时钟回放 pending 记录，也不会把首次投递误判为未来任务。
                .filter(item -> (item.status() == RunOutboxMessage.OutboxStatus.PENDING
                        || (item.status() == RunOutboxMessage.OutboxStatus.FAILED
                            && !item.nextAttemptAt().isAfter(point))
                        || (item.status() == RunOutboxMessage.OutboxStatus.CLAIMED
                            && item.claimExpiresAt() != null && !item.claimExpiresAt().isAfter(point))))
                .sorted(Comparator.comparing(RunOutboxMessage::createdAt))
                .limit(max)
                .forEach(item -> {
                    RunOutboxMessage claimed = new RunOutboxMessage(item.outboxId(), item.runId(), item.eventId(),
                            item.eventType(), item.payload(), RunOutboxMessage.OutboxStatus.CLAIMED,
                            item.attempts() + 1, item.nextAttemptAt(), item.createdAt(), item.publishedAt(),
                            item.lastError(), owner, point.plus(CLAIM_LEASE));
                    if (entries.replace(item.outboxId(), item, claimed)) result.add(claimed);
                });
        return List.copyOf(result);
    }

    @Override
    public boolean markPublished(String outboxId, String owner, Instant publishedAt) {
        RunOutboxMessage current = entries.get(outboxId);
        if (!owned(current, owner)) return false;
        RunOutboxMessage next = new RunOutboxMessage(current.outboxId(), current.runId(), current.eventId(),
                current.eventType(), current.payload(), RunOutboxMessage.OutboxStatus.PUBLISHED,
                current.attempts(), current.nextAttemptAt(), current.createdAt(),
                publishedAt == null ? Instant.now() : publishedAt, current.lastError(), null, null);
        return entries.replace(outboxId, current, next);
    }

    @Override
    public boolean markFailed(String outboxId, String owner, String error, Instant nextAttemptAt,
                              boolean deadLetter) {
        RunOutboxMessage current = entries.get(outboxId);
        if (!owned(current, owner)) return false;
        RunOutboxMessage next = new RunOutboxMessage(current.outboxId(), current.runId(), current.eventId(),
                current.eventType(), current.payload(), deadLetter ? RunOutboxMessage.OutboxStatus.DLQ
                        : RunOutboxMessage.OutboxStatus.FAILED, current.attempts(),
                nextAttemptAt == null ? Instant.now() : nextAttemptAt, current.createdAt(), current.publishedAt(),
                error == null ? "" : error, null, null);
        return entries.replace(outboxId, current, next);
    }

    @Override
    public Optional<RunOutboxMessage> find(String outboxId) { return Optional.ofNullable(entries.get(outboxId)); }

    private static boolean owned(RunOutboxMessage current, String owner) {
        return current != null && current.status() == RunOutboxMessage.OutboxStatus.CLAIMED
                && owner != null && owner.equals(current.claimOwner());
    }
}
