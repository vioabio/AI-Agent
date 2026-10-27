package com.vio.vioaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class TaskBudgetPropertiesTest {
    @EnableConfigurationProperties(TaskBudgetProperties.class) static class Binding { }
    @Test void defaultsOverridesAndInvalidConfigurationAreExplicit() {
        var runner=new ApplicationContextRunner().withUserConfiguration(Binding.class);
        runner.run(context->assertEquals(TaskBudgetProperties.defaults(),context.getBean(TaskBudgetProperties.class)));
        runner.withPropertyValues("vio.run.task-budget.max-model-calls=2","vio.run.task-budget.token-limit=1234")
                .run(context->assertEquals(new TaskBudgetProperties(2,1234),context.getBean(TaskBudgetProperties.class)));
        for(String value:new String[]{"vio.run.task-budget.max-model-calls=0","vio.run.task-budget.max-model-calls=65","vio.run.task-budget.token-limit=-1"})
            runner.withPropertyValues(value).run(context->assertNotNull(context.getStartupFailure()));
    }
}
