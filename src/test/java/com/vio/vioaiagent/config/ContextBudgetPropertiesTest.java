package com.vio.vioaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class ContextBudgetPropertiesTest {
    @EnableConfigurationProperties(ContextBudgetProperties.class) static class Binding { }
    @Test void defaultsAndOverridesBindAndInvalidReserveFailsStartup() {
        var runner=new ApplicationContextRunner().withUserConfiguration(Binding.class);
        runner.run(context->assertEquals(ContextBudgetProperties.defaults(),context.getBean(ContextBudgetProperties.class)));
        runner.withPropertyValues("vio.run.context-budget.context-tokens=1024","vio.run.context-budget.output-tokens=128",
                "vio.run.context-budget.history-tokens=256","vio.run.context-budget.history-turns=2")
                .run(context->assertEquals(896,context.getBean(ContextBudgetProperties.class).inputTokens()));
        runner.withPropertyValues("vio.run.context-budget.output-tokens=32768").run(context->assertNotNull(context.getStartupFailure()));
    }
}
