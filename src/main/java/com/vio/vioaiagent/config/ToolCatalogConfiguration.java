package com.vio.vioaiagent.config;

import com.vio.vioaiagent.tools.governance.ToolCatalogValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 将工具目录边界变成启动门禁，而不是依赖人工检查。 */
@Configuration
public class ToolCatalogConfiguration {
    @Bean
    public ToolCatalogValidator.CatalogSnapshot toolCatalogSnapshot() {
        return ToolCatalogValidator.validate();
    }
}
