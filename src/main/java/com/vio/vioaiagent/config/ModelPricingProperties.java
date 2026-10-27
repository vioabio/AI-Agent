package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("vio.run.pricing")
public record ModelPricingProperties(@DefaultValue("false") boolean enabled,
                                     @DefaultValue("") String catalogPath,
                                     @DefaultValue("") String scope) {
    public ModelPricingProperties {
        if (enabled && (catalogPath==null || catalogPath.isBlank() || scope==null
                || !scope.matches("[A-Za-z0-9_.:-]{1,100}"))) {
            throw new IllegalArgumentException("启用费用估算需指定本地价格目录与计费范围");
        }
    }
}
