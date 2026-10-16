package com.vio.vioaiagent.config;

import com.vio.vioaiagent.security.FailClosedToolAuditSink;
import com.vio.vioaiagent.security.ToolAuditSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Registers the durable audit fallback after component repositories have been scanned. */
@Configuration(proxyBeanMethods = false)
@Profile({"prod", "demo"})
public class ToolAuditSinkConfiguration {
    @Bean
    @ConditionalOnMissingBean(ToolAuditSink.class)
    public FailClosedToolAuditSink failClosedToolAuditSink() {
        // A ConditionalOnMissingBean component implements its own condition
        // type and can exclude itself during registration. A bean method on a
        // separate configuration evaluates before this sink is registered.
        return new FailClosedToolAuditSink();
    }
}
