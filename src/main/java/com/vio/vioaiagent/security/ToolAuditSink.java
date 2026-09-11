package com.vio.vioaiagent.security;

/**
 * 普通工具调用的结构化审计出口。
 *
 * <p>与旧版 {@link AuditLogger#log(AuditEntry)} 不同，这个契约允许调用方
 * 感知持久化失败。生产适配器必须把 {@link #durable()} 设为 {@code true}，
 * 这样工具网关才会在审计不可用时阻止把已执行结果报告为成功。</p>
 */
@FunctionalInterface
public interface ToolAuditSink {

    /** 写入一条已经过参数边界处理的工具审计事实。失败必须抛出 RuntimeException。 */
    void record(AuditEntry entry);

    /** 是否是跨进程/持久化的审计出口。 */
    default boolean durable() {
        return false;
    }

    /** 审计写入失败时是否必须阻止 EXECUTED 结果。 */
    default boolean failClosedOnError() {
        return durable();
    }

    /** 显式 no-op 只用于测试或本地嵌入式调用，不可作为生产实现。 */
    static ToolAuditSink noop() {
        return entry -> { };
    }
}
