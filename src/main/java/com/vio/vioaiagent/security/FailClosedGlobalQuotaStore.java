package com.vio.vioaiagent.security;

import java.time.Duration;
import java.time.Instant;

/**
 * 共享配额适配器缺失时的生产兜底。
 *
 * <p>它永远不会发放额度，也不会回退到 JVM Map。这样 Redis 连接工厂、
 * backend 配置或自动装配异常不会悄悄削弱跨实例限流。</p>
 */
public final class FailClosedGlobalQuotaStore implements GlobalQuotaStore {
    private final String reason;

    public FailClosedGlobalQuotaStore(String reason) {
        this.reason = reason == null || reason.isBlank() ? "全局配额适配器未配置" : reason;
    }

    @Override
    public QuotaDecision consume(String scope, String subject, int limit, Duration window, Instant now) {
        throw new RedisGlobalQuotaStore.QuotaStoreUnavailableException(reason);
    }
}
