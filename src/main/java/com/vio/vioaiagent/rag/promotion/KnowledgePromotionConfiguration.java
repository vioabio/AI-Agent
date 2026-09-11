package com.vio.vioaiagent.rag.promotion;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring only; ReleaseQualityGate itself remains framework-independent. */
@Configuration
public class KnowledgePromotionConfiguration {

    @Bean
    @ConditionalOnMissingBean(ReleaseQualityGate.class)
    public ReleaseQualityGate releaseQualityGate(ObjectProvider<EvaluationReportRepository> provider) {
        EvaluationReportRepository reports = provider == null
                ? EvaluationReportRepository.unavailable()
                : provider.getIfAvailable(EvaluationReportRepository::unavailable);
        return new ReleaseQualityGate(reports);
    }
}
