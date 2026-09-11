package com.vio.vioaiagent.config;

import com.vio.vioaiagent.security.FailClosedGlobalQuotaStore;
import com.vio.vioaiagent.security.GlobalQuotaStore;
import com.vio.vioaiagent.security.RedisGlobalQuotaStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 生产 Redis/Lua 全局配额适配器。
 *
 * <p>只有显式设置 {@code prod + global-quota-enabled=true +
 * global-quota-backend=redis} 才会创建。没有 Redis Template 时创建
 * fail-closed 适配器，避免 TokenStore 降级到单 JVM 计数。</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "vio.security", name = "global-quota-enabled",
        havingValue = "true")
@ConditionalOnProperty(prefix = "vio.security", name = "global-quota-backend",
        havingValue = "redis")
public class RedisGlobalQuotaConfiguration {

    @Bean
    @Primary
    @ConditionalOnBean(StringRedisTemplate.class)
    public GlobalQuotaStore redisGlobalQuotaStore(StringRedisTemplate redisTemplate) {
        return new RedisGlobalQuotaStore(redisTemplate);
    }

    /**
     * Redis starter may be present while auto-configuration is deliberately
     * excluded in a restricted deployment. Keep the security boundary strict.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean({StringRedisTemplate.class, GlobalQuotaStore.class})
    public GlobalQuotaStore redisUnavailableGlobalQuotaStore(
            @Value("${vio.security.global-quota-backend:redis}") String backend) {
        return new FailClosedGlobalQuotaStore("Redis 全局配额适配器不可用（backend="
                + backend + ")");
    }

    /**
     * Package-visible factory used by configuration tests without constructing
     * a Redis connection. The ObjectProvider keeps this path side-effect free.
     */
    static GlobalQuotaStore select(ObjectProvider<StringRedisTemplate> provider,
                                   String backend) {
        StringRedisTemplate template = provider == null ? null : provider.getIfAvailable();
        return template == null
                ? new FailClosedGlobalQuotaStore("Redis 全局配额适配器不可用（backend=" + backend + ")")
                : new RedisGlobalQuotaStore(template);
    }
}
