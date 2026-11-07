package com.vio.vioaiagent.run;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Run 事件 outbox 的持久化边界。 */
public interface RunOutboxRepository {
    RunOutboxMessage enqueue(RunOutboxMessage message);
    List<RunOutboxMessage> claimDue(String owner, Instant now, int limit);
    boolean markPublished(String outboxId, String owner, Instant publishedAt);
    boolean markFailed(String outboxId, String owner, String error, Instant nextAttemptAt, boolean deadLetter);
    Optional<RunOutboxMessage> find(String outboxId);
}
