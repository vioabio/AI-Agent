package com.vio.vioaiagent.security;

/**
 * HITL 审计出口契约。生产环境可接入 append-only 数据库、消息队列或合规审计服务；
 * 默认使用 no-op，保证本地/单元测试不要求外部基础设施。
 */
@FunctionalInterface
public interface HitlAuditSink {
    void record(HitlAuditEvent event);

    static HitlAuditSink noop() {
        return event -> { };
    }
}
