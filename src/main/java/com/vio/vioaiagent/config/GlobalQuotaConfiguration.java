package com.vio.vioaiagent.config;

import com.vio.vioaiagent.security.GlobalQuotaStore;
import com.vio.vioaiagent.security.InMemoryGlobalQuotaStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 配额适配器选择；默认保持关闭/本地兼容，验收后再启用生产实现。 */
@Configuration
public class GlobalQuotaConfiguration {
    @Bean
    @Profile("!prod")
    @ConditionalOnProperty(prefix = "vio.security", name = "global-quota-enabled",
            havingValue = "true")
    @ConditionalOnProperty(prefix = "vio.security", name = "global-quota-backend",
            havingValue = "memory", matchIfMissing = true)
    public GlobalQuotaStore inMemoryGlobalQuotaStore() {
        return new InMemoryGlobalQuotaStore();
    }
}
