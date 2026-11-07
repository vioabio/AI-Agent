package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * PostgreSQL 事件 + outbox 原子写入适配器。
 *
 * <p>事务由该独立 Bean 提供，避免在同一个 Service 内自调用导致
 * {@code @Transactional} 代理失效。真实 broker 仍由后续 dispatcher 负责。</p>
 */
@Component
@Profile({"prod", "demo"})
public class JdbcRunEventOutboxAppender implements RunEventOutboxAppender {
    private final EventRepository events;
    private final RunOutboxRepository outbox;

    public JdbcRunEventOutboxAppender(EventRepository events, RunOutboxRepository outbox) {
        this.events = events;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public AgentEvent append(AgentRun run, AgentEventType type, long eventIdHint,
                             Map<String, Object> payload) {
        if (run == null || type == null) throw new IllegalArgumentException("Run/事件类型不能为空");
        AgentEvent event = AgentEvent.of(Math.max(1, eventIdHint), type, run.sessionId(),
                run.id(), run.traceId(), payload);
        AgentEvent persisted = events.appendNext(event);
        String outboxId = run.id() + ":" + persisted.eventId();
        outbox.enqueue(RunOutboxMessage.pending(outboxId, run.id(), persisted.eventId(),
                persisted.type().wireName(), persisted.payload()));
        return persisted;
    }
}
