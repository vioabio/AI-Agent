package com.vio.vioaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class ModelPricingPropertiesTest {
    @EnableConfigurationProperties(ModelPricingProperties.class) static class Binding { }
    @Test void disabledByDefaultAndEnabledConfigurationRequiresAnExplicitCatalogAndScope() {
        var runner=new ApplicationContextRunner().withUserConfiguration(Binding.class);
        runner.run(context->assertFalse(context.getBean(ModelPricingProperties.class).enabled()));
        runner.withPropertyValues("vio.run.pricing.enabled=true").run(context->assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("vio.run.pricing.enabled=true","vio.run.pricing.catalog-path=prices.json","vio.run.pricing.scope=local-contract")
                .run(context->assertTrue(context.getBean(ModelPricingProperties.class).enabled()));
    }
    @Test void enabledBeanLoadsOnlyAnExplicitLocalCatalogAndInvalidFilesFailStartup() throws Exception {
        String file=java.nio.file.Path.of(getClass().getResource("/pricing/fixture-prices.json").toURI()).toString();
        var runner=new ApplicationContextRunner().withUserConfiguration(Binding.class,ModelPricingConfiguration.class)
                .withPropertyValues("vio.run.pricing.enabled=true","vio.run.pricing.scope=offline-test","vio.model.provider=deepseek");
        runner.withPropertyValues("vio.run.pricing.catalog-path="+file).run(context->assertEquals("test-fixture-v1",
                context.getBean(com.vio.vioaiagent.observability.ModelPriceCatalog.class).policy().get("version")));
        runner.withPropertyValues("vio.run.pricing.catalog-path=does-not-exist.json").run(context->assertNotNull(context.getStartupFailure()));
    }
}
