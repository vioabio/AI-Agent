package com.vio.vioaiagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import java.util.Set;

/** Reject unsupported production quota backends instead of silently degrading. */
@Component
@Profile("prod")
public class GlobalQuotaBackendValidator {
    private static final Set<String> SUPPORTED = Set.of("jdbc", "redis");

    @Value("${vio.security.global-quota-enabled:false}")
    private boolean enabled;

    @Value("${vio.security.global-quota-backend:jdbc}")
    private String backend;

    @Value("${spring.data.redis.host:}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @PostConstruct
    void validate() {
        if (!enabled) return;
        String normalized = backend == null ? "" : backend.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED.contains(normalized)) {
            throw new IllegalStateException("不支持的生产全局配额 backend: " + backend
                    + "，允许值为 jdbc 或 redis");
        }
        if ("redis".equals(normalized)
                && (redisHost == null || redisHost.isBlank() || "__unset__".equals(redisHost.trim()))) {
            throw new IllegalStateException("Redis backend 已启用，但 spring.data.redis.host 未通过环境/Secret Store 注入");
        }
        if ("redis".equals(normalized) && (redisPort < 1 || redisPort > 65_535)) {
            throw new IllegalStateException("Redis backend 端口无效: " + redisPort);
        }
    }
}
