package com.vio.vioaiagent.pokemon.guides;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.local-guides.semantic.enabled",havingValue="true")
@EnableConfigurationProperties(GuideSemanticProperties.class)
public class GuideSemanticConfiguration {
    @Bean public LocalGuideSemanticSearch localGuideSemanticSearch(GuideSemanticProperties properties){return new LocalGuideSemanticSearch(properties);}
}
