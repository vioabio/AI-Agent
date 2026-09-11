package com.vio.vioaiagent.config;

import com.vio.vioaiagent.security.FailClosedGlobalQuotaStore;
import com.vio.vioaiagent.security.GlobalQuotaStore;
import com.vio.vioaiagent.security.RedisGlobalQuotaStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RedisGlobalQuotaConfigurationTest {

    @Test
    void redisAdapterRequiresProductionAndExplicitBackend() {
        try (AnnotationConfigApplicationContext context = context(true, "redis", true, true)) {
            assertInstanceOf(RedisGlobalQuotaStore.class, context.getBean(GlobalQuotaStore.class));
        }

        try (AnnotationConfigApplicationContext context = context(true, "jdbc", true, true)) {
            assertFalse(context.getBeansOfType(GlobalQuotaStore.class).values().stream()
                    .anyMatch(RedisGlobalQuotaStore.class::isInstance));
        }

        try (AnnotationConfigApplicationContext context = context(true, "redis", false, true)) {
            assertFalse(context.getBeansOfType(GlobalQuotaStore.class).values().stream()
                    .anyMatch(RedisGlobalQuotaStore.class::isInstance));
        }
    }

    @Test
    void missingRedisTemplateProducesFailClosedStore() {
        try (AnnotationConfigApplicationContext context = context(true, "redis", true, false)) {
            assertInstanceOf(FailClosedGlobalQuotaStore.class, context.getBean(GlobalQuotaStore.class));
        }
    }

    private AnnotationConfigApplicationContext context(boolean enabled, String backend,
                                                        boolean prod, boolean withTemplate) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (prod) context.getEnvironment().setActiveProfiles("prod");
        TestPropertyValues.of(
                "vio.security.global-quota-enabled=" + enabled,
                "vio.security.global-quota-backend=" + backend)
                .applyTo(context);
        if (withTemplate) context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
        context.register(RedisGlobalQuotaConfiguration.class);
        context.refresh();
        return context;
    }
}
