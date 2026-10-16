package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({DurableRunProperties.class, KnowledgeIngestionProperties.class,
        ModelInvocationCacheProperties.class, RunAdmissionProperties.class})
public class DurableRunConfiguration {
}
