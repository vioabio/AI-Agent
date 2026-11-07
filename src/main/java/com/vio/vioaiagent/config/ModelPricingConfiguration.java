package com.vio.vioaiagent.config;

import com.vio.vioaiagent.observability.ModelPriceCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class ModelPricingConfiguration {
    @Bean
    ModelPriceCatalog modelPriceCatalog(ModelPricingProperties properties,
                                       @Value("${vio.model.provider:dashscope}") String provider) {
        return properties.enabled()?ModelPriceCatalog.load(properties.catalogPath(),properties.scope(),provider)
                :ModelPriceCatalog.disabled();
    }
}
