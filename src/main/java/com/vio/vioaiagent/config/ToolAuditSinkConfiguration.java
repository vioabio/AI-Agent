package com.vio.vioaiagent.config;

import com.vio.vioaiagent.security.FailClosedToolAuditSink;
import com.vio.vioaiagent.security.ToolAuditSink;
import com.vio.vioaiagent.security.ParameterMasker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Registers shared audit dependencies and the durable fallback after repository scanning. */
@Configuration(proxyBeanMethods = false)
@Profile({"prod", "demo"})
public class ToolAuditSinkConfiguration {
    @Bean
    @ConditionalOnMissingBean(ParameterMasker.class)
    public ParameterMasker parameterMasker() {
        // JdbcToolAuditSink requires a managed dependency; the gateway's
        // per-instance fallback cannot satisfy Spring constructor injection.
        return new ParameterMasker();
    }

    @Bean
    @ConditionalOnMissingBean(ToolAuditSink.class)
    public FailClosedToolAuditSink failClosedToolAuditSink() {
        // A ConditionalOnMissingBean component implements its own condition
        // type and can exclude itself during registration. A bean method on a
        // separate configuration evaluates before this sink is registered.
        return new FailClosedToolAuditSink();
    }
}
