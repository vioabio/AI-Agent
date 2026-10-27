package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Run admission limits. Token units are conservative reservations, not a billing contract. */
@ConfigurationProperties("vio.run.task-budget")
public record TaskBudgetProperties(@DefaultValue("16") int maxModelCalls,
                                   @DefaultValue("131072") long tokenLimit) {
    public TaskBudgetProperties {
        if (maxModelCalls < 1 || maxModelCalls > 64 || tokenLimit < 1 || tokenLimit > 100_000_000L) {
            throw new IllegalArgumentException("任务模型额度配置无效");
        }
    }

    public static TaskBudgetProperties defaults() { return new TaskBudgetProperties(16, 131072); }
}
