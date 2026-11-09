package com.vio.vioaiagent.pokemon.guides;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("vio.local-guides.semantic")
public record GuideSemanticProperties(@DefaultValue("") String endpoint,@DefaultValue("") String apiKey,
        @DefaultValue("") String model,@DefaultValue("1536") int dimensions,
        @DefaultValue(".local-data/guide-vectors.sqlite") String path,
        @DefaultValue("10") int timeoutSeconds,@DefaultValue("0.55") double minimumSimilarity) {
    public GuideSemanticProperties {
        if(dimensions<1||dimensions>8192||timeoutSeconds<1||timeoutSeconds>30||!Double.isFinite(minimumSimilarity)||minimumSimilarity<0||minimumSimilarity>1)throw new IllegalArgumentException("Embedding维度、超时或相似度配置无效");
    }
    @Override public String toString(){return "GuideSemanticProperties[model="+model+", dimensions="+dimensions+", credentials=REDACTED]";}
}
