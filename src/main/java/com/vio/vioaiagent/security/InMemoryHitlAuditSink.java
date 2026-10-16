package com.vio.vioaiagent.security;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 本地/测试审计 sink；生产环境必须使用 JDBC/合规适配器。 */
@Repository
@Primary
@Profile("!prod & !demo")
public class InMemoryHitlAuditSink implements HitlAuditSink {

    private final CopyOnWriteArrayList<HitlAuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(HitlAuditEvent event) {
        if (event == null) throw new IllegalArgumentException("audit event 不能为空");
        for (HitlAuditEvent existing : events) {
            if (existing.eventId().equals(event.eventId())) {
                if (!existing.equals(event)) {
                    throw new HitlApprovalConflictException("重复 eventId 的审计内容不一致: " + event.eventId());
                }
                return; // idempotent retry
            }
        }
        events.add(event);
    }

    public List<HitlAuditEvent> events() {
        return List.copyOf(events);
    }
}
