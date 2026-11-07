package com.vio.vioaiagent.security;

/**
 * 生产审计适配器缺失时的安全占位实现。
 *
 * <p>它故意不提供任何“成功写入”假象；工具网关收到该异常后会阻止
 * {@code EXECUTED} 结果。这样数据库/JDBC bean 配置错误不会静默退回本地文件。</p>
 */
public final class FailClosedToolAuditSink implements ToolAuditSink {
    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public boolean failClosedOnError() {
        return true;
    }

    @Override
    public void record(AuditEntry entry) {
        throw new AuditWriteException("生产工具审计适配器不可用");
    }
}
